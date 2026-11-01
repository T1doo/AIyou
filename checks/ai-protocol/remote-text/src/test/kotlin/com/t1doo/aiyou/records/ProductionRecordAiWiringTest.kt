package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** The production installation and VM/controller/SQL/vault/transport, with synthetic loopback only. */
class ProductionRecordAiWiringTest {
    @Test(timeout=30_000) fun bothProtocolsExplicitProbeGrantAndStartCommitOnlyASourceBoundCandidate():Unit=runBlocking {
        for (protocol in ConnectionProtocol.entries) SourceBoundLoopbackFixture(protocol,initiallyApproved=false,
            responseBytes=FixedTextCapabilityProbeTest.reply(protocol)).use { wire -> Harness(wire).use { h ->
            h.ready(); assertEquals(0,h.clients.get()); assertEquals(0,h.secretReads.get())
            h.probe()
            val sample=checkNotNull(wire.serverA.takeRequest(2,TimeUnit.SECONDS)!!.body).utf8()
            assertTrue(sample.contains("AIYOU_CAPABILITY_OK"));assertFalse(sample.contains("合成原文"))
            assertEquals(1,h.clients.get());assertEquals(1,h.secretReads.get())
            assertEquals(CapabilityState.SUPPORTED,h.profiles.profiles().single().observation.state)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,h.policy());assertTrue(h.rows("remote_text_jobs").isEmpty())
            h.grant(TextPurpose.ORGANIZATION)
            assertEquals(1,wire.serverA.requestCount) // Merely granting never sends a record.
            wire.serverA.enqueue(MockResponse.Builder().addHeader("Content-Type","text/event-stream")
                .body(Buffer().write(SourceBoundLoopbackFixture.complete(protocol))).build())
            val session=h.vm.openAiSession("original",1)
            assertEquals(RecordAiPhase.READY,h.vm.state.value.ai!!.phase)
            h.vm.startAiSession(session)
            val done=h.phase(RecordAiPhase.SUCCEEDED).ai!!
            val request=wire.serverA.takeRequest(2,TimeUnit.SECONDS)!!
            val body=checkNotNull(request.body).utf8();assertTrue(body.contains("合成原文"));assertFalse(body.contains("合成独立草稿"))
            assertEquals("/custom/v1/"+if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"chat/completions" else "messages",request.url.encodedPath)
            assertEquals(SourceVersion("original",1),done.annotation!!.source)
            assertEquals("合成原文",done.annotation!!.candidate.references.single().quote)
            assertEquals("合成原文",h.original());assertEquals(1,h.rows("text_annotations").size)
            assertTrue(done.notice.contains("尚未确认为事实或待办"));assertTrue(h.rows("manual_tasks").isEmpty())
            assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy)
            assertEquals(2,h.clients.get());assertEquals(2,h.secretReads.get());assertEquals(0,wire.serverB.requestCount)
            assertEquals(2,h.transports.distinct().size)
        } }
    }

    @Test(timeout=20_000) fun absentNetworkPermissionBlocksProbeBeforeBeginAndRecordBeforeClientOrSecret():Unit=runBlocking {
        SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false).use { wire ->
            Harness(wire,network=false).use { h ->
                h.ready();val session=h.vm.modelConnections.open(h.catalog)
                withTimeout(3_000){h.vm.modelConnections.state.first {!it.busy}}
                assertFalse(h.vm.modelConnections.visible().probeAvailable)
                h.vm.modelConnections.beginProbe(session,h.config)
                assertNull(h.vm.modelConnections.visible().probeEditor);assertTrue(h.profiles.profiles().isEmpty())
                // An already verified, explicitly granted synthetic record cannot bypass package admission.
                h.vm.modelConnections.leave();h.seedVerified();h.grant(TextPurpose.ORGANIZATION)
                val ai=h.vm.openAiSession("original",1)
                assertEquals(RecordAiPhase.BLOCKED,h.vm.state.value.ai!!.phase)
                assertTrue(h.vm.state.value.ai!!.notice.contains("未开放网络发送"))
                h.vm.startAiSession(ai)
                assertEquals(0,h.clients.get());assertEquals(0,h.secretReads.get());wire.noRequests()
                assertTrue(h.rows("remote_text_jobs").isEmpty());assertEquals("合成原文",h.original())
            }
        }
    }

    @Test(timeout=25_000) fun heldProductionExchangeDoesNotBlockLocalSaveAndStopCannotCommitLateCandidate():Unit=runBlocking {
        val protocol=ConnectionProtocol.CHAT_COMPLETIONS
        SourceBoundLoopbackFixture(protocol,initiallyApproved=false,
            responseBytes=FixedTextCapabilityProbeTest.reply(protocol)).use { wire -> Harness(wire).use { h ->
            h.ready();h.probe();wire.serverA.takeRequest(2,TimeUnit.SECONDS)
            h.grant(TextPurpose.ORGANIZATION);wire.enqueueHeld(protocol)
            val session=h.vm.openAiSession("original",1);h.vm.startAiSession(session)
            withTimeout(3_000){h.vm.state.first {it.ai?.preview?.isNotEmpty()==true}}
            h.vm.changeText("合成网络等待期间仍能本地保存");h.vm.save()
            withTimeout(3_000){h.vm.state.first {it.saveFeedbackId==1L&&!it.savePending}}
            assertTrue(h.vm.state.value.entries.any {it.originalText=="合成网络等待期间仍能本地保存"&&it.policy==ProcessingPolicy.LOCAL_ONLY})
            h.vm.stopAiSession(session);h.phase(RecordAiPhase.CANCELLED)
            wire.releaseTails();h.finish();h.fixture.reopen()
            assertTrue(h.rows("text_annotations").isEmpty());assertEquals("合成原文",h.original())
            assertEquals(0,wire.serverB.requestCount)
        } }
    }

    @Test(timeout=20_000) fun conversationGrantCannotBeReusedByOrganizationAndClosedOwnerCannotRebind():Unit=runBlocking {
        SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false).use { wire ->
            Harness(wire).use { h ->
                h.ready();h.seedVerified();h.grant(TextPurpose.CONVERSATION)
                h.runtime.bindOwner(h.store) // Same-owner retry is idempotent; replacement is rejected.
                assertThrows(IllegalStateException::class.java){h.runtime.bindOwner(object:EntryRepository by h.store {})}
                val session=h.vm.openAiSession("original",1);h.vm.startAiSession(session)
                h.phase(RecordAiPhase.FAILED);wire.noRequests();assertEquals(0,h.secretReads.get())
                assertTrue(h.rows("text_annotations").isEmpty())
                h.finish()
                assertThrows(IllegalStateException::class.java){h.runtime.bindOwner(h.store)}
            }
        }
    }

    private class Harness(val wire:SourceBoundLoopbackFixture,network:Boolean=true):AutoCloseable {
        val fixture=CapabilitySqlFixture().also {it.migrate()}
        val gateway=DrainingRecordsSqlDatabase(fixture.db)
        val catalog=SqlModelConnectionCatalog(gateway,ConnectionSecretVaultTest.key())
        val profiles=SqlModelCapabilityRepository(gateway,catalog)
        val clients=AtomicInteger();val secretReads=AtomicInteger()
        val transports=mutableListOf<OkHttpStreamingTransport>()
        val clock=Clock.fixed(Instant.parse("2026-10-19T13:00:00Z"),ZoneOffset.UTC)
        val config:ModelConnectionConfiguration
        val base:SqliteVmEntryRepository
        val store:EntryRepository
        val runtime=ProductionRecordAi({network},clock,{
            clients.incrementAndGet();wire.additionalTransport().also { synchronized(transports){transports.add(it)} }
        })
        val vm:LocalRecordsViewModel
        private var finished=false
        init {
            val c=catalog.change(ModelConnectionChange("connection",0,"create",ModelConnectionDetails("合成服务",wire.records.connectionA.apiRoot,
                wire.records.connectionA.protocol,"synthetic-model",setOf(ModelConfigurationPurpose.ORGANIZATION,ModelConfigurationPurpose.CONVERSATION))))!!
            config=catalog.apply(catalog.prepare(c,"synthetic-key",ConnectionSecretVaultTest.material()))
            val secrets=object:ConnectionSecretVault by catalog {
                override fun resolve(reference:SecretReference):OwnedConnectionSecret? {secretReads.incrementAndGet();return catalog.resolve(reference)}
            }
            val strict=RemoteTextTransactions(gateway,connectionCurrent=catalog::connectionCurrent)
            base=SqliteVmEntryRepository(fixture.db,gateway,strict){gateway.stopAndAwaitIdle()}
            store=object:EntryRepository by base,AutoCloseable {
                override val sourceBoundText:SourceBoundTextRepository get()=error("Production must not use the legacy text port")
                override val localModelConnections=LocalModelConnectionServices(secrets,strict)
                override val modelConnections=catalog
                override val modelCapabilities=profiles
                override val recordAuthorizations=SqlRecordAuthorizationRepository(gateway,catalog,profiles)
                override val localProcessing=SqlLocalProcessingRepository(gateway)
                override suspend fun prepare(){check(fixture.db.read("PRAGMA user_version").single().single()==14L)}
                override fun close()=base.close()
            }
            vm=LocalRecordsViewModel(Application(),{store},clock,productionAi=runtime)
        }
        suspend fun ready(){withTimeout(3_000){vm.state.first {it.ready&&!it.opening}}}
        suspend fun probe() {
            val s=vm.modelConnections.open(catalog)
            withTimeout(3_000){vm.modelConnections.state.first {!it.busy}}
            assertTrue(vm.modelConnections.visible().probeAvailable)
            vm.modelConnections.beginProbe(s,config)
            vm.modelConnections.runProbe(s,checkNotNull(vm.modelConnections.visible().probeEditor))
            withTimeout(4_000){vm.modelConnections.state.first {it.probeEditor==null&&it.notice!=null}}
            vm.modelConnections.leave()
        }
        fun seedVerified(){val ticket=profiles.begin(config,"synthetic-previous-probe",clock.millis());check(profiles.admit(ticket))
            profiles.finish(ticket,TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,clock.millis()))}
        suspend fun grant(purpose:TextPurpose) {
            vm.localProcessing.open(checkNotNull(vm.state.value.authorizationReadingSource("original",1)))
            var ui=processing { !it.busy&&it.authorization!=null }
            vm.localProcessing.prepareAuthorization(ui.session,config,purpose)
            ui=processing {!it.busy&&it.authorizationConfirmation!=null}
            vm.localProcessing.confirmAuthorization(ui.session,checkNotNull(ui.authorizationConfirmation))
            ui=processing {!it.busy&&it.notice!=null}
            assertNotNull(ui.authorization!!.snapshot.consent)
            vm.localProcessing.close(ui.session)
        }
        private suspend fun processing(predicate:(LocalProcessingUiState)->Boolean)=withTimeout(3_000){vm.state.first {it.localProcessing?.let(predicate)==true}.localProcessing!!}
        suspend fun phase(phase:RecordAiPhase)=withTimeout(4_000){vm.state.first {it.ai?.phase==phase}}
        fun rows(table:String)=fixture.db.read("SELECT * FROM $table")
        fun original()=fixture.db.read("SELECT originalText FROM entry_revisions WHERE entryId='original' AND revision=1").single().single()
        fun policy()=ProcessingPolicy.valueOf(fixture.db.read("SELECT privacyPolicy FROM entries WHERE id='original'").single().single() as String)
        fun finish(){if(!finished){finished=true;vm.clearForHostTest();check(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it}}}
        override fun close(){try {finish()}finally {catalog.close();fixture.close()}}
    }
}
