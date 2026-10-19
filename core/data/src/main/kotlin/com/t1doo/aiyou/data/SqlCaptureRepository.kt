package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64
import java.util.UUID
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** One encrypted draft slot; durable file intents precede any IO. No entry exists until all media authenticate. */
internal class SqlCaptureRepository(private val database: RecordsSqlDatabase,private val media: EncryptedAttachmentStore,
    private val tags: SqlManualTagRepository,
    private val newDraftPolicy: () -> ProcessingPolicy = { ProcessingDefaults.policy }) : CaptureRepository {
    override val supportsProcessingChoice = true
    override suspend fun changePolicy(expected: CaptureDraft, policy: ProcessingPolicy): CaptureDraft = safely {
        require(policy == ProcessingPolicy.LOCAL_ONLY || policy == ProcessingPolicy.AI_NONE)
        database.transaction { sql ->
            val actual = required(sql, expected.id); require(!actual.discarding)
            // A lost acknowledgement retries the exact version/content/range transition, never a rebase.
            if (expected.policy == policy) { same(expected, actual); actual }
            else {
                val changed = next(expected, policy = policy)
                if (encode(actual) == encode(changed)) actual
                else { same(expected, actual); replace(sql, actual, changed) }
            }
        }
    }
    override suspend fun current(): CaptureDraft = safely { database.transaction { sql ->
        read(sql) ?: run {
            val legacy=sql.query("SELECT id,version,text FROM capture_draft WHERE slot=1").singleOrNull()
            // A device default applies only at creation, never to an existing or legacy draft.
            val policy=if(legacy==null) newDraftPolicy() else ProcessingDefaults.policy
            require(policy==ProcessingPolicy.LOCAL_ONLY || policy==ProcessingPolicy.AI_NONE)
            val draft=CaptureDraft(legacy?.get(0) as String? ?: uuid(),1,legacy?.get(2) as String? ?: "",emptyList(),policy=policy)
            sql.execute("INSERT INTO record_package_receipts VALUES(?,?,1,'CAPTURE_CURRENT',?)",listOf(SLOT,draft.id,encode(draft)))
            if (legacy!=null) require(sql.execute("DELETE FROM capture_draft WHERE slot=1 AND id=? AND version=?",listOf(legacy[0],legacy[1]))==1)
            draft
        }
    } }
    override suspend fun edit(expected: CaptureDraft,text: String): CaptureDraft = safely { database.transaction { sql ->
        val actual=required(sql,expected.id);require(!actual.discarding)
        if (actual.text==text && actual.version==expected.version+1 && actual.images==expected.images && actual.policy==expected.policy) actual
        else { same(expected,actual);replace(sql,actual,next(actual,text=text)) }
    } }
    override suspend fun add(expected: CaptureDraft,intent: AttachmentIntent,bytes: ByteArray): CaptureDraft = safely {
        EncryptedAttachmentStore.validate(intent)
        require(intent.entryId==expected.id && intent.revision==1 && intent.mediaType in setOf("image/png","image/jpeg"))
        require(bytes.size.toLong()==intent.byteSize && packageHex(packageHash(bytes))==intent.sha256)
        RecordMediaPackageCipher.checkImage(RecordPackageImage(intent.entryId,1,intent.id,intent.mediaType,intent.byteSize,intent.sha256),bytes)
        val bound=database.transaction { sql ->
            val actual=required(sql,expected.id);require(!actual.discarding)
            require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf(REMOVED+intent.id)).isEmpty())
            val old=actual.images.singleOrNull { it.intent.id==intent.id }
            if (old!=null) { require(old.intent==intent && old.state!=CaptureImageState.REMOVING);actual }
            else { same(expected,actual);replace(sql,actual,next(actual,images=actual.images+CaptureDraftImage(intent,CaptureImageState.WRITING))) }
        }
        if (bound.images.single { it.intent.id==intent.id }.state==CaptureImageState.READY) {
            media.readCapture(intent).fill(0);return@safely bound
        }
        media.stageCapture(intent,bytes) { database.transaction { sql ->
            val actual=required(sql,expected.id);require(!actual.discarding)
            val item=actual.images.single { it.intent.id==intent.id };require(item.intent==intent && item.state!=CaptureImageState.REMOVING)
            replace(sql,actual,next(actual,images=actual.images.map { if(it.intent.id==intent.id) it.copy(state=CaptureImageState.READY) else it }))
        } }
    }
    override suspend fun image(expected: CaptureDraft,id: String): ByteArray = safely {
        val i=database.transaction { sql ->
            val actual=required(sql,expected.id);require(!actual.discarding)
            val i=actual.images.single { it.intent.id==id };require(i.state==CaptureImageState.READY && expected.images.any { it==i });i.intent
        }
        media.readCapture(i)
    }
    override suspend fun remove(draftId: String,imageId: String): CaptureDraft = safely {
        val actual=database.transaction { sql ->
            val a=required(sql,draftId);require(!a.discarding)
            val item=a.images.singleOrNull { it.intent.id==imageId } ?: return@transaction a
            if(item.state==CaptureImageState.REMOVING) a else replace(sql,a,next(a,images=a.images.map { if(it.intent.id==imageId) it.copy(state=CaptureImageState.REMOVING) else it }))
        }
        val item=actual.images.singleOrNull { it.intent.id==imageId } ?: return@safely actual
        media.cleanupPackage(listOf(item.intent))
        database.transaction { sql ->
            val a=required(sql,draftId);require(a.images.single { it.intent.id==imageId }.state==CaptureImageState.REMOVING)
            if (sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf(REMOVED+imageId)).isEmpty())
                sql.execute("INSERT INTO record_package_receipts VALUES(?,?,1,'CAPTURE_REMOVED',?)",listOf(REMOVED+imageId,draftId,item.intent.sha256))
            replace(sql,a,next(a,images=a.images.filterNot { it.intent.id==imageId }))
        }
    }
    override suspend fun discard(draftId: String): CaptureDraft = safely {
        val old=database.transaction { sql ->
            val a=read(sql)
            if (a?.id!=draftId) {
                require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf(DISCARDED+draftId)).isNotEmpty());null
            } else if(a.discarding) a else replace(sql,a,next(a,discarding=true))
        }
        if(old!=null) {
            media.cleanupPackage(old.images.map { it.intent })
            database.transaction { sql ->
                val a=required(sql,draftId);require(a.discarding)
                sql.execute("INSERT INTO record_package_receipts VALUES(?,?,1,'CAPTURE_DISCARDED',?)",listOf(DISCARDED+draftId,draftId,digest(a)))
                require(sql.execute("DELETE FROM record_package_receipts WHERE id=? AND state=?",listOf(SLOT,encode(a)))==1)
            }
        }
        current()
    }
    override suspend fun save(expected: CaptureDraft,capturedAtMillis: Long,zone: String): CaptureSaveResult = safely {
        ZoneId.of(zone);require(!expected.discarding && (expected.text.isNotBlank() || expected.images.isNotEmpty()) && expected.images.all { it.state==CaptureImageState.READY })
        OriginalExports.hash(expected.text) // Completed Unicode only; unfinished input remains in the draft.
        database.transaction { replay(it,expected) }?.let { return@safely it }
        media.commitCapture(expected.images.map { it.intent }) { database.transaction { sql ->
            replay(sql,expected)?.let { return@transaction it }
            val a=required(sql,expected.id);same(expected,a)
            require(!SqlErasureFileHooks.fenced(sql,a.id))
            require((sql.query("SELECT count(*) FROM record_package_receipts WHERE operation='CAPTURE_SAVED'").single().single() as Long)<4096)
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,?)",listOf(a.id,capturedAtMillis,zone,a.policy.name))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)",listOf(a.id,a.text,OriginalExports.hash(a.text),capturedAtMillis))
            sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(a.id))
            a.images.forEach { val i=it.intent
                sql.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,'READY')",listOf(i.id,i.entryId,1,i.mediaType,i.sha256,i.byteSize))
                sql.execute("DELETE FROM owned_file_creations WHERE entryId=? AND bucket='ATTACHMENTS' AND name=?",listOf(a.id,i.id+".aead"))
            }
            tags.createWithin(sql,a.id,emptyList(),"CAPTURE_TAG:"+a.id)
            sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')",listOf("CAPTURE_INDEX:"+a.id,a.id))
            sql.execute("INSERT INTO record_package_receipts VALUES(?,?,1,'CAPTURE_SAVED',?)",listOf(SAVED+a.id,a.id,digest(a)))
            require(sql.execute("DELETE FROM record_package_receipts WHERE id=? AND state=?",listOf(SLOT,encode(a)))==1)
            CaptureSaveResult(a.id,false,false)
        } }
    }
    override suspend fun recover(): Unit = safely {
        val a=database.transaction { read(it) } ?: return@safely
        if(a.discarding) { discard(a.id);return@safely }
        for(item in a.images) {
            if(item.state==CaptureImageState.REMOVING) { remove(a.id,item.intent.id);continue }
            val ready=media.recoverCapture(item.intent)
            val state=if(ready) CaptureImageState.READY else CaptureImageState.FAILED
            database.transaction { sql ->
                val current=required(sql,a.id);val found=current.images.singleOrNull { it.intent.id==item.intent.id } ?: return@transaction
                require(found.intent==item.intent && !current.discarding)
                if(found.state==CaptureImageState.REMOVING) return@transaction
                if(found.state!=state) replace(sql,current,next(current,images=current.images.map { if(it.intent.id==item.intent.id) it.copy(state=state) else it }))
            }
        }
    }
    private fun replay(sql: RecordsSqlConnection,expected: CaptureDraft): CaptureSaveResult? {
        val row=sql.query("SELECT entryId,state FROM record_package_receipts WHERE id=? AND operation='CAPTURE_SAVED'",listOf(SAVED+expected.id)).singleOrNull() ?: return null
        require(row==listOf(expected.id,digest(expected)))
        val entry=sql.query("SELECT id FROM entries WHERE id=?",listOf(expected.id)).isNotEmpty()
        val erased=SqlErasureFileHooks.fenced(sql,expected.id)
        require(entry || erased)
        val deleted=erased || sql.query("SELECT entryId FROM remote_source_access WHERE entryId=? AND deleted=1",listOf(expected.id)).isNotEmpty()
        return CaptureSaveResult(expected.id,true,deleted)
    }
    private fun next(a: CaptureDraft,text: String=a.text,images: List<CaptureDraftImage> = a.images,discarding: Boolean=a.discarding,
        policy: ProcessingPolicy=a.policy)=CaptureDraft(a.id,Math.addExact(a.version,1),text,images,discarding,policy)
    private fun same(a: CaptureDraft,b: CaptureDraft) { if(encode(a)!=encode(b)) throw CaptureConflict() }
    internal fun read(sql: RecordsSqlConnection): CaptureDraft? = sql.query("SELECT entryId,state FROM record_package_receipts WHERE id=? AND operation='CAPTURE_CURRENT'",listOf(SLOT)).singleOrNull()?.let { decode(it[1] as String).also { a ->require(a.id==it[0]) } }
    private fun required(sql: RecordsSqlConnection,id: String)=checkNotNull(read(sql)).also { if(it.id!=id) throw CaptureConflict() }
    private fun replace(sql: RecordsSqlConnection,a: CaptureDraft,b: CaptureDraft): CaptureDraft {
        require(a.id==b.id && b.version==a.version+1)
        if(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",listOf(encode(b),SLOT,encode(a)))!=1) throw CaptureConflict()
        return b
    }
    private fun encode(a: CaptureDraft): String=PackageWriter(420000).use { out ->
        // LOCAL_ONLY retains the exact v1 bytes, including existing CAS states and save receipts.
        // v2 adds an explicitly restricted AI_NONE draft; older readers reject it rather than weaken it.
        out.i32(if(a.policy==ProcessingPolicy.LOCAL_ONLY) 1 else 2)
        if(a.policy==ProcessingPolicy.AI_NONE) out.string(a.policy.name,16)
        out.string(a.id,36);out.i64(a.version);out.i32(if(a.discarding) 1 else 0);out.i32(a.text.length)
        a.text.forEach { out.i32(it.code) };out.i32(a.images.size)
        a.images.forEach { val i=it.intent;out.string(i.id,36);out.string(i.mediaType,32);out.i64(i.byteSize);out.raw(packageUnhex(i.sha256));out.i32(it.state.ordinal) }
        val bytes=out.bytes();try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
    }
    internal fun decode(encoded:String):CaptureDraft = decodeForBackup(encoded)
    private fun digest(a: CaptureDraft)=packageHex(packageHash(encode(a).toByteArray(Charsets.US_ASCII)))
    private suspend inline fun <T> safely(block: () -> T): T=try { block() } catch(e:CancellationException){throw e} catch(e:CaptureConflict){throw e} catch(_:Exception){throw CaptureStorageFailure()}
    companion object {
    internal fun decodeForBackup(encoded: String): CaptureDraft = CaptureDraftCodec.decode(encoded)
 private const val SLOT="CAPTURE_CURRENT";private const val SAVED="CAPTURE_SAVED:";private const val DISCARDED="CAPTURE_DISCARDED:";private const val REMOVED="CAPTURE_REMOVED:";private fun uuid()=UUID.randomUUID().toString() }
}
