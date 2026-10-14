package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Exact checked-in v7, actual additive migration, SQL, AEAD and private lock protocol. */
internal class PermanentErasureFixture : AutoCloseable {
    val root = Files.createTempDirectory("aiyou-erasure-")
    val databaseFile = root.resolve("records.sqlite")
    val bridge = root.resolve("sqlite_bridge.py")
    val mediaRoot = root.resolve("blobs").toFile()
    val editRoot = root.resolve("edits").toFile()
    lateinit var db: PythonSqliteDatabase; private set
    lateinit var gateway: DrainingRecordsSqlDatabase; private set
    lateinit var tags: SqlManualTagRepository; private set
    lateinit var tasks: SqlManualTaskRepository; private set
    lateinit var packages: SqlRecordPackageRepository; private set
    lateinit var bin: SqlRecycleBinRepository; private set
    lateinit var media: EncryptedAttachmentStore; private set
    lateinit var edits: EncryptedOriginalEditStore; private set
    lateinit var erase: SqlPermanentErasureRepository; private set
    var checkpoint: (String) -> Unit = {}
    var mediaCheckpoint: (String) -> Unit = {}
    var editCheckpoint: (String) -> Unit = {}
    init {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use { Files.copy(checkNotNull(it),bridge) }
        db = PythonSqliteDatabase(databaseFile,bridge,true,7)
        db.transaction { sql ->
            sql.execute("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            PermanentErasureSqlSchema.migration7To8.forEach { sql.execute(it) }
            PermanentErasureSqlSchema.guards.forEach { sql.execute(it) }
            LocalSearchSqlSchema.guards.forEach { sql.execute(it) }
            sql.execute("PRAGMA user_version=8")
        }
        openAdapters()
    }
    private fun openAdapters() {
        gateway = DrainingRecordsSqlDatabase(db)
        val hooks = SqlErasureFileHooks(gateway)
        tags = SqlManualTagRepository(gateway,KEY); tasks = SqlManualTaskRepository(gateway,KEY)
        packages = SqlRecordPackageRepository(gateway,tags)
        bin = SqlRecycleBinRepository(gateway,tags,SqlErasureFileHooks::fenced)
        media = EncryptedAttachmentStore(mediaRoot,Ledger(gateway),KEY.copyOf()) { mediaCheckpoint(it) }.also { it.installErasureHooks(hooks) }
        edits = EncryptedOriginalEditStore(editRoot,KEY.copyOf()) { editCheckpoint(it) }.also { it.installErasureHooks(hooks) }
        erase = SqlPermanentErasureRepository(gateway,media,edits,packages) { checkpoint(it) }
    }
    fun reopen() { retire(); db = PythonSqliteDatabase(databaseFile,bridge,false,7); openAdapters() }
    fun second() = PythonSqliteDatabase(databaseFile,bridge,false,7)
    fun entry(id: String = uuid(), text: String = "合成私密原文🙂") : String {
        db.transaction { sql ->
            sql.execute("INSERT INTO entries VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(id))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(id,text,OriginalExports.hash(text)))
            sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
            sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')",listOf("INDEX:"+id,id))
        }
        return id
    }
    fun intent(id: String): PermanentErasureIntent {
        val before = bin.read(id)
        val record = if (before.deleted) before else bin.apply(RecycleMutation(SourceVersion(id,before.entry.currentRevision.toLong()),before.generation,false,uuid()))
        return PermanentErasureIntent(SourceVersion(id,record.entry.currentRevision.toLong()),record.generation,uuid())
    }
    fun draft(id: String, text: String = "合成私密原文🙂") = OriginalEditDraft(uuid(),id,1,1,1,text,"合成编辑草稿",OriginalEditDraftState.ACTIVE)
    fun attachment(id: String, bytes: ByteArray, revision: Int = 1) = AttachmentIntent(uuid(),id,revision,"image/png",hash(bytes),bytes.size.toLong())
    private fun retire() {
        db.beforeSql = null; db.afterSql = null; db.beforeCommit = null
        gateway.seal(); media.close(); edits.close(); media.awaitIdle(); edits.awaitIdle(); gateway.awaitIdle()
        tags.wipeKey(); tasks.wipeKey(); db.close()
    }
    override fun close() {
        try { retire() } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    internal class Ledger(private val database: RecordsSqlDatabase) : AttachmentLedger {
        override suspend fun find(id: String): LocalAttachment? = database.transaction { sql ->
            sql.query("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments WHERE id=?",listOf(id)).singleOrNull()?.let(::row)
        }
        override suspend fun all(): List<LocalAttachment> = database.transaction { sql ->
            sql.query("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments ORDER BY id").map(::row)
        }
        override suspend fun forEntry(entryId: String, limit: Int): List<LocalAttachment> = database.transaction { sql ->
            sql.query("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments WHERE entryId=? ORDER BY id LIMIT ?",listOf(entryId,limit)).map(::row)
        }
        override suspend fun insert(value: LocalAttachment) = database.transaction { sql ->
            val i = value.intent
            check(value.state == AttachmentState.WRITING && sql.query("SELECT revision FROM entry_revisions WHERE entryId=? AND revision=?",listOf(i.entryId,i.revision)).size == 1)
            sql.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)",listOf(i.id,i.entryId,i.revision,i.mediaType,i.sha256,i.byteSize,value.state.name)); Unit
        }
        override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment = database.transaction { sql ->
            check(sql.execute("UPDATE attachments SET state=? WHERE id=? AND state=?",listOf(next.name,expected.intent.id,expected.state.name)) == 1)
            LocalAttachment(expected.intent,next)
        }
        private fun row(r: List<Any?>) = LocalAttachment(AttachmentIntent(r[0] as String,r[1] as String,(r[2] as Long).toInt(),r[3] as String,r[4] as String,r[5] as Long),AttachmentState.valueOf(r[6] as String))
    }
    companion object {
        val KEY = ByteArray(32) { (it+71).toByte() }
        fun uuid() = UUID.randomUUID().toString()
        fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Upgrade only package consumers; the v5/v6 migration preservation fixtures keep their old contract. */
internal fun packageFixture() = ManualTaskSqlTest.Fixture().also { f -> f.db.transaction { sql ->
    LocalSearchSqlSchema.migration6To7.forEach { sql.execute(it) }; LocalSearchSqlSchema.guards.forEach { sql.execute(it) }
    PermanentErasureSqlSchema.migration7To8.forEach { sql.execute(it) }; PermanentErasureSqlSchema.guards.forEach { sql.execute(it) }
    sql.execute("PRAGMA user_version=8")
} }
