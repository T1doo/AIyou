package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Actual admitted VM/directory/controller + authenticated SQL/vault + HTTP SSE, routed only to loopback. */
class ProductionQuestionWiringTest {
    @Test(timeout=20_000) fun navigationRetirementBeforeActualWireBlocksWithoutWaitingForRecompositionOrClose():Unit=runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { single -> Harness().use {h->
            h.ready();h.probe();h.directory();val controller=h.controller(single)
            controller.review(h.selection("合成退休前问题"));val review=h.await(controller,QuestionConsentStage.REVIEW).review!!
            h.answer(review.request)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.beforeResolve.set{entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
            controller.confirm(review);check(entered.await(3,TimeUnit.SECONDS))
            // No sync, close, recomposition or observer turn can rescue the gate: this same
            // single worker is still inside the real exchange's credential resolution.
            controller.retireUiScope();release.countDown()
            val drained=CountDownLatch(1);single.executor.execute{drained.countDown()}
            check(drained.await(4,TimeUnit.SECONDS))
            assertEquals("Retired request must not cross actual HTTP admission",1,h.wire.serverA.requestCount)
            assertEquals(listOf("CANCELLED","CANCELLED"),h.jobs());assertNull(controller.visible().receipt)
        }}
    }
    @Test(timeout=30_000) fun explicitFreeQuestionAndTwoSourceReviewUseProductionExchangeWithAtomicAnswerHistoryWithoutEntryWrites():Unit=runBlocking {
        Harness().use { h ->
            h.ready();h.probe();h.directory()
            assertEquals(1,h.wire.serverA.requestCount);assertEquals(1,h.clients.get())
            val view=h.selection("这两次合成记录中提到了什么？🙂")
            assertEquals("needle",h.source.get().input.query);assertEquals(2,view.selection.cards.size)
            val controller=h.controller();controller.review(view)
            val review=h.await(controller,QuestionConsentStage.REVIEW).review!!
            assertEquals(view.question,review.request.question);assertEquals(2,review.request.sources.size)
            assertTrue(h.jobs().isEmpty());assertEquals(1,h.wire.serverA.requestCount)
            h.answer(review.request);controller.confirm(review);controller.confirm(review)
            val ready=h.await(controller,QuestionConsentStage.READY)
            val body=checkNotNull(h.wire.serverA.takeRequest(3,TimeUnit.SECONDS)!!.body).utf8()
            assertTrue(body.contains(view.question));assertFalse(body.contains("不能外发的独立草稿"))
            assertEquals(listOf("COMMITTED","COMMITTED"),h.jobs())
            assertEquals(2,h.clients.get());assertEquals(2,h.secretReads.get());assertEquals(2,h.wire.serverA.requestCount)
            assertEquals(h.originals,h.rows("entry_revisions"));assertEquals(h.draft,h.rows("capture_draft"))
            assertTrue(h.rows("text_annotations").isEmpty());assertTrue(h.rows("manual_tasks").isEmpty())
            assertNull(h.authority.checkReceipt(ready.receipt!!){h.clock.instant()})
            assertSame(h.history,h.vm.state.value.chatHistoryOwner)
            h.vm.chatHistory.open()
            val history=withTimeout(4_000){h.vm.chatHistory.state.first{it?.available==true&&!it.busy}}!!
            assertEquals(1,history.items.size);assertEquals(view.question,history.items.single().question)
            h.vm.chatHistory.select(history,history.items.single())
            val opened=withTimeout(4_000){h.vm.chatHistory.state.first{it?.view!=null&&!it.busy}}!!
            assertEquals(ready.receipt.reply.paragraphs.single().text,opened.view!!.reply!!.paragraphs.single().text)
            assertEquals(review.request.sources.map{it.reference},opened.view.citations.map{it.reference})
            h.vm.chatHistory.close(opened)
            assertEquals(2,h.wire.serverA.requestCount) // History read cannot replay the send.

            val opens=AtomicInteger();controller.openSource(ready,ready.receipt.reply.paragraphs.single().references.first()){opens.incrementAndGet()}
            withTimeout(3_000){while(opens.get()==0)delay(5)};assertEquals(1,opens.get())
            // A new question immediately retires the old review/answer, without another search or send.
            controller.retireUiScope();h.evidence.changeQuestion(h.current(),"新的合成问题")
            controller.sync();assertEquals(QuestionConsentStage.STALE,controller.visible().stage)
            controller.confirm(review);assertEquals(2,h.wire.serverA.requestCount)
            assertEquals(LocalEvidenceSelectionStage.CHOOSING,h.current().selection.stage)
            h.evidence.changeQuestion(h.current(),"🙂".repeat(513));assertNotNull(h.current().questionError)
            h.evidence.confirm(h.current());assertEquals(LocalEvidenceSelectionStage.CHOOSING,h.current().selection.stage)
            assertEquals("新的合成问题",h.current().question)
        }
    }

    @Test(timeout=20_000) fun packageWithoutNetworkCanSelectMetadataButCannotReviewAuthorizeResolveSecretOrOpenClient():Unit=runBlocking {
        Harness(network=false).use { h ->
            h.ready();h.seed();h.directory();val controller=h.controller()
            val view=h.selection("合成问题，不发送")
            assertFalse(controller.available);controller.review(view);controller.confirm(null)
            assertEquals(QuestionConsentStage.UNAVAILABLE,controller.visible().stage)
            assertTrue(h.jobs().isEmpty());assertEquals(0,h.clients.get());assertEquals(0,h.secretReads.get())
            h.wire.noRequests();assertTrue(h.runtime.unavailableNotice()!!.contains("未开放网络发送"))
        }
    }

    @Test(timeout=25_000) fun stoppedHeldQuestionCannotPublishAndVmWaitsForItsRealSqlBorrowerBeforeClosingStore():Unit=runBlocking {
        Harness().use { h ->
            h.ready();h.probe();h.directory();val controller=h.controller();val view=h.selection("合成取消问题")
            controller.review(view);val review=h.await(controller,QuestionConsentStage.REVIEW).review!!
            h.wire.enqueueHeld(ConnectionProtocol.CHAT_COMPLETIONS)
            controller.confirm(review)
            withTimeout(4_000){while(h.wire.serverA.requestCount<2)delay(5)}
            controller.stop(controller.state.value);h.await(controller,QuestionConsentStage.CANCELLED)
            h.wire.releaseTails();controller.confirm(review)
            assertNull(controller.visible().receipt)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.afterReview.set{entered.countDown();check(release.await(4,TimeUnit.SECONDS))}
            controller.review(view);check(entered.await(3,TimeUnit.SECONDS))
            h.vm.clearForHostTest();h.finished=true
            assertFalse("SQL store must outlive an admitted non-cooperative question read",h.base.closed.await(100,TimeUnit.MILLISECONDS))
            release.countDown();check(h.base.closed.await(4,TimeUnit.SECONDS));assertNull(h.base.closeFailure.get())
            assertNull(controller.visible().receipt);assertEquals(2,h.wire.serverA.requestCount)
        }
    }

    private class Harness(network:Boolean=true):AutoCloseable {
        val f=RecordEventDateFixture()
        val wire=SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,
            responseBytes=FixedTextCapabilityProbeTest.reply(ConnectionProtocol.CHAT_COMPLETIONS))
        val clock=Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"),ZoneOffset.UTC)
        val catalog:SqlModelConnectionCatalog;val profiles:SqlModelCapabilityRepository
        val config:ModelConnectionConfiguration;val authority:SqlSelectedQuestionAuthorizationRepository
        val history:SqlChatHistoryRepository
        val clients=AtomicInteger();val secretReads=AtomicInteger()
        val afterReview=AtomicReference<()->Unit>({})
        val beforeResolve=AtomicReference<()->Unit>({})
        val source=AtomicReference<OriginalSearchState>()
        val evidence=LocalEvidenceController(source::get)
        val runtime=ProductionRecordAi({network},clock,{clients.incrementAndGet();wire.additionalTransport()})
        val base:SqliteVmEntryRepository;val store:EntryRepository;val vm:LocalRecordsViewModel
        val ids=(1..2).map{"65000000-0000-4000-8000-${it.toString().padStart(12,'0')}"}
        val originals:List<List<Any?>>;val draft:List<List<Any?>>
        var finished=false
        init {
            f.db.transaction {sql->
                ManualPeopleSqlSchema.migration11To12.forEach{sql.execute(it)}
                ModelConnectionSqlSchema.migration12To13.forEach{sql.execute(it)}
                ModelCapabilitySqlSchema.migration13To14.forEach{sql.execute(it)};sql.execute("PRAGMA user_version=14")
                sql.execute("INSERT INTO capture_draft VALUES(1,'draft',7,'不能外发的独立草稿')")
            }
            ids.forEach{f.entry(it,"🙂".repeat(90)+"needle 合成记录\n"+"中".repeat(300))}
            catalog=SqlModelConnectionCatalog(f.gateway,ConnectionSecretVaultTest.key());profiles=SqlModelCapabilityRepository(f.gateway,catalog)
            val changed=catalog.change(ModelConnectionChange("connection",0,"create",ModelConnectionDetails("合成问答连接",
                wire.records.connectionA.apiRoot,ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION))))!!
            config=catalog.apply(catalog.prepare(changed,"synthetic-key",ConnectionSecretVaultTest.material()))
            history=chatHistoryFixture(f.gateway)
            authority=SqlSelectedQuestionAuthorizationRepository(f.gateway,history,connectionCurrent={sql,c->profiles.connectionCurrent(sql,c,TextPurpose.CONVERSATION)})
            val observed=object:SelectedQuestionAuthorizationRepository by authority {
                override fun review(request:SelectedQuestionRequest,connection:ProviderConnection,promptVersion:String,clock:()->Instant)=
                    authority.review(request,connection,promptVersion,clock).also{afterReview.get().invoke()}
            }
            val secrets=object:ConnectionSecretVault by catalog {
                override fun resolve(reference:SecretReference):OwnedConnectionSecret? {beforeResolve.get().invoke();secretReads.incrementAndGet();return catalog.resolve(reference)}
            }
            val raw=RemoteTextTransactions(f.gateway,connectionCurrent=catalog::connectionCurrent)
            base=SqliteVmEntryRepository(f.db,f.gateway,raw){f.gateway.stopAndAwaitIdle()}
            store=object:EntryRepository by base,AutoCloseable {
                override val localModelConnections=LocalModelConnectionServices(secrets,raw)
                override val modelConnections=catalog
                override val modelCapabilities=profiles
                override val selectedQuestionAuthorizations=observed
                override val chatHistory=history
                override val recordAuthorizations=SqlRecordAuthorizationRepository(f.gateway,catalog,profiles)
                override val localProcessing=SqlLocalProcessingRepository(f.gateway)
                override val localSearch=f.search
                override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==14L)}
                override fun close()=base.close()
            }
            originals=rows("entry_revisions");draft=rows("capture_draft")
            vm=LocalRecordsViewModel(Application(),{store},clock,productionAi=runtime)
        }
        suspend fun ready(){withTimeout(4_000){vm.state.first{it.ready&&!it.opening}};withTimeout(4_000){vm.appAccessIdle.first{it}}}
        fun seed(){val t=profiles.begin(config,"synthetic-probe",clock.millis());check(profiles.admit(t))
            profiles.finish(t,TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,clock.millis()))}
        suspend fun probe(){val s=vm.modelConnections.open(catalog);withTimeout(3_000){vm.modelConnections.state.first{!it.busy}}
            vm.modelConnections.beginProbe(s,config);vm.modelConnections.runProbe(s,vm.modelConnections.visible().probeEditor!!)
            withTimeout(4_000){vm.modelConnections.state.first{it.probeEditor==null&&it.notice!=null}}
            assertEquals(CapabilityState.SUPPORTED,profiles.profiles().single().observation.state)
            wire.serverA.takeRequest(2,TimeUnit.SECONDS);vm.modelConnections.leave()}
        suspend fun directory(){
            ids.forEach {id->val records=checkNotNull(store.recordAuthorizations);val version=SourceVersion(id,1)
                val preview=records.previewConnectionAuthorization(records.readAuthorization(version).binding,config,TextPurpose.CONVERSATION){clock.instant()}
                records.changeAuthorization(RecordAuthorizationChange.confirm(preview,clock.instant().minusSeconds(1),clock.instant().plusSeconds(600),UUID.randomUUID().toString())){clock.instant()}}
            val runtime=vm.productionQuestions!!;runtime.open();withTimeout(4_000){runtime.state.first{!it.busy}}
            assertNull(runtime.state.value.error);runtime.select(runtime.state.value.session,runtime.state.value.configurations.single())
            assertNotNull(runtime.state.value.selected)
        }
        suspend fun selection(question:String):LocalEvidenceState {
            f.drain();val input=OriginalSearchInput("needle");val entries=f.search.candidates(LocalSearchRequest("")).entries
            val result=LocalOriginalSearchMatcher.indexed(entries,input,f.search)
            source.set(OriginalSearchState(input,result.stage,result.hits,entries=entries,proof=result.proof,owner=f.search,indexEpoch=f.search.epoch()))
            evidence.sync(true,source.get());withTimeout(4_000){evidence.state.first{it.stage==LocalEvidenceStage.READY}}
            evidence.state.value.cards.forEach{evidence.toggle(evidence.state.value,it)}
            evidence.changeQuestion(evidence.state.value,question);evidence.confirm(evidence.state.value)
            return withTimeout(4_000){evidence.state.first{it.selection.stage==LocalEvidenceSelectionStage.CONFIRMED}}
        }
        fun current()=evidence.visible(source.get())
        fun controller(dispatcher:CoroutineDispatcher=Dispatchers.IO):SelectedQuestionConsentController {
            val runtime=vm.productionQuestions!!;val binding=runtime.state.value.selected!!
            return SelectedQuestionConsentController(evidence,::current,{runtime.current(binding)},binding.owner,{store.selectedQuestionAuthorizations},
                null,{null},dispatcher=dispatcher,uiDispatcher=Dispatchers.Unconfined,remote=binding,currentRemote={runtime.state.value.selected}).also{runtime.own(it)}
        }
        suspend fun await(c:SelectedQuestionConsentController,s:QuestionConsentStage)=withTimeout(5_000){c.state.first{it.stage==s}}
        fun answer(request:SelectedQuestionRequest){val reply=SelectedQuestionReply(request.id,request.selectionId,false,listOf(SelectedQuestionParagraph("合成模型回答，待核对",request.sources.map{it.reference})))
            val text=SelectedQuestionHttpTest.response(ConnectionProtocol.CHAT_COMPLETIONS,reply)
            wire.serverA.enqueue(MockResponse.Builder().addHeader("Content-Type","text/event-stream").body(Buffer().write(text)).build())}
        fun rows(table:String)=f.db.read("SELECT * FROM $table")
        fun jobs()=f.db.read("SELECT state FROM remote_text_jobs ORDER BY entryId").map{it.single()}
        override fun close(){try{evidence.close();if(!finished){finished=true;vm.clearForHostTest();check(base.closed.await(5,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it}}}
            finally{authority.close();catalog.close();f.close();wire.close()}}
    }
}
