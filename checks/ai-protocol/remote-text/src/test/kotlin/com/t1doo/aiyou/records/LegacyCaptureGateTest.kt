package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual VM actor and shared capture gate; legacy storage uses owned synthetic SQLite. */
class LegacyCaptureGateTest {
    @Test(timeout=15000) fun acceptedLegacyEditDrainsBeforeReservationAndFrozenEntryPointsLeaveUiUntouched():Unit=runBlocking {
        Harness().use { h ->
            h.ready()
            h.prefix({h.vm.changeText("合成旧入口文字")}) {
                val before=h.vm.state.value
                h.vm.changeText("冻结后文字");h.vm.save();h.vm.requestCaptureDiscard();h.vm.retry()
                h.vm.cancelCaptureDiscard(1);h.vm.confirmCaptureDiscard(1)
                val after=h.vm.state.value
                assertEquals(before.text,after.text);assertEquals(before.draftNotice,after.draftNotice)
                assertEquals(before.saving,after.saving);assertEquals(before.savePending,after.savePending)
                assertEquals(before.opening,after.opening);assertEquals(before.captureDiscard,after.captureDiscard)
                assertEquals(before.error,after.error)
            }
            assertEquals("合成旧入口文字",h.store.readDraft()!!.text);assertNull(h.vm.state.value.error)
        }
    }
    @Test(timeout=15000) fun acceptedLegacySaveRetainsPermitThroughActualActorWrite():Unit=runBlocking {
        Harness().use { h ->
            h.ready();h.vm.changeText("合成旧入口保存");h.idle()
            h.prefix({h.vm.save()}) {}
            assertEquals("合成旧入口保存",h.saved.single().originalText)
            assertNull(h.store.readDraft());assertNull(h.vm.state.value.error);assertFalse(h.vm.state.value.savePending)
        }
    }
    @Test(timeout=15000) fun legacyDiscardAndLostAckRestoreDrainWhileLaterPromptActionsStayFrozen():Unit=runBlocking {
        Harness().use { h ->
            h.ready();h.vm.changeText("合成旧入口恢复");h.idle();h.vm.requestCaptureDiscard()
            val id=h.vm.state.value.captureDiscard!!.requestId
            val base=h.capture.peek()!!;val r=h.gate.freeze(base.id)!!
            try {
                val before=h.vm.state.value.captureDiscard
                h.vm.confirmCaptureDiscard(id);h.vm.cancelCaptureDiscard(id);h.vm.retry()
                assertEquals(before,h.vm.state.value.captureDiscard);assertNotNull(h.store.readDraft())
            } finally { h.gate.finish(r,base) }
            h.loseDiscardAck=true
            h.prefix({h.vm.confirmCaptureDiscard(id)}) {}
            assertTrue(h.vm.state.value.captureDiscard!!.recoveryRequired);assertNull(h.store.readDraft())
            h.loseDiscardAck=false
            h.prefix({h.vm.cancelCaptureDiscard(id)}) {}
            assertEquals("合成旧入口恢复",h.store.readDraft()!!.text)
            assertNull(h.vm.state.value.captureDiscard);assertNull(h.vm.state.value.error)
        }
    }
    private class Harness:AutoCloseable {
        val f=FullFixture();val capture=f.capture;val gate=capture.mutationGate
        @Volatile var loseDiscardAck=false
        @Volatile var saved=emptyList<StoredEntry>()
        val store=object:EntryRepository {
            override val captureRecords:CaptureRepository=capture
            override suspend fun readDraft():TextDraft?=f.db.read("SELECT id,version,text FROM capture_draft WHERE slot=1").singleOrNull()?.let {
                TextDraft(it[0] as String,it[1] as Long,it[2] as String)
            }
            override suspend fun updateDraft(text:String):TextDraft=gate.mutation {
                val old=readDraft();val next=TextDraft(old?.id ?: UUID.randomUUID().toString(),(old?.version ?: 0)+1,text)
                f.db.transaction { it.execute("INSERT OR REPLACE INTO capture_draft(slot,id,version,text) VALUES(1,?,?,?)",listOf(next.id,next.version,next.text)) };next
            }
            override suspend fun discardDraft(expected:TextDraft):Unit=gate.mutation {
                check(readDraft()==expected)
                f.db.transaction { it.execute("DELETE FROM capture_draft WHERE slot=1 AND id=? AND version=?",listOf(expected.id,expected.version)) }
                if(loseDiscardAck)error("synthetic lost legacy discard ACK")
            }
            override suspend fun restoreDiscardedDraft(expected:TextDraft,replacement:TextDraft):TextDraft=gate.mutation {
                val old=readDraft();check(old==null || old==expected || old==replacement)
                if(old!=null)old else {
                    f.db.transaction { it.execute("INSERT INTO capture_draft(slot,id,version,text) VALUES(1,?,?,?)",listOf(replacement.id,replacement.version,replacement.text)) };replacement
                }
            }
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=gate.mutation {
                check(readDraft()==expected);discardDraft(expected)
                StoredEntry(expected.id,1,expected.text,capturedAtMillis,captureTimeZone,ProcessingPolicy.LOCAL_ONLY).also { saved=saved+it }
            }
            override suspend fun entries()=saved
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("unrelated")
        }
        val vm=LocalRecordsViewModel(Application(),{store})
        suspend fun ready() { withTimeout(5000) { while(vm.state.value.mixedCapture?.draft==null)delay(5) };idle() }
        suspend fun idle() { withTimeout(5000) { while(!vm.appAccessIdle.value)delay(5) } }
        suspend fun prefix(action:()->Unit,frozen:()->Unit) {
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            assertTrue(vm.enqueueTaskOperation { entered.countDown();withContext(Dispatchers.IO){check(release.await(5,TimeUnit.SECONDS))} })
            assertTrue(entered.await(5,TimeUnit.SECONDS));action()
            val base=capture.peek()!!;val r=gate.freeze(base.id)!!
            try { frozen();release.countDown();idle();gate.reserve(r,base,base.text,0) }
            finally { release.countDown();idle();gate.finish(r,base) }
        }
        override fun close() { runBlocking { vm.clearForHostTest();withTimeout(5000){vm.awaitAppAccessClosed()} };f.close() }
    }
}
