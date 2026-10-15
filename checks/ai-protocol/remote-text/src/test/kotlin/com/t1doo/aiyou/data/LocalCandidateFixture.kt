package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.util.Base64

/** Exact exported v9 → product v10 SQL. No remote approval, resolver or transport is installed. */
internal class LocalCandidateFixture(upgrade: Boolean = true) : AutoCloseable {
    val directory = Files.createTempDirectory("aiyou-local-candidates-")
    private val bridge = directory.resolve("sqlite_bridge.py")
    private val file = directory.resolve("records.sqlite")
    var db: PythonSqliteDatabase; private set
    var gateway: DrainingRecordsSqlDatabase; private set
    var store: SqlLocalCandidateRepository; private set
    val source = SourceVersion("source", 1)
    val original = "我不喝咖啡🙂。他说“明天三点”只是引用，并未完成。"
    init {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use { Files.copy(checkNotNull(it), bridge) }
        db = PythonSqliteDatabase(file, bridge, true, 9)
        db.transaction { sql ->
            RemoteTextSqlSchema.guards.forEach { sql.execute(it) }
            sql.execute(ManualTagSqlSchema.guard); sql.execute(ManualTaskSqlSchema.guard)
            LocalSearchSqlSchema.guards.forEach { sql.execute(it) }
            PermanentErasureSqlSchema.guards.forEach { sql.execute(it) }
            RecordFavoriteSqlSchema.guards.forEach { sql.execute(it) }
            sql.execute("DROP TRIGGER IF EXISTS local_search_entries_update"); sql.execute(RecordFavoriteSqlSchema.sourceIndexUpdate)
            sql.execute("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            for ((id, text) in listOf("source" to original, "other" to "同名合成来源")) {
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'Asia/Shanghai','LOCAL_ONLY')", listOf(id))
                sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)", listOf(id, text, OriginalExports.hash(text)))
                sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')", listOf("INDEX:" + id, id))
            }
            sql.execute("INSERT INTO capture_draft VALUES(1,'draft',3,'合成未提交草稿')")
        }
        gateway = DrainingRecordsSqlDatabase(db)
        store = SqlLocalCandidateRepository(gateway, KEY)
        if (upgrade) upgrade()
    }
    fun upgrade() = db.transaction { sql ->
        LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) }
        LocalCandidateSqlSchema.guards.forEach { sql.execute(it) }
        sql.execute("PRAGMA user_version=10")
    }
    fun start(id: String = "batch") = LocalCandidateStart(id, source, 20)
    fun ready(id: String = "batch"): LocalCandidateBatch {
        val job = store.begin(start(id))
        return store.complete(job, LocalExcerptRules.extract(job))
    }
    fun review(batch: LocalCandidateBatch, decision: ExcerptReview = ExcerptReview.ACCEPTED, mutation: String = "review") =
        ExcerptReviewRequest(batch.id, batch.source, batch.sourceHash, batch.generation, batch.version,
            batch.items.first().id, decision, mutation, 30)
    fun cancel(batch: LocalCandidateBatch, mutation: String = "cancel") = CandidateCancelRequest(
        batch.id, batch.source, batch.sourceHash, batch.generation, batch.version, mutation, 31)
    fun revise(text: String = "合成改写后的原文", connection: PythonSqliteDatabase = db) = connection.transaction { sql ->
        sql.execute("INSERT INTO entry_revisions VALUES('source',2,?,?,6)", listOf(text, OriginalExports.hash(text)))
        sql.execute("UPDATE entries SET currentRevision=2 WHERE id='source'")
    }
    fun snapshotOld(): List<String> = db.read("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'local_candidate_batches' ORDER BY name")
        .flatMap { list ->
            val table = list.single() as String
            db.read("SELECT * FROM " + table + " ORDER BY 1").map { row ->
                table + ":" + row.joinToString("|") { value -> if (value is ByteArray) Base64.getEncoder().encodeToString(value) else value.toString() }
            }
        }
    fun row() = db.read("SELECT version,state,lastMutationId,payload FROM local_candidate_batches WHERE id='batch'").single()
    fun second() = PythonSqliteDatabase(file, bridge, false, 9)
    fun reopen() {
        gateway.stopAndAwaitIdle(); store.wipeKey(); db.close()
        db = PythonSqliteDatabase(file, bridge, false, 9); gateway = DrainingRecordsSqlDatabase(db)
        store = SqlLocalCandidateRepository(gateway, KEY)
    }
    override fun close() {
        db.beforeCommit = null; db.beforeSql = null; db.afterSql = null
        gateway.stopAndAwaitIdle(); store.wipeKey(); db.close()
        Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    companion object { val KEY = ByteArray(32) { (it + 91).toByte() } }
}
