import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.data.EncryptedOriginalEditStore
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.model.OriginalEditDraftState
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordsViewModel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real product controller + real production encrypted edit files; synthetic formal-record IO. */
class OriginalEditRecoveryTest {
    private val key = ByteArray(32) { (it + 113).toByte() }
    private fun original(revision: Int = 1, text: String = "合成正式原文") = StoredEntry("original", revision, text,
        1000, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private suspend fun vm(repo: EncryptedEntryRepository): LocalRecordsViewModel {
        repo.releaseRevision.complete(Unit)
        val vm = LocalRecordsViewModel(Application(), { repo })
        vm.state.first { it.ready }
        return vm
    }
    private suspend fun stop(vm: LocalRecordsViewModel) { vm.scope.cancel(); vm.scope.coroutineContext[Job]!!.join() }
    private suspend fun edit(vm: LocalRecordsViewModel): Long {
        vm.beginOriginalEdit("original", vm.state.value.entries.single().currentRevision)
        return vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!.sessionId
    }
    private suspend fun saved(vm: LocalRecordsViewModel, text: String) = vm.state.first {
        it.originalEdit?.let { edit -> edit.text == text && edit.draftNotice == "编辑草稿已加密保存在本机" } == true
    }
    private suspend fun exercise(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("aiyou-recovery-owned-").toFile()
        try { withTimeout(10000) { block(root) } } finally { root.deleteRecursively() }
    }
    private fun storage(root: File, hook: (String) -> Unit = {}) = EncryptedOriginalEditStore(root, key.copyOf(), hook)

    @Test fun coldControllerRebuildRestoresEditIndependentlyThenSavesOneNewOriginal() = runBlocking { exercise { root ->
        var encrypted = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val first = vm(repo)
        first.changeText("合成独立此刻草稿")
        first.state.first { it.draftNotice == "草稿已保存在本机" }
        val capture = repo.draft
        val id = edit(first)
        val text = "合成长修改\n" + "🌿".repeat(500)
        first.changeOriginalEdit(id, text); saved(first, text)
        stop(first); encrypted.close()
        encrypted = storage(root)
        // Rebuild the production controller and cipher adapter, keeping only the acknowledged formal IO fixture.
        val reopenedRepo = object : com.t1doo.aiyou.domain.EntryRepository by repo { override val editDrafts = encrypted }
        val second = LocalRecordsViewModel(Application(), { reopenedRepo })
        try {
            val ready = second.state.first { it.ready }
            assertEquals("合成独立此刻草稿", ready.text)
            val ref = ready.pendingEdits.single()
            second.resumeOriginalEdit(ref.entryId, ref.id)
            val restored = second.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
            assertEquals(text, restored.text); assertEquals(1, restored.baseRevision); assertFalse(restored.conflict)
            assertEquals(capture, repo.draft)
            second.saveOriginalEdit(restored.sessionId)
            second.state.first { it.originalEdit == null }
            assertEquals(1, repo.revisionSubmissions.size)
            assertEquals(listOf("合成正式原文", text), repo.revisions("original").map { it.originalText })
            assertEquals(capture, repo.draft)
            assertEquals(OriginalEditDraftState.COMMITTED, encrypted.read("original")!!.state)
            assertTrue(second.state.value.pendingEdits.isEmpty())
        } finally { stop(second); encrypted.close() }
    } }

    @Test fun changedAndRemovedSourcesRestoreAsConflictsAndNeverBecomeReplacementRecords() = runBlocking {
        for (removed in listOf(false, true)) exercise { root ->
            val encrypted = storage(root)
            val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
            val first = vm(repo); val id = edit(first)
            first.changeOriginalEdit(id, "合成待保留修改"); saved(first, "合成待保留修改"); stop(first)
            if (removed) repo.remove("original") else repo.seed(original(2, "合成其他会话新原文"))
            val second = vm(repo)
            try {
                val ref = second.state.value.pendingEdits.single()
                second.resumeOriginalEdit(ref.entryId, ref.id)
                val recovered = second.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
                assertTrue(recovered.conflict); assertEquals("合成待保留修改", recovered.text)
                second.saveOriginalEdit(recovered.sessionId)
                assertTrue(repo.revisionSubmissions.isEmpty())
                second.cancelOriginalEdit(recovered.sessionId); second.state.first { it.originalEdit == null }
                assertEquals(OriginalEditDraftState.DISCARDED, encrypted.read("original")!!.state)
                assertTrue(repo.submissions.isEmpty())
            } finally { stop(second); encrypted.close() }
        }
    }

    @Test fun explicitDiscardQueuesAfterStartedWriteAndLateCallbacksCannotResurrectIt() = runBlocking { exercise { root ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val encrypted = storage(root) { if (it == "encrypted") { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val controller = vm(repo)
        try {
            val id = edit(controller)
            controller.changeOriginalEdit(id, "合成正在写入")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.changeOriginalEdit(id, "合成最后一次修改")
            controller.cancelOriginalEdit(id)
            controller.changeOriginalEdit(id, "合成丢弃后迟到输入")
            release.countDown()
            controller.state.first { it.originalEdit == null }
            val tombstone = encrypted.read("original")!!
            assertEquals(OriginalEditDraftState.DISCARDED, tombstone.state)
            controller.changeOriginalEdit(id, "合成关闭后迟到输入"); controller.saveOriginalEdit(id)
            assertTrue(encrypted.pending().drafts.isEmpty()); assertTrue(repo.revisionSubmissions.isEmpty())
            stop(controller)
            controller.changeOriginalEdit(id, "合成已卸载回调")
            assertEquals(tombstone, encrypted.read("original"))
        } finally { release.countDown(); stop(controller); encrypted.close() }
    } }

    @Test fun writeFailureKeepsInputsAndCorrectedRetryAcknowledgesOnlyCompleteSnapshot() = runBlocking { exercise { root ->
        val fail = AtomicBoolean(true)
        val encrypted = storage(root) { if (it == "encrypted" && fail.getAndSet(false)) error("synthetic edit write failure") }
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val controller = vm(repo)
        try {
            val id = edit(controller)
            controller.changeOriginalEdit(id, "合成失败输入")
            controller.state.first { it.originalEdit?.error != null }
            assertEquals("合成失败输入", controller.state.value.originalEdit!!.text)
            assertEquals("合成正式原文", repo.entries().single().originalText)
            assertNull(encrypted.read("original"))
            controller.changeOriginalEdit(id, "合成纠正后的完整输入"); saved(controller, "合成纠正后的完整输入")
            assertNull(controller.state.value.originalEdit!!.error)
            assertEquals("合成纠正后的完整输入", encrypted.read("original")!!.text)
            controller.saveOriginalEdit(id); controller.state.first { it.originalEdit == null }
            assertEquals(1, repo.revisionSubmissions.size)
        } finally { stop(controller); encrypted.close() }
    } }

    @Test fun lostWriteAcknowledgementCanPersistCorrectedInputWithoutOverwritingCompetingWriter() = runBlocking { exercise { root ->
        val fail = AtomicBoolean(true)
        val encrypted = storage(root) { if (it == "published" && fail.getAndSet(false)) error("synthetic lost acknowledgement") }
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val controller = vm(repo)
        try {
            val id = edit(controller)
            controller.changeOriginalEdit(id, "合成已发布但未确认")
            controller.state.first { it.originalEdit?.error != null }
            assertEquals("合成已发布但未确认", encrypted.read("original")!!.text)
            controller.changeOriginalEdit(id, "合成下一次完整输入"); saved(controller, "合成下一次完整输入")
            assertFalse(controller.state.value.originalEdit!!.conflict)
            assertEquals(2, encrypted.read("original")!!.version)
        } finally { stop(controller); encrypted.close() }
    } }

    @Test fun committedReadbackRetryAndColdReopenNeverAppendTheSameRevisionTwice() = runBlocking { exercise { root ->
        val encrypted = storage(root)
        val repo = EncryptedEntryRepository(failRevisionReadback = true, editDrafts = encrypted).also { it.seed(original()) }
        val controller = vm(repo)
        try {
            val id = edit(controller)
            controller.changeOriginalEdit(id, "合成正式提交"); saved(controller, "合成正式提交")
            controller.saveOriginalEdit(id)
            controller.state.first { it.originalEdit?.awaitingReadback == true && it.originalEdit.error != null }
            assertEquals(OriginalEditDraftState.COMMITTED, encrypted.read("original")!!.state)
            controller.saveOriginalEdit(id); controller.state.first { it.originalEdit == null }
            assertEquals(1, repo.revisionSubmissions.size)
            stop(controller)
            val restarted = vm(repo)
            try { assertTrue(restarted.state.value.pendingEdits.isEmpty()); assertEquals(2, restarted.state.value.entries.single().currentRevision) }
            finally { stop(restarted) }
        } finally { stop(controller); encrypted.close() }
    } }

    @Test fun interruptionBetweenFormalCommitAndDraftFinalizationRestoresConflictWithoutDuplicateWrite() = runBlocking { exercise { root ->
        lateinit var repo: EncryptedEntryRepository
        val encrypted = storage(root) { if (it == "encrypted" && repo.revisionSubmissions.isNotEmpty()) error("synthetic finalization failure") }
        repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val controller = vm(repo)
        val id = edit(controller)
        controller.changeOriginalEdit(id, "合成已提交但草稿待收尾"); saved(controller, "合成已提交但草稿待收尾")
        controller.saveOriginalEdit(id)
        controller.state.first { it.originalEdit?.awaitingReadback == true && it.originalEdit.error != null }
        stop(controller); encrypted.close()
        val reopened = storage(root)
        val wrapper = object : com.t1doo.aiyou.domain.EntryRepository by repo { override val editDrafts = reopened }
        val restarted = LocalRecordsViewModel(Application(), { wrapper })
        try {
            restarted.state.first { it.ready }
            val ref = restarted.state.value.pendingEdits.single()
            restarted.resumeOriginalEdit(ref.entryId, ref.id)
            val restored = restarted.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
            assertTrue(restored.conflict); assertEquals("合成已提交但草稿待收尾", restored.text)
            restarted.saveOriginalEdit(restored.sessionId)
            assertEquals(1, repo.revisionSubmissions.size)
            assertEquals(2, repo.revisions("original").size)
        } finally { stop(restarted); reopened.close() }
    } }

    @Test fun concurrentRestoredEditorsKeepCompetingCipherAndExplicitLocalDiscardCannotDeleteIt() = runBlocking { exercise { root ->
        val firstStore = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = firstStore).also { it.seed(original()) }
        val first = vm(repo); val firstId = edit(first)
        first.changeOriginalEdit(firstId, "合成共享起点"); saved(first, "合成共享起点")
        val secondStore = storage(root)
        val wrapper = object : com.t1doo.aiyou.domain.EntryRepository by repo { override val editDrafts = secondStore }
        val second = LocalRecordsViewModel(Application(), { wrapper })
        try {
            second.state.first { it.ready }; second.beginOriginalEdit("original", 1)
            val secondId = second.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!.sessionId
            first.changeOriginalEdit(firstId, "合成先保存草稿"); saved(first, "合成先保存草稿")
            second.changeOriginalEdit(secondId, "合成另一输入仍保留")
            second.state.first { it.originalEdit?.draftConflict == true }
            assertEquals("合成另一输入仍保留", second.state.value.originalEdit!!.text)
            second.saveOriginalEdit(secondId); assertTrue(repo.revisionSubmissions.isEmpty())
            second.cancelOriginalEdit(secondId)
            assertNull(second.state.value.originalEdit)
            // Closing publishes UI state before its queued index refresh finishes.
            // Join both owned controller jobs before the independent disk assertion.
            stop(first); stop(second)
            assertEquals("合成先保存草稿", secondStore.read("original")!!.text)
        } finally { stop(first); stop(second); firstStore.close(); secondStore.close() }
    } }

    @Test fun removedOrUnsupportedSourceRejectsLateAutosaveWithoutRestoringDeletedContent() = runBlocking {
        for (removed in listOf(false, true)) exercise { root ->
            val encrypted = storage(root)
            val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
            val controller = vm(repo)
            try {
                val id = edit(controller)
                controller.changeOriginalEdit(id, "合成已确认草稿"); saved(controller, "合成已确认草稿")
                val prior = encrypted.read("original")!!
                val target = root.listFiles()!!.single { it.name.endsWith(".edit") }
                val bytes = target.readBytes()
                if (removed) repo.remove("original") else repo.seed(original().copy(policy = ProcessingPolicy.AI_NONE))
                controller.changeOriginalEdit(id, "合成删除后迟到修改")
                controller.state.first { it.originalEdit?.conflict == true && it.originalEdit.error != null }
                assertEquals("合成删除后迟到修改", controller.state.value.originalEdit!!.text)
                assertEquals(prior, encrypted.read("original")); assertArrayEquals(bytes, target.readBytes())
                controller.saveOriginalEdit(id); assertTrue(repo.revisionSubmissions.isEmpty())
            } finally { stop(controller); encrypted.close() }
        }
    }

    @Test fun dormantRecoveredDraftIsNotReplacedByHistoryUntilExplicitDiscardConfirmation() = runBlocking { exercise { root ->
        val encrypted = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also {
            it.seed(original()); it.seed(original(2, "合成版本二原文"))
        }
        val first = vm(repo); val id = edit(first)
        first.changeOriginalEdit(id, "合成已有未保存修改"); saved(first, "合成已有未保存修改")
        val dormant = encrypted.read("original")!!
        stop(first)
        val second = vm(repo)
        try {
            second.beginOriginalHistory("original", 2)
            var history = second.state.first { it.originalHistory?.loading == false }.originalHistory!!
            second.selectOriginalRevision(history.sessionId, 1)
            history = second.state.first { it.originalHistory?.let { h -> !h.loading && h.selectedRevision == 1 } == true }.originalHistory!!
            second.editOriginalHistory(history.sessionId, history.requestId, 1, 2)
            val restored = second.state.first { it.originalEdit?.loadingDraft == false && it.originalHistory == null }.originalEdit!!
            assertEquals("合成已有未保存修改", restored.text); assertEquals(2, restored.sourceRevision)
            assertEquals(dormant, encrypted.read("original"))
            second.beginOriginalHistory("original", 2)
            history = second.state.first { it.originalHistory?.loading == false }.originalHistory!!
            second.selectOriginalRevision(history.sessionId, 1)
            history = second.state.first { it.originalHistory?.let { h -> !h.loading && h.selectedRevision == 1 } == true }.originalHistory!!
            second.editOriginalHistory(history.sessionId, history.requestId, 1, 2)
            history = second.state.value.originalHistory!!
            assertNotNull(history.replacement); assertEquals(dormant, encrypted.read("original"))
            second.confirmHistoryReplacement(history.sessionId, history.requestId, history.replacement!!.id)
            second.state.first { it.originalEdit?.loadingDraft == false && it.originalHistory == null }
            val replacement = encrypted.read("original")!!
            assertNotEquals(dormant.id, replacement.id); assertEquals(1, replacement.sourceRevision)
            assertEquals("合成正式原文", replacement.text)
            try { encrypted.write(dormant, dormant.copy(version = dormant.version + 1, text = "合成旧草稿迟到输入")); fail("Expected conflict") }
            catch (_: OriginalEditDraftConflict) { }
            assertEquals(replacement, encrypted.read("original")); assertTrue(repo.revisionSubmissions.isEmpty())
        } finally { stop(second); encrypted.close() }
    } }

    @Test fun corruptEditCipherDoesNotReplaceOriginalCaptureDraftOrCipherEvidence() = runBlocking { exercise { root ->
        val encrypted = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val first = vm(repo); val id = edit(first)
        first.changeOriginalEdit(id, "合成待损坏草稿"); saved(first, "合成待损坏草稿"); stop(first)
        val target = root.listFiles()!!.single { it.name.endsWith(".edit") }
        val bytes = target.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 3).toByte() }
        target.writeBytes(bytes)
        val restarted = vm(repo)
        try {
            assertNotNull(restarted.state.value.editDraftError)
            restarted.beginOriginalEdit("original", 1)
            restarted.state.first { it.originalEdit?.loadingDraft == false }
            assertTrue(restarted.state.value.originalEdit!!.conflict)
            restarted.saveOriginalEdit(restarted.state.value.originalEdit!!.sessionId)
            assertTrue(repo.revisionSubmissions.isEmpty())
            assertEquals("合成正式原文", repo.entries().single().originalText)
            assertArrayEquals(bytes, target.readBytes())
        } finally { stop(restarted); encrypted.close() }
    } }
    @Test fun lostFirstPublicationAcknowledgementThenDirectDiscardSurvivesColdReopen() = runBlocking { exercise { root ->
        val fail = AtomicBoolean(true)
        val encrypted = storage(root) { if (it == "published" && fail.getAndSet(false)) error("synthetic lost acknowledgement") }
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val controller = vm(repo)
        val id = edit(controller)
        controller.changeOriginalEdit(id, "合成明确丢弃的未确认输入")
        controller.state.first { it.originalEdit?.error != null }
        val attempted = encrypted.read("original")!!
        controller.cancelOriginalEdit(id)
        controller.state.first { it.originalEdit == null }
        controller.changeOriginalEdit(id, "合成丢弃后迟到输入")
        stop(controller); encrypted.close()
        storage(root).use { cold ->
            assertEquals(OriginalEditDraftState.DISCARDED, cold.read("original")!!.state)
            assertTrue(cold.pending().drafts.isEmpty())
            try { cold.write(attempted, attempted.copy(version = 2, text = "合成旧写入")); fail("Expected conflict") }
            catch (_: OriginalEditDraftConflict) { }
        }
        assertTrue(repo.revisionSubmissions.isEmpty())
    } }

    @Test fun competingDraftAfterFormalCommitDoesNotBlockConfirmationOrRepeatRevision() = runBlocking { exercise { root ->
        val encrypted = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        repo.releaseRevision.complete(Unit)
        lateinit var competing: com.t1doo.aiyou.model.OriginalEditDraft
        val wrapped = object : com.t1doo.aiyou.domain.EntryRepository by repo {
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
                val committed = repo.revise(entryId, expectedRevision, text, createdAtMillis)
                val acknowledged = encrypted.read(entryId)!!
                competing = encrypted.write(acknowledged, acknowledged.copy(version = acknowledged.version + 1,
                    text = "合成竞争会话的未保存修改"))
                return committed
            }
        }
        val controller = LocalRecordsViewModel(Application(), { wrapped })
        try {
            controller.state.first { it.ready }
            val id = edit(controller)
            controller.changeOriginalEdit(id, "合成正式提交一次"); saved(controller, "合成正式提交一次")
            controller.saveOriginalEdit(id)
            controller.state.first { it.originalEdit == null }
            assertEquals(1, repo.revisionSubmissions.size)
            assertEquals(listOf("合成正式原文", "合成正式提交一次"), repo.revisions("original").map { it.originalText })
            assertEquals(competing, encrypted.read("original"))
            assertEquals(competing.id, controller.state.value.pendingEdits.single().id)
            assertTrue(controller.state.value.originalEditNotice!!.contains("另一会话"))
            controller.saveOriginalEdit(id)
            controller.resumeOriginalEdit(competing.entryId, competing.id)
            val recovered = controller.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
            assertTrue(recovered.conflict); assertEquals(competing.text, recovered.text)
            controller.saveOriginalEdit(recovered.sessionId)
            assertEquals(1, repo.revisionSubmissions.size)
        } finally { stop(controller); encrypted.close() }
    } }

    @Test fun conflictCloseReleasesOldBindingBeforeHistoryRestoresCompetingDormantDraft() = runBlocking { exercise { root ->
        val encrypted = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also {
            it.seed(original()); it.seed(original(2, "合成版本二"))
        }
        val controller = vm(repo)
        try {
            val id = edit(controller)
            controller.changeOriginalEdit(id, "合成旧会话"); saved(controller, "合成旧会话")
            val prior = encrypted.read("original")!!
            val competing = encrypted.write(prior, prior.copy(id = java.util.UUID.randomUUID().toString(), version = 1,
                text = "合成另一身份待恢复"))
            controller.changeOriginalEdit(id, "合成冲突输入")
            controller.state.first { it.originalEdit?.draftConflict == true }
            controller.cancelOriginalEdit(id)
            controller.beginOriginalHistory("original", 2)
            var history = controller.state.first { it.originalHistory?.loading == false }.originalHistory!!
            controller.selectOriginalRevision(history.sessionId, 1)
            history = controller.state.first { it.originalHistory?.let { h -> !h.loading && h.selectedRevision == 1 } == true }.originalHistory!!
            controller.editOriginalHistory(history.sessionId, history.requestId, 1, 2)
            val restored = controller.state.first { it.originalHistory == null && it.originalEdit?.loadingDraft == false }.originalEdit!!
            assertFalse(restored.conflict); assertEquals(competing.text, restored.text)
            assertEquals(competing, encrypted.read("original"))
            assertEquals(competing.id, controller.state.value.pendingEdits.single().id)
        } finally { stop(controller); encrypted.close() }
    } }

    @Test fun staleRecoveryIdentityRefreshesIndexWithoutDeletingReplacementDraft() = runBlocking { exercise { root ->
        val encrypted = storage(root)
        val repo = EncryptedEntryRepository(editDrafts = encrypted).also { it.seed(original()) }
        val first = vm(repo); val id = edit(first)
        first.changeOriginalEdit(id, "合成恢复身份A"); saved(first, "合成恢复身份A"); stop(first)
        val controller = vm(repo)
        try {
            val ref = controller.state.value.pendingEdits.single()
            val prior = encrypted.read("original")!!
            val replacement = encrypted.write(prior, prior.copy(id = java.util.UUID.randomUUID().toString(), version = 1,
                text = "合成恢复身份B"))
            controller.resumeOriginalEdit(ref.entryId, ref.id)
            controller.state.first { it.originalEdit?.draftConflict == true && it.pendingEdits.singleOrNull()?.id == replacement.id }
            controller.cancelOriginalEdit(controller.state.value.originalEdit!!.sessionId)
            controller.resumeOriginalEdit(replacement.entryId, replacement.id)
            val recovered = controller.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
            assertEquals(replacement.text, recovered.text); assertFalse(recovered.conflict)
            assertEquals(replacement, encrypted.read("original"))
        } finally { stop(controller); encrypted.close() }
    } }

    private suspend fun lostAcknowledgementDurabilityRetry(root: File, terminal: Boolean) {
        val publishFailure = AtomicBoolean(!terminal)
        val failConfirmation = AtomicBoolean(true)
        val confirmations = java.util.concurrent.atomic.AtomicInteger()
        val encrypted = storage(root) { if (it == "published" && publishFailure.getAndSet(false)) error("synthetic lost publication acknowledgement") }
        val guarded = object : com.t1doo.aiyou.domain.OriginalEditDraftStore by encrypted {
            override suspend fun write(expected: com.t1doo.aiyou.model.OriginalEditDraft?, next: com.t1doo.aiyou.model.OriginalEditDraft): com.t1doo.aiyou.model.OriginalEditDraft {
                if (expected == next) {
                    confirmations.incrementAndGet()
                    if (failConfirmation.get()) error("synthetic directory confirmation unavailable")
                }
                return encrypted.write(expected, next)
            }
        }
        val repo = EncryptedEntryRepository(editDrafts = guarded).also { it.seed(original()) }
        val controller = vm(repo)
        try {
            val id = edit(controller)
            val input = "合成等待持久确认的输入"
            controller.changeOriginalEdit(id, input)
            if (terminal) {
                saved(controller, input)
                publishFailure.set(true)
                controller.cancelOriginalEdit(id)
                controller.state.first { it.originalEdit?.let { e -> !e.discarding && e.error != null } == true }
            } else controller.state.first { it.originalEdit?.error != null }
            val target = root.listFiles()!!.single { it.name.endsWith(".edit") }
            val bytes = target.readBytes()
            if (terminal) controller.cancelOriginalEdit(id) else controller.changeOriginalEdit(id, input)
            controller.state.first { confirmations.get() == 1 && it.originalEdit?.let { e -> !e.discarding && e.error != null } == true }
            assertEquals(input, controller.state.value.originalEdit!!.text)
            assertNotEquals("编辑草稿已加密保存在本机", controller.state.value.originalEdit!!.draftNotice)
            assertArrayEquals(bytes, target.readBytes())
            failConfirmation.set(false)
            if (terminal) {
                controller.cancelOriginalEdit(id); controller.state.first { it.originalEdit == null }
                assertTrue(encrypted.pending().drafts.isEmpty())
                assertEquals(OriginalEditDraftState.DISCARDED, encrypted.read("original")!!.state)
            } else {
                controller.changeOriginalEdit(id, input); saved(controller, input)
                assertNull(controller.state.value.originalEdit!!.error)
                assertEquals(input, encrypted.read("original")!!.text)
            }
            assertEquals(2, confirmations.get()); assertArrayEquals(bytes, target.readBytes())
            assertTrue(repo.revisionSubmissions.isEmpty())
        } finally { stop(controller); encrypted.close() }
    }

    @Test fun publishedActiveRetryRequiresDurabilityConfirmationBeforeSavedNotice() = runBlocking {
        exercise { lostAcknowledgementDurabilityRetry(it, false) }
    }

    @Test fun publishedDiscardRetryCannotCloseUntilDurabilityConfirmationSucceeds() = runBlocking {
        exercise { lostAcknowledgementDurabilityRetry(it, true) }
    }

}
