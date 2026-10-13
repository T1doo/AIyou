package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

/** Synthetic records seeded in the actual exported v3 schema, then migrated by product SQL. */
internal class RemoteTextFixture(
    val protocol: ConnectionProtocol = ConnectionProtocol.CHAT_COMPLETIONS,
    val selectedB: Boolean = false,
    initiallyApproved: Boolean = true,
    val original: String = "原文🙂e\u0301中文\nsynthetic-private-source-text",
    seedHash: String = hash(original),
    migrate: Boolean = true,
) : Closeable {
    val directory: Path = Files.createTempDirectory("aiyou-remote-text-")
    private val bridge = directory.resolve("sqlite_bridge.py")
    private val file = directory.resolve("records.sqlite")
    private val connections = mutableListOf<PythonSqliteDatabase>()
    private val sequence = AtomicInteger()
    val now: Instant = Instant.parse("2026-10-13T12:00:00Z")
    val clock: () -> Instant = { now }
    val source = SourceVersion("synthetic-private-source", 3)
    val intent = RemoteSendIntent(source, TextPurpose.ORGANIZATION)
    val connectionA = ProviderConnection("synthetic-provider-a", 7,
        "https://provider-a.example.test/custom/v1/", protocol, "synthetic-model-a", SecretReference("synthetic-private-ref-a"))
    val connectionB = ProviderConnection("synthetic-provider-b", 11,
        "https://provider-b.example.test/custom/v1/", protocol, "synthetic-model-b", SecretReference("synthetic-private-ref-b"))
    val selected = if (selectedB) connectionB else connectionA
    val grant = ConsentGrant("synthetic-private-grant", 5, source, selected, TextPurpose.ORGANIZATION,
        now.minusSeconds(60), now.plusSeconds(3_600))
    val capturedAt = now.minusSeconds(20).toEpochMilli()
    val zone = "Asia/Shanghai"
    var db: PythonSqliteDatabase
        private set
    var repository: RemoteTextTransactions
        private set
    val beforeMigration: List<List<Any?>>

    init {
        try {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use { input ->
            checkNotNull(input) { "Missing owned SQLite bridge resource" }
            Files.copy(input, bridge)
        }
        db = open(initialize = true)
        db.transaction { sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,?,?,?,?)",
                listOf(source.sourceId, source.revision, capturedAt, zone, ProcessingPolicy.LOCAL_ONLY.name))
            sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,?,?,?,?)",
                listOf(source.sourceId, source.revision, original, seedHash, capturedAt))
            sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,?,?)",
                listOf("synthetic-local-job", source.sourceId, source.revision, "LOCAL_INDEX", "PENDING"))
            sql.execute("INSERT INTO capture_draft(slot,id,version,text) VALUES(1,?,?,?)",
                listOf("synthetic-draft", 7, "synthetic-private-capture-draft"))
            // An existing durable intent, without claiming that a ciphertext file exists.
            sql.execute("INSERT INTO attachments(id,entryId,revision,mediaType,sha256,byteSize,state) VALUES(?,?,?,?,?,?,?)",
                listOf("synthetic-attachment-intent", source.sourceId, source.revision, "image/png", hash("synthetic-attachment-intent"), 17, "WRITING"))
            val other = "synthetic-private-unrelated-record"
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,?,?,?,?)",
                listOf("synthetic-unrelated-source", 1, capturedAt, zone, ProcessingPolicy.LOCAL_ONLY.name))
            sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,?,?,?,?)",
                listOf("synthetic-unrelated-source", 1, other, hash(other), capturedAt))
        }
        beforeMigration = originals()
        if (migrate) migrateNow()
        repository = store(db)
        if (initiallyApproved) approve()
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun migrateNow() {
        db.transaction { sql ->
            RemoteTextSqlSchema.migration3To4.forEach { sql.execute(it) }
            RemoteTextSqlSchema.guards.forEach { sql.execute(it) }
            sql.execute("PRAGMA user_version=4")
        }
    }

    fun store(connection: PythonSqliteDatabase): RemoteTextTransactions = RemoteTextTransactions(connection) {
        "synthetic-job-${directory.fileName}-${sequence.incrementAndGet()}"
    }

    fun second(): PythonSqliteDatabase = open(false)
    fun generation(source: SourceVersion = this.source, connection: PythonSqliteDatabase = db): Long =
        (connection.read("SELECT generation FROM remote_source_access WHERE entryId=?", source.sourceId)
        .singleOrNull()?.firstOrNull() as? Long) ?: 0L

    fun approve(policy: ProcessingPolicy = ProcessingPolicy.REMOTE_ALLOWED,
                connection: ProviderConnection? = selected, consent: ConsentGrant? = grant) {
        assertNull(repository.updateAuthorization(source, generation(), policy, connection, consent))
    }

    fun queued(prompt: String = PROMPT): SourceBoundTextJob {
        val outcome = repository.queue(intent, prompt, clock)
        assertTrue("Actual current database original must queue", outcome is SourceQueueOutcome.Queued)
        return (outcome as SourceQueueOutcome.Queued).job
    }

    fun admitted(prompt: String = PROMPT): SourceBoundTextJob = queued(prompt).also {
        assertNull(repository.admit(it, clock))
    }

    fun candidate(text: String = "合成摘要", references: List<OriginalTextReference> = listOf(
        OriginalTextReference(source, 0, original.codePointCount(0, original.length), original),
    )) = DerivedTextCandidate(text, references)

    fun jobs(): List<List<Any?>> = db.read("SELECT id,state FROM remote_text_jobs ORDER BY id")
    fun annotations(): List<List<Any?>> = db.read("SELECT jobId,resultHash,candidate FROM text_annotations ORDER BY jobId")

    fun originals(): List<List<Any?>> = listOf("entries", "entry_revisions", "capture_draft", "local_jobs", "attachments")
        .flatMap { table -> db.read("SELECT * FROM $table ORDER BY 1,2").map { listOf(table) + it } }

    fun revise(connection: PythonSqliteDatabase = db) {
        connection.transaction { sql ->
            val next = source.revision + 1
            val text = "synthetic-private-new-original🙂"
            sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,?,?,?,?)",
                listOf(source.sourceId, next, text, hash(text), now.toEpochMilli()))
            assertEquals(1, sql.execute("UPDATE entries SET currentRevision=? WHERE id=?", listOf(next, source.sourceId)))
        }
    }

    fun physicallyErase() {
        db.transaction { sql ->
            listOf("text_annotations", "remote_text_jobs", "remote_source_access", "attachments", "local_jobs", "entry_revisions")
                .forEach { table -> sql.execute("DELETE FROM $table WHERE entryId=?", listOf(source.sourceId)) }
            sql.execute("DELETE FROM entries WHERE id=?", listOf(source.sourceId))
        }
    }

    fun reopen() {
        db.close()
        db = open(false)
        repository = store(db)
    }

    private fun open(initialize: Boolean): PythonSqliteDatabase = PythonSqliteDatabase(file, bridge, initialize)
        .also { connections.add(it) }

    override fun close() {
        connections.asReversed().forEach { it.beforeCommit = null; it.beforeSql = null; it.afterSql = null; it.close() }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    companion object {
        const val PROMPT = "synthetic-prompt-v1"
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
