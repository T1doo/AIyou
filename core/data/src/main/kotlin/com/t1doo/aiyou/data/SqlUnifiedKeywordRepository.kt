package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*

/** Private bounded collections, created only after the existing profile's full authentication. */
internal class KeywordSet<T>(items:List<T>,sources:List<SourceVersion>,
    private val guardedCurrent:((RecordsSqlConnection,()->Unit)->Boolean)?=null,val current:(RecordsSqlConnection)->Boolean) {
    val items=java.util.Collections.unmodifiableList(items.toList())
    val sources=java.util.Collections.unmodifiableList(sources.toList())
    fun current(sql:RecordsSqlConnection,checkCurrent:()->Unit):Boolean {
        checkCurrent();return guardedCurrent?.invoke(sql,checkCurrent) ?: current(sql).also{checkCurrent()}
    }
}

internal class SqlUnifiedKeywordRepository(private val database:RecordsSqlDatabase,
    private val originals:SqlLocalSearchRepository,private val audio:SqlAudioTranscriptRepository,
    private val images:SqlImageTextRepository,private val preferences:SqlSourcePreferenceRepository,
    private val memories:SqlSourceMemoryRepository):UnifiedKeywordRepository {
    private inner class Proof(override val keyword:String,override val scope:UnifiedKeywordScope,override val captureRange:UnifiedCaptureRange,
        val original:LocalSearchSnapshot?,val audioSet:KeywordSet<AudioTranscriptSnapshot>?,
        val imageSet:KeywordSet<ImageTextSnapshot>?,val preferenceSet:KeywordSet<SourcePreferenceSnapshot>?,
        val memorySet:KeywordSet<AcceptedMemoryKeywordHit>?,
        bindings:List<UnifiedKeywordBinding>,groups:List<UnifiedKeywordGroup>):UnifiedKeywordSnapshot {
        val owner=this@SqlUnifiedKeywordRepository
        override val bindings=java.util.Collections.unmodifiableList(bindings.toList())
        override val groups=java.util.Collections.unmodifiableList(groups.toList())
        override fun toString()="UnifiedKeywordSnapshot(redacted)"
    }
    private fun selected(scope:UnifiedKeywordScope,type:UnifiedKeywordScope)=scope==UnifiedKeywordScope.ALL||scope==type
    override suspend fun search(keyword:String,scope:UnifiedKeywordScope)=search(keyword,scope,{})
    override suspend fun search(keyword:String,scope:UnifiedKeywordScope,checkCurrent:()->Unit)=search(keyword,scope,UnifiedCaptureRange(),checkCurrent)
    override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        checkCurrent()
        PreferenceKeywords.validate(keyword);require(keyword.isNotBlank())
        // Existing FTS/alias candidates are only a superset. The final full literal check below
        // never turns a tag/alias candidate into an original-body match.
        val o=if(selected(scope,UnifiedKeywordScope.ORIGINAL))originals.candidates(LocalSearchRequest(keyword,from=captureRange.from?.toString().orEmpty(),through=captureRange.through?.toString().orEmpty(),dateBasis=RecordDateBasis.CAPTURED))else null
        val a=if(selected(scope,UnifiedKeywordScope.AUDIO))audio.keywordSet(keyword)else null
        val i=if(selected(scope,UnifiedKeywordScope.IMAGE))images.keywordSet(keyword)else null
        val p=if(selected(scope,UnifiedKeywordScope.PREFERENCE))preferences.keywordSet(keyword)else null
        checkCurrent()
        val m=if(selected(scope,UnifiedKeywordScope.ACCEPTED_MEMORY))memories.keywordSet(keyword,captureRange,checkCurrent)else null
        return database.transaction { sql ->
            checkCurrent()
            if(o!=null&&!originals.keywordCurrentWithin(sql,o)||a?.current(sql,checkCurrent)==false||i?.current(sql,checkCurrent)==false||p?.current(sql,checkCurrent)==false||m?.current(sql,checkCurrent)==false)throw UnifiedKeywordConflict()
            val sources=(o?.entries.orEmpty().map{SourceVersion(it.id,it.currentRevision.toLong())}+a?.sources.orEmpty()+i?.sources.orEmpty()+p?.sources.orEmpty()+m?.sources.orEmpty()).distinct()
            val bindings=sources.map{binding(sql,it)}
            val bySource=bindings.associateBy{it.source}
            fun bound(source:SourceVersion)=checkNotNull(bySource[source])
            val evidence=ArrayList<UnifiedKeywordEvidence>()
            o?.entries?.filter{it.originalText.contains(keyword)}?.forEach{evidence+=UnifiedKeywordEvidence.Original(bound(SourceVersion(it.id,it.currentRevision.toLong())),it)}
            a?.items?.forEach{evidence+=UnifiedKeywordEvidence.Audio(bound(it.anchor.source),it)}
            i?.items?.forEach{evidence+=UnifiedKeywordEvidence.Image(bound(it.anchor.source),it)}
            p?.items?.forEach{evidence+=UnifiedKeywordEvidence.Preference(bound(it.source),it)}
            m?.items?.forEach{require(it.binding==bound(it.binding.source));evidence+=UnifiedKeywordEvidence.AcceptedMemory(it)}
            require(evidence.map{it.key}.distinct().size==evidence.size)
            val groups=evidence.filter{captureRange.contains(it.binding.captured,it.binding.zone)}.groupBy{it.binding}.map{(b,rows)->UnifiedKeywordGroup(b,rows.sortedBy{it.key})}
                .sortedWith(compareByDescending<UnifiedKeywordGroup>{it.binding.captured}.thenBy{it.binding.source.sourceId})
            checkCurrent();Proof(keyword,scope,captureRange,o,a,i,p,m,bindings,groups)
        }
    }
    override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit)=resolve(snapshot,item,{},admit)
    override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit):Boolean {
        checkCurrent()
        val p=snapshot as? Proof?:return false
        if(p.owner!==this||item!=null&&p.groups.none{g->g.items.any{it===item}})return false
        // Fresh full-set media authentication is completed before the last shared transaction.
        search(p.keyword,p.scope,p.captureRange,checkCurrent)
        return database.transaction {sql->
            checkCurrent()
            if(p.original!=null&&!originals.keywordCurrentWithin(sql,p.original)||p.audioSet?.current?.invoke(sql)==false||
                p.imageSet?.current?.invoke(sql)==false||p.preferenceSet?.current?.invoke(sql)==false||
                p.memorySet?.current(sql,checkCurrent)==false||
                p.bindings.any{binding(sql,it.source)!=it})false
            else {checkCurrent();admit();true} // No old UI guard after the private admission.
        }
    }
    private fun binding(sql:RecordsSqlConnection,source:SourceVersion):UnifiedKeywordBinding {
        val r=sql.query("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,r.originalText FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=? AND COALESCE(a.deleted,0)=0 AND length(CAST(r.originalText AS BLOB))<=400000 AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)",listOf(source.sourceId)).singleOrNull()?:throw UnifiedKeywordConflict()
        if(r[0]!=source.revision||(r[6] as String).length>100000||OriginalExports.hash(r[6] as String)!=r[1])throw UnifiedKeywordConflict()
        return UnifiedKeywordBinding(source,r[1] as String,r[2] as Long,r[3] as Long,r[4] as String,ProcessingPolicy.valueOf(r[5] as String))
    }
}
