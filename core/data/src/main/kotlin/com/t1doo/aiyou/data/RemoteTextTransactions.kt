package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CancellationException

interface RecordsSqlDatabase { fun <T> transaction(block: (RecordsSqlConnection) -> T): T }
interface RecordsSqlConnection {
    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
    fun execute(sql: String, args: List<Any?> = emptyList()): Int
}
class RemoteTextStorageFailure : RuntimeException("Remote text storage unavailable")

/** All authoritative reads, checks and writes use one records database transaction. */
class RemoteTextTransactions(
    private val database: RecordsSqlDatabase,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SourceBoundTextRepository {
    /** Read current metadata for a later generation-CAS action; originals are never returned. */
    fun readAccess(sourceId: String): SourceAccessSnapshot? {
        try {
            return database.transaction { db ->
                readSource(db,sourceId)?.let { SourceAccessSnapshot(it.state,it.generation) }
            }
        } catch(failure:Throwable) {
            propagateCritical(failure)
            throw RemoteTextStorageFailure()
        }
    }

    override fun queue(intent: RemoteSendIntent, promptVersion: String, clock: () -> Instant): SourceQueueOutcome =
        safe(SourceQueueOutcome.Denied(SourceCommitDenial.STORAGE_UNAVAILABLE)) {
            database.transaction { db ->
                if (promptVersion.isBlank() || promptVersion.length>128 || !RemoteTextCodec.validUnicode(promptVersion))
                    return@transaction SourceQueueOutcome.Denied(SourceCommitDenial.JOB_INVALID)
                val source=readSource(db,intent.source.sourceId)
                    ?: return@transaction SourceQueueOutcome.Denied(SourceCommitDenial.SOURCE_CHANGED)
                authorize(source,intent,clock())?.let { return@transaction SourceQueueOutcome.Denied(it) }
                val config=checkNotNull(source.state.connection); val grant=checkNotNull(source.state.consent)
                val binding=RemoteTextCodec.binding(intent.source,intent.purpose,promptVersion,source.generation,config,grant)
                val existing=db.query("SELECT $JOB_COLUMNS FROM remote_text_jobs WHERE bindingKey=?",listOf(binding)).singleOrNull()
                if(existing!=null) {
                    val stored=readJob(existing)
                    // A queued/admitted capability is owned by its first exchange. Never share it
                    // with a second caller that could cancel or replay the first operation.
                    if(stored.status!="COMMITTED") return@transaction SourceQueueOutcome.Denied(SourceCommitDenial.JOB_INVALID)
                    val job=job(stored,source)
                    if(!matches(job,stored,source)) return@transaction SourceQueueOutcome.Denied(SourceCommitDenial.JOB_INVALID)
                    authorize(source,intent,clock())?.let { return@transaction SourceQueueOutcome.Denied(it) }
                    val annotation=readAnnotation(db,job,source)
                        ?: return@transaction SourceQueueOutcome.Denied(SourceCommitDenial.JOB_INVALID)
                    authorize(source,intent,clock())?.let { return@transaction SourceQueueOutcome.Denied(it) }
                    return@transaction SourceQueueOutcome.AlreadyCommitted(job,annotation)
                }
                val id=newId(); require(id.isNotBlank() && id.length<=256 && id.none { it.isISOControl() })
                authorize(source,intent,clock())?.let { return@transaction SourceQueueOutcome.Denied(it) }
                db.execute("INSERT INTO remote_text_jobs(id,bindingKey,entryId,sourceRevision,generation,purpose,promptVersion,connection,consent,state,sourceHash) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?)",
                    listOf(id,binding,intent.source.sourceId,intent.source.revision,source.generation,intent.purpose.name,promptVersion,
                        RemoteTextCodec.encodeConnection(config),RemoteTextCodec.encodeConsent(grant),source.hash))
                val stored=readJob(checkNotNull(db.query("SELECT $JOB_COLUMNS FROM remote_text_jobs WHERE id=?",listOf(id)).singleOrNull()))
                SourceQueueOutcome.Queued(job(stored,source))
            }
        }

    override fun check(job: SourceBoundTextJob, clock: () -> Instant): SendDenial? = safe(SendDenial.STATE_UNAVAILABLE) {
        database.transaction { db -> validateJob(db,job,clock)?.toSendDenial() }
    }

    override fun admit(job: SourceBoundTextJob, clock: () -> Instant): SendDenial? = safe(SendDenial.STATE_UNAVAILABLE) {
        database.transaction { db ->
            validateJob(db,job,clock)?.let { return@transaction it.toSendDenial() }
            val stored=storedJob(db,job.id) ?: return@transaction SendDenial.QUEUE_INVALIDATED
            if(stored.status!="QUEUED") return@transaction SendDenial.ALREADY_SENT
            val source=checkNotNull(readSource(db,job.intent.source.sourceId))
            authorize(source,job.intent,clock())?.let { return@transaction it.toSendDenial() }
            check(db.execute("UPDATE remote_text_jobs SET state='ADMITTED' WHERE id=? AND state='QUEUED'",listOf(job.id))==1)
            null
        }
    }

    override fun commit(job: SourceBoundTextJob, candidate: DerivedTextCandidate, clock: () -> Instant): SourceCommitOutcome =
        safe(SourceCommitOutcome.Denied(SourceCommitDenial.STORAGE_UNAVAILABLE)) {
            database.transaction { db ->
                val source=readSource(db,job.intent.source.sourceId)
                    ?: return@transaction SourceCommitOutcome.Denied(SourceCommitDenial.SOURCE_CHANGED)
                val stored=storedJob(db,job.id)
                    ?: return@transaction SourceCommitOutcome.Denied(SourceCommitDenial.JOB_INVALID)
                if(!matches(job,stored,source)) return@transaction SourceCommitOutcome.Denied(metadataDenial(job,source))
                candidateDenial(candidate,source)?.let { return@transaction SourceCommitOutcome.Denied(it) }
                val bytes=try { RemoteTextCodec.encodeCandidate(candidate) } catch(failure:Throwable) {
                    propagateCritical(failure); return@transaction SourceCommitOutcome.Denied(SourceCommitDenial.INVALID_RESULT)
                }
                val hash=RemoteTextCodec.sha256(bytes)
                // This fresh time is read after validation, while holding the same transaction
                // that inserts the annotation and completes the job.
                val now=clock()
                authorize(source,job.intent,now)?.let { return@transaction SourceCommitOutcome.Denied(it) }
                if(stored.status=="COMMITTED") {
                    val old=readAnnotation(db,job,source)
                        ?: return@transaction SourceCommitOutcome.Denied(SourceCommitDenial.JOB_INVALID)
                    val oldHash=RemoteTextCodec.sha256(RemoteTextCodec.encodeCandidate(old.candidate))
                    authorize(source,job.intent,clock())?.let { return@transaction SourceCommitOutcome.Denied(it) }
                    return@transaction if(oldHash==hash) SourceCommitOutcome.Committed(old,true)
                        else SourceCommitOutcome.Denied(SourceCommitDenial.RESULT_CONFLICT)
                }
                if(stored.status!="ADMITTED") return@transaction SourceCommitOutcome.Denied(SourceCommitDenial.JOB_NOT_ADMITTED)
                val timestamp=now.toEpochMilli()
                db.execute("INSERT INTO text_annotations(jobId,entryId,sourceRevision,generation,candidate,resultHash,createdAtMillis) VALUES(?,?,?,?,?,?,?)",
                    listOf(job.id,job.intent.source.sourceId,job.intent.source.revision,job.generation,bytes,hash,timestamp))
                check(db.execute("UPDATE remote_text_jobs SET state='COMMITTED' WHERE id=? AND state='ADMITTED'",listOf(job.id))==1)
                SourceCommitOutcome.Committed(StoredTextAnnotation(job.id,job.intent.source,job.generation,
                    RemoteTextCodec.decodeCandidate(bytes),timestamp),false)
            }
        }

    override fun cancel(job: SourceBoundTextJob) {
        try { database.transaction { db ->
            val source=readSource(db,job.intent.source.sourceId) ?: return@transaction
            val stored=storedJob(db,job.id) ?: return@transaction
            if(matches(job,stored,source) && stored.status in setOf("QUEUED","ADMITTED"))
                db.execute("UPDATE remote_text_jobs SET state='CANCELLED' WHERE id=? AND state IN ('QUEUED','ADMITTED')",listOf(job.id))
        } } catch(failure:Throwable) { propagateCritical(failure); throw RemoteTextStorageFailure() }
    }

    /** Explicit generation-CAS authorization change; new records continue to default LOCAL_ONLY. */
    fun updateAuthorization(source:SourceVersion,expectedGeneration:Long,policy:ProcessingPolicy,
                            connection:ProviderConnection?,consent:ConsentGrant?):SourceCommitDenial? =
        safe(SourceCommitDenial.STORAGE_UNAVAILABLE) { database.transaction { db ->
            val current=readSource(db,source.sourceId) ?: return@transaction SourceCommitDenial.SOURCE_CHANGED
            if(current.state.deleted) return@transaction SourceCommitDenial.SOURCE_DELETED
            if(current.source!=source) return@transaction SourceCommitDenial.SOURCE_CHANGED
            if(expectedGeneration<0 || current.generation!=expectedGeneration) return@transaction SourceCommitDenial.AUTHORIZATION_CHANGED
            if(consent!=null && (connection==null || consent.connection!=connection || consent.source!=source))
                return@transaction SourceCommitDenial.AUTHORIZATION_CHANGED
            val next=Math.addExact(expectedGeneration,1)
            val config=connection?.let(RemoteTextCodec::encodeConnection); val grant=consent?.let(RemoteTextCodec::encodeConsent)
            db.execute("UPDATE entries SET privacyPolicy=? WHERE id=? AND currentRevision=?",listOf(policy.name,source.sourceId,source.revision))
            db.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,?,0,?,?) ON CONFLICT(entryId) DO UPDATE SET generation=excluded.generation, connection=excluded.connection, consent=excluded.consent",
                listOf(source.sourceId,next,config,grant))
            invalidate(db,source.sourceId)
            null
        } }

    fun tombstone(source:SourceVersion,expectedGeneration:Long):SourceCommitDenial? =
        safe(SourceCommitDenial.STORAGE_UNAVAILABLE) { database.transaction { db ->
            val current=readSource(db,source.sourceId) ?: return@transaction SourceCommitDenial.SOURCE_CHANGED
            if(current.source!=source) return@transaction SourceCommitDenial.SOURCE_CHANGED
            if(expectedGeneration<0 || current.generation!=expectedGeneration) return@transaction SourceCommitDenial.AUTHORIZATION_CHANGED
            if(current.state.deleted) return@transaction SourceCommitDenial.SOURCE_DELETED
            val next=Math.addExact(expectedGeneration,1)
            db.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",listOf(source.sourceId))
            db.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,?,1,NULL,NULL) ON CONFLICT(entryId) DO UPDATE SET generation=excluded.generation, deleted=1, connection=NULL, consent=NULL",
                listOf(source.sourceId,next))
            invalidate(db,source.sourceId); null
        } }

    fun annotations(source:SourceVersion,clock:()->Instant):List<StoredTextAnnotation> {
        try { return database.transaction { db ->
            val current=readSource(db,source.sourceId) ?: return@transaction emptyList()
            if(current.source!=source || current.state.deleted || current.state.policy!=ProcessingPolicy.REMOTE_ALLOWED) return@transaction emptyList()
            val rows=db.query("SELECT $JOB_COLUMNS FROM remote_text_jobs WHERE entryId=? AND sourceRevision=? AND generation=? AND state='COMMITTED' ORDER BY id",
                listOf(source.sourceId,source.revision,current.generation))
            val result=rows.mapNotNull { values ->
                val stored=readJob(values); val ticket=job(stored,current)
                if(!matches(ticket,stored,current) || authorize(current,ticket.intent,clock())!=null) null
                else readAnnotation(db,ticket,current)
            }
            val purpose=current.state.consent?.purpose ?: return@transaction emptyList()
            if(authorize(current,RemoteSendIntent(source,purpose),clock())!=null) emptyList() else result
        } } catch(failure:Throwable) { propagateCritical(failure); throw RemoteTextStorageFailure() }
    }

    private fun invalidate(db:RecordsSqlConnection,id:String) {
        db.execute("UPDATE remote_text_jobs SET state='CANCELLED' WHERE entryId=? AND state IN ('QUEUED','ADMITTED')",listOf(id))
        db.execute("DELETE FROM text_annotations WHERE entryId=?",listOf(id))
    }
    private fun validateJob(db:RecordsSqlConnection,job:SourceBoundTextJob,clock:()->Instant):SourceCommitDenial? {
        val source=readSource(db,job.intent.source.sourceId) ?: return SourceCommitDenial.SOURCE_CHANGED
        val stored=storedJob(db,job.id) ?: return SourceCommitDenial.JOB_INVALID
        if(!matches(job,stored,source)) return metadataDenial(job,source)
        if(stored.status=="CANCELLED")return SourceCommitDenial.JOB_INVALID
        return authorize(source,job.intent,clock())
    }
    private fun metadataDenial(job:SourceBoundTextJob,source:SourceSnapshot)=when {
        source.state.deleted -> SourceCommitDenial.SOURCE_DELETED
        source.source!=job.intent.source -> SourceCommitDenial.SOURCE_CHANGED
        source.state.policy!=ProcessingPolicy.REMOTE_ALLOWED -> SourceCommitDenial.POLICY_BLOCKED
        source.generation!=job.generation || source.state.connection!=job.state.connection || source.state.consent!=job.state.consent -> SourceCommitDenial.AUTHORIZATION_CHANGED
        else -> SourceCommitDenial.JOB_INVALID
    }
    private fun authorize(source:SourceSnapshot,intent:RemoteSendIntent,now:Instant):SourceCommitDenial? {
        if(source.state.deleted)return SourceCommitDenial.SOURCE_DELETED
        if(source.source!=intent.source)return SourceCommitDenial.SOURCE_CHANGED
        if(source.state.policy!=ProcessingPolicy.REMOTE_ALLOWED)return SourceCommitDenial.POLICY_BLOCKED
        val config=source.state.connection ?: return SourceCommitDenial.AUTHORIZATION_CHANGED
        val grant=source.state.consent ?: return SourceCommitDenial.AUTHORIZATION_CHANGED
        if(grant.revoked || now<grant.issuedAt || now>=grant.expiresAt)return SourceCommitDenial.CONSENT_NOT_CURRENT
        if(grant.source!=intent.source || grant.connection!=config || grant.purpose!=intent.purpose)return SourceCommitDenial.AUTHORIZATION_CHANGED
        return null
    }
    private fun candidateDenial(value:DerivedTextCandidate,source:SourceSnapshot):SourceCommitDenial? {
        if(value.text.isBlank() || value.text.length>16_384 || !RemoteTextCodec.validUnicode(value.text))return SourceCommitDenial.INVALID_RESULT
        if(value.references.size !in 1..32)return SourceCommitDenial.INVALID_REFERENCE
        val count=source.text.codePointCount(0,source.text.length)
        for(ref in value.references) {
            if(ref.source!=source.source || ref.start<0 || ref.end<=ref.start || ref.end>count || !RemoteTextCodec.validUnicode(ref.quote))
                return SourceCommitDenial.INVALID_REFERENCE
            val start=source.text.offsetByCodePoints(0,ref.start); val end=source.text.offsetByCodePoints(0,ref.end)
            if(source.text.substring(start,end)!=ref.quote)return SourceCommitDenial.INVALID_REFERENCE
        }
        return null
    }
    private data class SourceSnapshot(val source:SourceVersion,val text:String,val hash:String,val captured:Long,
        val zone:String,val generation:Long,val state:RemoteSendState)
    private data class StoredJob(val id:String,val binding:String,val source:SourceVersion,val generation:Long,
        val purpose:TextPurpose,val prompt:String,val connection:ProviderConnection,val consent:ConsentGrant,val status:String,val hash:String)
    private fun readSource(db:RecordsSqlConnection,id:String):SourceSnapshot? {
        val row=db.query("SELECT e.id,e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0),a.connection,a.consent FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(id)).singleOrNull() ?: return null
        val version=number(row[1]); require(version in 1..Int.MAX_VALUE.toLong())
        val original=string(row[2]); require(original.length<=100_000 && RemoteTextCodec.validUnicode(original))
        val hash=string(row[3]); require(hash==RemoteTextCodec.textHash(original)); val zone=string(row[5]); ZoneId.of(zone)
        val source=SourceVersion(string(row[0]),version); val generation=number(row[7]); require(generation>=0)
        val deleted=number(row[8]); require(deleted in 0..1)
        val connection=(row[9] as? ByteArray)?.let { canonicalConnection(it) }
        val consent=(row[10] as? ByteArray)?.let { canonicalConsent(it) }
        require(row[9]==null || row[9] is ByteArray); require(row[10]==null || row[10] is ByteArray)
        val state=RemoteSendState(source,ProcessingPolicy.valueOf(string(row[6])),deleted==1L,connection,consent)
        return SourceSnapshot(source,original,hash,number(row[4]),zone,generation,state)
    }
    private fun storedJob(db:RecordsSqlConnection,id:String)=db.query("SELECT $JOB_COLUMNS FROM remote_text_jobs WHERE id=?",listOf(id)).singleOrNull()?.let(::readJob)
    private fun readJob(row:List<Any?>):StoredJob {
        val version=number(row[3]); require(version in 1..Int.MAX_VALUE.toLong()); val generation=number(row[4]);require(generation>0)
        val stored=StoredJob(string(row[0]),string(row[1]),SourceVersion(string(row[2]),version),generation,
            TextPurpose.valueOf(string(row[5])),string(row[6]),canonicalConnection(row[7] as ByteArray),canonicalConsent(row[8] as ByteArray),string(row[9]),string(row[10]))
        require(stored.prompt.isNotBlank() && stored.prompt.length<=128 && stored.status in setOf("QUEUED","ADMITTED","COMMITTED","CANCELLED"))
        require(stored.binding==RemoteTextCodec.binding(stored.source,stored.purpose,stored.prompt,stored.generation,stored.connection,stored.consent))
        return stored
    }
    private fun job(row:StoredJob,source:SourceSnapshot)=SourceBoundTextJob(row.id,row.generation,row.prompt,
        RemoteSendIntent(row.source,row.purpose),RemoteSendState(row.source,ProcessingPolicy.REMOTE_ALLOWED,false,row.connection,row.consent),
        source.text,row.hash,source.captured,source.zone)
    private fun matches(value:SourceBoundTextJob,row:StoredJob,source:SourceSnapshot):Boolean =
        value.id==row.id && value.generation==row.generation && value.promptVersion==row.prompt && value.intent==RemoteSendIntent(row.source,row.purpose) &&
        value.state==source.state && value.state.connection==row.connection && value.state.consent==row.consent && value.originalText==source.text &&
        value.contentHash==source.hash && row.hash==source.hash && value.capturedAtMillis==source.captured && value.captureTimeZone==source.zone &&
        source.source==row.source && source.generation==row.generation
    private fun readAnnotation(db:RecordsSqlConnection,job:SourceBoundTextJob,source:SourceSnapshot):StoredTextAnnotation? {
        val row=db.query("SELECT entryId,sourceRevision,generation,candidate,resultHash,createdAtMillis FROM text_annotations WHERE jobId=?",listOf(job.id)).singleOrNull() ?: return null
        require(string(row[0])==source.source.sourceId && number(row[1])==source.source.revision && number(row[2])==source.generation)
        val bytes=row[3] as ByteArray; require(RemoteTextCodec.sha256(bytes)==string(row[4]))
        val candidate=RemoteTextCodec.decodeCandidate(bytes); require(bytes.contentEquals(RemoteTextCodec.encodeCandidate(candidate)))
        require(candidateDenial(candidate,source)==null)
        return StoredTextAnnotation(job.id,source.source,source.generation,candidate,number(row[5]))
    }
    private fun canonicalConnection(bytes:ByteArray)=RemoteTextCodec.decodeConnection(bytes).also { require(bytes.contentEquals(RemoteTextCodec.encodeConnection(it))) }
    private fun canonicalConsent(bytes:ByteArray)=RemoteTextCodec.decodeConsent(bytes).also { require(bytes.contentEquals(RemoteTextCodec.encodeConsent(it))) }
    private fun string(value:Any?)=value as String
    private fun number(value:Any?):Long=when(value) { is Long->value;is Int->value.toLong();else->throw RemoteTextStorageFailure() }
    private fun SourceCommitDenial.toSendDenial()=when(this) {
        SourceCommitDenial.SOURCE_CHANGED->SendDenial.SOURCE_CHANGED
        SourceCommitDenial.SOURCE_DELETED->SendDenial.SOURCE_DELETED
        SourceCommitDenial.POLICY_BLOCKED->SendDenial.POLICY_BLOCKED
        SourceCommitDenial.CONSENT_NOT_CURRENT->SendDenial.CONSENT_EXPIRED
        SourceCommitDenial.AUTHORIZATION_CHANGED->SendDenial.QUEUE_INVALIDATED
        else->SendDenial.QUEUE_INVALIDATED
    }
    private fun <T> safe(fallback:T,block:()->T):T=try { block() } catch(failure:Throwable) { propagateCritical(failure);fallback }
    private fun propagateCritical(failure:Throwable) {
        if(failure is CancellationException || failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError)throw failure
    }
    companion object { private const val JOB_COLUMNS="id,bindingKey,entryId,sourceRevision,generation,purpose,promptVersion,connection,consent,state,sourceHash" }
}
