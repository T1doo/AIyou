import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.data.EncryptedOriginalEditStore
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.OriginalEditDraftBusy
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftIndex
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordsViewModel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Production cipher/file index and actual controller; formal DB is explicitly synthetic IO. */
class OriginalEditIndexIsolationTest {
    private val key = ByteArray(32) { (it + 19).toByte() }
    private fun draft(id: String) = OriginalEditDraft(UUID.randomUUID().toString(), id, 1, 1, 1,
        "合成原文-$id", "合成未提交修改-$id")
    private fun entry(d: OriginalEditDraft) = StoredEntry(d.entryId, 1, d.originalText, 1000, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private fun file(root: File, id: String) = File(root, MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".edit")
    private fun damage(root: File, id: String): ByteArray = file(root, id).readBytes().also {
        it[it.lastIndex] = (it.last().toInt() xor 1).toByte(); file(root, id).writeBytes(it)
    }
    private fun hashes(root: File) = root.listFiles()!!.filter { it.name.endsWith(".edit") }.associate {
        it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    private fun store(root: File, hook: (String) -> Unit = {}) = EncryptedOriginalEditStore(root, key.copyOf(), hook)
    private suspend fun exercise(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("aiyou-index-owned-").toFile()
        try { withTimeout(10000) { block(root) } } finally { root.deleteRecursively() }
    }
    private suspend fun seed(root: File, names: List<String> = listOf("healthy-one", "healthy-two", "damaged")): List<OriginalEditDraft> =
        names.map(::draft).also { drafts -> store(root).use { s -> drafts.forEach { s.write(null, it) } } }
    private suspend fun controller(repo: EntryRepository): LocalRecordsViewModel = LocalRecordsViewModel(Application(), { repo }).also {
        it.state.first { s -> s.ready }
    }
    private suspend fun stop(vm: LocalRecordsViewModel) { vm.scope.cancel(); vm.scope.coroutineContext[Job]!!.join() }
    private suspend inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected " + T::class.java.simpleName) } catch (f: Throwable) { if (f !is T) throw f }
    }
    private suspend fun replace(root: File, old: OriginalEditDraft): OriginalEditDraft {
        val replacement = old.copy(id = UUID.randomUUID().toString(), text = "合成替换后的独立草稿")
        val staged = File(root, "owned-replacement")
        store(staged).use { it.write(null, replacement) }
        val target = file(root, old.entryId)
        val modified = Files.getLastModifiedTime(target.toPath())
        Files.setLastModifiedTime(file(staged, old.entryId).toPath(), modified)
        Files.move(file(staged, old.entryId).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return replacement
    }

    @Test fun twoHealthyAndOneCorruptCipherColdIndexIsolatesAndPreservesEveryFile() = runBlocking { exercise { root ->
        val drafts = seed(root); val damaged = damage(root, "damaged"); val before = hashes(root)
        store(root).use { s ->
            val index = s.pending()
            assertEquals(drafts.take(2).map { it.id }.toSet(), index.drafts.map { it.id }.toSet())
            assertEquals(1, index.integrityFailureCount); assertEquals(0, index.changedDuringReadCount)
            assertEquals(drafts[0], s.read(drafts[0].entryId)); assertEquals(drafts[1], s.read(drafts[1].entryId))
        }
        assertEquals(before, hashes(root)); assertArrayEquals(damaged, file(root, "damaged").readBytes())
    } }

    @Test fun multipleCorruptAndWrongKeyAreCountedWithoutPretendingStoreIsEmpty() = runBlocking { exercise { root ->
        seed(root, listOf("one", "two", "three", "four")); damage(root, "three"); damage(root, "four")
        val before = hashes(root)
        store(root).use { s -> assertEquals(2, s.pending().drafts.size); assertEquals(2, s.pending().unreadableCount) }
        EncryptedOriginalEditStore(root, ByteArray(32) { 6 }).use { wrong ->
            val index = wrong.pending()
            assertTrue(index.drafts.isEmpty()); assertEquals(4, index.integrityFailureCount)
        }
        assertEquals(before, hashes(root))
        store(root).use { s -> assertEquals(2, s.pending().drafts.size); assertEquals(2, s.pending().unreadableCount) }
    } }

    @Test fun directoryAndListingFailuresPropagateInsteadOfReturningPartialOrEmptySuccess() = runBlocking {
        for (stage in listOf("listing", "listed")) exercise { root ->
            seed(root); val before = hashes(root)
            val moved = File(root.parentFile, root.name + "-owned-moved")
            check(!moved.exists()); val armed = AtomicBoolean(true)
            val s = store(root) { if (it == stage && armed.getAndSet(false)) {
                Files.move(root.toPath(), moved.toPath()); root.writeText("synthetic directory failure")
            } }
            try {
                fails<IOException> { s.pending() }
            } finally { check(root.isFile); check(root.delete()); Files.move(moved.toPath(), root.toPath()) }
            s.use { assertEquals(3, it.pending().drafts.size) }
            assertEquals(before, hashes(root))
        }
    }

    @Test fun fileIoFailureRemainsGlobalAndCannotBeMisclassifiedAsAuthenticationDamage() = runBlocking { exercise { root ->
        seed(root); val before = hashes(root)
        store(root) { if (it == "before-read") throw IOException("synthetic unreadable device boundary") }.use { s ->
            fails<IOException> { s.pending() }
        }
        assertEquals(before, hashes(root)); store(root).use { assertEquals(3, it.pending().drafts.size) }
    } }

    @Test fun indexLockAndFullSnapshotCasRejectConcurrentAndLateWritesWithoutChangingDrafts() = runBlocking { exercise { root ->
        val drafts = seed(root); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val armed = AtomicBoolean(true); val answer = AtomicReference<OriginalEditDraftIndex>(); val failure = AtomicReference<Throwable>()
        val s = store(root) { if (it == "listed" && armed.getAndSet(false)) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
        val reader = Thread { try { answer.set(runBlocking { s.pending() }) } catch (f: Throwable) { failure.set(f) } }
        store(root).use { writer ->
            try {
                reader.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
                fails<OriginalEditDraftBusy> { writer.write(drafts[0], drafts[0].copy(version = 2, text = "合成同时写入")) }
            } finally { release.countDown(); reader.join(3000) }
            assertFalse(reader.isAlive); assertNull(failure.get()); assertEquals(3, answer.get().drafts.size)
            val changed = writer.write(drafts[0], drafts[0].copy(version = 2, text = "合成后续合法修改"))
            val before = hashes(root)
            fails<OriginalEditDraftConflict> { writer.write(drafts[0], drafts[0].copy(version = 2, text = "合成迟到旧输入")) }
            assertEquals(changed, writer.read(changed.entryId)); assertEquals(before, hashes(root))
        }
        s.close()
    } }

    @Test fun replacementDuringAuthenticationIsIsolatedEvenWithPreservedMtimeAndOtherDraftsStayReadable() = runBlocking { exercise { root ->
        val drafts = seed(root); val first = drafts.minBy { file(root, it.entryId).name }
        val armed = AtomicBoolean(true); var replacement: OriginalEditDraft? = null
        store(root) { if (it == "authenticated" && armed.getAndSet(false)) replacement = runBlocking { replace(root, first) } }.use { s ->
            val index = s.pending()
            assertEquals(2, index.drafts.size); assertEquals(1, index.changedDuringReadCount); assertEquals(0, index.integrityFailureCount)
            assertFalse(index.drafts.any { it.id == first.id }); assertFalse(index.drafts.any { it.id == replacement!!.id })
            assertEquals(replacement, s.read(first.entryId)); assertEquals(3, s.pending().drafts.size)
        }
    } }

    @Test fun actualControllerRestoresHealthyAndUnavailableSourcesWhileCorruptCipherRemainsUntouched() = runBlocking {
        for (removed in listOf(false, true)) exercise { root ->
            val drafts = seed(root); val broken = damage(root, "damaged")
            val s = store(root); val repo = EncryptedEntryRepository(editDrafts = s).also { r -> drafts.forEach { r.seed(entry(it)) }; r.releaseRevision.complete(Unit) }
            repo.updateDraft("合成独立此刻输入")
            if (removed) repo.remove("healthy-two") else repo.seed(entry(drafts[1]).copy(currentRevision = 2, originalText = "合成新源版本"))
            val vm = controller(repo)
            try {
                assertEquals(2, vm.state.value.pendingEdits.size); assertEquals(1, vm.state.value.editDraftIssueCount)
                assertTrue(vm.state.value.editDraftIndexAvailable); assertNotNull(vm.state.value.editDraftError)
                assertTrue(vm.state.value.editDraftError!!.contains("可先恢复下方已校验的草稿。"))
                assertEquals("合成独立此刻输入", vm.state.value.text)
                vm.resumeOriginalEdit(drafts[1].entryId, drafts[1].id)
                val conflicted = vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
                assertTrue(conflicted.conflict); assertEquals(drafts[1].text, conflicted.text)
                vm.saveOriginalEdit(conflicted.sessionId); assertTrue(repo.revisionSubmissions.isEmpty())
                vm.cancelOriginalEdit(conflicted.sessionId); vm.state.first { it.originalEdit == null }
                vm.resumeOriginalEdit(drafts[0].entryId, drafts[0].id)
                val healthy = vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
                assertFalse(healthy.conflict); assertEquals(drafts[0].text, healthy.text)
                vm.saveOriginalEdit(healthy.sessionId); vm.state.first { it.originalEdit == null }
                assertEquals(1, repo.revisionSubmissions.size); assertEquals("合成独立此刻输入", repo.draft!!.text)
                assertArrayEquals(broken, file(root, "damaged").readBytes())
            } finally { stop(vm); s.close() }
        }
    }

    @Test fun wrongKeyControllerShowsSafeFailureCountWithFormalAndCaptureStillAvailable() = runBlocking { exercise { root ->
        val drafts = seed(root); val before = hashes(root)
        val wrong = EncryptedOriginalEditStore(root, ByteArray(32) { 9 })
        val repo = EncryptedEntryRepository(editDrafts = wrong).also { r -> drafts.forEach { r.seed(entry(it)) } }
        repo.updateDraft("合成独立输入")
        val vm = controller(repo)
        try {
            assertTrue(vm.state.value.ready); assertEquals(3, vm.state.value.entries.size)
            assertEquals(3, vm.state.value.editDraftIssueCount); assertTrue(vm.state.value.pendingEdits.isEmpty())
            assertNotNull(vm.state.value.editDraftError); assertTrue(vm.state.value.editDraftError!!.contains("密钥不匹配"))
            assertTrue(vm.state.value.editDraftError!!.contains("当前没有可安全恢复的编辑草稿，请保留数据并重试读取。"))
            assertFalse(vm.state.value.editDraftError!!.contains("下方"))
            drafts.forEach { assertFalse(vm.state.value.editDraftError!!.contains(it.text)); assertFalse(vm.state.value.editDraftError!!.contains(it.entryId)) }
            assertEquals("合成独立输入", vm.state.value.text); assertEquals(before, hashes(root))
        } finally { stop(vm); wrong.close() }
    } }

    @Test fun globalIndexRefreshFailureDisablesStaleRecoveryUntilRealIndexRetrySucceeds() = runBlocking { exercise { root ->
        val drafts = seed(root); damage(root, "damaged"); val fail = AtomicBoolean(false)
        val s = store(root) { if (it == "listing" && fail.get()) throw IOException("synthetic private failure detail") }
        val repo = EncryptedEntryRepository(editDrafts = s).also { r -> drafts.forEach { r.seed(entry(it)) } }
        val vm = controller(repo)
        try {
            val before = hashes(root); fail.set(true); vm.retry()
            vm.state.first { !it.editDraftIndexAvailable }
            assertEquals(2, vm.state.value.pendingEdits.size); assertTrue(vm.state.value.ready)
            assertFalse(vm.state.value.editDraftError!!.contains("synthetic private failure detail"))
            vm.resumeOriginalEdit(drafts[0].entryId, drafts[0].id); assertNull(vm.state.value.originalEdit)
            fail.set(false); vm.retry(); vm.state.first { it.editDraftIndexAvailable }
            assertEquals(1, vm.state.value.editDraftIssueCount); assertEquals(before, hashes(root))
            vm.resumeOriginalEdit(drafts[0].entryId, drafts[0].id)
            assertEquals(drafts[0].text, vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!.text)
        } finally { stop(vm); s.close() }
    } }

    @Test fun fileReplacementDuringRecoveryCannotDisplayOldInputOrOverwriteNewIdentity() = runBlocking { exercise { root ->
        val drafts = seed(root); damage(root, "damaged"); val armed = AtomicBoolean(false); var replacement: OriginalEditDraft? = null
        val s = store(root) { if (it == "authenticated" && armed.getAndSet(false)) replacement = runBlocking { replace(root, drafts[0]) } }
        val repo = EncryptedEntryRepository(editDrafts = s).also { r -> drafts.forEach { r.seed(entry(it)) } }
        val vm = controller(repo)
        try {
            armed.set(true); vm.resumeOriginalEdit(drafts[0].entryId, drafts[0].id)
            vm.state.first { it.originalEdit?.draftConflict == true && it.pendingEdits.any { ref -> ref.id == replacement?.id } }
            val refused = vm.state.value.originalEdit!!
            val newest = checkNotNull(replacement)
            assertNotEquals(drafts[0].text, refused.text); assertNotEquals(newest.text, refused.text)
            vm.saveOriginalEdit(refused.sessionId); assertTrue(repo.revisionSubmissions.isEmpty())
            vm.cancelOriginalEdit(refused.sessionId); vm.resumeOriginalEdit(newest.entryId, newest.id)
            val recovered = vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!!
            assertEquals(newest.text, recovered.text); assertFalse(recovered.conflict)
            assertEquals(newest, s.read(drafts[0].entryId)); assertEquals(1, vm.state.value.editDraftIssueCount)
        } finally { stop(vm); s.close() }
    } }
}
