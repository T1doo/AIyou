package com.t1doo.aiyou.ai

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual bounded authenticated transport and parser; only synthetic keys and constrained loopback. */
class FixedTextCapabilityProbeTest {
    @Test(timeout=20_000) fun explicitBothProtocolSamplesContainNoSourceAndNormalizeOnlyCompleteExactText() {
        for(protocol in ConnectionProtocol.entries) SourceBoundLoopbackFixture(protocol,initiallyApproved=false,
            fragmented=true,responseBytes=reply(protocol)).use { f ->
            val h=Fixture(f);assertEquals(0,f.serverA.requestCount)
            val exchange=h.probe.newExchange(h.ticket);assertEquals(0,f.serverA.requestCount)
            val result=exchange.execute();assertEquals(CapabilityState.SUPPORTED,result.state)
            assertEquals(CapabilityProbeReason.TEXT_STREAM_VERIFIED,result.reason)
            val request=f.serverA.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("/custom/v1/"+if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"chat/completions" else "messages",request.url.encodedPath)
            val body=Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
            assertEquals(setOf("model","stream","max_tokens","messages"),body.keys)
            assertEquals(32,body.getValue("max_tokens").jsonPrimitive.int)
            assertEquals("Reply with exactly AIYOU_CAPABILITY_OK. This is a synthetic text capability check.",body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"Bearer ${f.keyA}" else f.keyA,
                request.headers[if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"Authorization" else "x-api-key"])
            assertEquals(if(protocol==ConnectionProtocol.ANTHROPIC_MESSAGES)"2023-06-01" else null,request.headers["anthropic-version"])
            assertTrue(f.records.annotations().isEmpty());assertEquals(0,f.serverB.requestCount)
            assertThrows(IllegalStateException::class.java){exchange.execute()}
            assertEquals(CapabilityState.UNKNOWN,h.probe.newExchange(h.ticket).execute().state)
            assertEquals(1,f.serverA.requestCount)
            f.selectedRequestReleased()
        }
    }
    @Test(timeout=30_000) fun endpointUnsupportedIsSeparateFromAuthenticationLimitsTransientAndMalformedUnknown() {
        for((status,expected) in listOf(404 to CapabilityState.UNSUPPORTED,405 to CapabilityState.UNSUPPORTED,
            501 to CapabilityState.UNSUPPORTED,401 to CapabilityState.UNKNOWN,429 to CapabilityState.UNKNOWN,503 to CapabilityState.UNKNOWN)) {
            SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,responseCode=status).use { f ->
                val h=Fixture(f);val result=h.probe.newExchange(h.ticket).execute();assertEquals("HTTP $status",expected,result.state)
                assertEquals(1,f.serverA.requestCount);assertEquals(0,f.serverB.requestCount)
                assertFalse(result.toString().contains("synthetic-private"))
            }
        }
        for(body in listOf("data: [DONE]\n\n".toByteArray(),reply(ConnectionProtocol.CHAT_COMPLETIONS,"wrong-fixed-answer"),
            SourceBoundLoopbackFixture.openPreview(ConnectionProtocol.CHAT_COMPLETIONS),reply(ConnectionProtocol.CHAT_COMPLETIONS,"X".repeat(129)))) {
            SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,responseBytes=body).use { f ->
                val h=Fixture(f);assertEquals(CapabilityState.UNKNOWN,h.probe.newExchange(h.ticket).execute().state)
                assertEquals(1,f.serverA.requestCount)
            }
        }
    }
    @Test(timeout=20_000) fun retiredGenerationSecretAndCancellationRejectBeforeWireWithoutAnyFallback() {
        for(stage in 0..2) SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,
            responseBytes=reply(ConnectionProtocol.CHAT_COMPLETIONS)).use { f ->
            val h=Fixture(f)
            if(stage==0) h.current=false
            if(stage==1) f.beforeConnect={h.current=false}
            val exchange=h.probe.newExchange(h.ticket)
            if(stage==2) exchange.cancel()
            assertEquals(CapabilityState.UNKNOWN,exchange.execute().state);f.noRequests()
        }
        SourceBoundLoopbackFixture(ConnectionProtocol.ANTHROPIC_MESSAGES,initiallyApproved=false).use { f ->
            val h=Fixture(f);val unavailable=object:SecretResolver {
                override fun isCurrent(reference:SecretReference)=false
                override fun resolve(reference:SecretReference):ResolvedSecret?=error("Retired key must never resolve")
            }
            val probe=FixedTextCapabilityProbe(f.transport,h.profiles,unavailable,Clock.fixed(f.records.now,ZoneOffset.UTC))
            assertEquals(CapabilityState.UNKNOWN,probe.newExchange(h.ticket).execute().state);f.noRequests()
        }
    }
    @Test(timeout=15_000) fun cancelledHeldResponseReleasesOwnedCallAndNeverNormalizesPartialTextAsSupported() {
        SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,heldTail=true).use { f ->
            val h=Fixture(f);val exchange=h.probe.newExchange(h.ticket);val done=CountDownLatch(1)
            val result=AtomicReference<TextCapabilityObservation>()
            val worker=thread(name="synthetic-capability-held") {try {result.set(exchange.execute())} finally {done.countDown()} }
            try {
                assertNotNull(f.serverA.takeRequest(2,TimeUnit.SECONDS));exchange.cancel()
                assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(CapabilityState.UNKNOWN,result.get().state)
            } finally { exchange.cancel();f.releaseTails();worker.join(2000) }
            assertFalse(worker.isAlive);assertEquals(0,f.serverB.requestCount)
        }
    }
    private class Fixture(f:SourceBoundLoopbackFixture) {
        @Volatile var current=true
        private val config=f.records.connectionA.let { ModelConnectionConfiguration(it.providerId,it.version,
            ModelConnectionDetails("合成探测",it.apiRoot,it.protocol,it.modelId,setOf(ModelConfigurationPurpose.CONVERSATION)),it.secretRef) }
        val ticket=object:ModelCapabilityProbeTicket {override val profile=ModelCapabilityProfile(config,"synthetic-probe",1,
            f.records.now.toEpochMilli(),TextCapabilityObservation(CapabilityState.UNKNOWN,CapabilityProbeReason.IN_PROGRESS,f.records.now.toEpochMilli()))}
        val profiles=object:ModelCapabilityRepository {
            private var admitted=false
            override fun profiles()=listOf(ticket.profile)
            override fun current(ticket:ModelCapabilityProbeTicket)=current&&ticket===this@Fixture.ticket
            @Synchronized override fun admit(ticket:ModelCapabilityProbeTicket):Boolean =
                if(!current(ticket)||admitted) false else { admitted=true;true }
            override fun watch(ticket:ModelCapabilityProbeTicket,onRetired:()->Unit)=AutoCloseable { }
            override fun begin(expected:ModelConnectionConfiguration,probeId:String,startedAtMillis:Long):ModelCapabilityProbeTicket=error("No implicit probe")
            override fun finish(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile=error("Wire completion cannot write an archive")
            override fun confirm(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile?=error("Wire exchange cannot confirm archive results")
        }
        val probe=FixedTextCapabilityProbe(f.transport,profiles,f.resolver(),Clock.fixed(f.records.now,ZoneOffset.UTC))
    }
    companion object {
        fun reply(protocol:ConnectionProtocol,text:String="AIYOU_CAPABILITY_OK"):ByteArray =
            String(SourceBoundLoopbackFixture.complete(protocol),Charsets.UTF_8).replace(SourceBoundLoopbackFixture.TEXT,text).toByteArray()
    }
}
