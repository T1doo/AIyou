import com.t1doo.aiyou.data.EncryptedOriginalEditStore
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.domain.OriginalEditDraftIntegrityFailure
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Executes the production AES-GCM/file adapter on the host; no Android/Keystore claim. */
class OriginalEditStorageTest {
    private val key = ByteArray(32) { (it + 71).toByte() }
    private fun draft(text: String = "合成修改🌿") = OriginalEditDraft(UUID.randomUUID().toString(), "synthetic-original",
        3, 1, 1, "合成版本三原文", text)
    private suspend fun exercise(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("aiyou-edit-owned-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun store(root: File, checkpoint: (String) -> Unit = {}) = EncryptedOriginalEditStore(root, key.copyOf(), checkpoint)
    private fun terminal(value: OriginalEditDraft, state: OriginalEditDraftState) = value.copy(
        version = value.version + 1, originalText = "", text = "", state = state)
    private suspend inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected " + T::class.java.simpleName) } catch (failure: Throwable) { if (failure !is T) throw failure }
    }

    @Test fun productionCipherColdReopenRestoresExactUnicodeAndContainsNoPlaintext() = runBlocking { exercise { root ->
        val value = draft("合成秘密输入\n" + "🌿".repeat(1000) + '\uD800')
        store(root).use { assertEquals(value, it.write(null, value)) }
        val file = root.listFiles()!!.single { it.name.endsWith(".edit") }
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(value.text.toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1)))
        assertFalse(file.name.contains(value.entryId))
        store(root).use { assertEquals(value, it.read(value.entryId)); assertEquals(value.id, it.pending().single().id) }
    } }

    @Test fun discardTombstoneRejectsLateAutosaveBeforeAndAfterColdReopen() = runBlocking { exercise { root ->
        val value = draft()
        store(root).use { store ->
            store.write(null, value)
            assertEquals(OriginalEditDraftState.DISCARDED, store.write(value, terminal(value, OriginalEditDraftState.DISCARDED)).state)
            fails<OriginalEditDraftConflict> { store.write(value, value.copy(version = 2, text = "合成迟到输入")) }
        }
        store(root).use { store ->
            assertTrue(store.pending().isEmpty())
            fails<OriginalEditDraftConflict> { store.write(value, value.copy(version = 2, text = "合成重开后迟到输入")) }
            val tombstone = store.read(value.entryId)!!
            val newSession = draft("合成新的明确编辑")
            store.write(tombstone, newSession)
            fails<OriginalEditDraftConflict> { store.write(value, value.copy(version = 2, text = "合成旧身份")) }
            assertEquals(newSession, store.read(value.entryId))
        }
    } }

    @Test fun concurrentStoresCompareAcknowledgedSnapshotsAndNeverOverwriteAnotherWriter() = runBlocking { exercise { root ->
        val value = draft()
        store(root).use { first -> store(root).use { second ->
            first.write(null, value)
            val seen = second.read(value.entryId)!!
            val updated = first.write(value, value.copy(version = 2, text = "合成先完成修改"))
            fails<OriginalEditDraftConflict> { second.write(seen, seen.copy(version = 2, text = "合成陈旧修改")) }
            assertEquals(updated, second.read(value.entryId))
        } }
    } }

    @Test fun beforeRenameWriteFailureKeepsAcknowledgedCipherAndEncryptedPartial() = runBlocking { exercise { root ->
        val value = draft()
        store(root).use { it.write(null, value) }
        val target = root.listFiles()!!.single { it.name.endsWith(".edit") }
        val before = target.readBytes()
        store(root) { if (it == "encrypted") error("synthetic bounded failure") }.use { failing ->
            fails<IllegalStateException> { failing.write(value, value.copy(version = 2, text = "合成失败输入")) }
        }
        assertArrayEquals(before, target.readBytes())
        assertEquals(1, root.listFiles()!!.count { ".partial." in it.name })
        store(root).use { assertEquals(value, it.read(value.entryId)) }
    } }

    @Test fun lostPublicationAcknowledgementRetriesExactSnapshotWithoutReencrypting() = runBlocking { exercise { root ->
        val value = draft()
        store(root) { if (it == "published") error("synthetic lost acknowledgement") }.use { failing ->
            fails<IllegalStateException> { failing.write(null, value) }
        }
        val target = root.listFiles()!!.single { it.name.endsWith(".edit") }
        val bytes = target.readBytes()
        store(root).use { assertEquals(value, it.write(null, value)) }
        assertArrayEquals(bytes, target.readBytes())
    } }

    @Test fun wrongKeyBitFlipAndFilenameSwapFailClosedAndPreserveOriginalBytes() = runBlocking { exercise { root ->
        val value = draft()
        store(root).use { it.write(null, value) }
        val target = root.listFiles()!!.single { it.name.endsWith(".edit") }
        val original = target.readBytes()
        EncryptedOriginalEditStore(root, ByteArray(32) { 2 }).use { wrong ->
            fails<OriginalEditDraftIntegrityFailure> { wrong.read(value.entryId) }
            fails<OriginalEditDraftIntegrityFailure> { wrong.write(null, value) }
        }
        assertArrayEquals(original, target.readBytes())
        val corrupt = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        target.writeBytes(corrupt)
        store(root).use { failing ->
            fails<OriginalEditDraftIntegrityFailure> { failing.read(value.entryId) }
            fails<OriginalEditDraftIntegrityFailure> { failing.write(null, value) }
        }
        assertArrayEquals(corrupt, target.readBytes())
        target.writeBytes(original)
        val other = draft().copy(entryId = "synthetic-other")
        store(root).use { it.write(null, other) }
        val otherFile = root.listFiles()!!.single { it.name.endsWith(".edit") && it != target }
        otherFile.writeBytes(original)
        store(root).use { failing -> fails<OriginalEditDraftIntegrityFailure> { failing.read(other.entryId) } }
        assertArrayEquals(original, otherFile.readBytes())
    } }

    @Test fun maximumExactUtf16DraftAndTerminalStatesStayBoundedAndClosedKeyIsZeroed() = runBlocking { exercise { root ->
        val ownKey = key.copyOf()
        val value = draft("叶".repeat(99_999) + '\uD800').copy(originalText = "🌿".repeat(50_000))
        val actual = EncryptedOriginalEditStore(root, ownKey)
        actual.write(null, value)
        assertEquals(value, actual.read(value.entryId))
        fails<IllegalArgumentException> { actual.write(value, value.copy(version = 2, text = value.text + "x")) }
        assertEquals(value, actual.read(value.entryId))
        actual.write(value, terminal(value, OriginalEditDraftState.COMMITTED))
        assertTrue(actual.pending().isEmpty())
        actual.close()
        assertTrue(ownKey.all { it == 0.toByte() })
        fails<IllegalStateException> { actual.read(value.entryId) }
    } }
    @Test fun existingDirectoryStillRequiresKnownParentConfirmationAndCreatesNoUnknownAncestors() = runBlocking { exercise { root ->
        val missingParent = File(root, "unavailable-parent")
        var parentAvailable = true
        val child = object : File(root, "edits") {
            override fun getParentFile(): File = if (parentAvailable) root else missingParent
        }
        val value = draft()
        store(child).use { actual ->
            actual.write(null, value)
            val target = child.listFiles()!!.single { it.name.endsWith(".edit") }
            val before = target.readBytes()
            parentAvailable = false
            repeat(2) { fails<IllegalStateException> { actual.write(value, value.copy(version = 2, text = "合成未确认修改")) } }
            assertArrayEquals(before, target.readBytes()); assertFalse(missingParent.exists())
            parentAvailable = true
            assertEquals(value, actual.read(value.entryId))
            actual.write(value, value.copy(version = 2, text = "合成父目录恢复后重试"))
        }
        val nested = File(root, "unknown/ancestors/edits")
        store(nested).use { fails<IllegalStateException> { it.write(null, value) } }
        assertFalse(File(root, "unknown").exists())
    } }

}
