package com.t1doo.aiyou

import android.app.Application
import android.os.Process
import android.util.AtomicFile
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.AwaitableRecordStorage
import com.t1doo.aiyou.domain.OriginalEditDraftStore
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
import com.t1doo.aiyou.records.LocalRecordsViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production factory / Room / SQLCipher / Keystore / Activity. Requires a fresh dedicated install. */
@RunWith(AndroidJUnit4::class)
class ProductionColdStartPersistenceTest {
    @Test fun formalEditReceiptSurvivesKilledProcessAndActivityConfirmsWithoutAnotherRevision() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("aiyouRestartPhase")
        assumeTrue("External dedicated-device phases required", phase != null)
        val token = UUID.fromString(checkNotNull(args.getString("aiyouRestartToken"))).toString()
        val app = instrumentation.targetContext.applicationContext as Application
        val marker = AtomicFile(File(app.noBackupFilesDir, "d02-production-$token.json"))
        val original = "合成生产冷启动原文 $token"
        val replacement = "合成生产冷启动修改 🌿 $token"
        val capture = "合成独立此刻草稿 $token"
        when (phase) {
            "write" -> {
                // Never seed or reset an existing user library. Runner additionally requires fresh installation.
                assertFalse(File(app.noBackupFilesDir, "records/records.db").exists())
                val actual = EncryptedEntryRepository.open(app)
                val (seed, retryEntry, pending) = AutoCloseable { actual.closeAndAwait() }.use {
                    val seed = actual.saveDraft(actual.updateDraft(original), 1000, "Etc/UTC")
                    val retryEntry = actual.saveDraft(actual.updateDraft("合成确认失败重试 $token"), 2000, "Etc/UTC")
                    val pending = OriginalEditDraft(UUID.randomUUID().toString(), seed.id, 1, 1, 1, original, replacement)
                    actual.editDrafts.write(null, pending)
                    actual.reviseFromDraft(pending, 3000)
                    // Intentional crash boundary fixture: real transaction returned, no terminal tombstone yet.
                    assertEquals(OriginalEditDraftState.ACTIVE, actual.editDrafts.read(seed.id)!!.state)
                    actual.updateDraft(capture)
                    Triple(seed, retryEntry, pending)
                }

                // Inject only failed delivery/acknowledgement at the interface boundary. Every successful
                // operation delegates to the actual production factory; no database/cipher/key mock.
                val opened = EncryptedEntryRepository.open(app)
                AutoCloseable { opened.closeAndAwait() }.use {
                    var terminalFailures = 1
                    var readFailures = 0
                    val drafts = object : OriginalEditDraftStore by opened.editDrafts {
                        override suspend fun write(expected: OriginalEditDraft?, next: OriginalEditDraft): OriginalEditDraft {
                            if (next.state == OriginalEditDraftState.COMMITTED && terminalFailures-- > 0)
                                throw IOException("synthetic terminal acknowledgement unavailable")
                            val result = opened.editDrafts.write(expected, next)
                            if (next.state == OriginalEditDraftState.COMMITTED) readFailures = 1
                            return result
                        }
                    }
                    val boundary = object : EntryRepository by opened, AwaitableRecordStorage {
                        override val editDrafts: OriginalEditDraftStore = drafts
                        override suspend fun entries() = if (readFailures-- > 0)
                            throw IOException("synthetic readback unavailable") else opened.entries()
                        override fun close() = opened.close()
                        override fun awaitClosed() = opened.awaitClosed()
                    }
                    val lifecycle = ViewModelStore()
                    lateinit var model: LocalRecordsViewModel
                    try {
                        instrumentation.runOnMainSync { model = LocalRecordsViewModel(app, { boundary }); lifecycle.put("native-retry", model) }
                        await { model.state.value.ready && !model.state.value.opening }
                        instrumentation.runOnMainSync { model.beginOriginalEdit(retryEntry.id, 1) }
                        await { model.state.value.originalEdit?.loadingDraft == false }
                        val session = model.state.value.originalEdit!!.sessionId
                        instrumentation.runOnMainSync { model.changeOriginalEdit(session, "合成已确认重试修改 $token") }
                        await { model.state.value.originalEdit?.draftNotice == "编辑草稿已加密保存在本机" }
                        instrumentation.runOnMainSync { model.saveOriginalEdit(session) }
                        await { model.state.value.originalEdit?.let { !it.saving && it.awaitingReadback && it.error != null } == true }
                        assertEquals(2, opened.revisions(retryEntry.id).size)
                        instrumentation.runOnMainSync { model.saveOriginalEdit(session) }
                        await { model.state.value.originalEdit?.let { !it.saving && it.awaitingReadback && it.error != null } == true &&
                            openedDraftTerminal(opened, retryEntry.id) }
                        assertEquals(2, opened.revisions(retryEntry.id).size)
                        instrumentation.runOnMainSync { model.saveOriginalEdit(session) }
                        await { model.state.value.originalEdit == null }
                        assertEquals(2, opened.revisions(retryEntry.id).size)
                        assertEquals(capture, opened.readDraft()!!.text)
                    } finally { instrumentation.runOnMainSync { lifecycle.clear() } }
                }
                val acknowledgement = JSONObject().put("pid", Process.myPid()).put("entryId", seed.id)
                    .put("retryEntryId", retryEntry.id).put("draftId", pending.id)
                val stream = marker.startWrite()
                try { stream.write(acknowledgement.toString().toByteArray()); marker.finishWrite(stream) }
                catch (failure: Exception) { marker.failWrite(stream); throw failure }
                evidence(phase, token)
            }
            "read" -> {
                val acknowledgement = JSONObject(marker.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                assertNotEquals("Must be a different actual Android PID", acknowledgement.getInt("pid"), Process.myPid())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val admitted = java.util.concurrent.atomic.AtomicReference<LocalRecordsViewModel?>(null)
                    await {
                        scenario.onActivity { activity -> admitted.set(activity.admittedRecords()) }
                        admitted.get() != null
                    }
                    val model = checkNotNull(admitted.get())
                    await { model.state.value.ready && !model.state.value.opening }
                    assertEquals(capture, model.state.value.text)
                    val id = acknowledgement.getString("entryId")
                    val draftId = acknowledgement.getString("draftId")
                    assertEquals(2, model.state.value.entries.single { it.id == id }.currentRevision)
                    assertTrue(model.state.value.pendingEdits.any { it.id == draftId && it.entryId == id })
                    scenario.onActivity { model.resumeOriginalEdit(id, draftId) }
                    await { model.state.value.originalEdit?.loadingDraft == false }
                    val edit = model.state.value.originalEdit!!
                    assertEquals(replacement, edit.text)
                    assertTrue(edit.awaitingReadback); assertFalse(edit.conflict)
                    scenario.onActivity { model.saveOriginalEdit(edit.sessionId) }
                    await { model.state.value.originalEdit == null }
                    assertFalse(model.state.value.pendingEdits.any { it.entryId == id })
                    assertEquals(capture, model.state.value.text)
                    val actual = EncryptedEntryRepository.open(app)
                    try {
                        assertEquals(listOf(original, replacement), actual.revisions(id).map { it.originalText })
                        assertEquals(2, actual.revisions(acknowledgement.getString("retryEntryId")).size)
                        assertEquals(OriginalEditDraftState.COMMITTED, actual.editDrafts.read(id)!!.state)
                    } finally { actual.closeAndAwait() }
                }
                evidence(phase, token)
            }
            else -> fail("Unknown production phase; evidence/data preserved")
        }
    }

    private fun openedDraftTerminal(store: EncryptedEntryRepository, id: String): Boolean = runBlocking {
        store.editDrafts.read(id)?.state == OriginalEditDraftState.COMMITTED
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!predicate()) { check(System.nanoTime() < deadline) { "Native state deadline exceeded" }; Thread.sleep(50) }
    }
    private fun evidence(phase: String, token: String) {
        val bundle = android.os.Bundle().apply {
            putString("aiyou_phase", phase); putString("aiyou_token", token); putString("aiyou_pid", Process.myPid().toString())
            putString("aiyou_storage", "production Room/SQLCipher/AndroidKeyStore/Activity")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }
}
