package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Opt-in evaluator only. Never loads gold into the production-path runner. */
class IndependentHoldoutEvaluation {
    private val root=Path.of(System.getProperty("aiyou.quality.root"))
    private val out=Path.of(System.getProperty("aiyou.quality.output"))
    private val data=root.resolve("checks/retrieval-quality/v3/frozen")
    private fun JsonObject.s(k:String)=get(k)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.arr(k:String)=get(k)?.jsonArray?:JsonArray(emptyList())
    private fun sha(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
    private fun load(n:String)=Json.parseToJsonElement(Files.readString(data.resolve(n))).jsonObject
    private fun save(n:String,v:JsonObject){Files.createDirectories(out);val p=out.resolve(n);check(!Files.exists(p));Files.writeString(p,Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),v)+"\n",StandardOpenOption.CREATE_NEW)}
    private fun id(s:String)=UUID.nameUUIDFromBytes("AIyou independent synthetic v3:$s".toByteArray()).toString()
    private fun methodStage(v:String){assertEquals(v,System.getProperty("aiyou.quality.stage"))}
    private fun verify(manifest:String){
        val m=load(manifest)
        for(item in m.arr("files").map{it.jsonObject})assertEquals(item.s("sha256"),sha(Files.readAllBytes(data.resolve(item.s("path")))))
        for(item in m.arr("production_sources").map{it.jsonObject})assertEquals(item.s("sha256"),sha(Files.readAllBytes(root.resolve(item.s("path")))))
    }

    @Test fun calibrateRealControllersAndSqlBeforeHoldout():Unit=runBlocking {
        methodStage("calibration")
        val rows=Json.parseToJsonElement("""[
          {"id":"cal-a","text":"我没有修好木椅。","captured":"2026-09-01T00:00:00Z","zone":"UTC","names":["同伴甲"],"aliases":[{"alias":"伙伴甲","name":"同伴甲"}],"event_date":"2026-08-31","state":"current","revision":1},
          {"id":"cal-b","text":"我修好了木椅。","captured":"2026-10-01T00:00:00Z","zone":"UTC","names":[],"aliases":[],"event_date":null,"state":"current","revision":1},
          {"id":"cal-c","text":"木椅旧记录","captured":"2026-10-02T00:00:00Z","zone":"UTC","names":[],"aliases":[],"event_date":null,"state":"deleted","revision":1}
        ]""").jsonArray.map{it.jsonObject}
        H(rows).use{h->
            fun q(text:String,basis:String="CAPTURED",from:String="",through:String="")=buildJsonObject{put("id","cal");put("question",text);put("context",buildJsonObject{put("date_basis",basis);put("date_decision",if(from.isEmpty())"UNLIMITED" else "RANGE");put("from",from);put("through",through)})}
            fun plan(terms:List<String>)=buildJsonObject{put("decision","confirm");put("terms",JsonArray(terms.map(::JsonPrimitive)));put("selected",JsonArray(emptyList()));put("manual_terms",JsonArray(terms.map(::JsonPrimitive)))}
            val direct=h.run(q("木椅"),plan(listOf("木椅")),"direct_keywords")
            assertEquals(2,direct.arr("ranked").size);assertEquals("READY",direct.s("stage"));assertEquals(2,direct.arr("resolved").size)
            val alias=h.run(q("伙伴甲"),plan(listOf("伙伴甲")),"direct_keywords")
            assertEquals("cal-a",alias.arr("ranked").single().jsonObject.s("id"));assertFalse(alias.arr("ranked").single().jsonObject["original_match"]!!.jsonPrimitive.boolean)
            val assisted=h.run(q("木椅修好了吗？","EVENT","2026-08-31","2026-08-31"),plan(listOf("木椅")),"assisted_confirmed")
            assertEquals("cal-a",assisted.arr("ranked").single().jsonObject.s("id"));assertEquals(1,assisted["commits"]!!.jsonPrimitive.int)
            assertEquals(0,assisted["sql_before_confirm"]!!.jsonPrimitive.int)
            val cancelled=h.run(q("木椅？"),buildJsonObject{put("decision","abstain");put("reason","calibration cancellation")},"assisted_confirmed")
            assertEquals("ABSTAINED",cancelled.s("stage"));assertEquals(0,cancelled["query_count"]!!.jsonPrimitive.int)
        }
        save("calibration.json",buildJsonObject{put("status","PASS");put("holdout_queries",0);put("scope","real SQL/AEAD metadata/controllers/source lease and resolve, host only")})
    }

    @Test fun emitActualProposalsWithoutSearchingHoldout(){
        methodStage("proposals");verify("dataset-freeze.json")
        val questions=load("questions.json").arr("questions")
        save("proposals.json",buildJsonObject{put("question_sha256",sha(Files.readAllBytes(data.resolve("questions.json"))));put("query_count",0)
            put("questions",buildJsonArray{for(raw in questions){val q=raw.jsonObject;val p=QuestionKeywordFragments.propose(q.s("question"));add(buildJsonObject{
                put("id",q.s("id"));put("truncated",p.truncated);put("proposals",buildJsonArray{p.values.forEach{add(buildJsonObject{put("start",it.start);put("end",it.end);put("text",it.text)})}})
            })}})
        })
    }

    @Test fun runFrozenIndependentPairedPathsOnce():Unit=runBlocking{
        methodStage("paired");verify("execution-freeze.json")
        val records=load("corpus.json").arr("records").map{it.jsonObject}
        val questions=load("questions.json").arr("questions").map{it.jsonObject}
        assertEquals(24,questions.size)
        val observations=buildJsonArray{
            for(route in listOf("direct_keywords","assisted_confirmed")){
                val plans=load(if(route=="direct_keywords")"actions-direct.json" else "actions-assisted.json").arr("plans").map{it.jsonObject}
                assertEquals(questions.map{it.s("id")}.toSet(),plans.map{it.s("id")}.toSet());assertEquals(24,plans.size)
                H(records).use{h->
                    save("source-$route.json",h.sourceReceipt())
                    for(q in questions){
                        val plan=plans.single{it.s("id")==q.s("id")}
                        val context=q["context"]!!.jsonObject
                        val expected=buildJsonObject{for(k in listOf("date_basis","date_decision","from","through"))put(k,context.getValue(k))}
                        assertEquals("Date decision must be explicit in the frozen plan",expected,plan["date_choice"])
                        add(h.run(q,plan,route))
                    }
                }
            }
        }
        save("observations.json",buildJsonObject{put("execution_manifest_sha256",sha(Files.readAllBytes(data.resolve("execution-freeze.json"))));put("scope","actual controllers plus production indexed recall and authenticated SQL publication/resolve; agent choices, not users or automatic Q&A");put("results",observations)})
    }

    private inner class H(records:List<JsonObject>):AutoCloseable {
        val fixture=UnifiedKeywordFixture(withPeople=true)
        val dates=SqlRecordEventDateRepository(fixture.f.gateway)
        val names=records.associate{id(it.s("id")) to it.s("id")}
        val queries=AtomicInteger();val commits=AtomicInteger();val sql=AtomicInteger()
        var publication=CompletableDeferred<Boolean>();var resolution=CompletableDeferred<Boolean>()
        val observer=object:LocalSearchRepository by fixture.originals {
            override fun publishCurrent(snapshot:LocalSearchSnapshot,publish:()->Unit):Boolean = fixture.originals.publishCurrent(snapshot,publish).also{publication.complete(it)}
            override fun resolve(snapshot:LocalSearchSnapshot,entryId:String,publish:(StoredEntry)->Unit):Boolean = fixture.originals.resolve(snapshot,entryId,publish).also{resolution.complete(it)}
        }
        val source:AtomicReference<LocalRecordUiState>
        val input=AtomicReference(OriginalSearchInput(ownership=Any()))
        private var navigationChanged:(()->Unit)?=null
        val gate=SearchNavigationGate{navigationChanged?.invoke()}
        val search:LocalOriginalSearchController
        val helper:QuestionKeywordController
        init{
            for(r in records){
                val key=id(r.s("id"));val time=Instant.parse(r.s("captured")).toEpochMilli();val rev=r["revision"]!!.jsonPrimitive.int
                fixture.f.db.transaction{s->
                    s.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,'LOCAL_ONLY')",listOf(key,time,r.s("zone")))
                    val text=if(rev==2)r.s("old_text") else r.s("text")
                    s.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)",listOf(key,text,OriginalExports.hash(text),time))
                    s.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(key))
                }
                if(rev==2)fixture.f.revise(key,r.s("text"))
                val aliases=r.arr("aliases").map{it.jsonObject}.map{ManualPersonAlias(it.s("alias"),it.s("name"))}
                val people=r.arr("names").map{it.jsonPrimitive.content}
                if(people.isNotEmpty())fixture.people.replace(ManualPeopleMutation(fixture.people.read(key),people,UUID.randomUUID().toString(),aliases))
                if(r.s("event_date").isNotEmpty()){
                    val generation=(fixture.f.db.read("SELECT generation FROM remote_source_access WHERE entryId=?",key).single().single() as Long)
                    dates.set(RecordEventDateMutation(key,rev,OriginalExports.hash(r.s("text")),0,RecordEventDate(r.s("event_date"),r.s("zone")),UUID.randomUUID().toString(),generation))
                }
                if(r.s("state")=="deleted")fixture.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",key)
            }
            var batches=0;while(fixture.originals.batch().remaining)check(++batches<100)
            val entries=fixture.originals.candidates(LocalSearchRequest("")).entries
            source=AtomicReference(LocalRecordUiState(ready=true,opening=false,entries=entries,localSearch=observer,localSearchEpoch=observer.epoch(),
                manualPeople=fixture.people.snapshots(),manualPeopleAvailable=true,manualTags=fixture.f.tags.snapshots(),manualTagsAvailable=true,eventDates=dates.snapshots(),eventDatesAvailable=true))
            search=LocalOriginalSearchController(source::get,input::get,arbitrateNavigation=gate::arbitrate,matcher={e,i->queries.incrementAndGet();LocalOriginalSearchMatcher.indexed(e,i,observer)})
            helper=QuestionKeywordController(gate,currentBinding={QuestionSearchBinding(gate.generation,source.get(),input.get())},retireSearch=search::invalidateInput,commit={r->
                search.tryChangeInput{commits.incrementAndGet();input.set(input.get().copy(query=r.query,from=r.from,through=r.through,dateBasis=r.basis,mode=OriginalSearchMode.KEYWORDS,ownership=Any()))}
            })
            navigationChanged=helper::retire;helper.foreground(true);fixture.f.db.beforeSql={sql.incrementAndGet()}
        }
        private fun ui()=checkNotNull(helper.state.value)
        fun sourceReceipt()=buildJsonObject{
            put("epoch",source.get().localSearchEpoch!!)
            put("scope","actual repository snapshots after synthetic fixture installation; host SQLite with AEAD metadata, not device SQLCipher")
            put("sources",buildJsonArray{for(entry in source.get().entries){add(buildJsonObject{
                put("id",names.getValue(entry.id));put("revision",entry.currentRevision);put("text_sha256",OriginalExports.hash(entry.originalText))
                val people=source.get().manualPeople.getValue(entry.id)
                put("people_version",people.version);put("people_source_revision",people.sourceRevision);put("people_source_hash",people.sourceHash)
                put("names",JsonArray(people.names.map(::JsonPrimitive)));put("aliases",buildJsonArray{people.aliases.forEach{add(buildJsonObject{put("alias",it.alias);put("name",it.name)})}})
                val date=source.get().eventDates.getValue(entry.id)
                put("event_version",date.version);put("event_date",date.date?.localDate?.let(::JsonPrimitive)?:JsonNull)
            })}})
        }
        suspend fun run(q:JsonObject,plan:JsonObject,route:String):JsonObject{
            helper.retire();search.clear();queries.set(0);commits.set(0);sql.set(0);publication=CompletableDeferred()
            input.set(OriginalSearchInput(ownership=Any(),peopleSnapshot=source.get().manualPeople,tagSnapshot=source.get().manualTags,eventDateSnapshot=source.get().eventDates))
            val c=q["context"]!!.jsonObject;var preConfirmSql=0
            val actions=ArrayList<String>();var stage="ABSTAINED";var value:OriginalSearchState?=null;var publicationAck=false;var executorError:String?=null
            var proposals:QuestionKeywordFragments.Proposals?=null
            try{
                if(route=="assisted_confirmed"){
                    helper.open();actions+="open";helper.question(ui(),q.s("question"));actions+="question";proposals=ui().proposals
                    if(plan.s("decision")=="abstain"){helper.cancel(ui());actions+="cancel"}
                    else{
                        for(raw in plan.arr("selected")){val f=raw.jsonObject
                            val chosen=ui().proposals.values.single{it.start==f["start"]!!.jsonPrimitive.int&&it.end==f["end"]!!.jsonPrimitive.int&&it.text==f.s("text")}
                            helper.choose(ui(),chosen);actions+="choose:${chosen.start}:${chosen.end}"
                        }
                        if(plan["manual_terms"] is JsonArray){helper.terms(ui(),plan.arr("manual_terms").joinToString(" "){it.jsonPrimitive.content});actions+="terms"}
                        helper.basis(ui(),RecordDateBasis.valueOf(c.s("date_basis")));actions+="basis"
                        if(c.s("date_decision")=="RANGE"){
                            helper.from(ui(),c.s("from"));helper.through(ui(),c.s("through"));actions+="from/through"
                        }else{helper.dateChoice(ui(),QuestionDateChoice.UNLIMITED);actions+="unlimited"}
                        search.sync(false);preConfirmSql=sql.get();assertEquals(0,queries.get());assertEquals(0,preConfirmSql)
                        helper.confirm(ui());actions+="confirm"
                        if(helper.state.value!=null)stage="REJECTED" else stage="SUBMITTED"
                    }
                }else if(plan.s("decision")=="confirm"){
                    search.changeInput{input.set(input.get().copy(query=plan.arr("terms").joinToString(" "){it.jsonPrimitive.content},from=c.s("from"),through=c.s("through"),dateBasis=RecordDateBasis.valueOf(c.s("date_basis")),mode=OriginalSearchMode.KEYWORDS,ownership=Any()))}
                    search.inputCommitted();actions+="changeInput/inputCommitted";stage="SUBMITTED"
                }
                if(stage=="SUBMITTED"){
                    search.sync(helper.state.value==null);actions+="sync"
                    value=withTimeout(5000){search.state.first{it.stage !in listOf(OriginalSearchStage.WAITING,OriginalSearchStage.RUNNING)}}
                    stage=value.stage.name
                    if(value.proof!=null){publicationAck=withTimeout(5000){publication.await()};check(publicationAck);check(search.visible(source.get(),input.get())===value)}
                }
            }catch(_:TimeoutCancellationException){stage="TIMEOUT"}
             catch(e:Exception){stage="EXECUTOR_ERROR:${e.javaClass.simpleName}";executorError=e.stackTraceToString()}
            val resolved=buildJsonArray{
                if(stage=="READY")for(hit in value!!.hits.take(5)){
                    resolution=CompletableDeferred();val published=AtomicBoolean()
                    search.open(value,hit,publish={published.set(true)})
                    val ack=withTimeoutOrNull(5000){resolution.await()}
                    add(buildJsonObject{put("id",names.getValue(hit.entry.id));put("revision",hit.entry.currentRevision);put("commit_ack",ack==true);put("published",published.get())})
                }
            }
            return buildJsonObject{
                put("question_id",q.s("id"));put("route",route);put("stage",stage);put("query_count",queries.get());put("commits",commits.get());put("sql_statements",sql.get());put("sql_before_confirm",preConfirmSql)
                put("actions",JsonArray(actions.map(::JsonPrimitive)));put("plan",plan);put("context",c)
                put("submitted_query",input.get().query);put("publication_commit_ack",stage=="READY"&&publicationAck);put("executor_error",executorError?.let(::JsonPrimitive)?:JsonNull)
                put("ranked",buildJsonArray{if(stage=="READY")for(hit in value!!.hits){add(buildJsonObject{put("id",names.getValue(hit.entry.id));put("revision",hit.entry.currentRevision);put("original",hit.entry.originalText);put("original_match",hit.originalMatchesQuery);put("alias_matches",buildJsonArray{hit.aliasMatches.forEach{add(buildJsonObject{put("alias",it.alias);put("name",it.name)})}})})}})
                put("resolved",resolved);put("proposal_truncated",proposals?.truncated?:false)
            }
        }
        override fun close(){fixture.f.db.beforeSql=null;helper.dispose();search.close();fixture.close()}
    }
}
