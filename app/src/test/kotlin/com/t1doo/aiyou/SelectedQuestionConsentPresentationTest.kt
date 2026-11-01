package com.t1doo.aiyou

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.*
import java.util.concurrent.atomic.*
import org.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual root, controller and protocol codec with a synthetic authority port. Persistent SQL
 * authority is verified separately on the host; this is not native storage/device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class SelectedQuestionConsentPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val entries = listOf("picture", "text").mapIndexed { i, id ->
        StoredEntry(id, 1, "needle 合成摘录${i + 1}🙂", 5 + i.toLong(), "UTC", ProcessingPolicy.REMOTE_ALLOWED)
    }
    private val port = object : LocalSearchRepository {
        override fun batch() = LocalSearchProgress(0, false, 1)
        override fun rebuild() = 1L
        override fun epoch() = 1L
        override fun candidates(request: LocalSearchRequest) = object : LocalSearchSnapshot {
            override val epoch = 1L
            override val entries = this@SelectedQuestionConsentPresentationTest.entries
            override val tags = entries.associate { it.id to ManualTagSnapshot(it.id, 0, emptyList()) }
            override val pending = false
        }
        override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean { publish(); return true }
        override fun resolve(snapshot: LocalSearchSnapshot, entryId: String, publish: (StoredEntry) -> Unit): Boolean {
            snapshot.entries.find { it.id == entryId }?.let(publish) ?: return false; return true
        }
    }
    private val fixture = Fixture()
    private val records = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = entries,
        localSearch = port, localSearchEpoch = 1, selectedQuestionAuthorizationOwner = fixture.authority))
    private val selected = mutableStateOf<SelectedQuestionConsentFixture?>(fixture.capability)
    @Composable private fun Screen(large: Boolean = false,production:ProductionQuestions?=null,destination:Destination=Destination.MEMORIES) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (large) Density(density.density, 2f) else density) {
            AIyouTheme { Box(if (large) Modifier.size(320.dp, 640.dp) else Modifier) {
                AIyouApp(previewDestination = destination, local = records.value, currentRecords = { records.value },
                    selectedQuestionConsentFixture = if(production==null)selected.value else null,productionQuestions=production)
            } }
        }
    }
    private fun reveal(tag: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) {
            try { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag)); true }
            catch (_: AssertionError) { false }
        }
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun select() {
        reveal("original-search-query").performTextReplacement("needle")
        reveal("local-evidence-mode").performClick()
        reveal("local-evidence-select-picture").performClick()
        reveal("local-evidence-select-text").performClick()
        reveal("local-evidence-confirm").performClick(); reveal("local-evidence-confirmed")
    }
    private fun review() {
        reveal("question-consent-review").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("question-consent-confirm").fetchSemanticsNodes().size == 1 }
    }

    @Test fun narrowDoubleFontSeparateMultiSourceReviewAndExactOriginalJump() {
        compose.setContent { Screen(true) }; select()
        assertEquals(0, fixture.authorizations.get()); assertEquals(0, fixture.opened.get())
        review()
        compose.onNodeWithTag("question-consent-question").performScrollTo().assertTextEquals("needle")
        compose.onNodeWithTag("question-consent-destination").performScrollTo()
            .assertTextContains("https://consent-ui.invalid/custom/v1/chat/completions", substring = true)
        // Main search presents the newer capture first; preserve that source order rather
        // than mistaking the fixture port's insertion order for the user's current results.
        val searchOrder = listOf(entries[1], entries[0])
        assertTrue(compose.onNodeWithTag("question-consent-scope-scroll").getUnclippedBoundsInRoot().width >= 160.dp)
        searchOrder.forEachIndexed { i, e ->
            val quote = compose.onNodeWithTag("question-consent-quote-$i").performScrollTo().assertTextEquals(e.originalText)
            assertTrue(quote.getUnclippedBoundsInRoot().width >= 160.dp)
        }
        assertEquals(0, fixture.authorizations.get()); assertEquals(0, fixture.opened.get())
        val confirm = compose.onNodeWithTag("question-consent-confirm").assertIsDisplayed()
        assertTrue(confirm.getUnclippedBoundsInRoot().height >= 48.dp); confirm.performClick()
        reveal("question-consent-show-result").performClick()
        assertTrue(compose.onNodeWithTag("question-consent-result-scroll").getUnclippedBoundsInRoot().width >= 160.dp)
        compose.onNodeWithTag("question-consent-paragraph-0").assertTextEquals("合成授权演练结果")
        assertEquals(1, fixture.authorizations.get()); assertEquals(1, fixture.opened.get())
        assertEquals(searchOrder.map { it.id }, fixture.sentSources.get())
        compose.onNode(hasText("查看原文") and hasAnyAncestor(hasTestTag("question-consent-source-0-picture")))
            .performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("original-text", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("original-text", useUnmergedTree = true).assertTextContains(entries[0].originalText)
        compose.onNodeWithText("返回列表").performClick()
        compose.onAllNodesWithTag("question-consent-paragraph-0").assertCountEquals(0)
        compose.onAllNodesWithTag("question-consent-confirm").assertCountEquals(0)
    }

    @Test fun withdrawUnknownAckHidesResultAndExplicitRetryNeverExecutesAgain() {
        compose.setContent { Screen() }; select(); review()
        compose.onNodeWithTag("question-consent-confirm").performClick()
        reveal("question-consent-show-result").performClick()
        fixture.failWithdrawal.set(true)
        compose.onNodeWithTag("question-consent-result-withdraw").performClick()
        reveal("question-consent-withdraw").assertTextContains("重试撤回", substring = true)
        compose.onAllNodesWithTag("question-consent-paragraph-0").assertCountEquals(0)
        compose.onAllNodesWithTag("question-consent-show-result").assertCountEquals(0)
        fixture.failWithdrawal.set(false)
        reveal("question-consent-withdraw").performClick(); reveal("question-consent-withdrawn")
        assertEquals(2, fixture.withdrawals.get()); assertEquals(1, fixture.authorizations.get()); assertEquals(1, fixture.opened.get())
    }

    @Test fun serviceReplacementRetiresOpenReviewAndDefaultCapabilityNeverOffersSend() {
        compose.setContent { Screen() }; select(); review()
        compose.runOnIdle { selected.value = null }
        compose.onAllNodesWithTag("question-consent-confirm").assertCountEquals(0)
        reveal("question-consent-unavailable")
        compose.onAllNodesWithTag("question-consent-review").assertCountEquals(0)
        assertEquals(0, fixture.authorizations.get()); assertEquals(0, fixture.opened.get())
    }

    @Test fun actualProductionQuestionInputAndConnectionAreSeparateFromRecordSavingAndPackageGateCannotBeBypassed() {
        val config=ModelConnectionConfiguration("synthetic-production",1,ModelConnectionDetails("合成受控连接",
            "https://production-ui.invalid/custom/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",
            setOf(ModelConfigurationPurpose.CONVERSATION)),SecretReference("synthetic-unresolved-reference"))
        val catalog=object:ModelConnectionCatalog {
            override fun configurations()=listOf(config)
            override fun change(request:ModelConnectionChange):ModelConnectionConfiguration?=error("No metadata writes")
        }
        val profile=ModelCapabilityProfile(config,"synthetic-prior-probe",1,NOW.toEpochMilli(),
            TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,NOW.toEpochMilli()))
        val profiles=object:ModelCapabilityRepository {
            override fun profiles()=listOf(profile)
            override fun begin(expected:ModelConnectionConfiguration,probeId:String,startedAtMillis:Long):ModelCapabilityProbeTicket=error("No probe")
            override fun current(ticket:ModelCapabilityProbeTicket)=false
            override fun admit(ticket:ModelCapabilityProbeTicket)=false
            override fun watch(ticket:ModelCapabilityProbeTicket,onRetired:()->Unit):AutoCloseable=error("No probe")
            override fun finish(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile=error("No probe")
            override fun confirm(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile?=error("No probe")
        }
        val writes=AtomicInteger();val clients=AtomicInteger()
        val store=object:EntryRepository {
            override val modelConnections=catalog
            override val modelCapabilities=profiles
            override val selectedQuestionAuthorizations=fixture.authority
            override suspend fun readDraft():TextDraft?=error("No draft IO from questions")
            override suspend fun updateDraft(text:String):TextDraft {writes.incrementAndGet();error("No draft writes")}
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry {writes.incrementAndGet();error("No save")}
            override suspend fun entries()=this@SelectedQuestionConsentPresentationTest.entries
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry {writes.incrementAndGet();error("No revision")}
        }
        records.value=records.value.copy(text="合成记录草稿，不能变成问题",modelConnectionOwner=catalog,modelCapabilityOwner=profiles)
        val ai=ProductionRecordAi({false},Clock.fixed(NOW,ZoneOffset.UTC),{clients.incrementAndGet();error("No client")})
        ai.bindOwner(store)
        val runtime=ProductionQuestions({records.value},{action->runBlocking{action(store)};true},ai,Clock.fixed(NOW,ZoneOffset.UTC))
        runtime.bindOwner(store)
        try {
            compose.setContent {Screen(true,runtime,Destination.NOW)}
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("问一问"))
            compose.onNodeWithText("问一问").assertHeightIsAtLeast(48.dp).performClick()
            reveal("original-search-query").performTextReplacement("needle")
            reveal("question-draft").performTextReplacement("这两条合成记录讲了什么？")
            reveal("question-draft").assertTextContains("这两条合成记录讲了什么？")
            reveal("original-search-query").assertTextContains("needle")
            reveal("question-select-synthetic-production").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            reveal("local-evidence-select-picture").performClick();reveal("local-evidence-select-text").performClick()
            reveal("local-evidence-confirm").performClick();reveal("local-evidence-confirmed")
            reveal("question-consent-unavailable").assertTextContains("当前安装包未开放网络发送",substring=true)
            compose.onAllNodesWithTag("question-consent-review").assertCountEquals(0)
            compose.onAllNodesWithTag("question-consent-fixture").assertCountEquals(0)
            reveal("question-draft").performTextReplacement("改问另一个合成问题")
            compose.onAllNodesWithTag("local-evidence-confirmed").assertCountEquals(0)
            reveal("local-evidence-confirm").assertIsEnabled()
            compose.runOnIdle {
                assertEquals(0,writes.get());assertEquals(0,clients.get());assertEquals(0,fixture.authorizations.get());assertEquals(0,fixture.opened.get())
                assertEquals("合成记录草稿，不能变成问题",records.value.text)
            }
        } finally {runBlocking{runtime.closeAndJoin()};ai.close()}
    }

    /** Synthetic metadata/ack port only. Does not replace the product's SQL authority test. */
    private class Fixture {
        val opened = AtomicInteger(); val authorizations = AtomicInteger(); val withdrawals = AtomicInteger()
        val sentSources = AtomicReference<List<String>>()
        val failWithdrawal = AtomicBoolean(); private val state = AtomicReference("NEW")
        private val connection = ProviderConnection("synthetic-ui", 1, "https://consent-ui.invalid/custom/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS, "fixture-model", SecretReference("unresolved-synthetic-reference"))
        private val service = LocalProtocolQuestionService(SelectedQuestionTarget(connection.protocol, connection.apiRoot, connection.modelId),
            LocalQuestionProtocolFixture { request ->
                opened.incrementAndGet()
                val messages = JSONObject(request.bodyJson).getJSONArray("messages")
                val payload = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                val selectedSources = payload.getJSONArray("sources")
                sentSources.set((0 until selectedSources.length()).map { selectedSources.getJSONObject(it).getString("sourceId") })
                object : LocalQuestionWireExchange {
                    override fun cancel() { }
                    override fun execute(onChunk: (ByteArray) -> Unit) {
                        val refs = JSONArray(); val sources = payload.getJSONArray("sources")
                        for (i in 0 until sources.length()) {
                            val source = sources.getJSONObject(i); val ref = JSONObject()
                            listOf("sourceId", "sourceRevision", "start", "end", "quote").forEach { ref.put(it, source.get(it)) }; refs.put(ref)
                        }
                        val reply = JSONObject().put("requestId", payload.get("requestId")).put("selectionId", payload.get("selectionId"))
                            .put("noEvidence", false).put("paragraphs", JSONArray().put(JSONObject().put("text", "合成授权演练结果").put("references", refs)))
                        val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
                            .put("delta", JSONObject().put("role", "assistant").put("content", reply.toString())).put("finish_reason", "stop")))
                        onChunk(("data: $chunk\n\ndata: [DONE]\n\n").toByteArray())
                    }
                }
            })
        val capability = SelectedQuestionConsentFixture(connection, service, Clock.fixed(NOW, ZoneOffset.UTC), "UTC")
        val authority = object : SelectedQuestionAuthorizationRepository {
            override fun review(request: SelectedQuestionRequest, connection: ProviderConnection, promptVersion: String, clock: () -> Instant) =
                SelectedQuestionSendReview(request, connection, promptVersion, "synthetic-session", request.sources.map {
                    val source = it.reference.source
                    RecordAuthorizationSnapshot(RecordAuthorizationSource(source, "a".repeat(64), 1), ProcessingPolicy.REMOTE_ALLOWED, connection,
                        ConsentGrant("grant-${source.sourceId}", 1, source, connection, TextPurpose.CONVERSATION, NOW.minusSeconds(1), NOW.plusSeconds(600)))
                })
            override fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit {
                check(state.compareAndSet("NEW", "QUEUED")); authorizations.incrementAndGet()
                return SelectedQuestionSendPermit(review, review.sources.indices.map { "member-$it" })
            }
            override fun check(permit: SelectedQuestionSendPermit, clock: () -> Instant): SendDenial? =
                if (state.get() in listOf("QUEUED", "ADMITTED")) null else SendDenial.ALREADY_SENT
            override fun subscribe(permit: SelectedQuestionSendPermit, clock: () -> Instant, onInvalidated: (SendDenial) -> Unit) = object : SourceBoundTextSubscription {
                override fun check(): SendDenial? = if (state.get() in listOf("QUEUED", "ADMITTED")) null else SendDenial.ALREADY_SENT
                override fun close() { }
            }
            override fun admit(permit: SelectedQuestionSendPermit, clock: () -> Instant) { check(state.compareAndSet("QUEUED", "ADMITTED")) }
            override fun accept(permit: SelectedQuestionSendPermit, reply: SelectedQuestionReply, clock: () -> Instant): SelectedQuestionSendReceipt {
                check(state.compareAndSet("ADMITTED", "COMMITTED")); return SelectedQuestionSendReceipt(permit, reply, "b".repeat(64))
            }
            override fun checkReceipt(receipt: SelectedQuestionSendReceipt, clock: () -> Instant): SendDenial? =
                if (state.get() == "COMMITTED") null else SendDenial.ALREADY_SENT
            override fun withdraw(permit: SelectedQuestionSendPermit) {
                withdrawals.incrementAndGet(); if (failWithdrawal.get()) error("synthetic unknown ACK"); state.set("CANCELLED")
            }
            override fun cancelPending(permit: SelectedQuestionSendPermit) { state.compareAndSet("QUEUED", "CANCELLED"); state.compareAndSet("ADMITTED", "CANCELLED") }
        }
    }
    companion object { private val NOW = Instant.parse("2026-10-18T20:00:00Z") }
}
