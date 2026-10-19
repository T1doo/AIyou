package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.ProcessingPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production controller over actual capture SQL/cipher files, with deterministic ACK/queue boundaries. */
class CapturePolicyControllerTest {
    @Test fun changedDeviceDefaultKeepsCurrentInputAndOnlyNewDraftsAdoptIt(): Unit = runBlocking {
        var chosen=ProcessingPolicy.LOCAL_ONLY
        MixedCaptureControllerTest.Harness { chosen }.use { h ->
            h.start();h.c.edit("合成默认变更前的图文");h.drain();h.add()
            val original=h.ui.draft!!
            chosen=ProcessingPolicy.AI_NONE
            h.c.initialize(h.store)
            com.t1doo.aiyou.data.assertCaptureDraftEquals(original,h.ui.draft!!)
            assertEquals(original.text,h.ui.text)
            h.c.save();h.drain()
            assertFalse(h.ui.uncertainSave)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,h.local.entries.single().policy)
            assertEquals(ProcessingPolicy.AI_NONE,h.ui.draft!!.policy)
            assertNotEquals(original.id,h.ui.draft!!.id)
            assertArrayEquals(com.t1doo.aiyou.data.MixedCaptureSqlTest.png,h.f.media.read(original.images.single().intent.id))

            chosen=ProcessingPolicy.LOCAL_ONLY
            h.c.edit("合成仍选择不参与 AI 的第二份");h.drain()
            val localChoice=h.ui.draft!!
            h.c.changePolicy(localChoice.id,localChoice.version,ProcessingPolicy.LOCAL_ONLY);h.drain()
            val manual=h.ui.draft!!
            h.c.changePolicy(manual.id,manual.version,ProcessingPolicy.AI_NONE);h.drain()
            assertEquals(ProcessingPolicy.LOCAL_ONLY,chosen)
            h.c.save();h.drain()
            assertEquals(ProcessingPolicy.AI_NONE,h.local.entries.single { it.id==manual.id }.policy)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,h.local.entries.single { it.id==original.id }.policy)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,h.ui.draft!!.policy)
            assertTrue(h.f.db.read("SELECT id FROM remote_text_jobs").isEmpty())
        }
    }

    @Test fun explicitChoiceWaitsForAckPreservesTextImagesAndOnlyChangesThisNewRecord(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); h.c.edit("合成只作手账的图文"); h.drain(); h.add()
            h.failEdit=true; h.c.edit("合成范围选择前尚未确认的文字"); h.drain()
            val pendingText=h.ui.text; val textError=h.ui.error; assertNotNull(textError)
            val before = h.ui.draft!!
            assertTrue(h.ui.supportsProcessingChoice)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, before.policy)
            h.c.changePolicy(before.id, before.version, ProcessingPolicy.AI_NONE)
            assertTrue(h.ui.busy); assertNotNull(h.ui.policyChange)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            h.c.save(); h.c.edit("不得代入的旧回调"); h.c.remove(before.images.single().intent.id); h.c.requestDiscard()
            assertEquals(1, h.queue.size); assertEquals(pendingText, h.ui.text)
            h.drain()
            assertEquals(ProcessingPolicy.AI_NONE, h.ui.draft!!.policy)
            assertEquals(before.images, h.ui.draft!!.images)
            assertEquals(pendingText, h.ui.text); assertNull(h.ui.policyChange); assertFalse(h.ui.busy)
            assertEquals(textError,h.ui.error) // Confirming the range does not confirm a previously failed text write.
            h.c.initialize(h.store); assertEquals(textError,h.ui.error); assertEquals(pendingText,h.ui.text)
            h.failEdit=false
            h.c.save(); h.drain()
            assertEquals(ProcessingPolicy.AI_NONE, h.local.entries.single().policy)
            assertEquals(pendingText, h.local.entries.single().originalText)
            assertEquals(1, h.f.media.list(before.id, 1).size)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            h.c.edit("合成下一份默认记录"); h.drain(); h.c.save(); h.drain()
            assertEquals(ProcessingPolicy.AI_NONE, h.local.entries.first { it.id == before.id }.policy)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.local.entries.first { it.id != before.id }.policy)
        }
    }

    @Test fun lostPolicyAckRetainsExactRequestBlocksSaveAndRetriesWithoutAnotherTransition(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); h.c.edit("合成保留范围输入"); h.drain()
            val original = h.ui.draft!!; h.failPolicyAck = true
            h.c.changePolicy(original.id, original.version, ProcessingPolicy.AI_NONE); h.drain()
            val request = h.ui.policyChange!!
            assertFalse(h.ui.busy); assertNotNull(h.ui.error)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            assertEquals(ProcessingPolicy.AI_NONE, h.store.captureRecords!!.current().policy)
            h.c.initialize(h.store) // A routine VM records reload is not the user's range-confirmation retry.
            assertEquals(request.requestId, h.ui.policyChange?.requestId)
            assertEquals(original.version, h.ui.draft!!.version)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            h.c.save(); h.c.select(); h.c.edit("不得替换输入")
            h.c.changePolicy(original.id, original.version, ProcessingPolicy.LOCAL_ONLY)
            assertTrue(h.queue.isEmpty()); assertEquals(original.text, h.ui.text)
            assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            h.c.retryPolicy(request.requestId); h.drain()
            assertNull(h.ui.policyChange); assertNull(h.ui.error)
            assertEquals(original.version + 1, h.ui.draft!!.version)
            assertEquals(listOf(original, original), h.policyCalls.map { it.first })
            h.c.retryPolicy(request.requestId); assertTrue(h.queue.isEmpty())
            h.c.save(); h.drain()
            assertEquals(ProcessingPolicy.AI_NONE, h.local.entries.single().policy)
        }
    }

    @Test fun failedWriteAndReadKeepChoiceUntilExplicitReadWithoutResendingIt(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); h.c.edit("合成未改写的手账"); h.drain()
            val original = h.ui.draft!!; h.failPolicyBefore = true
            h.c.changePolicy(original.id, original.version, ProcessingPolicy.AI_NONE); h.drain()
            val request = h.ui.policyChange!!; h.failPolicyRead = true
            h.c.readCurrentPolicy(request.requestId); h.drain()
            assertEquals(request.requestId, h.ui.policyChange?.requestId)
            assertNotNull(h.ui.error); assertEquals(original.text, h.ui.text)
            assertEquals(1, h.policyCalls.size)
            h.failPolicyRead = false; h.c.readCurrentPolicy(request.requestId); h.drain()
            assertNull(h.ui.policyChange); assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            assertEquals(original.version, h.ui.draft!!.version)
            assertEquals(1, h.policyCalls.size)
            h.c.retryPolicy(request.requestId); assertTrue(h.queue.isEmpty())
            h.c.save(); h.drain()
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.local.entries.single().policy)
        }
    }

    @Test fun rejectedQueueRetainsChoiceAndCanRetrySameExpectedDraftWithoutPrematureSuccess(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); h.c.edit("合成队列拒绝后仍在"); h.drain()
            val original = h.ui.draft!!; h.acceptQueue = false
            h.c.changePolicy(original.id, original.version, ProcessingPolicy.AI_NONE)
            val request = h.ui.policyChange!!
            assertFalse(h.ui.busy); assertNotNull(h.ui.error); assertTrue(h.policyCalls.isEmpty())
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.store.captureRecords!!.current().policy)
            h.acceptQueue = true; h.c.retryPolicy(request.requestId); h.drain()
            assertEquals(1, h.policyCalls.size)
            assertSame(original, h.policyCalls.single().first)
            assertEquals(ProcessingPolicy.AI_NONE, h.ui.draft!!.policy)
            assertNull(h.ui.policyChange)
        }
    }

    @Test fun changedVersionDiscardAndDisposalRejectRetiredChoicesWithoutAnotherWrite(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); val old = h.ui.draft!!
            h.c.changePolicy(old.id, old.version, ProcessingPolicy.AI_NONE); h.drain()
            h.c.changePolicy(old.id, old.version, ProcessingPolicy.LOCAL_ONLY)
            assertTrue(h.queue.isEmpty()); assertEquals(1, h.policyCalls.size)
            h.c.requestDiscard(); h.c.confirmDiscard(); h.drain()
            val fresh = h.ui.draft!!; assertNotEquals(old.id, fresh.id)
            h.c.changePolicy(old.id, old.version, ProcessingPolicy.AI_NONE)
            assertTrue(h.queue.isEmpty()); assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            h.c.changePolicy(fresh.id, fresh.version, ProcessingPolicy.REMOTE_ALLOWED)
            assertTrue(h.queue.isEmpty())
            h.c.dispose(); h.c.changePolicy(fresh.id, fresh.version, ProcessingPolicy.AI_NONE); h.drain()
            assertEquals(1, h.policyCalls.size)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.store.captureRecords!!.current().policy)
        }
    }

    @Test fun alreadyExecutingOldAckCannotRefillAReplacedOrDiscardedDraftOwner(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); h.c.edit("合成已经离开的草稿"); h.drain()
            val old = h.ui.draft!!
            var replacement = ""
            h.afterPolicyWrite = {
                val next = h.store.captureRecords!!.discard(old.id); replacement = next.id
                h.local = h.local.copy(mixedCapture = MixedCaptureUiState(draft = next, text = "下一份合成输入",
                    supportsProcessingChoice = true))
            }
            h.c.changePolicy(old.id, old.version, ProcessingPolicy.AI_NONE); h.drain()
            assertEquals(replacement, h.ui.draft!!.id)
            assertEquals("下一份合成输入", h.ui.text)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
            assertFalse(h.ui.busy); assertNull(h.ui.policyChange)
            assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            assertEquals(replacement, h.store.captureRecords!!.current().id)
        }
    }

    @Test fun uncertainRecordSaveFreezesChosenRangeAndExactRetryDoesNotCreateAnotherRecord(): Unit = runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start(); h.c.edit("合成结果待核对的记录"); h.drain()
            val old = h.ui.draft!!
            h.c.changePolicy(old.id, old.version, ProcessingPolicy.AI_NONE); h.drain()
            val chosen = h.ui.draft!!
            h.failEntries = true; h.c.save(); h.drain()
            assertTrue(h.ui.uncertainSave)
            h.c.changePolicy(chosen.id, chosen.version, ProcessingPolicy.LOCAL_ONLY)
            assertTrue(h.queue.isEmpty()); assertEquals(1, h.policyCalls.size)
            assertEquals("AI_NONE", h.f.db.read("SELECT privacyPolicy FROM entries").single().single())
            h.c.save(); h.drain()
            assertFalse(h.ui.uncertainSave)
            assertEquals(1, h.local.entries.size)
            assertEquals(ProcessingPolicy.AI_NONE, h.local.entries.single().policy)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, h.ui.draft!!.policy)
        }
    }
}
