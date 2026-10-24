package com.t1doo.aiyou.data
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal class SqlAudioTranscriptRepository(private val database:RecordsSqlDatabase,key:ByteArray,private val media:AttachmentStore?=null,private val gate:CaptureDraftGate?=null):AudioTranscriptRepository {
    @Volatile private var retired=false
    private val cipher=AudioTranscriptCipher(key)
    private data class Row(val cells:List<Any?>) {val id get()=cells[0] as String;val version get()=cells[6] as Long;val state get()=cells[7] as String;val bytes get()=cells[10] as ByteArray;val created get()=cells[9] as Long}
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun aad(r:Row)=PackageWriter(4096).use{w->w.string("AIyou/manual-transcript/profile4/1",64);r.cells.take(10).forEach {v->if(v is Long){w.i32(1);w.i64(v)}else{w.i32(2);w.string(v as String,512)}};w.bytes()}
    private fun decode(c:List<Any?>):Row {require(c[5]==4L && c[7] in setOf("READY","INVALIDATED") && (c[6] as Long)>0);PreferenceValues.uuid(c[0] as String);SourceVersion(c[1] as String,c[2] as Long);require((c[3] as String).matches(Regex("[0-9a-f]{64}"))&&(c[4] as Long)>=0&&(c[9] as Long)>=0);if(c[7]=="INVALIDATED")require(c[8]=="SOURCE_INVALIDATED"&&(c[6] as Long) in 2..17)else require((c[8] as String).matches(Regex("[0-9a-f]{64}")));return Row(c)}
    private fun payload(r:Row):TranscriptPayload {require(r.state=="READY");val p=cipher.open(aad(r),r.bytes);val a=p.anchor;require(a.intent.entryId==r.cells[1]&&a.intent.revision.toLong()==r.cells[2]&&a.sourceHash==r.cells[3]&&a.generation==r.cells[4]&&p.expected==r.version-1&&p.history.first().at==r.created);val count=p.history.size.toLong();require(r.version in (count+if(p.withdrawn)1 else 0)..(count*2-if(p.withdrawn)0 else 1));if(!p.withdrawn)require(p.at==p.history.last().at);return p}
    private fun snapshot(r:Row,p:TranscriptPayload)=AudioTranscriptSnapshot(r.id,p.anchor,r.version,p.withdrawn,p.history)
    private fun row(sql:RecordsSqlConnection,id:String)=sql.query("SELECT $COLUMNS FROM local_candidate_batches WHERE id=?",listOf(id)).singleOrNull()?.let(::decode)
    private fun source(sql:RecordsSqlConnection,i:AttachmentIntent,samples:Int):AudioTranscriptSource {
        val r=sql.query("SELECT e.currentRevision,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0),r.originalText FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=? AND length(CAST(r.originalText AS BLOB))<=400000",listOf(i.entryId)).singleOrNull()?:throw AudioTranscriptConflict()
        if((r[7] as String).length>100000||OriginalExports.hash(r[7] as String)!=r[1]||r[0]!=i.revision.toLong()||r[6]!=0L||sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=? LIMIT 1",listOf(i.entryId)).isNotEmpty())throw AudioTranscriptConflict()
        val actual=sql.query("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments WHERE id=?",listOf(i.id)).singleOrNull()
        if(actual!=listOf(i.id,i.entryId,i.revision.toLong(),i.mediaType,i.sha256,i.byteSize,"READY"))throw AudioTranscriptConflict()
        return AudioTranscriptSource(i,r[1] as String,r[5] as Long,r[2] as Long,r[3] as String,ProcessingPolicy.valueOf(r[4] as String),samples)
    }
    private fun current(sql:RecordsSqlConnection,a:AudioTranscriptSource){if(source(sql,a.intent,a.samples)!=a)throw AudioTranscriptConflict()}
    override suspend fun preview(intent:AttachmentIntent):AudioTranscriptSource = safe {
        require(intent.mediaType=="audio/wav");database.transaction{source(it,intent,Math.toIntExact((intent.byteSize-44)/2))}
        val bytes=checkNotNull(media).read(intent.id)
        try {require(bytes.size.toLong()==intent.byteSize&&hash(bytes)==intent.sha256);val n=LocalPcmAudio.inspect(bytes).samples;database.transaction{source(it,intent,n)}}finally{bytes.fill(0)}
    }
    override suspend fun read(anchor:AudioTranscriptSource):AudioTranscriptSnapshot? = safe {
        if(preview(anchor.intent)!=anchor)throw AudioTranscriptConflict()
        database.transaction{sql->current(sql,anchor);val all=live(sql);all.singleOrNull{it.second.anchor==anchor}?.let{snapshot(it.first,it.second)}}
    }
    private class PlaybackRef(val owner:SqlAudioTranscriptRepository,val saved:AudioTranscriptSnapshot,val digest:String):AudioTranscriptPlaybackReference {
        override val anchor get()=saved.anchor
        override val range get()=saved.current.range
        override fun toString()="AudioTranscriptPlaybackReference(redacted)"
    }
    override fun ownsPlaybackReference(reference:AudioTranscriptPlaybackReference)=!retired && reference is PlaybackRef && reference.owner===this
    private fun authenticated(sql:RecordsSqlConnection,ref:PlaybackRef) {
        require(ownsPlaybackReference(ref));current(sql,ref.anchor)
        val row=row(sql,ref.saved.id)?:throw AudioTranscriptConflict()
        if(row.state!="READY"||hash(row.bytes)!=ref.digest||!snapshot(row,payload(row)).same(ref.saved)||ref.saved.withdrawn)throw AudioTranscriptConflict()
    }
    override suspend fun playbackReference(snapshot:AudioTranscriptSnapshot):AudioTranscriptPlaybackReference = safe {
        if(retired||snapshot.withdrawn||preview(snapshot.anchor.intent)!=snapshot.anchor)throw AudioTranscriptConflict()
        database.transaction {sql->
            current(sql,snapshot.anchor);val row=row(sql,snapshot.id)?:throw AudioTranscriptConflict()
            if(row.state!="READY"||!snapshot(row,payload(row)).same(snapshot))throw AudioTranscriptConflict()
            PlaybackRef(this,snapshot,hash(row.bytes))
        }
    }
    override suspend fun playback(reference:AudioTranscriptPlaybackReference,pin:CaptureDraftGate.PlaybackPin):AudioTranscriptPlayback = safe {
        val ref=reference as? PlaybackRef?:throw AudioTranscriptConflict()
        val owner=checkNotNull(gate)
        require(owner.isPlaybackActive(pin)&&ownsPlaybackReference(ref))
        database.transaction {authenticated(it,ref)}
        val bytes=checkNotNull(media).read(ref.anchor.intent.id)
        try {
            require(bytes.size.toLong()==ref.anchor.intent.byteSize&&hash(bytes)==ref.anchor.intent.sha256&&LocalPcmAudio.inspect(bytes).samples==ref.anchor.samples)
            database.transaction {authenticated(it,ref)}
            require(owner.isPlaybackActive(pin)&&ownsPlaybackReference(ref))
            AudioTranscriptPlayback(bytes,ref.saved.current.range)
        }catch(t:Throwable){bytes.fill(0);throw t}
    }
    override suspend fun write(request:AudioTranscriptWrite):AudioTranscriptSnapshot = safe {
        suspend fun commit():AudioTranscriptSnapshot {
            if(preview(request.anchor.intent)!=request.anchor)throw AudioTranscriptConflict()
            return database.transaction{sql->
                current(sql,request.anchor);val all=live(sql);val expected=request.expected
                val receipt=receipt(request);val old=row(sql,request.id)
                if(old!=null && old.state=="READY" && old.cells[8]==receipt && old.version==(expected?.version?:0)+1)return@transaction snapshot(old,payload(old))
                if(expected==null) {
                    if(old!=null||all.any{it.second.anchor.intent.id==request.anchor.intent.id})throw AudioTranscriptConflict()
                    if(all.size>=128||all.count{it.second.anchor.source==request.anchor.source}>=20||(sql.query("SELECT count(*) FROM local_candidate_batches").single().single() as Long)>=4096)throw AudioTranscriptLimit()
                } else if(old==null||!snapshot(old,payload(old)).same(expected))throw AudioTranscriptConflict()
                val history=if(request.withdraw)checkNotNull(expected).history else {
                    val h=expected?.history.orEmpty();if(h.size>=8)throw AudioTranscriptLimit()
                    if(expected!=null&&!expected.withdrawn&&expected.current.text==request.text&&expected.current.range==request.range)throw AudioTranscriptConflict()
                    h+AudioTranscriptVersion(h.size+1,checkNotNull(request.text),request.at,request.range)
                }
                val p=TranscriptPayload(request.anchor,history,request.withdraw,expected?.version?:0,request.at)
                val cells=listOf(request.id,request.anchor.intent.entryId,request.anchor.intent.revision.toLong(),request.anchor.sourceHash,request.anchor.generation,4L,(expected?.version?:0)+1,"READY",receipt,old?.created?:request.at,byteArrayOf())
                val next=Row(cells);val encrypted=cipher.seal(aad(next),p)
                if(all.sumOf{it.first.bytes.size.toLong()}-(old?.bytes?.size?:0)+encrypted.size>4_194_304)throw AudioTranscriptLimit()
                if(old==null)sql.execute("INSERT INTO local_candidate_batches($COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?)",cells.dropLast(1)+encrypted)
                else if(sql.execute("UPDATE local_candidate_batches SET version=?,lastMutationId=?,payload=? WHERE id=? AND ruleVersion=4 AND state='READY' AND version=?",listOf(next.version,receipt,encrypted,request.id,old.version))!=1)throw AudioTranscriptConflict()
                val saved=checkNotNull(row(sql,request.id));check(payload(saved)==p);snapshot(saved,p)
            }
        }
        gate?.sourceMutation {commit()}?:commit()
    }
    private fun live(sql:RecordsSqlConnection):List<Pair<Row,TranscriptPayload>> {
        val size=sql.query("SELECT count(*),COALESCE(sum(length(payload)),0) FROM local_candidate_batches WHERE ruleVersion=4 AND state='READY'").single()
        if((size[0] as Long)>128||(size[1] as Long)>4_194_304)throw AudioTranscriptLimit()
        val values=sql.query("SELECT $COLUMNS FROM local_candidate_batches WHERE ruleVersion=4 AND state='READY' ORDER BY createdAtMillis DESC,id LIMIT 129").map{cells->val r=decode(cells);r to payload(r).also{current(sql,it.anchor)}}
        validateSet(values);return values
    }
    private fun validateSet(rows:List<Pair<Row,TranscriptPayload>>) {if(rows.size>128||rows.groupingBy{it.second.anchor.source}.eachCount().values.any{it>20})throw AudioTranscriptLimit();require(rows.map{it.second.anchor.intent.id}.distinct().size==rows.size)}
    private inner class ExportScope(val snapshot:OriginalExportSnapshot,val audios:List<RecordPackageAudio>,
        val signatures:List<String>,items:List<AudioTranscriptSnapshot>):OpenTranscriptScope {
        val owner=this@SqlAudioTranscriptRepository
        override val items:List<AudioTranscriptSnapshot> = java.util.Collections.unmodifiableList(items.toList())
        override fun toString()="OpenTranscriptScope(redacted)"
    }
    internal fun openExportWithin(sql:RecordsSqlConnection,snapshot:OriginalExportSnapshot,audios:List<RecordPackageAudio>):OpenTranscriptScope {
        check(!retired)
        // Authenticate ALL READY rows, including withdrawn/history, before selecting this exact media set.
        val all=live(sql);val ids=audios.map {it.attachmentId}.toSet()
        val selected=all.filter {it.second.anchor.intent.id in ids}
        selected.forEach { (_,p) ->
            val a=p.anchor;val i=a.intent
            require(audios.any {it.attachmentId==i.id && it.sourceId==i.entryId && it.sourceRevision==i.revision && it.sha256==i.sha256 && it.byteSize==i.byteSize})
            require(snapshot.records.any {it.entry.id==i.entryId && it.entry.currentRevision==i.revision && it.contentHash==a.sourceHash &&
                it.sourceGeneration==a.generation && it.entry.capturedAtMillis==a.capturedAtMillis && it.entry.captureTimeZone==a.zone && it.entry.policy==a.policy})
        }
        return ExportScope(snapshot,java.util.Collections.unmodifiableList(audios.toList()),
            selected.map {it.first.id+":"+it.first.version+":"+hash(it.first.bytes)},
            selected.filterNot {it.second.withdrawn}.map {this.snapshot(it.first,it.second)})
    }
    internal fun openExportCurrentWithin(sql:RecordsSqlConnection,scope:OpenTranscriptScope,snapshot:OriginalExportSnapshot,audios:List<RecordPackageAudio>):Boolean {
        val proof=scope as? ExportScope?:return false
        if(proof.owner!==this || proof.snapshot!==snapshot || proof.audios!=audios || retired)return false
        val now=openExportWithin(sql,snapshot,audios) as ExportScope
        return proof.signatures==now.signatures && proof.items.size==now.items.size && proof.items.zip(now.items).all{(a,b)->a.same(b)}
    }
    private inner class Page(override val keyword:String,override val cursor:PreferenceCursor?,override val next:PreferenceCursor?,items:List<AudioTranscriptSnapshot>,val signatures:List<String>):AudioTranscriptPage {val owner=this@SqlAudioTranscriptRepository;override val items:List<AudioTranscriptSnapshot> = java.util.Collections.unmodifiableList(items.toList());override fun toString()="AudioTranscriptPage(redacted)"}
    override suspend fun search(keyword:String,cursor:PreferenceCursor?):AudioTranscriptPage = safe {
        PreferenceKeywords.validate(keyword)
        val initial=database.transaction{sql->val rows=live(sql);rows.map{it.second.anchor} to rows.map{it.first.id+":"+it.first.version+":"+hash(it.first.bytes)}}
        val anchors=initial.first
        // One-clip batches: live() admits at most 128 canonical WAVs, each <= MAX_WAV_BYTES.
        // preview() authenticates and wipes each buffer before the next read. The independent
        // full-backup media bound must not shrink this repository's legal saved-data domain.
        for(a in anchors)if(preview(a.intent)!=a)throw AudioTranscriptConflict()
        database.transaction{page(it,keyword,cursor).also{p->if(p.signatures!=initial.second)throw AudioTranscriptConflict()}}
    }
    private fun page(sql:RecordsSqlConnection,keyword:String,cursor:PreferenceCursor?):Page {
        val all=live(sql);val matched=all.filter{!it.second.withdrawn&&it.second.history.last().text.contains(keyword)}.filter{cursor==null||it.first.created<cursor.createdAtMillis||it.first.created==cursor.createdAtMillis&&it.first.id>cursor.id};val selected=matched.take(20)
        return Page(keyword,cursor,if(matched.size>20)selected.last().first.let{PreferenceCursor(it.created,it.id)}else null,selected.map{snapshot(it.first,it.second)},all.map{it.first.id+":"+it.first.version+":"+hash(it.first.bytes)})
    }
    internal suspend fun keywordSet(keyword:String):KeywordSet<AudioTranscriptSnapshot> {
        val initial=search(keyword,null) as Page
        return database.transaction { sql ->
            val all=live(sql)
            if(all.map{it.first.id+":"+it.first.version+":"+hash(it.first.bytes)}!=initial.signatures)throw AudioTranscriptConflict()
            val items=ArrayList<AudioTranscriptSnapshot>();var cursor:PreferenceCursor?=null
            do {val p=page(sql,keyword,cursor);items.addAll(p.items);cursor=p.next}while(cursor!=null)
            KeywordSet(items,all.map{it.second.anchor.source}) { current ->
                page(current,keyword,null).signatures==initial.signatures
            }
        }
    }
    override suspend fun resolve(page:AudioTranscriptPage,item:AudioTranscriptSnapshot?,publish:()->Unit):Boolean = safe {
        val p=page as? Page?:return@safe false;if(p.owner!==this||item!=null&&p.items.none{it===item})return@safe false
        val fresh=search(p.keyword,p.cursor) as Page
        if(p.signatures!=fresh.signatures||p.next!=fresh.next)return@safe false
        val confirmed=database.transaction{sql->val last=page(sql,p.keyword,p.cursor);last.signatures==p.signatures&&last.next==p.next};if(confirmed)publish();confirmed
    }
    private fun receipt(r:AudioTranscriptWrite):String {
        val ranged=r.range!=null||r.expected?.history.orEmpty().any {it.range!=null}
        val b=PackageWriter(131072).use {w->
            w.string(if(ranged)"human-transcript-write-v2"else "human-transcript-write-v1",64)
            w.string(r.id,36);TranscriptEncoding.anchor(w,r.anchor);w.i64(r.expected?.version?:0)
            r.expected?.let {e->w.i32(if(e.withdrawn)1 else 0);w.i32(e.history.size);e.history.forEach {w.i32(it.revision);w.string(it.text,4000);w.i64(it.at);if(ranged)TranscriptEncoding.range(w,it.range)}}
            w.string(r.mutationId,36);w.i64(r.at);w.i32(if(r.withdraw)1 else 0);w.string(r.text.orEmpty(),4000)
            if(ranged)TranscriptEncoding.range(w,r.range);w.bytes()
        };return try {hash(b)}finally {b.fill(0)}
    }
    fun portable(cells:List<Any?>,restoring:Boolean):List<Any?> {val r=decode(cells);if(r.state=="INVALIDATED"){require(r.bytes.isEmpty());return cells};return cells.toMutableList().also{it[10]=cipher.portable(aad(r),r.bytes,restoring)}}
    fun validateFullWithin(sql:RecordsSqlConnection){live(sql);sql.query("SELECT $COLUMNS FROM local_candidate_batches WHERE ruleVersion=4 AND state='INVALIDATED' LIMIT 4097").forEach{require(decode(it).bytes.isEmpty())}}
    fun validatePortable(data:FullBackupData) {
        val allocated=mutableListOf<ByteArray>()
        try {val rows=data.tables[8].filter{it[5]==4L}.mapNotNull{cells->val r=decode(portable(cells,true).also{if(it!==cells)allocated+=it[10] as ByteArray});if(r.state=="INVALIDATED")null else{val p=payload(r);val a=p.anchor;val e=data.tables[0].single{it[0]==a.intent.entryId};val v=data.tables[1].single{it[0]==e[0]&&it[1]==e[1]};val access=data.tables[2].singleOrNull{it[0]==e[0]};require(e[1]==a.intent.revision.toLong()&&v[3]==a.sourceHash&&(access?.get(1)?:0L)==a.generation&&access?.get(2)!=1L&&e[2]==a.capturedAtMillis&&e[3]==a.zone&&e[4]==a.policy.name);require(data.tables[3].contains(listOf(a.intent.id,a.intent.entryId,a.intent.revision.toLong(),a.intent.mediaType,a.intent.sha256,a.intent.byteSize,"READY")));r to p}}
            validateSet(rows);if(rows.sumOf{it.first.bytes.size.toLong()}>4_194_304)throw AudioTranscriptLimit()
        }finally{allocated.forEach{it.fill(0)}}
    }
    fun wipeKey(){retired=true;cipher.wipe()}
    private suspend inline fun <T> safe(block:()->T):T=try{block()}catch(c:CancellationException){throw c}catch(c:AudioTranscriptConflict){throw c}catch(c:AudioTranscriptLimit){throw c}catch(_:Exception){throw AudioTranscriptUnavailable()}
    companion object {private const val COLUMNS="id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload"}
}
