package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual controller → encrypted vault/SQL → fixed protocol/Mock → durable profile; no record send port. */
class PersistentCapabilityFlowTest {
    @Test(timeout=30_000) fun bothProtocolSettingsCommandsPersistProfilesAndLostCommitAckNeverRequestsAgain():Unit=runBlocking {
        for(protocol in ConnectionProtocol.entries) SourceBoundLoopbackFixture(protocol,initiallyApproved=false,
            responseBytes=FixedTextCapabilityProbeTest.reply(protocol)).use { f ->
            val h=Harness(f);val c=h.config;val cipher=h.configBytes();val s=h.controller.open(h.catalog);h.drain()
            h.controller.beginProbe(s,c);assertEquals(0,f.serverA.requestCount)
            var written=false
            f.records.db.afterSql={sql ->if(sql.startsWith("UPDATE model_capability_profiles SET phase='FINISHED'"))written=true
                if(sql=="COMMIT"&&written){written=false;throw IOException("Synthetic result ACK loss")} }
            h.controller.runProbe(s,h.controller.visible().probeEditor!!);h.drain()
            val pending=h.controller.visible().probeEditor!!;assertNotNull(pending.pending);assertEquals(1,f.serverA.requestCount)
            f.records.db.afterSql=null
            f.records.db.beforeSql={sql->if(sql.startsWith("UPDATE model_capability_profiles"))throw IOException("Result retry must be read-only")}
            h.controller.retryProbePersistence(s,pending);h.drain();f.records.db.beforeSql=null
            assertNull(h.controller.visible().probeEditor);val profile=h.controller.visible().capabilityProfiles.single()
            assertEquals(c,profile.connection);assertEquals(CapabilityState.SUPPORTED,profile.observation.state)
            assertArrayEquals(cipher,h.configBytes());assertEquals(listOf(c),h.catalog.configurations())
            assertEquals(1,f.serverA.requestCount);assertEquals(0,f.serverB.requestCount)
            val request=f.serverA.takeRequest(1,TimeUnit.SECONDS)!!;val body=checkNotNull(request.body).utf8()
            assertTrue(body.contains("AIYOU_CAPABILITY_OK"));assertFalse(body.contains(f.records.original));assertFalse(body.contains("synthetic-private-capture-draft"))
            for(table in listOf("remote_text_jobs","text_annotations"))assertTrue(f.records.db.read("SELECT * FROM $table").isEmpty())
            assertTrue(f.records.db.read("SELECT privacyPolicy FROM entries").all {it.single()=="LOCAL_ONLY"})
            assertEquals("synthetic-private-capture-draft",f.records.db.read("SELECT text FROM capture_draft").single().single())
            h.close()
        }
    }
    @Test(timeout=20_000) fun duplicateTicketAndColdOwnerRecoveryCannotPerformASecondAuthenticatedRequest() {
        SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,
            responseBytes=FixedTextCapabilityProbeTest.reply(ConnectionProtocol.CHAT_COMPLETIONS)).use { f ->
            val h=Harness(f);val start=f.records.now.toEpochMilli();val t=h.profiles.begin(h.config,"same-probe",start)
            val first=h.probe.newExchange(t).execute();assertEquals(CapabilityState.SUPPORTED,first.state)
            assertEquals(CapabilityState.UNKNOWN,h.probe.newExchange(t).execute().state)
            f.records.second().use { db ->
                val catalog=SqlModelConnectionCatalog(db,ConnectionSecretVaultTest.key())
                val profiles=SqlModelCapabilityRepository(db,catalog)
                try {
                    assertFalse(profiles.current(t));val recovered=profiles.begin(h.config,"same-probe",start)
                    val probe=FixedTextCapabilityProbe(f.transport,profiles,LocalConnectionSecretResolver(catalog),Clock.fixed(f.records.now,ZoneOffset.UTC))
                    assertEquals(CapabilityState.UNKNOWN,probe.newExchange(recovered).execute().state)
                    assertEquals(1,f.serverA.requestCount);assertEquals(CapabilityState.UNKNOWN,profiles.profiles().single().observation.state)
                    val saved=h.profiles.finish(t,first);assertEquals(listOf(saved),profiles.profiles())
                } finally {catalog.close()}
            }
            assertEquals(0,f.serverB.requestCount);h.close()
        }
    }
    @Test(timeout=25_000) fun newerGenerationEditAndRevokeStopSilentHeldStreamsAndRejectLatePersistence() {
        for(stage in 0..2) SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,heldTail=true).use { f ->
            val h=Harness(f);val t=h.profiles.begin(h.config,"old",f.records.now.toEpochMilli());val exchange=h.probe.newExchange(t)
            val done=CountDownLatch(1);val result=AtomicReference<TextCapabilityObservation>()
            val worker=thread(name="synthetic-persistent-probe") {try {result.set(exchange.execute())} finally {done.countDown()} }
            try {
                assertNotNull(f.serverA.takeRequest(2,TimeUnit.SECONDS))
                when(stage) {
                    0 -> h.profiles.begin(h.config,"new",f.records.now.toEpochMilli())
                    1 -> h.catalog.change(ModelConnectionChange(h.config.id,h.config.version,"edit",h.config.details))
                    2 -> h.catalog.revoke(h.config,"revoke")
                }
                assertTrue("SQL observer must retire an idle old generation",done.await(3,TimeUnit.SECONDS))
                assertEquals(CapabilityState.UNKNOWN,result.get().state)
                assertThrows(ModelConnectionConflict::class.java){h.profiles.finish(t,result.get())}
                assertEquals(1,f.serverA.requestCount);assertEquals(0,f.serverB.requestCount)
            } finally {exchange.cancel();f.releaseTails();worker.join(2000);h.close()}
            assertFalse(worker.isAlive)
        }
    }
    @Test(timeout=15_000) fun lostDurableAdmissionAckCannotSendOrReuseTicketEvenThoughNoHttpResultExists() {
        SourceBoundLoopbackFixture(ConnectionProtocol.ANTHROPIC_MESSAGES,initiallyApproved=false).use { f ->
            val h=Harness(f);val t=h.profiles.begin(h.config,"admit-lost",f.records.now.toEpochMilli());var changed=false
            f.records.db.afterSql={sql ->if(sql.startsWith("UPDATE model_capability_profiles SET phase='ADMITTED'"))changed=true
                if(sql=="COMMIT"&&changed){changed=false;throw IOException("Synthetic admission ACK loss")} }
            val unknown=h.probe.newExchange(t).execute();assertEquals(CapabilityState.UNKNOWN,unknown.state);f.noRequests()
            f.records.db.afterSql=null
            assertEquals("ADMITTED",f.records.db.read("SELECT phase FROM model_capability_profiles").single().single())
            assertEquals(CapabilityState.UNKNOWN,h.probe.newExchange(t).execute().state);f.noRequests()
            h.profiles.finish(t,unknown);assertEquals(CapabilityState.UNKNOWN,h.profiles.profiles().single().observation.state);h.close()
        }
    }
    private class Harness(val f:SourceBoundLoopbackFixture):AutoCloseable {
        val catalog:SqlModelConnectionCatalog
        val config:ModelConnectionConfiguration
        val profiles:SqlModelCapabilityRepository
        val probe:FixedTextCapabilityProbe
        private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        private val local:LocalRecordUiState
        val controller:ModelConnectionsController
        init {
            f.records.db.transaction {sql ->(ModelConnectionSqlSchema.migration12To13+ModelCapabilitySqlSchema.migration13To14).forEach {sql.execute(it)}}
            catalog=SqlModelConnectionCatalog(f.records.db,ConnectionSecretVaultTest.key())
            val a=f.records.connectionA
            val c=catalog.change(ModelConnectionChange(a.providerId,0,"config",ModelConnectionDetails("合成探测",a.apiRoot,a.protocol,a.modelId,
                setOf(ModelConfigurationPurpose.CONVERSATION,ModelConfigurationPurpose.ORGANIZATION))))!!
            config=catalog.apply(catalog.prepare(c,"synthetic-key",ConnectionSecretVaultTest.material()))
            profiles=SqlModelCapabilityRepository(f.records.db,catalog)
            probe=FixedTextCapabilityProbe(f.transport,profiles,LocalConnectionSecretResolver(catalog),Clock.fixed(f.records.now,ZoneOffset.UTC))
            local=LocalRecordUiState(ready=true,opening=false,text="synthetic-private-capture-draft",modelConnectionOwner=catalog,modelSecretOwner=catalog,modelCapabilityOwner=profiles)
            controller=ModelConnectionsController({local},{queue.addLast(it);true},probe,Clock.fixed(f.records.now,ZoneOffset.UTC),probeDispatcher=kotlinx.coroutines.Dispatchers.Unconfined)
        }
        private val store=object:EntryRepository {
            override val modelConnections get()=catalog
            override val modelCapabilities get()=profiles
            override val localModelConnections get()=LocalModelConnectionServices(catalog,f.records.repository)
            override suspend fun readDraft():TextDraft?=error("No source read from probe")
            override suspend fun updateDraft(text:String):TextDraft=error("No source write")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No source write")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No source write")
        }
        fun configBytes()=f.records.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray
        suspend fun drain(){while(queue.isNotEmpty())queue.removeFirst()(store)}
        override fun close(){controller.dispose();catalog.close()}
    }
}
