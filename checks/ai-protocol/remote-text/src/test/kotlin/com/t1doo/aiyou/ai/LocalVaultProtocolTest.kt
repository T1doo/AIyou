package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.LocalConnectionSecretResolver
import com.t1doo.aiyou.records.SourceBoundRecordAiCapability
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Actual local SQL vault + production adapter + existing constrained loopback; no external model. */
class LocalVaultProtocolTest {
    @Test(timeout=20_000) fun bothProtocolsResolveOnlyCurrentEncryptedSyntheticMaterialAndCommitThroughStrictPort() {
        for(protocol in ConnectionProtocol.entries) SourceBoundLoopbackFixture(protocol).use { f ->
            val h=setup(f);val owner=Owner(LocalModelConnectionServices(h.countedVault,h.repository),h.repository)
            val clock=Clock.fixed(f.records.now,ZoneOffset.UTC)
            assertThrows(IllegalStateException::class.java) { SourceBoundRecordAiCapability.fromLocalConnections(f.transport,Owner(null,h.repository),clock,73) }
            val capability=SourceBoundRecordAiCapability.fromLocalConnections(f.transport,owner,clock,73)
            val outcome=capability.newExchange(f.records.intent).execute()
            assertEquals(StreamEvent.Completed,outcome.terminal);assertNotNull(outcome.annotation)
            val request=f.serverA.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/custom/v1/"+if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"chat/completions" else "messages",request.url.encodedPath)
            assertEquals(if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"Bearer ${ConnectionSecretVaultTest.MATERIAL}" else ConnectionSecretVaultTest.MATERIAL,
                request.headers[if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"Authorization" else "x-api-key"])
            assertEquals(1,h.opens.get());assertEquals(1,h.closes.get());assertEquals(0,f.serverB.requestCount)
            assertEquals(f.records.original,f.records.db.read("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=3",f.records.source.sourceId).single().single())
            capability.close();assertTrue(h.vault.current(h.bound.secretRef!!));h.vault.close()
        }
    }
    @Test(timeout=30_000) fun revocationDuringResolutionOrBeforeSocketAdmissionCannotReachEitherServerOrCommit() {
        for(protocol in ConnectionProtocol.entries) for(stage in 0..2) SourceBoundLoopbackFixture(protocol).use { f ->
            val h=setup(f)
            val resolver=object:SecretResolver by h.resolver {
                override fun resolve(reference:SecretReference):ResolvedSecret? {
                    val value=h.resolver.resolve(reference)
                    if(stage==0) h.vault.revoke(h.bound,"synthetic-retire-during-resolve")
                    return value
                }
                override fun watch(reference:SecretReference,onRetired:()->Unit):AutoCloseable? {
                    if(stage==2) h.vault.close() // registration observes invalidation synchronously
                    return h.resolver.watch(reference,onRetired)
                }
            }
            if(stage==1) f.beforeConnect={h.vault.revoke(h.bound,"synthetic-before-socket")}
            val outcome=f.exchange(resolver,repository=h.repository).execute()
            assertNull(outcome.annotation);assertNotEquals(StreamEvent.Completed,outcome.terminal);f.noRequests()
            assertEquals(h.opens.get(),h.closes.get());assertTrue(f.records.annotations().isEmpty());h.vault.close()
        }
    }
    @Test(timeout=20_000) fun revokedReferenceOrClosedOwnerStopsHeldStreamAndCannotAcceptLateCompletion() {
        for(closeOwner in listOf(false,true)) SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,heldTail=true).use { f ->
            val h=setup(f);val preview=CountDownLatch(1);val finished=CountDownLatch(1);val result=AtomicReference<SourceBoundSendOutcome>()
            val exchange=f.exchange(h.resolver,repository=h.repository)
            val worker=thread(name="synthetic-local-vault-exchange") { try { result.set(exchange.execute { if(it is StreamEvent.TextDelta)preview.countDown() }) } finally { finished.countDown() } }
            try {
                assertTrue(preview.await(4,TimeUnit.SECONDS))
                if(closeOwner)h.vault.close() else h.vault.revoke(h.bound,"synthetic-stream-revoke")
                assertTrue(finished.await(4,TimeUnit.SECONDS));assertNull(result.get().annotation)
                assertNotEquals(StreamEvent.Completed,result.get().terminal);assertTrue(f.records.annotations().isEmpty());assertEquals(h.opens.get(),h.closes.get())
            } finally { exchange.cancel();f.releaseTails();worker.join(2_000);h.vault.close() }
            assertFalse(worker.isAlive)
        }
    }
    private class Harness(val vault:SqlModelConnectionCatalog,val bound:ModelConnectionConfiguration,val repository:RemoteTextTransactions) {
        val opens=AtomicInteger();val closes=AtomicInteger()
        val countedVault=object:ConnectionSecretVault by vault {
            override fun watch(reference:SecretReference,onRetired:()->Unit):AutoCloseable {
                opens.incrementAndGet();val actual=vault.watch(reference,onRetired)
                return AutoCloseable { actual.close();closes.incrementAndGet() }
            }
        }
        val resolver=LocalConnectionSecretResolver(countedVault)
    }
    private class Owner(override val localModelConnections:LocalModelConnectionServices?,
        override val sourceBoundText:SourceBoundTextRepository):EntryRepository {
        override suspend fun readDraft():TextDraft?=error("No draft read")
        override suspend fun updateDraft(text:String):TextDraft=error("No draft write")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No record write")
        override suspend fun entries()=emptyList<StoredEntry>()
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No revision write")
    }
    private fun setup(f:SourceBoundLoopbackFixture):Harness {
        f.records.db.transaction { sql->ModelConnectionSqlSchema.migration12To13.forEach { sql.execute(it) } }
        val vault=SqlModelConnectionCatalog(f.records.db,ConnectionSecretVaultTest.key())
        val a=f.records.connectionA
        val config=vault.change(ModelConnectionChange(a.providerId,0,"synthetic-config",ModelConnectionDetails("合成协议连接",a.apiRoot,a.protocol,a.modelId,setOf(ModelConfigurationPurpose.ORGANIZATION))))!!
        val bound=vault.apply(vault.prepare(config,"synthetic-key-put",ConnectionSecretVaultTest.material()))
        val connection=bound.textConnection(TextPurpose.ORGANIZATION)
        f.records.approve(connection=connection,consent=f.records.grant.copy(connection=connection))
        return Harness(vault,bound,RemoteTextTransactions(f.records.db,connectionCurrent=vault::connectionCurrent))
    }
}
