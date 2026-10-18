package com.t1doo.aiyou

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual product navigation/Compose/controller with synthetic memory ports; SQL proof is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
@OptIn(ExperimentalFoundationApi::class)
class RecycleBinPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val f = Fixture()
    private var saved: SaveableStateRegistry? = null
    private val navigation = AtomicReference<OriginalReadingCallbacks>()
    private val observer = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { navigation.set(callbacks) }
        override fun onClipboard(clipboard: Clipboard) = Unit
        override fun onSelection(selection: SelectionState) = Unit
    }
    private fun content(doubleFont: Boolean = false) = compose.setContent {
        saved = LocalSaveableStateRegistry.current
        val density = LocalDensity.current
        CompositionLocalProvider(LocalOriginalReadingObserver provides observer,
            LocalDensity provides Density(density.density, if (doubleFont) 2f else density.fontScale)) {
            AIyouTheme { AIyouApp(local = f.state.value, previewDestination = Destination.MEMORIES,
                recycleBin = f.controller, currentRecords = { f.state.value }) }
        }
    }
    private fun pump() = compose.runOnIdle { f.drain() }
    private fun openOriginal() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performScrollTo().performClick()
    }
    private fun moveDialog() {
        openOriginal()
        compose.onNodeWithTag("original-recycle-move").assertIsDisplayed().assertIsEnabled().performClick(); pump()
    }
    private fun browse() {
        // After deleting the visible row, the remembered timeline can keep its
        // header offscreen. Compose that lazy item before looking up its node.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("recycle-open"))
        compose.onNodeWithTag("recycle-open").assertIsDisplayed().performClick(); pump()
    }
    @Test fun actualMoveAndExplicitRestoreKeepFullOriginalTagsAndRequireEachConfirmation() {
        content(); moveDialog(); compose.onNodeWithText("移入回收站？").assertExists()
        compose.onNodeWithText("已经导出、另存或发给服务商的数据无法通过本次操作撤回。").performScrollTo().assertExists()
        compose.onNodeWithTag("recycle-cancel").performClick(); compose.runOnIdle { assertFalse(f.deleted); assertTrue(f.applied.isEmpty()) }
        compose.onNodeWithTag("original-recycle-move").assertIsDisplayed().assertIsEnabled().performClick(); pump()
        compose.onNodeWithTag("recycle-confirm").performClick()
        compose.runOnIdle { assertFalse(f.deleted); assertTrue(f.state.value.recycleBin!!.confirmation!!.busy) }
        pump(); compose.runOnIdle { assertTrue(f.deleted); assertTrue(f.state.value.entries.isEmpty()); navigation.get().returnToList() }
        compose.onNodeWithTag("entry-one").assertDoesNotExist(); browse()
        compose.onNodeWithTag("recycle-view-one").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-original").assertTextEquals(f.original.originalText)
        compose.onNodeWithText("手工标签：合成私密标签").performScrollTo().assertExists()
        compose.onNodeWithText("已保留 2 个原文版本、1 项附件记录（含待确认项）。").performScrollTo().assertExists()
        compose.onNodeWithTag("recycle-restore").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-cancel").performClick(); compose.runOnIdle { assertTrue(f.deleted); assertEquals(1, f.applied.size) }
        compose.onNodeWithTag("recycle-restore").performScrollTo().performClick(); compose.onNodeWithTag("recycle-confirm").performClick(); pump()
        compose.onNodeWithTag("recycle-notice").assertTextEquals("已恢复到本机列表；不参与 AI，旧远端授权未恢复。")
        compose.runOnIdle { assertFalse(f.deleted); assertEquals(ProcessingPolicy.AI_NONE, f.state.value.entries.single().policy)
            assertEquals(2, f.applied.size); navigation.get().selectDestination(Destination.MEMORIES) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNodeWithTag("entry-one").assertIsDisplayed()
        assertEquals(listOf("合成私密标签"), f.state.value.manualTags.getValue("one").names)
    }
    @Test fun loadingEmptyAndFailedReadsAreDistinctAndNeverExposeStaleTrashBody() {
        content(); compose.onNodeWithTag("recycle-open").performScrollTo().performClick()
        compose.onNodeWithText("正在读取回收站").assertExists(); pump()
        compose.onNodeWithText("回收站为空").assertExists(); compose.onNodeWithTag("recycle-original").assertDoesNotExist()
        compose.runOnIdle { f.failRead = true }
        compose.onNodeWithTag("recycle-refresh").performScrollTo().performClick(); pump()
        compose.onNodeWithText("回收站读取失败").assertExists(); compose.onNodeWithText("回收站为空").assertDoesNotExist()
        compose.runOnIdle { f.failRead = false }; compose.onNodeWithTag("recycle-refresh").performScrollTo().performClick(); pump()
        compose.onNodeWithText("回收站为空").assertExists(); assertTrue(f.applied.isEmpty())
    }
    @Test fun failedOrLostAcknowledgementKeepsExactTargetForStableRetryWithoutClaimingSuccess() {
        content(); moveDialog(); compose.runOnIdle { f.failAfterCommit = true }
        compose.onNodeWithTag("recycle-confirm").performClick(); pump()
        compose.runOnIdle { assertTrue(f.deleted); assertNull(f.state.value.recycleNotice); assertNotNull(f.state.value.recycleBin!!.confirmation!!.error) }
        compose.onNodeWithText("重试确认").assertExists(); compose.onNodeWithText("已移入回收站。").assertDoesNotExist()
        compose.runOnIdle { f.failAfterCommit = false }; compose.onNodeWithTag("recycle-confirm").performClick(); pump()
        compose.runOnIdle { assertNull(f.state.value.recycleBin); assertNotNull(f.state.value.recycleNotice)
            assertEquals(2, f.applied.size); assertEquals(1, f.applied.distinct().size); assertEquals(2L, f.generation) }
    }
    @Test @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowDoubleFontKeepsConfirmCancelAndRestoreReachableAndHidesContentOnReadFailure() {
        f.deleted = true; f.original = f.original.copy(policy = ProcessingPolicy.AI_NONE); f.state.value = f.state.value.copy(entries = emptyList())
        content(true); browse(); compose.onNodeWithTag("recycle-view-one").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-restore").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("recycle-confirm").assertIsDisplayed(); compose.onNodeWithTag("recycle-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { f.state.value = f.state.value.copy(error = "合成本机读取失败") }
        compose.onNodeWithTag("recycle-original").assertDoesNotExist(); compose.onNodeWithText("手工标签：合成私密标签").assertDoesNotExist()
        compose.onNodeWithText("回收站暂不可读取").assertExists(); assertTrue(f.applied.isEmpty())
    }
    @Test fun retainedReaderAndConfirmationCallbacksRejectNavigationAndNewSessionBeforeRecomposition() {
        content(); openOriginal()
        val move = compose.onNodeWithTag("original-recycle-move").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { navigation.get().selectDestination(Destination.TASKS); move(); assertNull(f.state.value.recycleBin); assertTrue(f.queue.isEmpty()) }
        compose.runOnIdle { navigation.get().selectDestination(Destination.MEMORIES) }
        moveDialog(); val confirm = compose.onNodeWithTag("recycle-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            val old = f.state.value.recycleBin!!; f.controller.cancelConfirmation(old.session, old.confirmation!!.id)
            f.controller.requestMove("one", 1); f.drain(); val fresh = f.state.value.recycleBin!!
            assertNotEquals(old.session, fresh.session); confirm(); assertFalse(f.deleted); assertTrue(f.queue.isEmpty())
            assertEquals(fresh.confirmation!!.id, f.state.value.recycleBin!!.confirmation!!.id)
        }
    }
    @Test fun rawSavedStateExcludesRecycleOriginalTagsOperationAndConfirmationAndQueuedCloseDoesNotWrite() {
        content(); moveDialog()
        compose.runOnIdle {
            val ui = f.state.value.recycleBin!!
            assertNoPrivateState(checkNotNull(saved).performSave(), listOf("合成私密原文", "合成私密标签", ui.confirmation!!.mutation.mutationId, "recycleBin"))
            f.controller.confirm(ui.session, ui.confirmation.id); f.controller.close(ui.session); f.drain()
            assertFalse(f.deleted); assertTrue(f.applied.isEmpty()); assertEquals("合成独立草稿", f.state.value.text)
        }
    }
    @Test fun retainedRestoreButtonForBRejectsAfterSelectingAAndConfirmationNamesTheCurrentTarget() {
        f.deleted = true; f.extra = RecycleRecord(StoredEntry("two", 1, "另一条合成原文 B", 43, "UTC", ProcessingPolicy.AI_NONE), 7, true,
            ManualTagSnapshot("two", 0, emptyList()), 1, 0)
        f.state.value = f.state.value.copy(entries = emptyList()); content(); browse()
        compose.onNodeWithTag("recycle-view-two").performScrollTo().performClick()
        val oldRestore = compose.onNodeWithTag("recycle-restore").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            val session = f.state.value.recycleBin!!.session
            f.controller.select(session, null); f.controller.select(session, "one"); oldRestore()
            assertNull(f.state.value.recycleBin!!.confirmation); assertTrue(f.queue.isEmpty()); assertTrue(f.applied.isEmpty())
        }
        compose.onNodeWithTag("recycle-restore").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("one", f.state.value.recycleBin!!.confirmation!!.record.entry.id) }
        compose.onNodeWithText("确认时的原文（节选）：" + originalPreview(f.original.originalText)).assertExists()
    }
    @Test @Config(qualifiers = "w280dp-h1000dp")
    fun permanentEraseRequiresExplicitConfirmationAndCancelKeepsPrivateStateOutOfSavedStateAtDoubleFont() {
        f.deleted=true;f.state.value=f.state.value.copy(entries=emptyList(),supportsPermanentErasure=true)
        content(doubleFont=true);browse()
        compose.onNodeWithTag("recycle-view-one").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-erase").performScrollTo().performClick()
        compose.onNodeWithText("永久删除这条记录？").assertExists()
        compose.onNodeWithTag("recycle-confirm").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertTrue(f.erasureApplied.isEmpty());assertFalse(f.erased)
            val mutation=f.state.value.recycleBin!!.confirmation!!.erasureIntent!!.mutationId
            assertNoPrivateState(saved!!.performSave(),listOf("合成私密原文🙂","合成私密标签","合成独立草稿",mutation))
        }
        compose.onNodeWithTag("recycle-cancel").performClick();pump()
        compose.runOnIdle {assertTrue(f.erasureApplied.isEmpty());assertFalse(f.erased);assertNull(f.state.value.recycleBin!!.confirmation)}
    }
    @Test fun permanentEraseLostReceiptOffersExplicitRetryWithTheSameIntentAndNoFalseSuccess() {
        f.deleted=true;f.failAfterErase=true;f.state.value=f.state.value.copy(entries=emptyList(),supportsPermanentErasure=true)
        content();browse();compose.onNodeWithTag("recycle-view-one").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-erase").performScrollTo().performClick();compose.onNodeWithTag("recycle-confirm").performClick();pump()
        compose.onNodeWithText("重试确认").assertExists();compose.onNodeWithTag("recycle-notice").assertDoesNotExist()
        compose.onNodeWithTag("recycle-confirm").performClick();pump()
        compose.runOnIdle {assertTrue(f.erased);assertEquals(2,f.erasureApplied.size);assertEquals(1,f.erasureApplied.distinct().size);assertNull(f.state.value.recycleBin!!.confirmation)}
        compose.onNodeWithTag("recycle-notice").assertExists()
    }
    @Test fun rereadPendingErasureDisablesRestoreAndContinuesTheDurableConfirmation() {
        f.deleted=true;f.failBeforeErase=true;f.state.value=f.state.value.copy(entries=emptyList(),supportsPermanentErasure=true)
        content();browse();compose.onNodeWithTag("recycle-view-one").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-erase").performScrollTo().performClick();compose.onNodeWithTag("recycle-confirm").performClick();pump()
        compose.onNodeWithTag("recycle-cancel").performClick();compose.onNodeWithTag("recycle-refresh").performClick();pump()
        compose.onNodeWithTag("recycle-view-one").performScrollTo().performClick()
        compose.onNodeWithTag("recycle-erasure-pending").performScrollTo().assertExists()
        compose.onNodeWithTag("recycle-restore").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("recycle-erase").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(f.erasureApplied.single(),f.state.value.recycleBin!!.confirmation!!.erasureIntent!!.mutationId);f.failBeforeErase=false}
        compose.onNodeWithTag("recycle-confirm").performClick();pump();compose.runOnIdle {assertTrue(f.erased)}
    }
    @Test fun retainedPermanentEraseButtonCannotRetargetAfterSelectionChangesBeforeRecomposition() {
        f.deleted=true;f.extra=RecycleRecord(StoredEntry("two",1,"另一条合成原文 B",43,"UTC",ProcessingPolicy.AI_NONE),7,true,ManualTagSnapshot("two",0,emptyList()),1,0)
        f.state.value=f.state.value.copy(entries=emptyList(),supportsPermanentErasure=true)
        content();browse();compose.onNodeWithTag("recycle-view-two").performScrollTo().performClick()
        val old=compose.onNodeWithTag("recycle-erase").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            val session=f.state.value.recycleBin!!.session;f.controller.select(session,null);f.controller.select(session,"one");old()
            assertNull(f.state.value.recycleBin!!.confirmation);assertTrue(f.queue.isEmpty());assertTrue(f.erasureApplied.isEmpty())
        }
        compose.onNodeWithTag("recycle-erase").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("one",f.state.value.recycleBin!!.confirmation!!.record.entry.id);assertEquals("two",f.extra!!.entry.id)}
    }
    private fun assertNoPrivateState(value: Any?, forbidden: List<String>) {
        when (value) {
            null, is Number, is Boolean, is Char -> Unit
            is String -> forbidden.forEach { assertFalse("Private recycle state entered SavedState", value.contains(it)) }
            is java.util.UUID -> assertNoPrivateState(value.toString(), forbidden)
            is State<*> -> assertNoPrivateState(value.value, forbidden)
            is ByteArray -> assertNoPrivateState(value.toString(Charsets.UTF_8), forbidden)
            is Map<*, *> -> value.forEach { (key, child) -> assertNoPrivateState(key, forbidden); assertNoPrivateState(child, forbidden) }
            is Iterable<*> -> value.forEach { assertNoPrivateState(it, forbidden) }
            is Array<*> -> value.forEach { assertNoPrivateState(it, forbidden) }
            is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray -> Unit
            is android.os.Bundle -> value.keySet().forEach { assertNoPrivateState(it, forbidden); assertNoPrivateState(value.get(it), forbidden) }
            else -> fail("Unexpected SavedState type requires inspection: " + value.javaClass.name)
        }
    }
    private class Fixture {
        var original = StoredEntry("one", 1, "合成私密原文🙂".repeat(70) + "完整原文尾部", 42, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        val tags = ManualTagSnapshot("one", 1, listOf("合成私密标签"))
        var deleted = false; var generation = 1L; var failRead = false; var failAfterCommit = false
        var extra: RecycleRecord? = null
        var erased=false;var failBeforeErase=false;var failAfterErase=false
        val erasureApplied=mutableListOf<String>();private var erasureReceipt: PermanentErasureReceipt?=null
        val applied = mutableListOf<String>(); private val receipts = mutableMapOf<String, Long>()
        val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, supportsRecycleBin = true,
            text = "合成独立草稿", entries = listOf(original), manualTagsAvailable = true, manualTags = mapOf("one" to tags)))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        private fun record() = RecycleRecord(original, generation, deleted, tags, 2, 1)
        private val bin = object : RecycleBinRepository {
            override fun records(): List<RecycleRecord> { if (failRead) throw RecycleStorageFailure(); return (if (deleted && !erased) listOf(record()) else emptyList()) + listOfNotNull(extra) }
            override fun read(entryId: String): RecycleRecord { check(entryId == "one"); return record() }
            override fun apply(mutation: RecycleMutation): RecycleRecord {
                if(erasureReceipt!=null) throw RecycleConflict()
                applied.add(mutation.mutationId)
                if (receipts[mutation.mutationId] == generation && deleted != mutation.expectedDeleted) return record()
                if (generation != mutation.expectedGeneration || deleted != mutation.expectedDeleted) throw RecycleConflict()
                generation++; deleted = !deleted; original = original.copy(policy = ProcessingPolicy.AI_NONE); receipts[mutation.mutationId] = generation
                if (failAfterCommit) throw RecycleStorageFailure(); return record()
            }
        }
        private val tagPort = object : ManualTagRepository {
            override fun read(entryId: String) = tags
            override fun snapshots() = if (deleted) emptyMap() else mapOf("one" to tags)
            override fun replace(mutation: ManualTagMutation): ManualTagSnapshot = error("Outside UI port")
        }
        private val store = object : EntryRepository {
            override val permanentErasure=object : PermanentErasureRepository {
                override fun read(entryId: String)=erasureReceipt?.takeIf {it.intent.source.sourceId==entryId}
                override fun pending(limit: Int,afterEntryId: String?)=PermanentErasurePage(listOfNotNull(erasureReceipt?.takeUnless {it.complete}),0,null)
                override suspend fun erase(intent: PermanentErasureIntent): PermanentErasureReceipt {
                    erasureApplied+=intent.mutationId
                    val prior=erasureReceipt
                    if(prior!=null && (prior.intent.source!=intent.source || prior.intent.generation!=intent.generation || prior.intent.mutationId!=intent.mutationId)) throw PermanentErasureConflict()
                    if(prior?.complete==true) return prior
                    if(!deleted || intent.source.sourceId!="one" || intent.source.revision!=original.currentRevision.toLong() || intent.generation!=generation) throw PermanentErasureConflict()
                    erasureReceipt=PermanentErasureReceipt(intent,PermanentErasurePhase.PREPARED)
                    if(failBeforeErase) throw PermanentErasureIncomplete()
                    erased=true;erasureReceipt=PermanentErasureReceipt(intent,PermanentErasurePhase.DONE)
                    if(failAfterErase) {failAfterErase=false;throw PermanentErasureIncomplete()}
                    return erasureReceipt!!
                }
            }
            override val recycleBin = bin
            override val manualTags = tagPort
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Outside UI port")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Outside UI port")
            override suspend fun entries() = if (deleted) emptyList() else listOf(original)
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Outside UI port")
        }
        val controller = RecycleBinController({ state.value }, { state.value = it(state.value) }, { queue.addLast(it); true })
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) }
    }
}
