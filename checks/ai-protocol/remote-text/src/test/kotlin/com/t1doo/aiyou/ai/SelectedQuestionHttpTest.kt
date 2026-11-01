package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.SelectedQuestionAuthorizationTest.Harness
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real persisted group and real HTTP/SSE engine. Only synthetic loopback; not a live provider. */
class SelectedQuestionHttpTest {
    @Test(timeout=30_000) fun bothProtocolsAndAuthenticatedBudgetOptionsSendOnlyReviewedExcerptsAndAcceptOneCompleteReceipt() {
        for(protocol in ConnectionProtocol.entries) for(completionBudget in if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)listOf(false,true)else listOf(false)) {
            val connection=connection(protocol,completionBudget)
            Harness(connectionOverride=connection).use { h ->
                val permit=h.authorize();val reply=h.reply(permit.review.request)
                SourceBoundLoopbackFixture(protocol,initiallyApproved=false,responseBytes=response(protocol,reply)).use { wire ->
                    val secrets=wire.resolver()
                    val exchange=wire.transport.newSelectedQuestionExchange(h.repo,permit,secrets,h.clock)
                    val receipt=exchange.execute()
                    assertNull(h.repo.checkReceipt(receipt,h.clock));assertEquals(reply.paragraphs.map{it.text},receipt.reply.paragraphs.map{it.text})
                    val request=wire.serverA.takeRequest(2,TimeUnit.SECONDS)!!
                    val body=Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
                    assertEquals(4096,body[if(completionBudget)"max_completion_tokens" else "max_tokens"]!!.jsonPrimitive.int)
                    if(completionBudget){assertFalse(body.containsKey("max_tokens"));assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)}
                    val payload=body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
                    assertTrue(payload.contains(permit.review.request.question));assertFalse(payload.contains("UNSELECTED_PRIVATE_TAIL"));assertFalse(payload.contains("UNSELECTED_OTHER_RECORD"))
                    assertEquals("/custom/v1/"+if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"chat/completions" else "messages",request.url.encodedPath)
                    assertEquals(listOf("COMMITTED","COMMITTED"),h.jobs().map{it[1]})
                    assertTrue(h.f.db.read("SELECT * FROM text_annotations").isEmpty())
                    assertThrows(IllegalStateException::class.java){exchange.execute()}
                    assertThrows(SelectedQuestionSendDenied::class.java){wire.transport.newSelectedQuestionExchange(h.repo,permit,secrets,h.clock).execute()}
                    assertEquals(1,wire.serverA.requestCount);assertEquals(0,wire.serverB.requestCount);wire.wiped(secrets)
                }
            }
        }
    }

    @Test(timeout=30_000) fun sourceSecretAndDirectoryChangesImmediatelyBeforeHttpWriteCannotSendOrAdmitAnyMember() {
        for(change in listOf("source","secret","directory")) {
            val current=AtomicBoolean(true)
            Harness(connectionCurrent={_,_->current.get()},connectionOverride=connection()).use {h->
                val permit=h.authorize()
                SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false).use {wire->
                    val base=wire.resolver();val secretCurrent=AtomicBoolean(true)
                    val secrets=object:SecretResolver by base {override fun isCurrent(reference:SecretReference)=secretCurrent.get()}
                    wire.beforeConnect={when(change){"source"->h.withdrawSource(1);"secret"->secretCurrent.set(false);else->current.set(false)}}
                    assertThrows(SelectedQuestionSendDenied::class.java){wire.transport.newSelectedQuestionExchange(h.repo,permit,secrets,h.clock).execute()}
                    wire.noRequests();assertTrue(h.f.db.read("SELECT * FROM text_annotations").isEmpty())
                    assertFalse(h.jobs().any{it[1]=="COMMITTED"});wire.wiped(base)
                }
            }
        }
    }

    @Test(timeout=20_000) fun secondSourceWithdrawalCancelsSilentStreamAndRejectsLateOutputForTheWholeGroup() {
        Harness(connectionOverride=connection()).use {h->
            val permit=h.authorize()
            SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,heldTail=true).use {wire->
                val exchange=wire.transport.newSelectedQuestionExchange(h.repo,permit,wire.resolver(),h.clock)
                val finished=CountDownLatch(1);val failure=AtomicReference<Throwable?>();val receipt=AtomicReference<SelectedQuestionSendReceipt?>()
                val worker=thread(name="synthetic-selected-question") {try{receipt.set(exchange.execute())}catch(t:Throwable){failure.set(t)}finally{finished.countDown()}}
                try {
                    assertNotNull(wire.serverA.takeRequest(2,TimeUnit.SECONDS));assertEquals(listOf("ADMITTED","ADMITTED"),h.jobs().map{it[1]})
                    h.withdrawSource(1)
                    assertTrue(finished.await(2,TimeUnit.SECONDS));assertNull(receipt.get());assertNotNull(failure.get())
                    assertFalse(h.jobs().any{it[1]=="COMMITTED"});assertTrue(h.f.db.read("SELECT * FROM text_annotations").isEmpty())
                }finally{exchange.cancel();wire.releaseTails();worker.join(2000)}
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test(timeout=20_000) fun malformedCompleteAnswerAndCancelledQueuedExecutionNeverPublishAReceipt() {
        for(cancelled in listOf(false,true))Harness(connectionOverride=connection()).use {h->
            val permit=h.authorize()
            SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false).use {wire->
                val secrets=wire.resolver();val exchange=wire.transport.newSelectedQuestionExchange(h.repo,permit,secrets,h.clock)
                if(cancelled)exchange.cancel()
                assertThrows(Exception::class.java){exchange.execute()}
                assertEquals(if(cancelled)0 else 1,wire.serverA.requestCount)
                assertFalse(h.jobs().any{it[1]=="COMMITTED"});assertTrue(h.f.db.read("SELECT * FROM text_annotations").isEmpty())
                if(cancelled)assertTrue(secrets.values.isEmpty())
            }
        }
    }

    @Test(timeout=20_000) fun lostAcceptAckCannotResendAndOnlyExactReplyMayRecoverTheCommittedReceipt() {
        Harness(connectionOverride=connection()).use {h->
            val permit=h.authorize();val reply=h.reply(permit.review.request);val before=h.authority()
            val dropped=object:SelectedQuestionAuthorizationRepository by h.repo {
                override fun accept(permit:SelectedQuestionSendPermit,reply:SelectedQuestionReply,clock:()->java.time.Instant):SelectedQuestionSendReceipt {
                    h.repo.accept(permit,reply,clock);throw IOException("Synthetic accepted reply ACK loss")
                }
            }
            SourceBoundLoopbackFixture(ConnectionProtocol.CHAT_COMPLETIONS,initiallyApproved=false,responseBytes=response(ConnectionProtocol.CHAT_COMPLETIONS,reply)).use {wire->
                assertThrows(IOException::class.java){wire.transport.newSelectedQuestionExchange(dropped,permit,wire.resolver(),h.clock).execute()}
                assertEquals(listOf("COMMITTED","COMMITTED"),h.jobs().map{it[1]})
                assertThrows(SelectedQuestionSendDenied::class.java){wire.transport.newSelectedQuestionExchange(h.repo,permit,wire.resolver(),h.clock).execute()}
                h.f.db.beforeSql={sql->if(listOf("INSERT","UPDATE","DELETE","REPLACE").any{sql.startsWith(it)})error("Recovery must be read-only")}
                val recovered=h.repo.accept(permit,reply,h.clock);assertNull(h.repo.checkReceipt(recovered,h.clock))
                h.f.db.beforeSql=null
                assertEquals(before,h.authority());assertEquals(1,wire.serverA.requestCount);assertEquals(0,wire.serverB.requestCount)
            }
        }
    }

    companion object {
        fun connection(protocol:ConnectionProtocol=ConnectionProtocol.CHAT_COMPLETIONS,completion:Boolean=false)=ProviderConnection(
            "synthetic-provider-a",7,"https://provider-a.example.test/custom/v1/",protocol,"synthetic-model-a",SecretReference("synthetic-private-ref-a"),
            if(completion)ChatBudgetField.MAX_COMPLETION_TOKENS else ChatBudgetField.MAX_TOKENS,completion)
        fun response(protocol:ConnectionProtocol,reply:SelectedQuestionReply):ByteArray {
            val answer=buildJsonObject {
                put("requestId",reply.requestId);put("selectionId",reply.selectionId);put("noEvidence",reply.noEvidence)
                putJsonArray("paragraphs"){reply.paragraphs.forEach {p->add(buildJsonObject {
                    put("text",p.text);putJsonArray("references"){p.references.forEach {r->add(buildJsonObject {
                        put("sourceId",r.source.sourceId);put("sourceRevision",r.source.revision);put("start",r.start);put("end",r.end);put("quote",r.quote)
                    })}}
                })}}
            }.toString()
            val escaped=JsonPrimitive(answer).toString().removeSurrounding("\"")
            return String(SourceBoundLoopbackFixture.complete(protocol),Charsets.UTF_8).replace(SourceBoundLoopbackFixture.TEXT,escaped).toByteArray(Charsets.UTF_8)
        }
    }
}
