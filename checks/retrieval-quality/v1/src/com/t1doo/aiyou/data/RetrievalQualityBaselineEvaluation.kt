package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Records actual owner results. Gold relevance labels are never used to retrieve or rank. */
class RetrievalQualityBaselineEvaluation {
    private fun JsonObject.text(key:String)=get(key)?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.flag(key:String)=get(key)?.jsonPrimitive?.booleanOrNull==true
    private fun stable(id:String)=UUID.nameUUIDFromBytes("AIyou synthetic quality v1:$id".toByteArray()).toString()
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun recordActualCurrentOwnerRanksWithoutChangingProductionOrUsingGold():Unit=runBlocking {
        val root=Path.of(System.getProperty("aiyou.quality.root"))
        val output=Path.of(System.getProperty("aiyou.quality.output"));Files.createDirectories(output)
        val bytes=Files.readAllBytes(root.resolve("checks/retrieval-quality/v1/catalog.json"))
        val expected=Files.readString(root.resolve("checks/retrieval-quality/v1/catalog.sha256")).substringBefore(' ')
        assertEquals(expected,digest(bytes))
        val catalog=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val records=catalog.getValue("records").jsonArray.map{it.jsonObject}
        val queries=catalog.getValue("queries").jsonArray.map{it.jsonObject}
        val labels=records.associate{stable(it.text("id")) to it.text("id")}
        assertEquals(records.size,labels.size)
        val rows=ArrayList<JsonObject>()
        UnifiedKeywordFixture(withPeople=true).use { h ->
            for(r in records){
                val id=stable(r.text("id"));val text=r.text("text");val captured=Instant.parse(r.text("captured")).toEpochMilli()
                h.f.db.transaction{sql->
                    sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,'LOCAL_ONLY')",listOf(id,captured,r.text("zone")))
                    sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)",listOf(id,text,OriginalExports.hash(text),captured))
                    sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
                }
                if(r.containsKey("audio"))h.audio(id,r.text("audio"))
                if(r.containsKey("image"))h.image(id,r.text("image"))
                if(r.flag("preference"))h.preference(id)
                if(r.containsKey("note")){
                    val memory=AcceptedMemoryKeywordSqlTest.memory(h,id,r.text("note"))
                    if(r.flag("revoke"))AcceptedMemoryKeywordSqlTest.change(h,memory,SourceMemoryAction.REVOKE)
                }
                if(r.containsKey("names")){
                    val names=r.getValue("names").jsonArray.map{it.jsonPrimitive.content}
                    val aliases=r.getValue("aliases").jsonArray.map{it.jsonObject.let{a->ManualPersonAlias(a.text("alias"),a.text("name"))}}
                    h.people.replace(ManualPeopleMutation(h.people.read(id),names,stable("names:"+r.text("id")),aliases))
                }
                if(r.containsKey("revision2"))h.f.revise(id,r.text("revision2"))
                if(r.flag("deleted"))h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
                if(r.flag("ai_none"))h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",id)
            }
            var passes=0
            while(h.originals.batch().remaining){check(++passes<100)}
            for(q in queries){
                val routes=q.getValue("gold").jsonObject.keys // route names only; values never influence runtime results
                val query=q.text("query");val from=q.text("from");val through=q.text("through")
                if("unified" in routes){
                    val p=h.repo.search(query,UnifiedKeywordScope.valueOf(q.text("scope")),UnifiedCaptureRange.parse(from,through),{})
                    assertTrue(h.repo.resolve(p){})
                    rows+=buildJsonObject{
                        put("query_id",q.text("id"));put("route","unified");put("stage","READY");put("proof_current",true)
                        putJsonArray("ranked"){p.groups.forEach{g->
                            assertEquals(g.items.size,g.items.map{it.key}.distinct().size)
                            add(buildJsonObject{put("id",checkNotNull(labels[g.binding.source.sourceId]));put("revision",g.binding.source.revision)
                                putJsonArray("kinds"){g.items.forEach{add(it.key.substringBefore(':'))}}})
                        }}
                    }
                }
                if("original" in routes || "evidence" in routes){
                    val entries=h.originals.candidates(LocalSearchRequest("")).entries
                    val input=OriginalSearchInput(query,from,through,tagSnapshot=h.f.tags.snapshots(),peopleSnapshot=h.people.snapshots())
                    val result=LocalOriginalSearchMatcher.indexed(entries,input,h.originals)
                    assertEquals(OriginalSearchStage.READY,result.stage)
                    assertTrue(h.originals.publishCurrent(checkNotNull(result.proof)){})
                    if("original" in routes)rows+=buildJsonObject{
                        put("query_id",q.text("id"));put("route","original");put("stage",result.stage.name);put("proof_current",true)
                        putJsonArray("ranked"){result.hits.forEach{hit->add(buildJsonObject{
                            put("id",checkNotNull(labels[hit.entry.id]));put("revision",hit.entry.currentRevision)
                            put("original_match",hit.originalMatchesQuery);put("alias_match",hit.aliasMatches.isNotEmpty())
                        })}}
                    }
                    if("evidence" in routes){
                        val state=OriginalSearchState(input,result.stage,result.hits,proof=result.proof,owner=h.originals)
                        val selected=localEvidenceCandidates(state,1)
                        rows+=buildJsonObject{
                            put("query_id",q.text("id"));put("route","evidence");put("stage",selected.stage.name);put("proof_current",true);put("limited",selected.limited)
                            putJsonArray("ranked"){selected.cards.forEach{card->add(buildJsonObject{
                                put("id",checkNotNull(labels[card.reference.source.sourceId]));put("revision",card.reference.source.revision)
                            })}}
                        }
                    }
                }
            }
        }
        val observation=buildJsonObject{
            put("catalog_sha256",digest(bytes));put("production_base_sha","6cc68b6a3fbdab4f92d20cb30f5553363b300cef")
            put("scope","actual production SQL/AEAD search and original matcher/evidence projection; host SQLite adapter, not device SQLCipher")
            putJsonArray("results"){rows.forEach{add(it)}}
        }
        Files.writeString(output.resolve("observations.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),observation)+"\n")
        assertEquals(queries.sumOf{it.getValue("gold").jsonObject.size},rows.size)
    }
}
