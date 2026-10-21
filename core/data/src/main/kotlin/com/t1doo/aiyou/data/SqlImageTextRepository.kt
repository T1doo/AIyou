package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Profile5 is independently encrypted. No schema change, FTS plaintext, model, or network. */
internal class SqlImageTextRepository(private val database:RecordsSqlDatabase,key:ByteArray,
    private val media:AttachmentStore?=null,private val gate:CaptureDraftGate?=null):ImageTextRepository {
    private val cipher=ImageTextCipher(key)
    private data class Row(val cells:List<Any?>) {
        val id get()=cells[0] as String;val version get()=cells[6] as Long
        val state get()=cells[7] as String;val bytes get()=cells[10] as ByteArray;val created get()=cells[9] as Long
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun aad(row:Row)=PackageWriter(4096).use{w->
        w.string("AIyou/image-derived-text/profile5/1",64)
        row.cells.take(10).forEach{if(it is Long){w.i32(1);w.i64(it)}else{w.i32(2);w.string(it as String,512)}};w.bytes()
    }
    private fun decode(cells:List<Any?>):Row {
        require(cells[5]==5L&&cells[7] in setOf("READY","CANCELLED","INVALIDATED"))
        PreferenceValues.uuid(cells[0] as String);SourceVersion(cells[1] as String,cells[2] as Long)
        require((cells[3] as String).matches(Regex("[0-9a-f]{64}"))&&(cells[4] as Long)>=0&&(cells[9] as Long)>=0)
        val row=Row(cells)
        if(row.state=="READY")require(row.version in 1..8&&(cells[8] as String).matches(Regex("[0-9a-f]{64}")))
        else {require(row.version in 2..9&&row.bytes.isEmpty());if(row.state=="INVALIDATED")require(cells[8]=="SOURCE_INVALIDATED")else require((cells[8] as String).matches(Regex("[0-9a-f]{64}")))}
        return row
    }
    private fun payload(row:Row):ImageTextPayload {
        require(row.state=="READY");val p=cipher.open(aad(row),row.bytes);val a=p.anchor
        require(a.intent.entryId==row.cells[1]&&a.intent.revision.toLong()==row.cells[2]&&a.sourceHash==row.cells[3]&&a.generation==row.cells[4])
        require(p.history.size.toLong()==row.version&&p.history.first().at==row.created);return p
    }
    private fun snapshot(row:Row,p:ImageTextPayload)=ImageTextSnapshot(row.id,p.anchor,p.engine,p.history)
    private fun row(sql:RecordsSqlConnection,id:String)=sql.query("SELECT $COLUMNS FROM local_candidate_batches WHERE id=?",listOf(id)).singleOrNull()?.let(::decode)
    private fun source(sql:RecordsSqlConnection,intent:AttachmentIntent):ImageTextSource {
        val r=sql.query("SELECT e.currentRevision,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0),r.originalText FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=? AND length(CAST(r.originalText AS BLOB))<=400000",listOf(intent.entryId)).singleOrNull()?:throw ImageTextConflict()
        if((r[7] as String).length>100000||OriginalExports.hash(r[7] as String)!=r[1]||r[0]!=intent.revision.toLong()||r[6]!=0L||sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=? LIMIT 1",listOf(intent.entryId)).isNotEmpty())throw ImageTextConflict()
        val actual=sql.query("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments WHERE id=?",listOf(intent.id)).singleOrNull()
        if(actual!=listOf(intent.id,intent.entryId,intent.revision.toLong(),intent.mediaType,intent.sha256,intent.byteSize,"READY"))throw ImageTextConflict()
        return ImageTextSource(intent,r[1] as String,r[5] as Long,r[2] as Long,r[3] as String,ProcessingPolicy.valueOf(r[4] as String))
    }
    private fun current(sql:RecordsSqlConnection,anchor:ImageTextSource){if(source(sql,anchor.intent)!=anchor)throw ImageTextConflict()}
    override suspend fun preview(intent:AttachmentIntent):ImageTextSource = safe {
        val before=database.transaction{source(it,intent)}
        val bytes=checkNotNull(media).read(intent.id)
        try {
            RecordMediaPackageCipher.checkImage(RecordPackageImage(intent.entryId,intent.revision,intent.id,intent.mediaType,intent.byteSize,intent.sha256),bytes)
            database.transaction{source(it,intent).also{a->if(a!=before)throw ImageTextConflict()}}
        }finally{bytes.fill(0)}
    }
    override suspend fun read(anchor:ImageTextSource):ImageTextSnapshot? = safe {
        if(preview(anchor.intent)!=anchor)throw ImageTextConflict()
        database.transaction{sql->current(sql,anchor);live(sql).singleOrNull{it.second.anchor==anchor}?.let{snapshot(it.first,it.second)}}
    }
    override suspend fun write(request:ImageTextWrite):ImageTextSnapshot? = safe {
        suspend fun commit():ImageTextSnapshot? {
            if(!request.delete&&preview(request.anchor.intent)!=request.anchor)throw ImageTextConflict()
            return database.transaction{sql->
                current(sql,request.anchor);val all=live(sql);val expected=request.expected;val receipt=receipt(request);val old=row(sql,request.id)
                if(old!=null&&old.cells[8]==receipt&&old.version==(expected?.version?:0)+1) {
                    if(request.delete&&old.state=="CANCELLED")return@transaction null
                    if(!request.delete&&old.state=="READY")return@transaction snapshot(old,payload(old))
                }
                if(expected==null) {
                    if(old!=null||all.any{it.second.anchor.intent.id==request.anchor.intent.id})throw ImageTextConflict()
                    if(all.size>=128||all.count{it.second.anchor.source==request.anchor.source}>=20||(sql.query("SELECT count(*) FROM local_candidate_batches").single().single() as Long)>=4096)throw ImageTextLimit()
                }else if(old==null||old.state!="READY"||!snapshot(old,payload(old)).same(expected))throw ImageTextConflict()
                if(request.delete) {
                    if(sql.execute("UPDATE local_candidate_batches SET version=?,state='CANCELLED',lastMutationId=?,payload=X'' WHERE id=? AND ruleVersion=5 AND state='READY' AND version=?",listOf(expected!!.version+1,receipt,request.id,expected.version))!=1)throw ImageTextConflict()
                    val deleted=checkNotNull(row(sql,request.id));check(deleted.state=="CANCELLED"&&deleted.bytes.isEmpty());return@transaction null
                }
                val engine=expected?.engine?:checkNotNull(request.extraction).engine
                val history=if(expected==null)listOf(ImageTextVersion(1,checkNotNull(request.extraction).text,false,request.at))else {
                    if(expected.history.size>=8)throw ImageTextLimit()
                    if(expected.current.text==request.text)throw ImageTextConflict()
                    expected.history+ImageTextVersion(expected.history.size+1,checkNotNull(request.text),true,request.at)
                }
                val p=ImageTextPayload(request.anchor,engine,history)
                val cells=listOf(request.id,request.anchor.intent.entryId,request.anchor.intent.revision.toLong(),request.anchor.sourceHash,request.anchor.generation,5L,history.size.toLong(),"READY",receipt,old?.created?:request.at,byteArrayOf())
                val next=Row(cells);val encrypted=cipher.seal(aad(next),p)
                if(all.sumOf{it.first.bytes.size.toLong()}-(old?.bytes?.size?:0)+encrypted.size>4_194_304)throw ImageTextLimit()
                if(old==null)sql.execute("INSERT INTO local_candidate_batches($COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?)",cells.dropLast(1)+encrypted)
                else if(sql.execute("UPDATE local_candidate_batches SET version=?,lastMutationId=?,payload=? WHERE id=? AND ruleVersion=5 AND state='READY' AND version=?",listOf(next.version,receipt,encrypted,request.id,old.version))!=1)throw ImageTextConflict()
                val saved=checkNotNull(row(sql,request.id));check(payload(saved)==p);snapshot(saved,p)
            }
        }
        gate?.sourceMutation{commit()}?:commit()
    }
    private fun live(sql:RecordsSqlConnection):List<Pair<Row,ImageTextPayload>> {
        val size=sql.query("SELECT count(*),COALESCE(sum(length(payload)),0) FROM local_candidate_batches WHERE ruleVersion=5 AND state='READY'").single()
        if((size[0] as Long)>128||(size[1] as Long)>4_194_304)throw ImageTextLimit()
        val values=sql.query("SELECT $COLUMNS FROM local_candidate_batches WHERE ruleVersion=5 AND state='READY' ORDER BY createdAtMillis DESC,id LIMIT 129").map{cells->val row=decode(cells);row to payload(row).also{current(sql,it.anchor)}}
        validateSet(values);return values
    }
    private fun validateSet(values:List<Pair<Row,ImageTextPayload>>) {
        if(values.size>128||values.groupingBy{it.second.anchor.source}.eachCount().values.any{it>20}||values.sumOf{it.first.bytes.size.toLong()}>4_194_304)throw ImageTextLimit()
        require(values.map{it.second.anchor.intent.id}.distinct().size==values.size)
    }
    private inner class Page(override val keyword:String,override val cursor:PreferenceCursor?,override val next:PreferenceCursor?,items:List<ImageTextSnapshot>,val signatures:List<String>):ImageTextPage {
        val owner=this@SqlImageTextRepository;override val items:List<ImageTextSnapshot> = java.util.Collections.unmodifiableList(items.toList())
        override fun toString()="ImageTextPage(redacted)"
    }
    override suspend fun search(keyword:String,cursor:PreferenceCursor?):ImageTextPage = safe {
        PreferenceKeywords.validate(keyword)
        val initial=database.transaction{sql->val rows=live(sql);rows.map{it.second.anchor} to signatures(rows)}
        // Bounded, one authenticated original at a time; never borrow the backup's64MiB quota.
        for(anchor in initial.first)if(preview(anchor.intent)!=anchor)throw ImageTextConflict()
        database.transaction{page(it,keyword,cursor).also{p->if(p.signatures!=initial.second)throw ImageTextConflict()}}
    }
    private fun signatures(values:List<Pair<Row,ImageTextPayload>>)=values.map{it.first.id+":"+it.first.version+":"+hash(it.first.bytes)}
    private fun page(sql:RecordsSqlConnection,keyword:String,cursor:PreferenceCursor?):Page {
        val all=live(sql);val matched=all.filter{it.second.history.last().text.contains(keyword)}.filter{cursor==null||it.first.created<cursor.createdAtMillis||it.first.created==cursor.createdAtMillis&&it.first.id>cursor.id};val selected=matched.take(20)
        return Page(keyword,cursor,if(matched.size>20)selected.last().first.let{PreferenceCursor(it.created,it.id)}else null,selected.map{snapshot(it.first,it.second)},signatures(all))
    }
    override suspend fun resolve(page:ImageTextPage,item:ImageTextSnapshot?,publish:()->Unit):Boolean = safe {
        val p=page as? Page?:return@safe false;if(p.owner!==this||item!=null&&p.items.none{it===item})return@safe false
        val fresh=search(p.keyword,p.cursor) as Page;if(p.signatures!=fresh.signatures||p.next!=fresh.next)return@safe false
        val confirmed=database.transaction{sql->val last=page(sql,p.keyword,p.cursor);last.signatures==p.signatures&&last.next==p.next};if(confirmed)publish();confirmed
    }
    private fun receipt(request:ImageTextWrite):String {
        val bytes=PackageWriter(131072).use{w->
            w.string("image-derived-text-write-v1",64);w.string(request.id,36);ImageTextEncoding.anchor(w,request.anchor);w.i64(request.expected?.version?:0)
            request.expected?.let{val b=ImageTextEncoding.encode(ImageTextPayload(it.anchor,it.engine,it.history));try{w.i32(b.size);w.raw(b)}finally{b.fill(0)}}
            request.extraction?.let{w.string(it.engine.id,64);w.string(it.engine.version,64);w.i32(if(it.engine.synthetic)1 else 0);w.string(it.text,4000)}
            w.string(request.text.orEmpty(),4000);w.i32(if(request.delete)1 else 0);w.string(request.mutationId,36);w.i64(request.at);w.bytes()
        };return try{hash(bytes)}finally{bytes.fill(0)}
    }
    fun portable(cells:List<Any?>,restoring:Boolean):List<Any?> {
        val row=decode(cells);if(row.state!="READY")return cells
        return cells.toMutableList().also{it[10]=cipher.portable(aad(row),row.bytes,restoring)}
    }
    fun validateFullWithin(sql:RecordsSqlConnection) {live(sql);sql.query("SELECT $COLUMNS FROM local_candidate_batches WHERE ruleVersion=5 AND state!='READY' LIMIT 4097").forEach{require(decode(it).bytes.isEmpty())}}
    fun validatePortable(data:FullBackupData) {
        val allocated=mutableListOf<ByteArray>()
        try {
            val rows=data.tables[8].filter{it[5]==5L}.mapNotNull{cells->
                val row=decode(portable(cells,true).also{if(it!==cells)allocated+=it[10] as ByteArray})
                if(row.state!="READY")null else {
                    val p=payload(row);val a=p.anchor;val e=data.tables[0].single{it[0]==a.intent.entryId};val v=data.tables[1].single{it[0]==e[0]&&it[1]==e[1]};val access=data.tables[2].singleOrNull{it[0]==e[0]}
                    require(e[1]==a.intent.revision.toLong()&&v[3]==a.sourceHash&&(access?.get(1)?:0L)==a.generation&&access?.get(2)!=1L&&e[2]==a.capturedAtMillis&&e[3]==a.zone&&e[4]==a.policy.name)
                    require(data.tables[3].contains(listOf(a.intent.id,a.intent.entryId,a.intent.revision.toLong(),a.intent.mediaType,a.intent.sha256,a.intent.byteSize,"READY")));row to p
                }
            };validateSet(rows)
        }finally{allocated.forEach{it.fill(0)}}
    }
    fun wipeKey(){cipher.wipe()}
    private suspend inline fun<T> safe(block:()->T):T=try{block()}catch(c:CancellationException){throw c}catch(c:ImageTextConflict){throw c}catch(c:ImageTextLimit){throw c}catch(_:Exception){throw ImageTextUnavailable()}
    companion object{private const val COLUMNS="id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload"}
}
