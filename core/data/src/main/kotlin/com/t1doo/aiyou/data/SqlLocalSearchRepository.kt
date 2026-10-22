package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CancellationException

/** Only the encrypted owner constructs the production instance. No separate DB or files. */
internal class SqlLocalSearchRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository,
    private val runtimeProfile: () -> String = { LocalSearchTokenizer.profile },
    private val people: SqlManualPeopleRepository? = null) : LocalSearchRepository {
    constructor(database: RecordsSqlDatabase, tags: SqlManualTagRepository, runtimeProfile: () -> String) :
        this(database, tags, runtimeProfile, null)
    private data class Stamp(val epoch: Long, val version: Int, val profile: String)
    private val favorites = SqlRecordFavoriteRepository(database)
    private val eventDateStorage = SqlRecordEventDateRepository(database)
    private fun activeProfile(): String = runtimeProfile().also {
        require(Regex("v1:[0-9a-f]{64}").matches(it))
    }
    private data class ImageBinding(val id: String, val revision: Int, val mediaType: String,
        val hash: String, val bytes: Long, val state: AttachmentState)
    private data class Binding(val entry: StoredEntry, val hash: String, val tagVersion: Long, val visibility: Long,
        val images: List<ImageBinding>? = null)
    private inner class Proof(val stamp: Stamp, val runtime: String, val bindings: List<Binding>,
        tagValues: Map<String, ManualTagSnapshot>, override val pending: Boolean,
        override val favorites: Map<String, RecordFavoriteSnapshot>? = null,
        override val eventDates: Map<String, RecordEventDateSnapshot>? = null,
        val imageRequest: LocalSearchRequest? = null,
        override val people: Map<String, ManualPeopleSnapshot>? = null) : LocalSearchSnapshot {
        override val epoch = stamp.epoch
        val owner = this@SqlLocalSearchRepository
        override val entries = Collections.unmodifiableList(bindings.map { it.entry })
        override val tags = Collections.unmodifiableMap(HashMap(tagValues))
        override val currentImageEntryIds = if (imageRequest != null)
            Collections.unmodifiableSet(bindings.map { it.entry.id }.toSet()) else null
        override fun toString() = "LocalSearchSnapshot(redacted)"
    }

    private val sourceFrom = " FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision " +
        "LEFT JOIN remote_source_access a ON a.entryId=e.id LEFT JOIN entry_manual_tags t ON t.entryId=e.id " +
        "LEFT JOIN local_search_documents m ON m.entryId=e.id "
    private val live = "COALESCE(a.deleted,0)=0"
    private fun valid(epoch: Long, profile: String) = "COALESCE(m.sourceRevision=e.currentRevision AND m.contentHash=r.contentHash " +
        "AND m.tagVersion=COALESCE(t.version,0) AND m.visibilityGeneration=COALESCE(a.generation,0) " +
        "AND m.tokenizerVersion=1 AND m.tokenizerProfile='" + profile + "' AND m.indexedEpoch>=0 AND m.indexedEpoch<=" + epoch +
        " AND (SELECT count(*) FROM local_search_fts f WHERE f.entryId=e.id)=1,0)"
    private fun dirty(epoch: Long, profile: String) = "(" + valid(epoch, profile) + "=0 OR EXISTS(SELECT 1 FROM local_jobs j " +
        "WHERE j.entryId=e.id AND j.sourceRevision=e.currentRevision AND j.operation='LOCAL_INDEX' " +
        "AND j.state IN ('PENDING','RUNNING','WAITING_CONDITIONS')))"

    override fun epoch(): Long = safely { database.transaction { epochWithin(it).epoch } }
    private fun epochWithin(sql: RecordsSqlConnection): Stamp {
        val rows = sql.query("SELECT slot,epoch,tokenizerVersion,tokenizerProfile FROM local_search_epoch")
        require(rows.size == 1 && rows[0][0] == 1L)
        val epoch = rows[0][1] as Long; val version = Math.toIntExact(rows[0][2] as Long)
        require(epoch >= 0 && epoch < Long.MAX_VALUE && version > 0)
        return Stamp(epoch, version, rows[0][3] as String)
    }
    private fun advance(sql: RecordsSqlConnection, profile: String): Long {
        val current = epochWithin(sql).epoch
        val next = Math.addExact(current, 1)
        require(next < Long.MAX_VALUE)
        check(sql.execute("UPDATE local_search_epoch SET epoch=?,tokenizerVersion=1,tokenizerProfile=? WHERE slot=1 AND epoch=?",
            listOf(next, profile, current)) == 1)
        return next
    }

    override fun rebuild(): Long = safely {
        val profile = activeProfile()
        database.transaction { resetWithin(it, profile) }
    }
    private fun resetWithin(sql: RecordsSqlConnection, profile: String): Long {
        epochWithin(sql)
        sql.execute("DELETE FROM local_search_fts")
        sql.execute("DELETE FROM local_search_documents")
        val next = advance(sql, profile)
        sql.execute("UPDATE local_jobs SET state='PENDING' WHERE operation='LOCAL_INDEX' AND EXISTS(" +
            "SELECT 1 FROM entries e WHERE e.id=local_jobs.entryId AND e.currentRevision=local_jobs.sourceRevision " +
            "AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1))")
        return next
    }

    override fun batch(): LocalSearchProgress = safely {
        val profile = activeProfile()
        database.transaction { sql ->
            val initial = epochWithin(sql)
            if (initial.version != LocalSearchTokenizer.VERSION || initial.profile != profile) resetWithin(sql, profile)
            var current = epochWithin(sql).epoch
            val ids = sql.query("SELECT e.id" + sourceFrom + "WHERE " + live + " AND " + dirty(current, profile) +
                " ORDER BY e.id LIMIT 8").map { it.single() as String }
            var processed = 0
            var chars = 0
            for (id in ids) {
                // SQLite byte length is a conservative UTF-16 bound, including NUL;
                // reserve the maximum 12 x 40 supplementary-code-point tag names
                // before loading the next original/AEAD tags into memory.
                val upperBound = Math.addExact((sql.query("SELECT length(CAST(r.originalText AS BLOB))" +
                    sourceFrom + "WHERE e.id=? AND " + live, listOf(id)).single().single() as Long), 960L)
                if (processed > 0 && chars + upperBound > 400_000) break
                require(upperBound <= 400_000)
                val bound = read(sql, id) ?: throw LocalSearchStale()
                val labels = tags.readWithin(sql, id)
                val size = bound.entry.originalText.length + labels.names.sumOf { it.length }
                if (processed > 0 && chars + size > 400_000) break
                require(size <= 400_000)
                if (processed == 0) current = advance(sql, profile)
                val body = LocalSearchTokenizer.document(bound.entry.originalText)
                // Separate fields; current manual tags never become fabricated original quotes.
                val tagText = LocalSearchTokenizer.document(labels.names.joinToString("\n"))
                sql.execute("DELETE FROM local_search_fts WHERE entryId=?", listOf(id))
                sql.execute("DELETE FROM local_search_documents WHERE entryId=?", listOf(id))
                sql.execute("INSERT INTO local_search_fts(entryId,bodyTokens,tagTokens) VALUES(?,?,?)",
                    listOf(id, body, tagText))
                sql.execute("INSERT INTO local_search_documents(entryId,sourceRevision,contentHash,tagVersion," +
                    "visibilityGeneration,tokenizerVersion,tokenizerProfile,indexedEpoch) VALUES(?,?,?,?,?,1,?,?)",
                    listOf(id, bound.entry.currentRevision, bound.hash, bound.tagVersion, bound.visibility, profile, current))
                // A legacy/package source may have no canonical job ID. Do not overwrite any receipt.
                val jobs = sql.query("SELECT id FROM local_jobs WHERE entryId=? AND sourceRevision=? AND operation='LOCAL_INDEX'",
                    listOf(id, bound.entry.currentRevision))
                if (jobs.isEmpty()) sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) " +
                    "VALUES(?,?,?,'LOCAL_INDEX','SUCCEEDED')",
                    listOf("LOCAL_SEARCH:" + id + ":" + bound.entry.currentRevision, id, bound.entry.currentRevision))
                else sql.execute("UPDATE local_jobs SET state='SUCCEEDED' WHERE entryId=? AND sourceRevision=? AND operation='LOCAL_INDEX'",
                    listOf(id, bound.entry.currentRevision))
                sql.execute("UPDATE local_jobs SET state='CANCELLED' WHERE entryId=? AND sourceRevision!=? " +
                    "AND operation='LOCAL_INDEX' AND state IN ('PENDING','RUNNING','WAITING_CONDITIONS')",
                    listOf(id, bound.entry.currentRevision))
                chars += upperBound.toInt(); processed++
            }
            val remaining = sql.query("SELECT e.id" + sourceFrom + "WHERE " + live + " AND " + dirty(current, profile) +
                " LIMIT 1").isNotEmpty()
            LocalSearchProgress(processed, remaining, current)
        }
    }

    private fun date(value: String): LocalDate? {
        if (value.isEmpty()) return null
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) throw LocalSearchInvalid()
        return try { LocalDate.parse(value) } catch (_: Exception) { throw LocalSearchInvalid() }
    }

    private class Budget {
        var chars = 0L
        var bytes = 0L
        fun add(text: String) {
            chars += text.length
            val raw = text.toByteArray(Charsets.UTF_8)
            try { bytes += raw.size } finally { raw.fill(0) }
            if (chars > 1_000_000 || bytes > 4_000_000) throw LocalSearchTooBroad()
        }
    }

    /** Authenticated record-local lookup, never a global replacement in the FTS expression. */
    private fun aliasCandidates(sql: RecordsSqlConnection, request: LocalSearchRequest,
        from: LocalDate?, through: LocalDate?, budget: Budget, favoriteCondition: String, imageCondition: String): Set<String> {
        val owner = people ?: return emptySet()
        if (request.exact || request.query.isBlank()) return emptySet()
        val whole = ManualPeopleAliases.fold(request.query.trim())
        val terms = request.query.split(Regex("[\\p{javaWhitespace}\\p{Zs}]+")).filter { it.isNotEmpty() }
            .map(ManualPeopleAliases::fold).toSet() + whole
        val ids = LinkedHashSet<String>()
        var scanned = 0
        var authenticated = 0
        while (true) {
            val rows = sql.query("SELECT e.id,e.capturedAtMillis,e.captureTimeZone FROM entry_manual_people p " +
                "JOIN entries e ON e.id=p.entryId LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE " +
                live + favoriteCondition + imageCondition + " ORDER BY e.id LIMIT 100 OFFSET " + scanned)
            if (rows.isEmpty()) break
            for (row in rows) {
                if (++scanned > 10_000) throw LocalSearchTooBroad()
                val id = row[0] as String
                val selectedDate = if (request.dateBasis == RecordDateBasis.EVENT)
                    eventDateStorage.readWithin(sql, id).date?.let { LocalDate.parse(it.localDate) } ?: continue
                    else Instant.ofEpochMilli(row[1] as Long).atZone(ZoneId.of(row[2] as String)).toLocalDate()
                if ((from != null && selectedDate < from) || (through != null && selectedDate > through)) continue
                if (request.tag != null) {
                    val tag = tags.readWithin(sql, id); tag.names.forEach(budget::add)
                    if (request.tag !in tag.names) continue
                }
                if (++authenticated > 4096) throw LocalSearchTooBroad()
                val snapshot = owner.readWithin(sql, id)
                snapshot.names.forEach(budget::add)
                snapshot.aliases.forEach { budget.add(it.alias); budget.add(it.name) }
                if (request.person != null && request.person !in snapshot.names) continue
                if (snapshot.aliases.any { ManualPeopleAliases.fold(it.alias) in terms || ManualPeopleAliases.fold(it.name) in terms }) {
                    ids += id
                    // One FTS bind plus at most 512 IDs; reject the whole scope rather than truncate it.
                    if (ids.size > 512) throw LocalSearchTooBroad()
                }
            }
            if (rows.size < 100) break
        }
        return ids
    }

    override fun candidates(request: LocalSearchRequest): LocalSearchSnapshot = safely {
        val profile = activeProfile()
        if (request.query.length > 100_000) throw LocalSearchInvalid()
        val from = date(request.from); val through = date(request.through)
        if (from != null && through != null && from > through) throw LocalSearchInvalid()
        request.tag?.let { if (ManualTags.canonical(listOf(it)) != listOf(it)) throw LocalSearchInvalid() }
        request.person?.let { if (ManualTags.canonical(listOf(it)) != listOf(it)) throw LocalSearchInvalid() }
        database.transaction { sql ->
            val stamp = epochWithin(sql)
            // Never interpret missing or malformed favorite metadata as an empty collection.
            if (request.onlyFavorites) try { favorites.validateWithin(sql) }
                catch (_: RecordFavoriteTooBroad) { throw LocalSearchFavoriteLimit() }
            val favoriteValues = if (request.onlyFavorites) HashMap<String,RecordFavoriteSnapshot>() else null
            // Unknown event dates are excluded, never replaced with the captured calendar day.
            val eventValues = if (request.dateBasis == RecordDateBasis.EVENT) {
                try { eventDateStorage.validateWithin(sql) }
                    catch (_: RecordEventDateTooBroad) { throw LocalSearchTooBroad() }
                HashMap<String,RecordEventDateSnapshot>()
            } else null
            val favoriteCondition = if (request.onlyFavorites) " AND e.isFavorite=1" else ""
            val imageCondition = if (request.onlyImages) " AND EXISTS(SELECT 1 FROM attachments p " +
                "WHERE p.entryId=e.id AND p.revision=e.currentRevision AND p.mediaType IN ('image/png','image/jpeg'))" else ""
            val budget = Budget()
            val aliasIds = aliasCandidates(sql, request, from, through, budget, favoriteCondition, imageCondition)
            val expression = LocalSearchTokenizer.query(request.query, request.exact)
            val condition = if (expression == null || stamp.version != 1 || stamp.profile != profile) "" else
                " AND (" + valid(stamp.epoch, profile) + "=0 OR e.id IN (SELECT entryId FROM local_search_fts WHERE local_search_fts MATCH ?)" +
                    (if (aliasIds.isEmpty()) "" else " OR e.id IN (" + aliasIds.joinToString(",") { "?" } + ")") + ")"
            val args = if (condition.isEmpty()) emptyList() else listOf(expression) + aliasIds.toList()
            val personValues = if (request.person != null || people != null && !request.exact && request.query.isNotBlank()) HashMap<String, ManualPeopleSnapshot>() else null
            val bindings = ArrayList<Binding>()
            val labels = HashMap<String, ManualTagSnapshot>()
            var scanned = 0
            while (true) {
                val page = sql.query("SELECT e.id,e.capturedAtMillis,e.captureTimeZone" + sourceFrom +
                    "WHERE " + live + favoriteCondition + imageCondition + condition + " ORDER BY e.id LIMIT 100 OFFSET " + scanned, args)
                if (page.isEmpty()) break
                for (row in page) {
                    if (++scanned > 10_000) throw LocalSearchTooBroad()
                    val id = row[0] as String
                    val event = if (eventValues != null) eventDateStorage.readWithin(sql,id) else null
                    val selectedDate = if (eventValues != null) event?.date?.let { LocalDate.parse(it.localDate) } ?: continue
                        else Instant.ofEpochMilli(row[1] as Long).atZone(ZoneId.of(row[2] as String)).toLocalDate()
                    if ((from != null && selectedDate < from) || (through != null && selectedDate > through)) continue
                    // Authenticate real tags even for body-only queries; corrupted rows are never skipped as misses.
                    val tag = tags.readWithin(sql, id)
                    tag.names.forEach(budget::add)
                    if (request.tag != null && request.tag !in tag.names) continue
                    val person = if (personValues != null) checkNotNull(people).readWithin(sql, id) else null
                    person?.names?.forEach(budget::add)
                    person?.aliases?.forEach { budget.add(it.alias); budget.add(it.name) }
                    if (request.person != null && request.person !in checkNotNull(person).names) continue
                    val bound = read(sql, id, request.onlyImages) ?: throw LocalSearchStale()
                    budget.add(bound.entry.originalText)
                    bindings += bound
                    labels[id] = tag
                    if (personValues != null) personValues[id] = checkNotNull(person)
                    if (favoriteValues != null) favoriteValues[id] = favorites.readWithin(sql,id)
                    if (eventValues != null) eventValues[id] = checkNotNull(event)
                    if (bindings.size > 1000) throw LocalSearchTooBroad()
                }
                if (page.size < 100) break
            }
            val pending = sql.query("SELECT e.id" + sourceFrom + "WHERE " + live + " AND " + dirty(stamp.epoch, profile) +
                " LIMIT 1").isNotEmpty() || stamp.version != 1 || stamp.profile != profile
            Proof(stamp, profile, Collections.unmodifiableList(bindings.toList()), labels, pending,
                favoriteValues?.let { Collections.unmodifiableMap(it) },eventValues?.let { Collections.unmodifiableMap(it) },
                request.takeIf { it.onlyImages }, personValues?.let { Collections.unmodifiableMap(it) })
        }
    }

    override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean =
        verify(snapshot, null) { publish() }
    override fun resolve(snapshot: LocalSearchSnapshot, entryId: String, publish: (StoredEntry) -> Unit): Boolean =
        verify(snapshot, entryId) { publish(checkNotNull(it)) }

    private fun verify(snapshot: LocalSearchSnapshot, entryId: String?, publish: (StoredEntry?) -> Unit): Boolean {
        val proof=snapshot as? Proof ?: return false
        if(proof.owner!==this || entryId!=null && proof.bindings.none{it.entry.id==entryId})return false
        return safely {
            if(activeProfile()!=proof.runtime)return@safely false
            database.transaction { verifyWithin(it,snapshot,entryId,publish) }
        }
    }

    internal fun keywordCurrentWithin(sql:RecordsSqlConnection,snapshot:LocalSearchSnapshot):Boolean =
        verifyWithin(sql,snapshot,null) {}

    private fun verifyWithin(sql:RecordsSqlConnection,snapshot:LocalSearchSnapshot,entryId:String?,publish:(StoredEntry?)->Unit):Boolean {
        val proof=snapshot as? Proof ?: return false
        if(proof.owner!==this || entryId!=null && proof.bindings.none {it.entry.id==entryId} || activeProfile()!=proof.runtime)return false
        if (epochWithin(sql) != proof.stamp) return false
        // Attachment inserts/changes do not advance the text index epoch. Recompute this bounded
        // candidate scope under the same write lease, including empty→nonempty membership.
        if (proof.imageRequest != null && (candidates(proof.imageRequest) as Proof).bindings != proof.bindings)
            return false
        var selected: StoredEntry? = null
        for (before in proof.bindings) {
            val current = read(sql, before.entry.id, before.images != null) ?: return false
            if (current != before) return false
            val tag = tags.readWithin(sql, before.entry.id)
            val previousTag = proof.tags[before.entry.id] ?: return false
            if (tag.entryId != previousTag.entryId || tag.version != previousTag.version ||
                tag.names != previousTag.names) return false
            proof.eventDates?.let { values ->
                val previous = values[before.entry.id] ?: return false
                val currentEvent = eventDateStorage.readWithin(sql,before.entry.id)
                if (previous.version != currentEvent.version || previous.date != currentEvent.date) return false
            }
            proof.people?.let { values ->
                val old = checkNotNull(values[before.entry.id])
                val now = checkNotNull(people).readWithin(sql, before.entry.id)
                if (old.version != now.version || old.names != now.names || old.aliases != now.aliases || old.sourceRevision != now.sourceRevision ||
                    old.sourceHash != now.sourceHash || old.generation != now.generation) return false
            }
            if (entryId == before.entry.id) selected = current.entry
        }
        // The actual owner/CAS callback executes before releasing this counted write transaction.
        publish(selected)
        return true
    }

    private fun read(sql: RecordsSqlConnection, id: String, onlyImages: Boolean = false): Binding? {
        val rows = sql.query("SELECT e.id,e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis," +
            "e.captureTimeZone,e.privacyPolicy,COALESCE(t.version,0),COALESCE(a.generation,0)" + sourceFrom +
            "WHERE e.id=? AND " + live, listOf(id))
        if (rows.isEmpty()) return null
        require(rows.size == 1)
        val row = rows.single()
        val text = row[2] as String; val hash = row[3] as String
        require(text.length <= 100_000 && OriginalExports.hash(text) == hash)
        val revision = Math.toIntExact(row[1] as Long)
        val images = if (onlyImages) {
            val rows = sql.query("SELECT id,revision,mediaType,sha256,byteSize,state FROM attachments " +
                "WHERE entryId=? AND revision=? AND mediaType IN ('image/png','image/jpeg') ORDER BY id LIMIT 65",listOf(id,revision))
            if (rows.isEmpty()) return null
            if (rows.size > 64) throw LocalSearchTooBroad()
            rows.map { image ->
                val imageId = image[0] as String; ManualTags.identifier(imageId)
                val imageHash = image[3] as String; val bytes = image[4] as Long
                require(imageHash.matches(Regex("[0-9a-f]{64}")) && bytes in 1..16_777_216)
                ImageBinding(imageId,Math.toIntExact(image[1] as Long),image[2] as String,imageHash,bytes,
                    AttachmentState.valueOf(image[5] as String))
            }
        } else null
        require(text.isNotBlank() || sql.query("SELECT id FROM attachments WHERE entryId=? AND revision=? AND mediaType IN ('image/png','image/jpeg','audio/wav') LIMIT 1",listOf(id,row[1])).isNotEmpty())
        val tagVersion = row[7] as Long; val visibility = row[8] as Long
        require(revision > 0 && tagVersion >= 0 && visibility >= 0)
        ZoneId.of(row[5] as String)
        return Binding(StoredEntry(row[0] as String, revision, text, row[4] as Long, row[5] as String,
            ProcessingPolicy.valueOf(row[6] as String)), hash, tagVersion, visibility,images)
    }

    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalSearchFailure) { throw failure }
        catch (_: Exception) { throw LocalSearchFailure() }
}
