package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.quality.QuestionFragmentsPrototype
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Opt-in design evidence only. Product helper UI/controller is not implemented by this test. */
class QuestionAssistanceDesignProbe {
    private fun JsonObject.text(k:String)=get(k)?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.flag(k:String)=get(k)?.jsonPrimitive?.booleanOrNull==true
    private fun stable(v:String)=UUID.nameUUIDFromBytes("AIyou synthetic question probe v2:$v".toByteArray()).toString()
    @Test fun boundedFragmentsKeepSourceTextAndRejectInvalidOrUnconfirmableInputs() {
        val full=String(IntArray(512){0x4e00+it},0,512)
        val p=QuestionFragmentsPrototype.propose(full)
        assertEquals(64,p.values.size);assertTrue(p.truncated)
        assertTrue(p.values.all{it.text.codePointCount(0,it.text.length)<=16&&it.text==full.substring(it.start,it.end)})
        assertThrows(IllegalArgumentException::class.java){QuestionFragmentsPrototype.propose(full+"字")}
        assertThrows(IllegalArgumentException::class.java){QuestionFragmentsPrototype.propose(String(charArrayOf(0xd800.toChar())))}
        assertThrows(IllegalArgumentException::class.java){QuestionFragmentsPrototype.propose("字"+0.toChar())}
        assertTrue(QuestionFragmentsPrototype.propose("？！").values.isEmpty())
        val quoted="“如果没去𠀀园”是问题，不是事实。";val points=quoted.codePoints().toArray()
        QuestionFragmentsPrototype.propose(quoted).values.forEach{assertEquals(it.text,String(points,it.start,it.end-it.start))}
        assertTrue(QuestionFragmentsPrototype.propose("我喜欢哪种茶").values.none{it.text=="偏好"})
        assertThrows(IllegalArgumentException::class.java){QuestionFragmentsPrototype.confirmedQuery(emptyList())}
        assertThrows(IllegalArgumentException::class.java){QuestionFragmentsPrototype.confirmedQuery((1..9).map{it.toString()})}
        assertThrows(IllegalArgumentException::class.java){QuestionFragmentsPrototype.confirmedQuery(listOf(full))}
        assertEquals("没 种 薄荷",QuestionFragmentsPrototype.confirmedQuery(listOf("没","种","薄荷")))
    }
    @Test fun recordNewVocabularyAndDistractorsThroughActualRecallAfterScriptedUserConfirmation():Unit=runBlocking {
        val root=Path.of(System.getProperty("aiyou.quality.root"));val out=Path.of(System.getProperty("aiyou.quality.output"));Files.createDirectories(out)
        val bytes=Files.readAllBytes(root.resolve("checks/retrieval-quality/v2/question-probes.json"))
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        assertEquals(Files.readString(root.resolve("checks/retrieval-quality/v2/question-probes.sha256")).substringBefore(' '),hash)
        val data=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val records=data.getValue("records").jsonArray.map{it.jsonObject};val labels=records.associate{stable(it.text("id")) to it.text("id")}
        val results=ArrayList<JsonObject>()
        UnifiedKeywordFixture().use{h->
            for(r in records){val id=stable(r.text("id"));val text=r.text("text");val time=Instant.parse(r.text("captured")).toEpochMilli()
                h.f.db.transaction{sql->
                    sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,'LOCAL_ONLY')",listOf(id,time,r.text("zone")))
                    sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)",listOf(id,text,OriginalExports.hash(text),time))
                    sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
                }
                if(r.containsKey("revision2"))h.f.revise(id,r.text("revision2"))
                if(r.flag("deleted"))h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
            }
            var batches=0;while(h.originals.batch().remaining){check(++batches<100)}
            suspend fun lookup(query:String,from:String,through:String):JsonObject {
                val entries=h.originals.candidates(LocalSearchRequest("")).entries
                val input=OriginalSearchInput(query,from,through)
                val found=LocalOriginalSearchMatcher.indexed(entries,input,h.originals)
                assertEquals(OriginalSearchStage.READY,found.stage);assertTrue(h.originals.publishCurrent(checkNotNull(found.proof)){})
                return buildJsonObject{
                    put("query",query);putJsonArray("ranked"){found.hits.forEach{hit->add(buildJsonObject{
                        put("id",checkNotNull(labels[hit.entry.id]));put("revision",hit.entry.currentRevision)
                        put("original",hit.entry.originalText);put("original_match",hit.originalMatchesQuery)
                    })}}
                }
            }
            for(q in data.getValue("queries").jsonArray.map{it.jsonObject}){
                val question=q.text("question");val proposed=QuestionFragmentsPrototype.propose(question)
                assertTrue(proposed.values.size<=64);val cp=question.codePoints().toArray()
                proposed.values.forEach{assertEquals(it.text,String(cp,it.start,it.end-it.start))}
                val fields=buildJsonObject{
                    put("id",q.text("id"));put("question",question);put("status",q.text("status"));put("truncated",proposed.truncated)
                    putJsonArray("proposals"){proposed.values.forEach{add(buildJsonObject{put("text",it.text);put("start",it.start);put("end",it.end)})}}
                }.toMutableMap()
                if(q.text("status")=="clarification_required"){
                    fields["query_executed"]=JsonPrimitive(false)
                }else{
                    val terms=q.getValue("confirmed_terms").jsonArray.map{it.jsonPrimitive.content}
                    val joined=QuestionFragmentsPrototype.confirmedQuery(terms)
                    fields["query_executed"]=JsonPrimitive(true)
                    fields["confirmed_query"]=JsonPrimitive(joined)
                    fields["all_terms_offered"]=JsonPrimitive(terms.all{t->proposed.values.any{it.text==t}})
                    fields["raw_question"]=lookup(question,q.text("from"),q.text("through"))
                    fields["after_scripted_confirmation"]=lookup(joined,q.text("from"),q.text("through"))
                }
                results+=JsonObject(fields)
            }
        }
        val observation=buildJsonObject{
            put("corpus_sha256",hash);put("production_base_sha","6cc68b6a3fbdab4f92d20cb30f5553363b300cef")
            put("prototype_revision",2);put("resource_revision","whole-run candidate <=16 codepoints; same frozen corpus/labels; no content-specific rule")
            put("scope","nonshipping fragment prototype and actual original recall after prewritten synthetic user choices; no automatic query selection, UI or user study")
            putJsonArray("results"){results.forEach{add(it)}}
        }
        Files.writeString(out.resolve("question-observations.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),observation)+"\n")
    }
}
