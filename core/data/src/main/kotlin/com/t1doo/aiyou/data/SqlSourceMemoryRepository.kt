package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Profile2 shares the physical candidate table, never excerpt payloads or their key purpose. */
internal class SqlSourceMemoryRepository(private val database: RecordsSqlDatabase, key: ByteArray) : SourceMemoryRepository {
    private val cipher=SourceMemoryCipher(key)
    private data class Row(val id:String,val source:SourceVersion,val hash:String,val generation:Long,
        val profile:Int,val version:Long,val state:String,val mutation:String,val created:Long,val bytes:ByteArray)
    private inner class InboxProof(override val cursor:SourceMemoryInboxCursor?, override val section:SourceMemoryInboxSection,
        override val next:SourceMemoryInboxCursor?, values:List<SourceMemorySnapshot>, val signatures:List<String>) : SourceMemoryInboxSnapshot {
        val owner=this@SqlSourceMemoryRepository
        override val items=java.util.Collections.unmodifiableList(values.toList())
        override fun toString()="SourceMemoryInboxSnapshot(redacted)"
    }
    override val inbox:SourceMemoryInboxRepository=object:SourceMemoryInboxRepository {
        override fun capture(cursor:SourceMemoryInboxCursor?,section:SourceMemoryInboxSection):SourceMemoryInboxSnapshot = safe {
            database.transaction { readInbox(it,cursor,section) }
        }
        override fun publishCurrent(snapshot:SourceMemoryInboxSnapshot,publish:()->Unit)=verifyInbox(snapshot,null,publish)
        override fun resolve(snapshot:SourceMemoryInboxSnapshot,item:SourceMemorySnapshot,publish:()->Unit)=verifyInbox(snapshot,item,publish)
    }
    private fun verifyInbox(snapshot:SourceMemoryInboxSnapshot,item:SourceMemorySnapshot?,publish:()->Unit):Boolean = safe {
        val proof=snapshot as? InboxProof ?: return@safe false
        if(proof.owner!==this || item!=null && proof.items.none { it===item }) return@safe false
        database.transaction { sql ->
            val fresh=readInbox(sql,proof.cursor,proof.section)
            if(fresh.signatures!=proof.signatures || fresh.next!=proof.next) false else { publish();true }
        }
    }
    private fun readInbox(sql:RecordsSqlConnection,cursor:SourceMemoryInboxCursor?,section:SourceMemoryInboxSection):InboxProof {
        val args=if(cursor==null) emptyList() else listOf(cursor.createdAtMillis,cursor.createdAtMillis,cursor.id)
        val page=if(cursor==null) "" else " AND (c.createdAtMillis<? OR (c.createdAtMillis=? AND c.id>?))"
        // Filter eligibility before loading any original or ciphertext. One lookahead row proves more pages.
        val metadata=sql.query("SELECT c.id,c.entryId,c.sourceRevision,c.createdAtMillis,length(c.payload),length(CAST(r.originalText AS BLOB)) "+
            "FROM local_candidate_batches c JOIN entries e ON e.id=c.entryId "+
            "JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision "+
            "LEFT JOIN remote_source_access a ON a.entryId=e.id "+
            "WHERE c.ruleVersion=2 AND c.state='READY' AND c.sourceRevision=e.currentRevision "+
            "AND e.privacyPolicy IN ('LOCAL_ONLY','REMOTE_ALLOWED') AND COALESCE(a.deleted,0)=0 "+
            "AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"+page+
            " ORDER BY c.createdAtMillis DESC,c.id LIMIT 21",args)
        val selected=metadata.take(20)
        require(selected.sumOf { it[4] as Long }<=2_097_152 &&
            selected.distinctBy { it[1] }.sumOf { it[5] as Long }<=8_000_000)
        val sources=mutableMapOf<SourceVersion,SourceMemoryPreview>()
        val items=mutableListOf<SourceMemorySnapshot>();val signatures=mutableListOf<String>()
        selected.forEach { cells ->
            val id=cells[0] as String;val row=checkNotNull(row(sql,id))
            val source=sources.getOrPut(row.source) { readSource(sql,row.source) }
            validateSourceCapacity(sql,row.source)
            val saved=snapshot(row,payload(row,source)) // Authenticate every selected row, including non-pending rows.
            signatures+=receipt { w ->
                w.string(row.id,512);w.string(row.hash,64);w.i64(row.generation);w.i64(row.version);w.i64(row.created)
                w.string(MessageDigest.getInstance("SHA-256").digest(row.bytes).joinToString("") { "%02x".format(it.toInt() and 255) },64)
                w.i64(source.capturedAtMillis);w.string(source.captureTimeZone,128);w.string(source.policy.name,32)
            }
            if(saved.current?.review==section.review) items+=saved
        }
        val next=if(metadata.size>20) selected.last().let { SourceMemoryInboxCursor(it[3] as Long,it[0] as String) } else null
        return InboxProof(cursor,section,next,items,signatures)
    }
    override fun preview(source:SourceVersion):SourceMemoryPreview = safe { database.transaction { readSource(it,source) } }
    override fun memories(source:SourceVersion):List<SourceMemorySnapshot> = safe { database.transaction { sql ->
        val current=readSource(sql,source)
        validateSourceCapacity(sql,source)
        sql.query("SELECT "+COLUMNS+" FROM local_candidate_batches WHERE entryId=? AND sourceRevision=? AND ruleVersion=2 ORDER BY CASE WHEN state='READY' THEN 0 ELSE 1 END,createdAtMillis DESC,id LIMIT 20",
            listOf(source.sourceId,source.revision)).map { cells ->
            val row=decode(cells)
            if(row.state=="INVALIDATED" || !matches(row,current)) snapshot(row,null) else snapshot(row,payload(row,current))
        }
    } }
    override fun create(request:SourceMemoryCreate):SourceMemorySnapshot = safe { database.transaction { sql ->
        val source=readSource(sql,request.preview.source);val p=request.preview
        if(source.sourceHash!=p.sourceHash || source.generation!=p.generation || source.original!=p.original ||
            source.capturedAtMillis!=p.capturedAtMillis || source.captureTimeZone!=p.captureTimeZone || source.policy!=p.policy) throw SourceMemoryConflict()
        validate(request.content,source)
        val receipt=createReceipt(request)
        val old=row(sql,request.id)
        if(old!=null) {
            bind(old,source);val saved=payload(old,source)
            if(old.version!=1L || old.mutation!=receipt || old.created!=request.requestedAtMillis ||
                saved.operation!="CREATE" || saved.epoch!=p.epoch || saved.versions.single().content!=request.content)
                throw SourceMemoryConflict()
            return@transaction snapshot(old,saved)
        }
        if(source.epoch!=p.epoch) throw SourceMemoryConflict()
        if(readyCount(sql,source.source).also(SourceMemoryCapacity::count)>=SourceMemoryRules.MAX_MEMORIES) throw SourceMemoryLimit()
        val version=SourceMemoryVersion(1,request.content,SourceMemoryReview.PROPOSED,null,null,request.requestedAtMillis,request.requestedAtMillis)
        val initial=Row(request.id,source.source,source.sourceHash,source.generation,PROFILE_VERSION,1,"READY",receipt,request.requestedAtMillis,byteArrayOf())
        val data=SourceMemoryCipher.Payload(source.capturedAtMillis,source.captureTimeZone,source.policy,p.epoch,listOf(version),"CREATE",0,0,null,request.requestedAtMillis)
        sql.execute("INSERT INTO local_candidate_batches("+COLUMNS+") VALUES(?,?,?,?,?,?,?,?,?,?,?)",listOf(initial.id,initial.source.sourceId,
            initial.source.revision,initial.hash,initial.generation,initial.profile,initial.version,initial.state,initial.mutation,initial.created,cipher.seal(aad(initial),data)))
        val saved=checkNotNull(row(sql,initial.id));check(payload(saved,source)==data);snapshot(saved,data)
    } }
    override fun mutate(request:SourceMemoryMutation):SourceMemorySnapshot = safe { database.transaction { sql ->
        val source=readSource(sql,request.source);val old=row(sql,request.id) ?: throw SourceMemoryConflict();bind(old,source)
        validateSourceCapacity(sql,source.source)
        if(request.sourceHash!=old.hash || request.generation!=old.generation) throw SourceMemoryConflict()
        val before=payload(old,source);val latest=before.versions.last();val receipt=mutationReceipt(request)
        val nextReview=try { SourceMemoryRules.decision(request.previousReview,request.action) } catch(_:IllegalArgumentException) { throw SourceMemoryConflict() }
        request.content?.let { validate(it,source) }
        if(old.mutation==receipt) {
            if(old.version!=request.expectedVersion+1 || before.operation!=request.action.name || before.expectedVersion!=request.expectedVersion ||
                before.expectedContentRevision!=request.expectedContentRevision || before.previousReview!=request.previousReview || before.at!=request.requestedAtMillis ||
                latest.review!=nextReview || latest.revision!=request.expectedContentRevision+(if(request.action==SourceMemoryAction.CORRECT) 1 else 0) ||
                request.content!=null && latest.content!=request.content) throw SourceMemoryConflict()
            return@transaction snapshot(old,before)
        }
        if(old.version!=request.expectedVersion || latest.revision!=request.expectedContentRevision || latest.review!=request.previousReview) throw SourceMemoryConflict()
        val versions=if(request.action==SourceMemoryAction.CORRECT) {
            if(before.versions.size>=SourceMemoryRules.MAX_VERSIONS) throw SourceMemoryLimit()
            val content=checkNotNull(request.content);if(content==latest.content) throw SourceMemoryConflict()
            before.versions.dropLast(1)+latest.copy(review=SourceMemoryReview.SUPERSEDED,priorReview=latest.review)+
                SourceMemoryVersion(latest.revision+1,content,SourceMemoryReview.PROPOSED,null,latest.revision,request.requestedAtMillis,request.requestedAtMillis)
        } else before.versions.dropLast(1)+latest.copy(review=nextReview,reviewedAtMillis=request.requestedAtMillis)
        val data=before.copy(versions=versions,operation=request.action.name,expectedVersion=request.expectedVersion,
            expectedContentRevision=request.expectedContentRevision,previousReview=request.previousReview,at=request.requestedAtMillis)
        val next=old.copy(version=old.version+1,mutation=receipt)
        if(sql.execute("UPDATE local_candidate_batches SET version=?,lastMutationId=?,payload=? WHERE id=? AND version=? AND state='READY' AND ruleVersion=2",
                listOf(next.version,receipt,cipher.seal(aad(next),data),old.id,old.version))!=1) throw SourceMemoryConflict()
        val saved=checkNotNull(row(sql,old.id));check(saved.version==next.version && saved.mutation==receipt && payload(saved,source)==data)
        snapshot(saved,data)
    } }
    private fun readSource(sql:RecordsSqlConnection,expected:SourceVersion):SourceMemoryPreview {
        val r=sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(expected.sourceId)).singleOrNull() ?: throw SourceMemoryConflict()
        if(r[0]!=expected.revision || r[7]!=0L || r[5]==ProcessingPolicy.AI_NONE.name ||
            sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=? LIMIT 1",listOf(expected.sourceId)).isNotEmpty()) throw SourceMemoryConflict()
        val epoch=sql.query("SELECT epoch FROM local_search_epoch WHERE slot=1").single().single() as Long
        ZoneId.of(r[4] as String)
        return SourceMemoryPreview(expected,r[2] as String,r[6] as Long,r[1] as String,r[3] as Long,r[4] as String,ProcessingPolicy.valueOf(r[5] as String),epoch)
    }
    private fun matches(row:Row,source:SourceMemoryPreview)=row.source==source.source && row.hash==source.sourceHash && row.generation==source.generation && row.profile==PROFILE_VERSION
    private fun readyCount(sql:RecordsSqlConnection,source:SourceVersion)=sql.query(
        "SELECT COUNT(*) FROM local_candidate_batches WHERE entryId=? AND sourceRevision=? AND ruleVersion=2 AND state='READY'",
        listOf(source.sourceId,source.revision)).single().single() as Long
    private fun validateSourceCapacity(sql:RecordsSqlConnection,source:SourceVersion)=SourceMemoryCapacity.count(readyCount(sql,source))
    private fun bind(row:Row,source:SourceMemoryPreview) { if(row.state!="READY" || !matches(row,source)) throw SourceMemoryConflict() }
    private fun validate(content:SourceMemoryContent,source:SourceMemoryPreview) {
        try { SourceMemoryRules.validate(content,source.source,source.original) } catch(_:IllegalArgumentException) { throw InvalidSourceMemory() }
    }
    private fun payload(row:Row,source:SourceMemoryPreview):SourceMemoryCipher.Payload {
        bind(row,source);val p=cipher.open(aad(row),row.bytes,row.source)
        require(row.version>=1 && p.captured==source.capturedAtMillis && p.zone==source.captureTimeZone && p.policy==source.policy && p.epoch>=0)
        require(p.versions.isNotEmpty() && p.versions.map { it.revision }==(1..p.versions.size).toList())
        p.versions.forEachIndexed { i,v ->
            validate(v.content,source)
            require(if(i==p.versions.lastIndex) v.review!=SourceMemoryReview.SUPERSEDED else v.review==SourceMemoryReview.SUPERSEDED)
        }
        require(p.versions.first().createdAtMillis==row.created && p.at>=0)
        if(p.operation=="CREATE") require(row.version==1L && p.expectedVersion==0L && p.expectedContentRevision==0 && p.previousReview==null &&
            p.versions.size==1 && p.versions.last().review==SourceMemoryReview.PROPOSED && p.at==row.created)
        else {
            require(p.expectedVersion==row.version-1 && p.expectedContentRevision in 1..8 && p.previousReview!=null)
            val action=SourceMemoryAction.valueOf(p.operation);val current=p.versions.last()
            require(current.review==SourceMemoryRules.decision(checkNotNull(p.previousReview),action))
            require(current.revision==p.expectedContentRevision+(if(action==SourceMemoryAction.CORRECT) 1 else 0))
            if(action==SourceMemoryAction.CORRECT) require(p.versions[p.versions.lastIndex-1].priorReview==p.previousReview && current.createdAtMillis==p.at)
            else require(current.reviewedAtMillis==p.at)
        }
        return p
    }
    private fun snapshot(row:Row,p:SourceMemoryCipher.Payload?)=SourceMemorySnapshot(row.id,row.source,row.hash,row.generation,row.version,
        if(p==null) SourceMemoryState.INVALIDATED else SourceMemoryState.ACTIVE,row.created,p?.captured,p?.zone,p?.versions.orEmpty())
    private fun createReceipt(r:SourceMemoryCreate)=receipt { w ->
        w.string("CREATE",16);w.string(r.id,512);w.string(r.mutationId,512);w.i64(r.requestedAtMillis)
        val p=r.preview;sourceReceipt(w,p.source,p.sourceHash,p.generation);w.i64(p.capturedAtMillis);w.string(p.captureTimeZone,128)
        w.string(p.policy.name,32);w.i64(p.epoch);SourceMemoryEncoding.write(w,r.content)
    }
    private fun mutationReceipt(r:SourceMemoryMutation)=receipt { w ->
        w.string(r.action.name,16);w.string(r.id,512);w.string(r.mutationId,512);w.i64(r.requestedAtMillis)
        sourceReceipt(w,r.source,r.sourceHash,r.generation);w.i64(r.expectedVersion);w.i32(r.expectedContentRevision);w.string(r.previousReview.name,16)
        r.content?.let { SourceMemoryEncoding.write(w,it) }
    }
    private fun sourceReceipt(w:PackageWriter,s:SourceVersion,hash:String,generation:Long) {
        w.string(s.sourceId,512);w.i64(s.revision);w.string(hash,64);w.i64(generation)
    }
    private fun receipt(write:(PackageWriter)->Unit):String {
        val bytes=PackageWriter(8192).use { w -> w.string("AIyou/source-memory/request/1",64);write(w);w.bytes() }
        return try { "source-memory-v1-"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } } finally { bytes.fill(0) }
    }
    private fun aad(row:Row)=PackageWriter(4096).use { w ->
        w.string("AIyou/user-source-memory/envelope/1",64);w.string(row.id,512);sourceReceipt(w,row.source,row.hash,row.generation)
        w.i32(row.profile);w.i64(row.version);w.string(row.state,16);w.string(row.mutation,512);w.i64(row.created);w.bytes()
    }
    private fun row(sql:RecordsSqlConnection,id:String)=sql.query("SELECT "+COLUMNS+" FROM local_candidate_batches WHERE id=?",listOf(id)).singleOrNull()?.let {
        if(it[5]!=PROFILE_VERSION.toLong()) throw SourceMemoryConflict()
        decode(it)
    }
    private fun decode(r:List<Any?>)=Row(r[0] as String,SourceVersion(r[1] as String,r[2] as Long),r[3] as String,r[4] as Long,
        Math.toIntExact(r[5] as Long),r[6] as Long,r[7] as String,r[8] as String,r[9] as Long,r[10] as ByteArray).also {
        ManualTags.identifier(it.id);ManualTags.identifier(it.mutation);require(it.profile==PROFILE_VERSION && it.version>=1 && it.generation>=0 && it.created>=0)
        require(it.state in setOf("READY","INVALIDATED"))
    }
    private inline fun <T> safe(block:()->T):T=try { block() }
        catch(c:CancellationException) { throw c }
        catch(c:SourceMemoryConflict) { throw c }
        catch(c:InvalidSourceMemory) { throw c }
        catch(c:SourceMemoryLimit) { throw c }
        catch(_:Exception) { throw SourceMemoryStorageFailure() }
    internal fun validateFullPortableRow(cells:List<Any?>,proof:FullBackupSourceProof?) {
        val row=decode(cells)
        if(row.state=="INVALIDATED") require(row.bytes.isEmpty())
        else {
            val p=checkNotNull(proof);require(!p.deleted && p.policy!=ProcessingPolicy.AI_NONE)
            payload(row,SourceMemoryPreview(p.source,p.hash,p.generation,p.original,p.captured,p.zone,p.policy,0))
        }
    }
    internal fun validateFullWithin(sql:RecordsSqlConnection) {
        val rows=sql.query("SELECT "+COLUMNS+" FROM local_candidate_batches WHERE ruleVersion=2 ORDER BY id LIMIT 4097")
        require(rows.size<=4096)
        sql.query("SELECT COUNT(*) FROM local_candidate_batches WHERE ruleVersion=2 AND state='READY' GROUP BY entryId,sourceRevision").forEach {
            SourceMemoryCapacity.count(it.single() as Long)
        }
        rows.map(::decode).forEach { row ->
            if(row.state=="INVALIDATED") require(row.bytes.isEmpty())
            else payload(row,readSource(sql,row.source))
        }
    }
    private data class KeywordReceipt(val epoch:Long,val rows:Long,val digest:String)
    private class KeywordScan(val receipt:KeywordReceipt,val items:List<AcceptedMemoryKeywordHit>)
    /** The guard belongs to this invocation, never to the retained proof. No global row limit. */
    internal fun keywordSet(keyword:String,checkCurrent:()->Unit)=keywordSet(keyword,UnifiedCaptureRange(),checkCurrent)
    internal fun keywordSet(keyword:String,captureRange:UnifiedCaptureRange,checkCurrent:()->Unit,tagMatch:((RecordsSqlConnection,SourceVersion)->Boolean)?=null):KeywordSet<AcceptedMemoryKeywordHit> {
        PreferenceKeywords.validate(keyword);require(keyword.isNotBlank())
        val first=database.transaction{keywordScan(it,keyword,captureRange,true,checkCurrent,tagMatch)}
        return KeywordSet(first.items,first.items.map{it.binding.source}.distinct(),guardedCurrent={sql,guard->
            keywordScan(sql,keyword,captureRange,false,guard,tagMatch).receipt==first.receipt
        }) {sql->keywordScan(sql,keyword,captureRange,false,{},tagMatch).receipt==first.receipt}
    }
    private fun keywordScan(sql:RecordsSqlConnection,keyword:String,captureRange:UnifiedCaptureRange,collect:Boolean,checkCurrent:()->Unit,tagMatch:((RecordsSqlConnection,SourceVersion)->Boolean)?):KeywordScan {
        checkCurrent()
        val epoch=sql.query("SELECT epoch FROM local_search_epoch WHERE slot=1").single().single() as Long
        val digest=MessageDigest.getInstance("SHA-256")
        require(epoch>=0)
        fun append(write:(PackageWriter)->Unit) {
            val bytes=PackageWriter(8192).use {w->write(w);w.bytes()}
            try{digest.update(bytes)}finally{bytes.fill(0)}
        }
        append{it.string("AIyou/unified/profile2-keywords/1",64);it.i64(epoch)}
        val items=ArrayList<AcceptedMemoryKeywordHit>();var cursor:String?=null;var count=0L;var matched=0
        do {
            checkCurrent()
            // The same BINARY order is used by both the cursor and ORDER BY, including Unicode IDs.
            val page=sql.query("SELECT c.id,c.entryId,c.sourceRevision,length(c.payload),length(CAST(r.originalText AS BLOB)) "+
                "FROM local_candidate_batches c JOIN entries e ON e.id=c.entryId "+
                "JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision "+
                "LEFT JOIN remote_source_access a ON a.entryId=e.id "+
                "WHERE c.ruleVersion=2 AND c.state='READY' AND c.sourceRevision=e.currentRevision "+
                "AND e.privacyPolicy IN ('LOCAL_ONLY','REMOTE_ALLOWED') AND COALESCE(a.deleted,0)=0 "+
                "AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"+
                (if(cursor==null) "" else " AND c.id COLLATE BINARY > ? COLLATE BINARY")+
                " ORDER BY c.id COLLATE BINARY LIMIT 20",if(cursor==null) emptyList() else listOf(cursor))
            page.forEach {meta->
                checkCurrent();require((meta[3] as Long) in 29..32797 && (meta[4] as Long) in 1..400000)
                val sourceVersion=SourceVersion(meta[1] as String,meta[2] as Long)
                validateSourceCapacity(sql,sourceVersion)
                // Fetch BLOB/body only after bounded SQL lengths. Both passes authenticate every row,
                // including nonmatching and non-accepted rows; no full-set plaintext/history cache.
                val row=checkNotNull(row(sql,meta[0] as String))
                try {
                    require(row.bytes.size.toLong()==meta[3]);checkCurrent()
                    val source=readSource(sql,sourceVersion) // Preview recomputes the real original hash.
                    checkCurrent();val saved=payload(row,source);checkCurrent()
                    append {w->
                        w.string(row.id,512);w.i32(row.profile);w.string(row.state,16)
                        sourceReceipt(w,row.source,row.hash,row.generation);w.i64(row.version)
                        w.string(row.mutation,512);w.i64(row.created)
                        w.string(MessageDigest.getInstance("SHA-256").digest(row.bytes).joinToString(""){"%02x".format(it.toInt() and 255)},64)
                        w.string(source.sourceHash,64);w.i64(source.generation);w.i64(source.capturedAtMillis)
                        w.string(source.captureTimeZone,128);w.string(source.policy.name,32)
                    }
                    count=Math.addExact(count,1)
                    val tagFits=tagMatch?.invoke(sql,sourceVersion)?:true
                    val latest=saved.versions.last()
                    if(latest.review==SourceMemoryReview.ACCEPTED){
                        val q=latest.content.reference.quote.contains(keyword);val n=latest.content.note.contains(keyword)
                        if(tagFits&&(q||n)&&captureRange.contains(source.capturedAtMillis,source.captureTimeZone)){
                            if(++matched>1000)throw UnifiedKeywordTooBroad()
                            if(collect)items+=AcceptedMemoryKeywordHit(row.id,UnifiedKeywordBinding(source.source,source.sourceHash,
                                source.generation,source.capturedAtMillis,source.captureTimeZone,source.policy),row.version,
                                latest.revision,latest.content,q,n)
                        }
                    }
                    checkCurrent()
                } finally {row.bytes.fill(0)}
            }
            cursor=page.lastOrNull()?.get(0) as? String
        }while(page.size==20)
        append{it.i64(count)};checkCurrent()
        return KeywordScan(KeywordReceipt(epoch,count,digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}),items)
    }
    internal fun portable(cells:List<Any?>,restoring:Boolean):ByteArray {
        val row=decode(cells)
        if(row.state=="INVALIDATED") { require(row.bytes.isEmpty());return byteArrayOf() }
        require(row.bytes.isNotEmpty() && row.state=="READY")
        return cipher.portable(aad(row),row.source,row.bytes,restoring)
    }
    fun wipeKey()=cipher.wipe()
    companion object {
        const val PROFILE_VERSION=2
        private const val COLUMNS="id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload"
    }
}
