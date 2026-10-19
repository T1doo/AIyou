package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.concurrent.CancellationException

/** Existing source-access rows hold the actual snapshots; local_jobs holds only a decision proof.
 * Nested calls use the SAME database owner/outer transaction, not another connection or private store.
 */
internal class SqlRecordAuthorizationRepository(private val database: RecordsSqlDatabase,
    private val catalog: SqlModelConnectionCatalog? = null,
    private val capabilities: ModelCapabilityRepository? = null) : RecordAuthorizationRepository {
    override val supportsConnectionReview get() = catalog != null
    private val access = RemoteTextTransactions(database)

    override fun readAuthorization(source: SourceVersion): RecordAuthorizationSnapshot = safely {
        database.transaction { readWithin(it, source) }
    }

    override fun previewAuthorization(expected: RecordAuthorizationSource, purpose: TextPurpose,
        clock: () -> Instant): RecordAuthorizationPreview = safely { database.transaction { sql ->
        val current = readWithin(sql, expected.source)
        if (current.binding != expected) throw RecordAuthorizationConflict()
        RecordAuthorizationPreview(current, purpose, currentConsent(sql, current, purpose, clock()))
    } }

    override fun previewConnectionAuthorization(expected: RecordAuthorizationSource, configuration: ModelConnectionConfiguration,
        purpose: TextPurpose, clock: () -> Instant): RecordAuthorizationPreview = safely { database.transaction { sql ->
        val current = readWithin(sql, expected.source)
        if (current.binding != expected || catalog?.capabilityCurrent(sql, configuration) != true) throw RecordAuthorizationConflict()
        val target = configuration.textConnection(purpose)
        val profile = capabilities?.profiles()?.singleOrNull { it.connection == configuration }
        RecordAuthorizationPreview(current, purpose, current.connection == target && currentConsent(sql, current, purpose, clock()), configuration, profile)
    } }

    override fun confirmAuthorization(request: RecordAuthorizationChange, clock: () -> Instant): RecordAuthorizationReceipt? = safely {
        database.transaction { sql ->
            val current = readWithin(sql, request.expected.binding.source)
            if (current.binding.sourceHash != request.expected.binding.sourceHash) throw RecordAuthorizationConflict()
            val row = sql.query("SELECT entryId,sourceRevision,operation,state FROM local_jobs WHERE id=?", listOf("RECORD_AUTH:" + request.mutationId)).singleOrNull()
                ?: return@transaction null
            if (row != receiptProof(request)) throw RecordAuthorizationConflict()
            result(sql, request, current, true, clock())
        }
    }
    private fun receiptProof(request: RecordAuthorizationChange) = listOf(request.expected.binding.source.sourceId,
        request.expected.binding.source.revision, "RECORD_AUTH:" + request.action.name + ":" + request.targetPolicy.name,
        "AUTH1:" + request.committedGeneration + ":" + proof(request))
    private fun requireConnection(sql: RecordsSqlConnection, request: RecordAuthorizationChange) {
        if (request.action != RecordAuthorizationAction.CONFIRM) return
        val grant = checkNotNull(request.grant)
        if (catalog != null && !catalog.connectionCurrent(sql, grant.connection, grant.purpose)) throw RecordAuthorizationConflict()
        request.selectedConfiguration?.let { c ->
            if (catalog?.capabilityCurrent(sql, c) != true || c.textConnection(grant.purpose) != grant.connection) throw RecordAuthorizationConflict()
        }
    }

    override fun changeAuthorization(request: RecordAuthorizationChange, clock: () -> Instant): RecordAuthorizationReceipt = safely {
        database.transaction { sql ->
            val before = request.expected
            val current = readWithin(sql, before.binding.source)
            if (current.binding.sourceHash != before.binding.sourceHash) throw RecordAuthorizationConflict()
            val id = "RECORD_AUTH:" + request.mutationId
            val operation = "RECORD_AUTH:" + request.action.name + ":" + request.targetPolicy.name
            val state = "AUTH1:" + request.committedGeneration + ":" + proof(request)
            val receipt = sql.query("SELECT entryId,sourceRevision,operation,state FROM local_jobs WHERE id=?", listOf(id)).singleOrNull()
            if (receipt != null) {
                if (receipt != listOf(before.binding.source.sourceId, before.binding.source.revision, operation, state))
                    throw RecordAuthorizationConflict()
                return@transaction result(sql, request, current, true, clock())
            }
            if (current != before) throw RecordAuthorizationConflict()
            val now = clock()
            requireCurrent(request, now)
            requireConnection(sql, request)
            val denial = access.updateAuthorization(before.binding.source, before.binding.generation,
                request.targetPolicy, request.resultingConnection, request.grant)
            if (denial != null) {
                if (denial == SourceCommitDenial.STORAGE_UNAVAILABLE) throw RecordAuthorizationStorageFailure()
                throw RecordAuthorizationConflict()
            }
            sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,?,?)",
                listOf(id, before.binding.source.sourceId, before.binding.source.revision, operation, state))
            val after = readWithin(sql, before.binding.source)
            val finalNow = clock()
            requireCurrent(request, finalNow)
            requireConnection(sql, request)
            result(sql, request, after, false, finalNow).also { check(it.decisionStillCurrent) }
        }
    }

    private fun readWithin(sql: RecordsSqlConnection, source: SourceVersion): RecordAuthorizationSnapshot {
        if (SqlErasureFileHooks.fenced(sql, source.sourceId)) throw RecordAuthorizationConflict()
        val snapshot = access.readAccess(source.sourceId) ?: throw RecordAuthorizationConflict()
        if (snapshot.state.source != source || snapshot.state.deleted) throw RecordAuthorizationConflict()
        val hash = sql.query("SELECT contentHash FROM entry_revisions WHERE entryId=? AND revision=?",
            listOf(source.sourceId, source.revision)).singleOrNull()?.single() as? String ?: throw RecordAuthorizationConflict()
        return RecordAuthorizationSnapshot(RecordAuthorizationSource(source, hash, snapshot.generation),
            snapshot.state.policy, snapshot.state.connection, snapshot.state.consent)
    }

    private fun currentConsent(sql: RecordsSqlConnection, snapshot: RecordAuthorizationSnapshot, purpose: TextPurpose, now: Instant): Boolean {
        val grant = snapshot.consent ?: return false
        return snapshot.policy == ProcessingPolicy.REMOTE_ALLOWED && !grant.revoked &&
            grant.source == snapshot.binding.source && grant.connection == snapshot.connection &&
            grant.purpose == purpose && now >= grant.issuedAt && now < grant.expiresAt &&
            localConnectionCurrent(sql, grant.connection, purpose)
    }
    private fun localConnectionCurrent(sql: RecordsSqlConnection, connection: ProviderConnection, purpose: TextPurpose): Boolean =
        try { catalog == null || catalog.connectionCurrent(sql, connection, purpose) } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            false // Metadata and withdrawal remain usable when the derived catalog cannot authenticate.
        }
    private fun localSelectionCurrent(sql: RecordsSqlConnection, configuration: ModelConnectionConfiguration): Boolean =
        try { catalog?.capabilityCurrent(sql, configuration) == true } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            false
        }
    private fun requireCurrent(request: RecordAuthorizationChange, now: Instant) {
        request.grant?.let { if (it.revoked || now < it.issuedAt || now >= it.expiresAt) throw RecordAuthorizationNotCurrent() }
    }
    private fun result(sql: RecordsSqlConnection, request: RecordAuthorizationChange, current: RecordAuthorizationSnapshot,
        replayed: Boolean, now: Instant): RecordAuthorizationReceipt {
        val matches = current.binding == request.expected.binding.copy(generation = request.committedGeneration) &&
            current.policy == request.targetPolicy && current.connection == request.resultingConnection && current.consent == request.grant
        val grant = request.grant
        val selection = request.selectedConfiguration
        val effective = matches && (grant == null || currentConsent(sql, current, grant.purpose, now)) &&
            (selection == null || localSelectionCurrent(sql, selection))
        return RecordAuthorizationReceipt(current, request.committedGeneration, replayed, effective)
    }
    private fun proof(request: RecordAuthorizationChange): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                fun text(value: String) { val data = value.toByteArray(Charsets.UTF_8);try { out.writeInt(data.size);out.write(data) } finally { data.fill(0) } }
                fun blob(value: ByteArray?) { if (value == null) out.writeInt(-1) else try { out.writeInt(value.size);out.write(value) } finally { value.fill(0) } }
                val expected = request.expected
                text(if (request.selectedConfiguration == null) "AIyou record authorization decision v1" else "AIyou record authorization decision v2");text(request.mutationId);text(request.action.name)
                text(expected.binding.source.sourceId);out.writeLong(expected.binding.source.revision)
                text(expected.binding.sourceHash);out.writeLong(expected.binding.generation);text(expected.policy.name)
                blob(expected.connection?.let(RemoteTextCodec::encodeConnection));blob(expected.consent?.let(RemoteTextCodec::encodeConsent))
                text(request.targetPolicy.name);blob(request.grant?.let(RemoteTextCodec::encodeConsent))
                request.selectedConfiguration?.let { c ->
                    text(c.id);out.writeLong(c.version);text(checkNotNull(c.secretRef).value)
                    blob(ModelConnectionPayload.encode(ModelConnectionPayload.Value(c.details)))
                }
            }
            buffer.toByteArray()
        }
        return try { RemoteTextCodec.sha256(bytes) } finally { bytes.fill(0) }
    }
    private inline fun <T> safely(block: () -> T): T = try { block() } catch (failure: Throwable) {
        when (failure) {
            is CancellationException, is VirtualMachineError, is ThreadDeath, is LinkageError -> throw failure
            is RecordAuthorizationConflict, is RecordAuthorizationNotCurrent, is RecordAuthorizationStorageFailure -> throw failure
            else -> throw RecordAuthorizationStorageFailure()
        }
    }
}
