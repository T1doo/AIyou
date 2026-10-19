package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual VM/records actor/controller/catalog/vault and SQL. No Android/device/live provider claim. */
class RecordConnectionReviewViewModelTest {
    @Test(timeout=20_000) fun directoryReviewCancelAndExplicitSinglePurposeGrantNeverStartSendingOrChangeDefault():Unit=runBlocking {
        Harness().use { h ->
            h.ready();val opened=h.open();val before=h.read();assertEquals(listOf(h.config),opened.connections)
            val old=h.review(TextPurpose.ORGANIZATION);assertEquals(before,old.expected);assertEquals(before,h.read());h.noSending()
            h.vm.localProcessing.cancelAuthorization(opened.session,old)
            val fresh=h.review(TextPurpose.CONVERSATION)
            h.vm.localProcessing.confirmAuthorization(opened.session,old);assertEquals(0,h.changes.get())
            h.vm.localProcessing.confirmAuthorization(opened.session,fresh)
            val done=h.ui { !it.busy&&it.notice!=null }
            assertEquals(fresh.grant,done.authorization!!.snapshot.consent);assertEquals(TextPurpose.CONVERSATION,h.read().consent!!.purpose)
            assertEquals(before.binding.generation+1,h.read().binding.generation);assertEquals(1,h.changes.get())
            assertEquals("合成原文",h.vm.state.value.entries.single { it.id==h.source.sourceId }.originalText)
            assertTrue(done.notice!!.contains("未发送记录"));h.noSending()
        }
    }
    @Test(timeout=25_000) fun committedLostAckAndActualRollbackRetryOnlyReadSameDecisionWithoutRenewal():Unit=runBlocking {
        for(committed in listOf(true,false)) Harness().use { h ->
            h.ready();val ui=h.open();val request=h.review();var written=false
            h.db.afterSql={sql ->if(sql.startsWith("INSERT INTO local_jobs"))written=true
                if(committed&&sql=="COMMIT"&&written){written=false;throw IOException("Synthetic grant ACK lost")}}
            if(!committed)h.db.beforeCommit={if(written){written=false;throw IOException("Synthetic grant rollback")}}
            h.vm.localProcessing.confirmAuthorization(ui.session,request)
            val uncertain=h.ui { !it.busy&&it.error!=null };assertSame(request,uncertain.pendingAuthorization)
            h.db.afterSql=null;h.db.beforeCommit=null
            h.db.beforeSql={sql ->if(listOf("INSERT","UPDATE","DELETE","REPLACE").any {sql.startsWith(it)})error("Consent retry must only read")}
            h.vm.localProcessing.retry(ui.session,uncertain.request)
            val final=h.ui { !it.busy&&(if(committed)it.notice!=null else it.error?.contains("未写入或续期")==true) }
            h.db.beforeSql=null
            assertEquals(1,h.changes.get());assertEquals(1,h.confirmations.get())
            if(committed){assertNull(final.pendingAuthorization);assertEquals(request.grant,h.read().consent)}
            else {assertSame(request,final.pendingAuthorization);assertNull(h.read().consent);assertEquals(ProcessingPolicy.LOCAL_ONLY,h.read().policy)}
            h.noSending()
        }
    }
    @Test(timeout=25_000) fun keyReplacementRevokeAndSourceGenerationAbaCannotCommitFrozenDialogDecision():Unit=runBlocking {
        for(kind in 0..2) Harness().use { h ->
            h.ready();val ui=h.open();val request=h.review();val before=h.read()
            when(kind) {
                0 -> h.catalog.apply(h.catalog.prepare(h.config,"replacement","SYNTHETIC_ONLY_NEW_KEY".toCharArray()))
                1 -> h.catalog.revoke(h.config,"revoke")
                else -> {val raw=RemoteTextTransactions(h.gateway);assertNull(raw.updateAuthorization(h.source,before.binding.generation,ProcessingPolicy.AI_NONE,null,null));assertNull(raw.updateAuthorization(h.source,before.binding.generation+1,ProcessingPolicy.LOCAL_ONLY,null,null))}
            }
            h.vm.localProcessing.confirmAuthorization(ui.session,request)
            h.ui { !it.busy&&it.error!=null }
            assertNull(h.read().consent);assertEquals(ProcessingPolicy.LOCAL_ONLY,h.read().policy);h.noSending()
        }
    }
    @Test(timeout=20_000) fun pageCloseAndAuthorizationOwnerReplacementRejectRetainedReviewActions():Unit=runBlocking {
        Harness().use { h ->
            h.ready();val first=h.open();val old=h.review()
            h.vm.localProcessing.close(first.session);val next=h.open();h.review()
            h.vm.localProcessing.confirmAuthorization(first.session,old);assertEquals(0,h.changes.get())
            val retained=h.value().authorizationConfirmation!!
            h.activeAuthorization=h.newAuthorization();h.vm.refreshLocalSearch()
            withTimeout(3_000){h.vm.state.first {it.recordAuthorizationOwner===h.activeAuthorization}}
            assertNull(h.vm.state.value.localProcessing)
            h.vm.localProcessing.confirmAuthorization(next.session,retained);assertEquals(0,h.changes.get());h.noSending()
        }
    }
    @Test(timeout=25_000) fun actualSilentMockProbeNeverBlocksRecordDraftAndSaveOnTheSharedActor():Unit=runBlocking {
        SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,heldTail=true,
            httpBounds=HttpBounds(connectMillis=500,totalMillis=10_000,idleMillis=10_000,writeMillis=1_000)).use { wire ->
            Harness(wire).use { h ->
                h.ready();val session=h.probeReview();h.vm.modelConnections.runProbe(session,h.vm.modelConnections.visible().probeEditor!!)
                assertNotNull(wire.serverA.takeRequest(3,TimeUnit.SECONDS));assertTrue(h.vm.modelConnections.visible().probeEditor!!.busy)
                h.vm.changeText("合成网络等待期间仍保存");h.vm.save()
                withTimeout(2_000){h.vm.state.first {it.saveFeedbackId==1L&&!it.savePending}}
                assertTrue(h.vm.modelConnections.visible().probeEditor!!.busy)
                assertTrue(h.vm.state.value.entries.any {it.originalText=="合成网络等待期间仍保存"&&it.policy==ProcessingPolicy.LOCAL_ONLY})
                h.vm.modelConnections.cancelProbe(session,h.vm.modelConnections.visible().probeEditor!!)
                withTimeout(3_000){while(h.vm.modelConnections.editingOrBusy)delay(5)}
                assertEquals(1,wire.serverA.requestCount);assertEquals(0,wire.serverB.requestCount)
                assertTrue(h.profiles.profiles().all {it.observation.state==CapabilityState.UNKNOWN});h.noSending()
                wire.releaseTails()
            }
        }
    }
    @Test(timeout=25_000) fun cancelledNonCooperativeProbeKeepsOneSlotAndStorageAliveUntilActualExit():Unit=runBlocking {
        Harness(controlled=true).use { h ->
            h.ready();val session=h.probeReview();val first=h.vm.modelConnections.visible().probeEditor!!
            h.vm.modelConnections.runProbe(session,first);assertTrue(h.controlledProbe!!.entered.await(3,TimeUnit.SECONDS))
            h.vm.modelConnections.cancelProbe(session,h.vm.modelConnections.visible().probeEditor!!)
            assertTrue(h.controlledProbe.cancelled.await(2,TimeUnit.SECONDS))
            withTimeout(3_000){while(h.vm.modelConnections.visible().busy)delay(5)}
            h.vm.modelConnections.beginProbe(session,h.config)
            assertNull(h.vm.modelConnections.visible().probeEditor)
            h.vm.modelConnections.runProbe(session,first) // Retained callbacks cannot bypass the occupied slot.
            assertEquals(1,h.controlledProbe.factories.get());assertEquals(1,h.controlledProbe.executions.get())
            assertTrue(h.vm.modelConnections.visible().notice!!.contains("上次检查仍在停止"))
            h.vm.modelConnections.leave()
            h.vm.changeText("合成等待退出仍保存");h.vm.save()
            withTimeout(2_000){h.vm.state.first {it.saveFeedbackId==1L&&!it.savePending}}
            withTimeout(2_000){h.vm.appAccessIdle.first {it}} // Exclude actor bookkeeping as a reason retirement is blocked.
            assertFalse(h.vm.canRetireForAppAccess())
            h.vm.clearForHostTest();h.cleared=true
            assertFalse(h.base.closingEntered.await(100,TimeUnit.MILLISECONDS))
            h.controlledProbe.release.countDown()
            assertTrue(h.base.closed.await(3,TimeUnit.SECONDS));h.base.closeFailure.get()?.let {throw it}
            h.fixture!!.reopen();assertEquals("ADMITTED",h.fixture.db.read("SELECT phase FROM model_capability_profiles").single().single())
            assertEquals(CapabilityState.UNKNOWN,h.fixture.profiles.profiles().single().observation.state)
        }
    }
    private class ControlledProbe(private val profiles:ModelCapabilityRepository):ModelCapabilityProbe {
        val entered=CountDownLatch(1);val cancelled=CountDownLatch(1);val release=CountDownLatch(1)
        val factories=AtomicInteger();val executions=AtomicInteger()
        override fun newExchange(ticket:ModelCapabilityProbeTicket):ModelCapabilityProbeExchange {
            factories.incrementAndGet()
            return object:ModelCapabilityProbeExchange {
                override fun execute():TextCapabilityObservation {
                    check(profiles.admit(ticket));executions.incrementAndGet();entered.countDown()
                    check(release.await(8,TimeUnit.SECONDS))
                    return TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,NOW.toEpochMilli())
                }
                override fun cancel(){cancelled.countDown()} // Deliberately does not release the non-cooperative worker.
            }
        }
    }
    private class Harness(val wire:SourceBoundLoopbackFixture?=null,controlled:Boolean=false):AutoCloseable {
        val fixture=if(wire==null)CapabilitySqlFixture().also {it.migrate()}else null
        val db=fixture?.db?:checkNotNull(wire).records.db
        val gateway=DrainingRecordsSqlDatabase(db)
        val source=wire?.records?.source?:SourceVersion("original",1)
        val catalog:SqlModelConnectionCatalog
        val config:ModelConnectionConfiguration
        val profiles:SqlModelCapabilityRepository
        val changes=AtomicInteger();val confirmations=AtomicInteger();val sending=AtomicInteger()
        val controlledProbe:ControlledProbe?
        @Volatile var activeAuthorization:RecordAuthorizationRepository
        val base:SqliteVmEntryRepository
        val vm:LocalRecordsViewModel
        var cleared=false
        init {
            if(wire!=null)db.transaction {sql ->(ModelConnectionSqlSchema.migration12To13+ModelCapabilitySqlSchema.migration13To14).forEach {sql.execute(it)}}
            catalog=SqlModelConnectionCatalog(gateway,ConnectionSecretVaultTest.key())
            val d=wire?.records?.connectionA?.let {ModelConnectionDetails("合成目录服务",it.apiRoot,it.protocol,it.modelId,setOf(ModelConfigurationPurpose.CONVERSATION,ModelConfigurationPurpose.ORGANIZATION))}
                ?:ModelConnectionCatalogTest.details()
            val c=catalog.change(ModelConnectionChange("config",0,"create",d))!!
            config=catalog.apply(catalog.prepare(c,"key",ConnectionSecretVaultTest.material()))
            profiles=SqlModelCapabilityRepository(gateway,catalog)
            activeAuthorization=newAuthorization()
            val strict=RemoteTextTransactions(gateway,connectionCurrent=catalog::connectionCurrent)
            base=SqliteVmEntryRepository(db,gateway,strict){gateway.stopAndAwaitIdle();catalog.close()}
            val store=object:EntryRepository by base,AutoCloseable {
                override val modelConnections get()=catalog
                override val modelCapabilities get()=profiles
                override val localModelConnections get()=LocalModelConnectionServices(catalog,strict)
                override val localProcessing=if(wire==null)SqlLocalProcessingRepository(gateway)else null
                override val recordAuthorizations get()=activeAuthorization
                override suspend fun prepare(){check(db.read("PRAGMA user_version").single().single()==if(wire==null)14L else 4L)}
                override fun close()=base.close()
            }
            controlledProbe=if(controlled)ControlledProbe(profiles)else null
            val probe=controlledProbe?:wire?.let {FixedTextCapabilityProbe(it.transport,profiles,LocalConnectionSecretResolver(catalog),Clock.fixed(NOW,ZoneOffset.UTC))}
            vm=LocalRecordsViewModel(Application(),{store},Clock.fixed(NOW,ZoneOffset.UTC),
                bindAi={sending.incrementAndGet();error("Production record sending remains closed")},capabilityProbe=probe)
        }
        fun newAuthorization():RecordAuthorizationRepository {
            val actual=SqlRecordAuthorizationRepository(gateway,catalog,profiles)
            return object:RecordAuthorizationRepository by actual {
                override fun changeAuthorization(request:RecordAuthorizationChange,clock:()->Instant):RecordAuthorizationReceipt {
                    changes.incrementAndGet();return actual.changeAuthorization(request,clock)
                }
                override fun confirmAuthorization(request:RecordAuthorizationChange,clock:()->Instant):RecordAuthorizationReceipt? {
                    confirmations.incrementAndGet();return actual.confirmAuthorization(request,clock)
                }
            }
        }
        suspend fun ready(){withTimeout(3_000){vm.state.first {it.ready&&!it.opening}}}
        suspend fun open():LocalProcessingUiState {vm.localProcessing.open(checkNotNull(vm.state.value.authorizationReadingSource(source.sourceId,source.revision.toInt())));return ui {!it.busy&&it.authorization!=null}}
        suspend fun review(purpose:TextPurpose=TextPurpose.ORGANIZATION):RecordAuthorizationChange {
            vm.localProcessing.prepareAuthorization(value().session,config,purpose)
            return checkNotNull(ui {!it.busy&&it.authorizationConfirmation!=null}.authorizationConfirmation)
        }
        fun read()=activeAuthorization.readAuthorization(source)
        fun value()=checkNotNull(vm.state.value.localProcessing)
        suspend fun ui(test:(LocalProcessingUiState)->Boolean)=withTimeout(3_000){vm.state.first {it.localProcessing?.let(test)==true}.localProcessing!!}
        suspend fun probeReview():Long {
            val session=vm.modelConnections.open(catalog)
            withTimeout(3_000){vm.modelConnections.state.first {!it.busy&&it.configurations.isNotEmpty()}}
            vm.modelConnections.beginProbe(session,config);checkNotNull(vm.modelConnections.visible().probeEditor);return session
        }
        fun noSending(){assertEquals(0,sending.get());for(t in listOf("remote_text_jobs","text_annotations"))assertTrue(db.read("SELECT * FROM $t").isEmpty());assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy)}
        override fun close(){controlledProbe?.release?.countDown();if(!cleared){cleared=true;vm.clearForHostTest()};check(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it};fixture?.close()}
    }
    companion object {private val NOW=Instant.parse("2026-10-19T13:00:00Z")}
}
