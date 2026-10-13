package com.t1doo.aiyou

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.t1doo.aiyou.data.EncryptedOriginalEditStore
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.records.LocalRecordsViewModel
import com.t1doo.aiyou.ui.Destination
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real App, ViewModel and AES-GCM files; formal-record DB is an explicit JVM IO fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class OriginalEditRecoveryPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("aiyou-edit-compose-owned-").toFile()
    private val key = ByteArray(32) { (it + 43).toByte() }
    private lateinit var cipher: EncryptedOriginalEditStore
    private lateinit var vm: LocalRecordsViewModel
    private val input = "合成未保存修改🌿\n" + (1..15).joinToString("\n") { "合成长行 $it" }
    private val base = "合成正式原文"

    @After fun close() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        if (::cipher.isInitialized) cipher.close()
        root.deleteRecursively()
    }

    private class FormalFixture(override val editDrafts: EncryptedOriginalEditStore, base: String) : EntryRepository {
        var record: StoredEntry? = StoredEntry("original", 1, base, 1000, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
        var capture = TextDraft("capture-independent", 3, "合成此刻独立草稿")
        var writes = 0
        val history = mutableListOf(revision(1, base))
        override suspend fun readDraft() = capture
        override suspend fun updateDraft(text: String) = capture.copy(version = capture.version + 1, text = text).also { capture = it }
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Capture save not requested")
        override suspend fun entries() = listOfNotNull(record)
        override suspend fun revisions(entryId: String) = history.toList()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
            val current = record ?: throw RecordConflict()
            if (current.currentRevision != expectedRevision) throw RecordConflict()
            writes++
            history += revision(expectedRevision + 1, text)
            return current.copy(currentRevision = expectedRevision + 1, originalText = text).also { record = it }
        }
        companion object {
            fun revision(number: Int, text: String) = OriginalRevision("original", number, text,
                MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }, 1000)
        }
    }

    private fun seed() = OriginalEditDraft(UUID.randomUUID().toString(), "original", 1, 1, 1, base, input).also { draft ->
        // Seed through the actual production cipher and then cold rebuild that adapter.
        EncryptedOriginalEditStore(root, key.copyOf()).use { runBlocking { it.write(null, draft) } }
    }

    private fun start(repo: FormalFixture) {
        vm = LocalRecordsViewModel(ApplicationProvider.getApplicationContext<Application>(), { repo })
        compose.setContent {
            val local by vm.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { Box(Modifier.size(320.dp, 640.dp)) {
                    AIyouApp(previewDestination = Destination.MEMORIES, local = local,
                        onDraftChange = vm::changeText, onSave = vm::save, onRetry = vm::retry,
                        onBeginOriginalEdit = vm::beginOriginalEdit, onOriginalEditChange = vm::changeOriginalEdit,
                        onOriginalEditSave = vm::saveOriginalEdit, onOriginalEditCancel = vm::cancelOriginalEdit,
                        onOriginalEditKeep = vm::keepOriginalEdit,
                        onResumeOriginalEdit = vm::resumeOriginalEdit)
                } }
            }
        }
        compose.waitUntil(5000) { vm.state.value.ready }
    }

    private fun scrollRecovery(entryId: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("resume-edit-$entryId"))
    }

    private fun scrollIndexText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun resume() {
        scrollRecovery("original")
        val button = compose.onNodeWithTag("resume-edit-original").performScrollTo()
        button.assertIsDisplayed()
        assertTrue(button.getUnclippedBoundsInRoot().height >= 48.dp)
        button.performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit?.loadingDraft == false }
        compose.onNodeWithTag("original-edit-text").assertTextContains(input)
    }

    @Test fun narrowLargeFontExplicitKeepReturnsToOriginalAndResumesWithoutFormalWrite() {
        seed(); cipher = EncryptedOriginalEditStore(root, key.copyOf())
        val repo = FormalFixture(cipher, base)
        val capture = repo.capture
        start(repo); resume()
        val keep = compose.onNodeWithTag("original-edit-keep").performScrollTo()
        keep.assertIsDisplayed().assertIsEnabled()
        assertTrue(keep.getUnclippedBoundsInRoot().height >= 48.dp)
        keep.performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }
        assertEquals(0, repo.writes); assertEquals(base, repo.record!!.originalText)
        assertEquals(capture, repo.capture)
        assertEquals(OriginalEditDraftState.ACTIVE, runBlocking { cipher.read("original") }!!.state)
        compose.onNodeWithText("返回列表").performClick()
        resume()
        compose.onNodeWithTag("original-edit-text").assertTextContains(input)
        compose.onNodeWithTag("original-edit-save").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }
        assertEquals(1, repo.writes)
        assertEquals(listOf(base, input), repo.history.map { it.originalText })
        assertEquals(capture, repo.capture)
    }

    @Test fun explicitKeepConfirmationFailureStaysInEditorAndVisibleRetryThenReturns() {
        seed()
        val fail = AtomicBoolean(false)
        cipher = EncryptedOriginalEditStore(root, key.copyOf()) { if (it == "authenticated" && fail.getAndSet(false))
            error("synthetic keep confirmation read failure") }
        val repo = FormalFixture(cipher, base)
        start(repo); resume()
        fail.set(true)
        compose.onNodeWithTag("original-edit-keep").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit?.let { !it.keeping && it.error != null } == true }
        assertEquals(input, vm.state.value.originalEdit!!.text)
        compose.onNodeWithText("保留草稿尚未确认，当前输入仍在编辑中。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("original-edit-keep").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }
        assertEquals(0, repo.writes)
        assertEquals(input, runBlocking { cipher.read("original") }!!.text)
    }

    @Test fun narrowLargeFontColdRecoveryCorrectionAndSaveKeepCaptureAndImmutableOriginals() {
        seed(); cipher = EncryptedOriginalEditStore(root, key.copyOf())
        val repo = FormalFixture(cipher, base)
        val capture = repo.capture
        start(repo); resume()
        val corrected = input + "\n合成用户纠正"
        compose.onNodeWithTag("original-edit-text").performTextReplacement(corrected)
        compose.waitUntil(5000) { vm.state.value.originalEdit?.draftNotice == "编辑草稿已加密保存在本机" }
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }
        assertEquals(1, repo.writes)
        assertEquals(listOf(base, corrected), repo.history.map { it.originalText })
        assertEquals(capture, repo.capture); assertEquals(capture.text, vm.state.value.text)
        assertEquals(OriginalEditDraftState.COMMITTED, runBlocking { cipher.read("original") }!!.state)
        assertTrue(vm.state.value.pendingEdits.isEmpty())
    }

    @Test fun missingSourceRecoveryRetainsTextAndExplicitDiscardCannotResurrectIt() {
        val draft = seed(); cipher = EncryptedOriginalEditStore(root, key.copyOf())
        val repo = FormalFixture(cipher, base).also { it.record = null }
        start(repo); resume()
        assertTrue(vm.state.value.originalEdit!!.conflict)
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("original-edit-cancel").performScrollTo().performClick()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(input, vm.state.value.originalEdit!!.text)
        compose.onNodeWithTag("original-edit-cancel").performScrollTo().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }
        assertEquals(0, repo.writes); assertEquals("合成此刻独立草稿", repo.capture.text)
        val marker = runBlocking { cipher.read("original") }!!
        assertEquals(OriginalEditDraftState.DISCARDED, marker.state)
        try { runBlocking { cipher.write(draft, draft.copy(version = 2, text = "合成迟到写入")) }; fail("Expected rejected late write") }
        catch (_: com.t1doo.aiyou.domain.OriginalEditDraftConflict) { }
        assertEquals(marker, runBlocking { cipher.read("original") })
    }

    @Test fun actualCipherWriteFailureRemainsVisibleAndCorrectedRetryPreservesCompleteInput() {
        seed()
        val fail = AtomicBoolean(true)
        cipher = EncryptedOriginalEditStore(root, key.copyOf()) { if (it == "encrypted" && fail.getAndSet(false)) error("synthetic write failure") }
        val repo = FormalFixture(cipher, base)
        start(repo); resume()
        val corrected = "合成第一次纠正失败🌿"
        compose.onNodeWithTag("original-edit-text").performTextReplacement(corrected)
        compose.waitUntil(5000) { vm.state.value.originalEdit?.error != null }
        assertEquals(corrected, vm.state.value.originalEdit!!.text)
        assertEquals(input, runBlocking { cipher.read("original") }!!.text)
        compose.onNodeWithText("修改尚未保存").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("original-edit-text").performScrollTo().assertIsDisplayed().assertTextContains(corrected)
        val retry = corrected + "\n合成纠正后重试"
        compose.onNodeWithTag("original-edit-text").performTextReplacement(retry)
        compose.waitUntil(5000) { vm.state.value.originalEdit?.draftNotice == "编辑草稿已加密保存在本机" }
        assertNull(vm.state.value.originalEdit!!.error)
        assertEquals(retry, runBlocking { cipher.read("original") }!!.text)
        assertEquals(base, repo.record!!.originalText); assertEquals(0, repo.writes)
    }
    private fun seedPartialIndex(): Pair<File, ByteArray> {
        seed()
        val healthy = OriginalEditDraft(UUID.randomUUID().toString(), "healthy-secondary", 1, 1, 1,
            "合成次要源原文", "合成第二份健康修改")
        val damaged = healthy.copy(id = UUID.randomUUID().toString(), entryId = "damaged", text = "合成损坏私文不可泄露")
        EncryptedOriginalEditStore(root, key.copyOf()).use { s -> runBlocking { s.write(null, healthy); s.write(null, damaged) } }
        val target = File(root, MessageDigest.getInstance("SHA-256").digest("damaged".toByteArray()).joinToString("") { "%02x".format(it) } + ".edit")
        val bytes = target.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        target.writeBytes(bytes)
        return target to bytes
    }

    @Test fun partialIndexWarningIsVisibleAndHealthyRecoveryRemainsUsableWithoutShowingDamagedText() {
        val (target, bytes) = seedPartialIndex()
        cipher = EncryptedOriginalEditStore(root, key.copyOf()); val repo = FormalFixture(cipher, base)
        start(repo)
        scrollIndexText("1 份编辑草稿无法读取")
        compose.onNodeWithText("1 份编辑草稿无法读取").performScrollTo().assertIsDisplayed()
        assertEquals(2, vm.state.value.pendingEdits.size)
        for (entryId in listOf("healthy-secondary", "original")) {
            scrollRecovery(entryId)
            compose.onNodeWithTag("resume-edit-$entryId").assertIsDisplayed().assertIsEnabled()
        }
        compose.onNodeWithTag("resume-edit-damaged").assertDoesNotExist()
        compose.onNodeWithText("合成损坏私文不可泄露").assertDoesNotExist()
        resume()
        compose.onNodeWithTag("original-edit-save").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }
        assertEquals(1, repo.writes); assertEquals("合成此刻独立草稿", repo.capture.text)
        assertArrayEquals(bytes, target.readBytes()); assertEquals(1, vm.state.value.editDraftIssueCount)
    }

    @Test fun wrongKeyIndexHasExplicitVisibleErrorAndNoRecoveryButtons() {
        val (target, bytes) = seedPartialIndex()
        cipher = EncryptedOriginalEditStore(root, ByteArray(32) { 12 }); val repo = FormalFixture(cipher, base)
        start(repo)
        scrollIndexText("3 份编辑草稿无法读取")
        compose.onNodeWithText("3 份编辑草稿无法读取").performScrollTo().assertIsDisplayed()
        scrollIndexText(vm.state.value.editDraftError!!)
        compose.onNodeWithText(vm.state.value.editDraftError!!).performScrollTo().assertIsDisplayed()
        assertTrue(vm.state.value.editDraftError!!.contains("密钥不匹配"))
        compose.onAllNodesWithText("恢复编辑草稿").assertCountEquals(0)
        compose.onNodeWithText("合成损坏私文不可泄露").assertDoesNotExist()
        assertTrue(vm.state.value.ready); assertEquals(base, repo.record!!.originalText)
        assertEquals("合成此刻独立草稿", vm.state.value.text); assertEquals(0, repo.writes)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun globalIndexFailureDisablesExistingButtonsAndVisibleRetryRestoresHealthyRecovery() {
        val (target, bytes) = seedPartialIndex(); val fail = AtomicBoolean(false)
        cipher = EncryptedOriginalEditStore(root, key.copyOf()) { if (it == "listing" && fail.get()) throw java.io.IOException("synthetic private IO detail") }
        val repo = FormalFixture(cipher, base); start(repo)
        fail.set(true)
        scrollIndexText("重试读取")
        compose.onNodeWithText("重试读取").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.state.value.editDraftIndexAvailable }
        scrollIndexText("编辑草稿需要处理")
        compose.onNodeWithText("编辑草稿需要处理").performScrollTo().assertIsDisplayed()
        scrollRecovery("original")
        compose.onNodeWithTag("resume-edit-original").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("synthetic private IO detail").assertDoesNotExist()
        fail.set(false)
        scrollIndexText("重试读取")
        compose.onNodeWithText("重试读取").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.editDraftIndexAvailable }
        scrollIndexText("1 份编辑草稿无法读取")
        compose.onNodeWithText("1 份编辑草稿无法读取").performScrollTo().assertIsDisplayed()
        scrollRecovery("original")
        compose.onNodeWithTag("resume-edit-original").performScrollTo().assertIsEnabled()
        resume(); assertEquals(input, vm.state.value.originalEdit!!.text)
        assertArrayEquals(bytes, target.readBytes()); assertEquals(0, repo.writes)
    }

}
