package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Independent profile6. No inferred statement, plaintext topic index, remote or task authority. */
internal class SqlSourceFactRepository(private val database: RecordsSqlDatabase, key: ByteArray) : SourceFactRepository {
    private val cipher=SourceFactCipher(key)
    private data class Row(val id:String,val source:SourceVersion,val hash:String,val generation:Long,val version:Long,
        val state:String,val mutation:String,val created:Long,val bytes:ByteArray)
    private data class Item(val row:Row,val payload:FactPayload)
    private inner class SourcePage(override val preview:SourceMemoryPreview,values:List<SourceFactSnapshot>,groups:List<SourceFactSnapshot>,val signatures:List<String>):FactSourcePage {
        val owner=this@SqlSourceFactRepository
        override val items=java.util.Collections.unmodifiableList(values.toList())
        override val topics=java.util.Collections.unmodifiableList(groups.toList())
    }
    override fun captureSource(source:SourceVersion):FactSourcePage = safe { database.transaction { sourcePage(it,source) } }
    private fun sourcePage(sql:RecordsSqlConnection,source:SourceVersion):SourcePage {
        val preview=readSource(sql,source);val items=live(sql);validateGraph(items)
        val snapshots=items.map { snapshot(it.row,it.payload) }
        return SourcePage(preview,snapshots.filter { it.source==source },snapshots.distinctBy { it.topicId },items.map { it.row.id+":"+it.row.version+":"+hash(it.row.bytes) })
    }
    override fun resolveSource(page:FactSourcePage,publish:()->Unit):Boolean = safe {
        val p=page as? SourcePage ?: return@safe false
        if(p.owner!==this)return@safe false
        database.transaction { sql -> val fresh=sourcePage(sql,p.preview.source);val a=fresh.preview;val b=p.preview
            if(fresh.signatures!=p.signatures || a.sourceHash!=b.sourceHash || a.generation!=b.generation || a.epoch!=b.epoch || a.policy!=b.policy || a.capturedAtMillis!=b.capturedAtMillis || a.captureTimeZone!=b.captureTimeZone)false else { publish();true }
        }
    }
    override fun acceptedForTopic(expected:SourceFactSnapshot):SourceFactSnapshot? = safe { database.transaction { sql ->
        val all=live(sql);validateGraph(all);val actual=all.singleOrNull { it.row.id==expected.id } ?: throw FactConflict()
        if(!snapshot(actual.row,actual.payload).same(expected))throw FactConflict()
        all.singleOrNull { it.payload.topicId==expected.topicId && it.payload.reviews.last().review==FactReview.ACCEPTED }?.let { snapshot(it.row,it.payload) }
    } }
    override fun resolveEndpoint(expected:SourceFactSnapshot,endpoint:FactEndpoint,publish:(SourceFactSnapshot?)->Unit):Boolean = safe { database.transaction { sql ->
        // Authenticate historical reciprocal slots across the whole bounded group. The target's
        // last mutation receipt can belong to a later replacement and cannot prove this edge.
        val all=live(sql);validateGraph(all)
        val current=all.singleOrNull { it.row.id==expected.id } ?: throw FactConflict()
        if(!snapshot(current.row,current.payload).same(expected) || current.payload.reviews.none { it.predecessor==endpoint || it.successor==endpoint })throw FactConflict()
        val target=all.singleOrNull { it.row.id==endpoint.id }
        publish(target?.let { snapshot(it.row,it.payload) });true
    } }
    private inner class Page(override val section:FactReview,override val cursor:FactCursor?,override val next:FactCursor?,
        values:List<SourceFactSnapshot>,val signatures:List<String>,override val keyword:String?=null):FactPage {
        val owner=this@SqlSourceFactRepository
        override val items=java.util.Collections.unmodifiableList(values.toList())
        override fun toString()="FactPage(redacted)"
    }
    override fun preview(source:SourceVersion)=safe { database.transaction { readSource(it,source) } }
    override fun facts(source:SourceVersion):List<SourceFactSnapshot> = safe { database.transaction { sql ->
        readSource(sql,source)
        val all=live(sql);validateGraph(all)
        all.filter { it.row.source==source }.sortedWith(compareByDescending<Item>{it.row.created}.thenBy{it.row.id})
            .map { snapshot(it.row,it.payload) }
    } }
    override fun create(request:FactCreate):SourceFactSnapshot = safe { database.transaction { sql ->
        val source=readSource(sql,request.preview.source);val p=request.preview
        if(source.sourceHash!=p.sourceHash || source.generation!=p.generation || source.original!=p.original || source.capturedAtMillis!=p.capturedAtMillis || source.captureTimeZone!=p.captureTimeZone || source.policy!=p.policy)throw FactConflict()
        FactRules.validate(request.content,source)
        val receipt=createReceipt(request)
        val all=live(sql);validateGraph(all) // Replay never bypasses capacity or authenticated history.
        row(sql,request.id)?.let { old ->
            val saved=payload(old,source)
            if(old.version!=1L || old.mutation!=receipt || old.created!=request.requestedAtMillis || saved.operation!="CREATE" || saved.epoch!=p.epoch ||
                saved.contents.single().content!=request.content || saved.topicId!=request.topicId || saved.topicLabel!=request.topicLabel)throw FactConflict()
            return@transaction snapshot(old,saved)
        }
        if(source.epoch!=p.epoch)throw FactConflict()
        if(all.size>=128 || all.count { it.row.source==source.source }>=20 ||
            (sql.query("SELECT count(*) FROM local_candidate_batches").single().single() as Long)>=4096)throw FactLimit()
        val group=all.filter { it.payload.topicId==request.topicId }
        request.topicExpected?.let { expected ->
            val actual=all.singleOrNull { it.row.id==expected.id } ?: throw FactConflict()
            if(!snapshot(actual.row,actual.payload).same(expected))throw FactConflict()
        }
        if(group.isNotEmpty() && (request.topicExpected==null || group.any { it.payload.topicLabel!=request.topicLabel }))throw FactConflict()
        val data=FactPayload(source.capturedAtMillis,source.captureTimeZone,source.policy,p.epoch,request.topicId,request.topicLabel,
            listOf(FactContentVersion(1,request.content,request.requestedAtMillis)),listOf(FactCheckpoint(1,FactReview.PROPOSED,null,request.requestedAtMillis)),"CREATE",0,0,null,request.requestedAtMillis)
        val initial=Row(request.id,source.source,source.sourceHash,source.generation,1,"READY",receipt,request.requestedAtMillis,byteArrayOf())
        val encrypted=cipher.seal(aad(initial),data)
        if(all.sumOf { it.row.bytes.size.toLong() }+encrypted.size>4_194_304)throw FactLimit()
        sql.execute("INSERT INTO local_candidate_batches($COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?)",listOf(initial.id,initial.source.sourceId,initial.source.revision,initial.hash,initial.generation,6,1,"READY",receipt,initial.created,encrypted))
        val saved=checkNotNull(row(sql,initial.id));check(payload(saved,source)==data);snapshot(saved,data)
    } }
    override fun mutate(request:FactMutation):SourceFactSnapshot = safe { database.transaction { sql ->
        val expected=request.expected;val source=readSource(sql,expected.source);val old=row(sql,expected.id) ?: throw FactConflict()
        val before=payload(old,source);val receipt=mutationReceipt(request)
        if(old.mutation==receipt) {
            if(old.version!=expected.rowVersion+1 || before.operation!=request.action.name || before.expectedVersion!=expected.rowVersion ||
                before.expectedContentRevision!=expected.current?.revision || before.previousReview!=expected.review?.review || before.at!=request.requestedAtMillis)throw FactConflict()
            request.replacing?.let { prior ->
                val a=row(sql,prior.id) ?: throw FactConflict();val p=payload(a,readSource(sql,prior.source))
                if(a.version!=prior.rowVersion+1 || a.mutation!=receipt || p.operation!="REPLACE_OLD")throw FactConflict()
            }
            validateGraph(live(sql));return@transaction snapshot(old,before)
        }
        if(!snapshot(old,before).same(expected) || old.version==Long.MAX_VALUE)throw FactConflict()
        val latest=before.reviews.last();val decision=FactRules.decision(latest.review,request.action)
        val all=live(sql);validateGraph(all)
        var next=before
        if(request.action==FactAction.ACCEPT) {
            val active=all.filter { it.payload.topicId==before.topicId && it.payload.reviews.last().review==FactReview.ACCEPTED }
            val replacing=request.replacing
            if(replacing==null) { if(active.isNotEmpty())throw FactConflict() }
            else {
                val prior=active.singleOrNull { it.row.id==replacing.id } ?: throw FactConflict()
                if(active.size!=1 || !snapshot(prior.row,prior.payload).same(replacing) || prior.row.version==Long.MAX_VALUE || prior.payload.topicLabel!=before.topicLabel)throw FactConflict()
                val review=prior.payload.reviews.last();require(review.successor==null && latest.predecessor==null)
                val link=FactEndpoint(old.id,before.contents.last().revision,request.mutationId,receipt)
                val retired=prior.payload.copy(reviews=prior.payload.reviews.dropLast(1)+review.copy(review=FactReview.SUPERSEDED,priorReview=FactReview.ACCEPTED,reviewedAtMillis=request.requestedAtMillis,successor=link),
                    operation="REPLACE_OLD",expectedVersion=prior.row.version,expectedContentRevision=review.contentRevision,previousReview=review.review,at=request.requestedAtMillis)
                save(sql,prior.row,retired,receipt,readSource(sql,prior.row.source))
                next=next.copy(reviews=next.reviews.dropLast(1)+latest.copy(review=decision,reviewedAtMillis=request.requestedAtMillis,predecessor=FactEndpoint(prior.row.id,review.contentRevision,request.mutationId,receipt)))
            }
        }
        next=if(request.action==FactAction.CORRECT) {
            val c=checkNotNull(request.content);FactRules.validate(c,source)
            if(before.contents.size>=8)throw FactLimit()
            if(c==before.contents.last().content)throw FactConflict()
            next.copy(contents=before.contents+FactContentVersion(before.contents.size+1,c,request.requestedAtMillis),
                reviews=before.reviews.dropLast(1)+latest.copy(review=FactReview.SUPERSEDED,priorReview=latest.review,reviewedAtMillis=request.requestedAtMillis)+FactCheckpoint(before.contents.size+1,FactReview.PROPOSED,null,request.requestedAtMillis))
        } else next.copy(reviews=next.reviews.dropLast(1)+next.reviews.last().copy(review=decision,reviewedAtMillis=request.requestedAtMillis))
        next=next.copy(operation=request.action.name,expectedVersion=old.version,expectedContentRevision=before.contents.last().revision,previousReview=latest.review,at=request.requestedAtMillis)
        val saved=save(sql,old,next,receipt,source);validateGraph(live(sql));snapshot(saved,next)
    } }
    private fun save(sql:RecordsSqlConnection,old:Row,p:FactPayload,receipt:String,source:SourceMemoryPreview):Row {
        val next=old.copy(version=Math.addExact(old.version,1),mutation=receipt)
        if(sql.execute("UPDATE local_candidate_batches SET version=?,lastMutationId=?,payload=? WHERE id=? AND ruleVersion=6 AND version=? AND state='READY'",listOf(next.version,receipt,cipher.seal(aad(next),p),old.id,old.version))!=1)throw FactConflict()
        val saved=checkNotNull(row(sql,old.id));check(saved.version==next.version && saved.mutation==receipt && payload(saved,source)==p);return saved
    }
    private fun live(sql:RecordsSqlConnection):List<Item> {
        val size=sql.query("SELECT count(*),COALESCE(sum(length(payload)),0) FROM local_candidate_batches WHERE ruleVersion=6 AND state='READY'").single()
        if((size[0] as Long)>128 || (size[1] as Long)>4_194_304)throw FactLimit()
        return sql.query("SELECT $SELECT_COLUMNS FROM local_candidate_batches WHERE ruleVersion=6 AND state='READY' ORDER BY id LIMIT 129").map { cells ->
            val row=decode(cells);val source=readSource(sql,row.source);Item(row,payload(row,source))
        }
    }
    override fun capture(section:FactReview,cursor:FactCursor?):FactPage = safe { database.transaction { capture(it,section,cursor) } }
    private fun capture(sql:RecordsSqlConnection,section:FactReview,cursor:FactCursor?):Page {
        require(section in setOf(FactReview.PROPOSED,FactReview.ACCEPTED))
        val all=live(sql);validateGraph(all)
        val page=if(cursor==null)"" else " AND (c.createdAtMillis<? OR (c.createdAtMillis=? AND c.id>?))"
        val args=if(cursor==null)emptyList() else listOf(cursor.createdAtMillis,cursor.createdAtMillis,cursor.id)
        val rows=sql.query("SELECT "+COLUMNS.split(',').joinToString { if(it=="payload") "CASE WHEN length(c.payload)<=32797 THEN c.payload ELSE NULL END" else "c.$it" }+" FROM local_candidate_batches c JOIN entries e ON e.id=c.entryId LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE c.ruleVersion=6 AND c.state='READY' AND e.currentRevision=c.sourceRevision AND e.privacyPolicy IN ('LOCAL_ONLY','REMOTE_ALLOWED') AND COALESCE(a.deleted,0)=0 AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"+page+" ORDER BY c.createdAtMillis DESC,c.id LIMIT 21",args)
        val selected=rows.take(20).map(::decode);require(selected.sumOf { it.bytes.size }<=655940)
        val items=selected.map { snapshot(it,payload(it,readSource(sql,it.source))) }
        return Page(section,cursor,if(rows.size>20)selected.last().let { FactCursor(it.created,it.id) } else null,
            items.filter { it.review?.review==section },all.map { it.row.id+":"+it.row.version+":"+hash(it.row.bytes) })
    }
    override fun searchAccepted(keyword:String,cursor:FactCursor?):FactPage {
        FactKeywords.validate(keyword)
        return safe { database.transaction { searchAccepted(it,keyword,cursor) } }
    }
    private fun searchAccepted(sql:RecordsSqlConnection,keyword:String,cursor:FactCursor?):Page {
        // Authenticate the entire bounded live set and graph BEFORE matching and paging.
        // Filtering a raw LIMIT 21 page would silently miss older accepted statements.
        val all=live(sql);validateGraph(all)
        val matches=all.filter { it.payload.reviews.last().review==FactReview.ACCEPTED &&
            (it.payload.topicLabel.contains(keyword) || it.payload.contents.last().content.reference.quote.contains(keyword)) }
            .sortedWith(compareByDescending<Item> { it.row.created }.thenBy { it.row.id })
            .filter { cursor==null || it.row.created<cursor.createdAtMillis || it.row.created==cursor.createdAtMillis && it.row.id>cursor.id }
        val selected=matches.take(20)
        return Page(FactReview.ACCEPTED,cursor,if(matches.size>20)selected.last().row.let {FactCursor(it.created,it.id)} else null,
            selected.map {snapshot(it.row,it.payload)},all.map {it.row.id+":"+it.row.version+":"+hash(it.row.bytes)},keyword)
    }
    internal fun keywordSet(keyword:String):KeywordSet<SourceFactSnapshot> {
        FactKeywords.validate(keyword)
        return database.transaction { sql ->
            val first=searchAccepted(sql,keyword,null)
            val all=live(sql);validateGraph(all)
            val items=ArrayList<SourceFactSnapshot>();var cursor:FactCursor?=null
            do {val p=searchAccepted(sql,keyword,cursor);items.addAll(p.items);cursor=p.next}while(cursor!=null)
            KeywordSet(items,all.map{it.row.source}) { current -> searchAccepted(current,keyword,null).signatures==first.signatures }
        }
    }
    override fun resolve(page:FactPage,item:SourceFactSnapshot?,publish:()->Unit):Boolean = safe {
        val p=page as? Page ?: return@safe false
        if(p.owner!==this || item!=null && p.items.none { it===item })return@safe false
        database.transaction { sql -> val fresh=p.keyword?.let {searchAccepted(sql,it,p.cursor)} ?: capture(sql,p.section,p.cursor)
            if(fresh.signatures!=p.signatures || fresh.next!=p.next)false else { publish();true }
        }
    }
    private fun readSource(sql:RecordsSqlConnection,expected:SourceVersion):SourceMemoryPreview {
        val r=sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=? AND length(CAST(r.originalText AS BLOB))<=400000",listOf(expected.sourceId)).singleOrNull() ?: throw FactConflict()
        if(r[0]!=expected.revision || r[7]!=0L || r[5]=="AI_NONE" || sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=? LIMIT 1",listOf(expected.sourceId)).isNotEmpty())throw FactConflict()
        val epoch=sql.query("SELECT epoch FROM local_search_epoch WHERE slot=1").single().single() as Long
        ZoneId.of(r[4] as String)
        return SourceMemoryPreview(expected,r[2] as String,r[6] as Long,r[1] as String,r[3] as Long,r[4] as String,ProcessingPolicy.valueOf(r[5] as String),epoch)
    }
    private fun payload(row:Row,source:SourceMemoryPreview):FactPayload {
        if(row.state!="READY" || row.source!=source.source || row.hash!=source.sourceHash || row.generation!=source.generation)throw FactConflict()
        val p=cipher.open(aad(row),row.bytes,row.source)
        require(p.captured==source.capturedAtMillis && p.zone==source.captureTimeZone && p.policy==source.policy && p.epoch>=0)
        FactValues.uuid(p.topicId);FactValues.label(p.topicLabel)
        require(p.contents.map { it.revision }==(1..p.contents.size).toList() && p.reviews.map { it.contentRevision }==(1..p.contents.size).toList())
        p.contents.forEachIndexed { i,v ->
            FactRules.validate(v.content,source)
            if(i<p.contents.lastIndex)require(p.reviews[i].review==FactReview.SUPERSEDED && p.reviews[i].successor==null)
            listOfNotNull(p.reviews[i].predecessor,p.reviews[i].successor).forEach { require(it.id!=row.id) }
        }
        require(p.contents.first().createdAtMillis==row.created && p.at>=0)
        if(p.operation=="CREATE")require(row.version==1L && p.expectedVersion==0L && p.expectedContentRevision==0 && p.previousReview==null && p.contents.size==1 && p.reviews.single().review==FactReview.PROPOSED)
        else {
            require(p.expectedVersion==row.version-1 && p.expectedContentRevision in 1..8 && p.previousReview!=null)
            val result=if(p.operation=="REPLACE_OLD") { require(p.previousReview==FactReview.ACCEPTED);FactReview.SUPERSEDED } else FactRules.decision(checkNotNull(p.previousReview),FactAction.valueOf(p.operation))
            require(p.reviews.last().review==result && p.contents.last().revision==p.expectedContentRevision+(if(p.operation=="CORRECT")1 else 0))
            if(p.operation=="REPLACE_OLD")require(p.reviews.last().successor!=null)
        }
        return p
    }
    private fun snapshot(row:Row,p:FactPayload?)=SourceFactSnapshot(row.id,row.source,row.hash,row.generation,row.version,
        if(p==null)FactAvailability.INVALIDATED else FactAvailability.AVAILABLE,row.created,p?.captured,p?.zone,p?.policy,p?.topicId,p?.topicLabel,p?.contents.orEmpty(),p?.reviews.orEmpty())
    private fun validateGraph(items:List<Item>) {
        require(items.size<=128)
        if(items.groupingBy { it.row.source }.eachCount().values.any { it>20 })throw FactLimit()
        items.groupBy { it.payload.topicId }.values.forEach { group ->
            require(group.map { it.payload.topicLabel }.distinct().size==1 && group.count { it.payload.reviews.last().review==FactReview.ACCEPTED }<=1)
        }
        val byId=items.associateBy { it.row.id };require(byId.size==items.size)
        items.forEach { item -> item.payload.reviews.forEach { checkpoint ->
            fun check(edge:FactEndpoint?,predecessor:Boolean) {
                if(edge==null)return
                val other=byId[edge.id] ?: return // No content is retained or authority recovered from an unavailable endpoint.
                require(other.payload.topicId==item.payload.topicId)
                val counterpart=other.payload.reviews.single { it.contentRevision==edge.contentRevision }
                val back=if(predecessor)counterpart.successor else counterpart.predecessor
                require(back==FactEndpoint(item.row.id,checkpoint.contentRevision,edge.operationId,edge.digest))
            }
            check(checkpoint.predecessor,true);check(checkpoint.successor,false)
        } }
        val visited=mutableSetOf<Pair<String,Int>>();val visiting=mutableSetOf<Pair<String,Int>>()
        fun visit(id:String,rev:Int) {
            val key=id to rev;if(key in visited)return;require(visiting.add(key))
            val item=checkNotNull(byId[id]);val c=item.payload.reviews.single { it.contentRevision==rev }
            c.successor?.takeIf { it.id in byId }?.let { visit(it.id,it.contentRevision) }
            if(rev<item.payload.contents.size)visit(id,rev+1)
            visiting.remove(key);visited.add(key)
        }
        items.forEach { item -> item.payload.contents.forEach { visit(item.row.id,it.revision) } }
    }
    private fun row(sql:RecordsSqlConnection,id:String):Row?=sql.query("SELECT $SELECT_COLUMNS FROM local_candidate_batches WHERE id=?",listOf(id)).singleOrNull()?.let(::decode)
    private fun decode(r:List<Any?>):Row {
        require(r[5]==6L);val x=Row(r[0] as String,SourceVersion(r[1] as String,r[2] as Long),r[3] as String,r[4] as Long,r[6] as Long,r[7] as String,r[8] as String,r[9] as Long,r[10] as ByteArray)
        FactValues.uuid(x.id);require(x.version>=1 && x.generation>=0 && x.created>=0 && x.state in setOf("READY","INVALIDATED"));return x
    }
    private fun aad(row:Row)=PackageWriter(4096).use { w ->
        w.string("AIyou/fact/profile6/1",64);w.string(row.id,36);w.string(row.source.sourceId,512);w.i64(row.source.revision);w.string(row.hash,64);w.i64(row.generation)
        w.i32(6);w.i64(row.version);w.string(row.state,16);w.string(row.mutation,64);w.i64(row.created);w.bytes()
    }
    private fun expected(w:PackageWriter,s:SourceFactSnapshot) {
        w.string(s.id,36);w.string(s.source.sourceId,512);w.i64(s.source.revision);w.string(s.sourceHash,64);w.i64(s.generation);w.i64(s.rowVersion);w.i64(s.createdAtMillis)
        w.i64(checkNotNull(s.capturedAtMillis));w.string(checkNotNull(s.captureTimeZone),128);w.string(checkNotNull(s.policy).name,32);w.string(checkNotNull(s.topicId),36);w.string(checkNotNull(s.topicLabel),160)
        FactEncoding.histories(w,s.contents,s.reviews)
    }
    private fun createReceipt(r:FactCreate)=receipt { w ->
        w.string("CREATE",16);w.string(r.id,36);w.string(r.mutationId,36);w.i64(r.requestedAtMillis)
        val p=r.preview;w.string(p.source.sourceId,512);w.i64(p.source.revision);w.string(p.sourceHash,64);w.i64(p.generation);w.i64(p.capturedAtMillis);w.string(p.captureTimeZone,128);w.string(p.policy.name,32);w.i64(p.epoch)
        w.string(r.topicId,36);w.string(r.topicLabel,160);FactEncoding.content(w,r.content);w.i32(if(r.explicitOrdinarySelfReport)1 else 0)
        w.i32(if(r.topicExpected==null)0 else 1);r.topicExpected?.let { expected(w,it) }
    }
    private fun mutationReceipt(r:FactMutation)=receipt { w ->
        w.string(r.action.name,16);w.string(r.mutationId,36);w.i64(r.requestedAtMillis);w.i32(if(r.explicitOrdinarySelfReport)1 else 0);expected(w,r.expected)
        r.content?.let { FactEncoding.content(w,it) };w.i32(if(r.replacing==null)0 else 1);r.replacing?.let { expected(w,it) }
    }
    private fun receipt(write:(PackageWriter)->Unit):String {
        val b=PackageWriter(131072).use { w -> w.string("AIyou/fact/request/1",64);write(w);w.bytes() }
        return try { hash(b) } finally { b.fill(0) }
    }
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    private inline fun <T> safe(block:()->T):T=try { block() } catch(c:CancellationException) { throw c } catch(c:FactLimit) { throw c } catch(c:FactConflict) { throw c } catch(_:Exception) { throw FactUnavailable() }
    internal fun validateFullPortableRow(cells:List<Any?>,proof:FullBackupSourceProof?) { portableItem(cells,proof) }
    private fun portableItem(cells:List<Any?>,proof:FullBackupSourceProof?):Item? {
        val row=decode(cells)
        if(row.state=="INVALIDATED") { require(row.bytes.isEmpty());return null }
        val p=checkNotNull(proof);require(!p.deleted && p.policy!=ProcessingPolicy.AI_NONE)
        val source=SourceMemoryPreview(p.source,p.hash,p.generation,p.original,p.captured,p.zone,p.policy,0)
        return Item(row,payload(row,source))
    }
    internal fun validatePortableGraph(rows:List<Pair<List<Any?>,FullBackupSourceProof?>>) {
        if(rows.count { it.first[7]=="READY" }>128 || rows.sumOf { (it.first[10] as ByteArray).size.toLong() }>4_194_304)throw FactLimit()
        validateGraph(rows.mapNotNull { portableItem(it.first,it.second) })
    }
    internal fun validateFullWithin(sql:RecordsSqlConnection) {
        validateGraph(live(sql))
        val rows=sql.query("SELECT $SELECT_COLUMNS FROM local_candidate_batches WHERE ruleVersion=6 ORDER BY id LIMIT 4097");require(rows.size<=4096)
        rows.map(::decode).filter { it.state=="INVALIDATED" }.forEach { require(it.bytes.isEmpty()) }
    }
    internal fun portable(cells:List<Any?>,restoring:Boolean):ByteArray {
        val row=decode(cells);if(row.state=="INVALIDATED") { require(row.bytes.isEmpty());return byteArrayOf() }
        return cipher.portable(aad(row),row.source,row.bytes,restoring)
    }
    fun wipeKey()=cipher.wipe()
    companion object {
        private const val COLUMNS="id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload"
        private val SELECT_COLUMNS=COLUMNS.substringBeforeLast(',')+",CASE WHEN length(payload)<=32797 THEN payload ELSE NULL END"
    }
}
