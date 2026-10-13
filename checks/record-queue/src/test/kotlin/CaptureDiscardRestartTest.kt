import android.app.Application
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.LocalRecordsViewModel
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Synthetic durable capture store. Rebuilt adapters read only disk; not Room/SQLCipher evidence. */
class CaptureDiscardRestartTest {
    private class DiskStore(private val root: File, var failAfterDelete: Boolean = false) : EntryRepository {
        private val file = File(root, "synthetic-capture.bin")
        var failDeleteReadback = false
        var failRestoreWrite = false
        var failRestoreAcknowledgement = false
        var deleteAttempted = false
        var restores = 0
        var updates = 0
        override suspend fun readDraft(): TextDraft? {
            if (deleteAttempted && failDeleteReadback) { failDeleteReadback = false; error("synthetic delete readback") }
            return if (!file.exists()) null else DataInputStream(FileInputStream(file)).use {
            TextDraft(it.readUTF(), it.readLong(), it.readUTF())
            }
        }
        override suspend fun updateDraft(text: String): TextDraft {
            updates++
            val old = readDraft()
            return TextDraft(old?.id ?: UUID.randomUUID().toString(), (old?.version ?: 0) + 1, text).also { persist(it) }
        }
        private fun persist(draft: TextDraft) {
            FileOutputStream(file).use { stream ->
                val out = DataOutputStream(stream)
                out.writeUTF(draft.id); out.writeLong(draft.version); out.writeUTF(draft.text); out.flush(); stream.fd.sync()
            }
        }
        override suspend fun discardDraft(expected: TextDraft) {
            val actual = readDraft()
            if (actual != null && actual != expected) throw RecordConflict()
            if (file.exists()) check(file.delete())
            deleteAttempted = true
            if (failAfterDelete) { failAfterDelete = false; error("synthetic lost delete acknowledgement") }
        }
        override suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft {
            restores++
            val current = readDraft()
            if (current == expected || current == replacement) return checkNotNull(current)
            if (current != null) throw RecordConflict()
            if (failRestoreWrite) { failRestoreWrite = false; error("synthetic recovery write failure") }
            persist(replacement)
            if (failRestoreAcknowledgement) { failRestoreAcknowledgement = false; error("synthetic recovery acknowledgement failure") }
            return replacement
        }
        override suspend fun entries() = emptyList<StoredEntry>()
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Unexpected revision")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Unexpected save")
    }
    private suspend fun stop(vm: LocalRecordsViewModel) { vm.scope.cancel(); vm.scope.coroutineContext[Job]!!.join() }
    @Test fun returnAfterCommittedDeleteMustDurablyKeepInputBeforeClosingAndRestarting() = runBlocking { restart(false) }
    @Test fun returnAfterFailedReadbackMustDurablyKeepInputBeforeClosingAndRestarting() = runBlocking { restart(true) }
    private suspend fun restart(readback: Boolean) {
        val root = Files.createTempDirectory("aiyou-capture-return-owned-").toFile()
        try { withTimeout(5000) {
            val firstStore = DiskStore(root, failAfterDelete = !readback).also { it.failDeleteReadback = readback }
            val original = firstStore.updateDraft("合成需要保留的输入")
            val first = LocalRecordsViewModel(Application(), { firstStore })
            try {
                first.state.first { it.ready }
                first.requestCaptureDiscard()
                val request = first.state.value.captureDiscard!!.requestId
                first.confirmCaptureDiscard(request)
                first.state.first { it.captureDiscard?.error != null }
                assertTrue(first.state.value.draftNotice.contains("未确认"))
                assertNull(firstStore.readDraft()) // Delete really committed before its synthetic acknowledgement failed.
                first.cancelCaptureDiscard(request)
                first.state.first { it.captureDiscard == null }
                assertEquals(original.text, first.state.value.text)
            } finally { stop(first) }
            // New repository and ViewModel, no old adapter or state shared.
            val reopened = LocalRecordsViewModel(Application(), { DiskStore(root) })
            try {
                val ready = reopened.state.first { it.ready }
                assertEquals("Returning to editing must not silently lose the retained input on restart", original.text, ready.text)
            } finally { stop(reopened) }
        } } finally { root.deleteRecursively() }
    }

    @Test fun recoveryWriteFailureRemainsVisibleAndCanRetryWithoutDeletingAgain() = runBlocking { recoveryFailure(false) }
    @Test fun lostRecoveryAcknowledgementRetriesSameReplacementIdentity() = runBlocking { recoveryFailure(true) }
    private suspend fun recoveryFailure(afterWrite: Boolean) {
        val root = Files.createTempDirectory("aiyou-capture-recovery-owned-").toFile()
        try { withTimeout(5000) {
            val store = DiskStore(root, failAfterDelete = true)
            val original = store.updateDraft("合成恢复失败输入")
            val vm = LocalRecordsViewModel(Application(), { store })
            try {
                vm.state.first { it.ready }; vm.requestCaptureDiscard()
                val id = vm.state.value.captureDiscard!!.requestId
                vm.confirmCaptureDiscard(id); vm.state.first { it.captureDiscard?.error != null }
                store.failRestoreWrite = !afterWrite; store.failRestoreAcknowledgement = afterWrite
                vm.cancelCaptureDiscard(id)
                vm.state.first { it.captureDiscard?.recoveryFailed == true }
                assertTrue(vm.state.value.draftNotice.contains("未确认"))
                assertTrue(vm.state.value.captureDiscard!!.error!!.contains("退出应用可能丢失"))
                val published = store.readDraft()
                assertEquals(afterWrite, published != null)
                vm.retry() // Ordinary retry must not resurrect or delete anything.
                assertEquals(1, store.restores)
                vm.confirmCaptureDiscard(id) // Now explicitly labelled retry retention, not retry deletion.
                vm.state.first { it.captureDiscard == null }
                val restored = DiskStore(root).readDraft()!!
                assertEquals(original.text, restored.text)
                assertNotEquals(original.id, restored.id)
                if (afterWrite) assertEquals(published, restored)
                assertEquals(2, store.restores)
                vm.confirmCaptureDiscard(id) // Late deletion callback cannot consume the restored identity.
                assertEquals(restored, store.readDraft())
            } finally { stop(vm) }
        } } finally { root.deleteRecursively() }
    }

    @Test fun newDraftBeforeReturnIsNeverOverwrittenAndExplicitUnsavedExitIsAccurate() = runBlocking {
        val root = Files.createTempDirectory("aiyou-capture-conflict-owned-").toFile()
        try { withTimeout(5000) {
            val store = DiskStore(root, failAfterDelete = true)
            val original = store.updateDraft("合成相同文字 ABA")
            val vm = LocalRecordsViewModel(Application(), { store })
            try {
                vm.state.first { it.ready }; vm.requestCaptureDiscard(); val id = vm.state.value.captureDiscard!!.requestId
                vm.confirmCaptureDiscard(id); vm.state.first { it.captureDiscard?.error != null }
                val competing = DiskStore(root).updateDraft(original.text)
                assertNotEquals(original.id, competing.id)
                vm.cancelCaptureDiscard(id)
                vm.state.first { it.captureDiscard?.recoveryFailed == true }
                assertTrue(vm.state.value.captureDiscard!!.conflict)
                assertEquals(competing, DiskStore(root).readDraft())
                vm.cancelCaptureDiscard(id) // UI explicitly labels this return as unsaved.
                assertNull(vm.state.value.captureDiscard)
                assertTrue(vm.state.value.draftNotice.contains("退出应用可能丢失"))
                assertEquals(competing, DiskStore(root).readDraft())
                assertEquals(original.text, vm.state.value.text)
            } finally { stop(vm) }
        } } finally { root.deleteRecursively() }
    }

    @Test fun plainCancelWritesNothingAndDeletionRetryDoesNotRestore() = runBlocking {
        val root = Files.createTempDirectory("aiyou-capture-cancel-owned-").toFile()
        try { withTimeout(5000) {
            val store = DiskStore(root, failAfterDelete = true)
            val original = store.updateDraft("合成确认前取消")
            val vm = LocalRecordsViewModel(Application(), { store })
            try {
                vm.state.first { it.ready }; vm.requestCaptureDiscard(); val first = vm.state.value.captureDiscard!!.requestId
                vm.cancelCaptureDiscard(first)
                assertEquals(original, store.readDraft()); assertEquals(1, store.updates); assertEquals(0, store.restores)
                vm.requestCaptureDiscard(); val second = vm.state.value.captureDiscard!!.requestId
                vm.confirmCaptureDiscard(second); vm.state.first { it.captureDiscard?.error != null }
                vm.confirmCaptureDiscard(second); vm.state.first { it.captureDiscard == null }
                assertNull(DiskStore(root).readDraft()); assertEquals(0, store.restores)
            } finally { stop(vm) }
        } } finally { root.deleteRecursively() }
    }
}
