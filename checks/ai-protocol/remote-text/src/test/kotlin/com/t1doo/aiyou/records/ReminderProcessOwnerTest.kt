package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReminderProcessOwnerTest {
    @Test fun firstUnlockAndLockedEpochCannotBeRetroactivelyAuthorized():Unit=runBlocking {
        val bridge=ReminderOperationBridge();var unlocked=false;var reads=0;var opens=0;var allowed=false
        val owners=ReminderProcessOwner(bridge,{unlocked},{reads++;allowed},{opens++;error("No authorized cold open")},{error("No driver")},{100}){error("No fingerprint")}
        try{
            assertNull(owners.capture());assertEquals(0,reads);assertEquals(0,opens)
            unlocked=true
            val old=owners.capture()!!;assertFalse(owners.execute(old));assertEquals(1,reads);assertEquals(0,opens)
            allowed=true;assertNull(owners.find(old.nonce,old.id));assertFalse(owners.execute(old));assertNull(owners.capture())
            owners.frontOpening();assertNull(owners.capture());assertEquals(1,reads)
        }finally{bridge.close()}
    }
    @Test fun unknownPreferenceDoesNotRetryTheSameTicketOrAskForAKey():Unit=runBlocking {
        val bridge=ReminderOperationBridge();var reads=0;var opens=0
        val owners=ReminderProcessOwner(bridge,{true},{reads++;error("Synthetic uncertain preference")},{opens++;error("No key")},{error("No driver")},{100}){error("No fingerprint")}
        try{
            val old=owners.capture()!!;assertTrue(runCatching{owners.execute(old)}.isFailure)
            assertFalse(owners.execute(old));assertNull(owners.capture());assertEquals(1,reads);assertEquals(0,opens)
        }finally{bridge.close()}
    }
    @Test fun frontOpeningSealsColdPreparationAndWaitsForPhysicalCloseBeforeReplacement():Unit=runBlocking {
        RebuildFixture().use{f->
            val bridge=ReminderOperationBridge();val entered=CountDownLatch(1);val release=CountDownLatch(1);val closed=AtomicInteger()
            val codec=ReminderEnvelopeCipher(LocalCandidateFixture.KEY)
            val owners=ReminderProcessOwner(bridge,{true},{true},{
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                object:OwnedReminderRuntime {
                    override val resources=ReminderRuntimeResources(f.tasks,f.meta,codec)
                    override fun close()=Unit
                    override fun awaitClosed(){closed.incrementAndGet();codec.close()}
                }
            },{error("Retired cold preparation must not reach driver")},{100}){f.fingerprint}
            try{
                val ticket=owners.capture()!!;val executing=async(Dispatchers.Default){runCatching{owners.execute(ticket)}}
                assertTrue(entered.await(5,TimeUnit.SECONDS));owners.frontOpening();assertFalse(ticket.active);assertNull(owners.capture())
                val closing=async(Dispatchers.Default){owners.closeCold()}
                delay(30);assertFalse(closing.isCompleted);assertEquals(0,closed.get())
                release.countDown();assertTrue(executing.await().isFailure);closing.await();assertEquals(1,closed.get())
                // A fresh gate owner is now possible, proving closure released the unique slot.
                val replacement=bridge.runtime{bridge.gate.openOwner(true)};bridge.closeOwner(replacement){}
            }finally{release.countDown();bridge.close()}
        }
    }
    @Test fun sixtyFourUniqueIdentitiesAreBoundedAndRepeatedIdentityDoesNotConsumeAnotherSlot(){
        val bridge=ReminderOperationBridge();val owners=ReminderProcessOwner(bridge,{true},{error("No preference read")},{error("No key")},{error("No driver")},{100}){error("No fingerprint")}
        try{
            val first=owners.capture(ReminderEnvelope("%064x".format(0),ByteArray(29)))!!
            repeat(63){assertNotNull(owners.capture(ReminderEnvelope("%064x".format(it+1),ByteArray(29))))}
            assertSame(first,owners.capture(ReminderEnvelope("%064x".format(0),ByteArray(29))))
            assertNull(owners.capture(ReminderEnvelope("%064x".format(64),ByteArray(29))))
            owners.locked();assertFalse(first.active);assertNull(owners.find(first.nonce,first.id))
        }finally{bridge.close()}
    }
    @Test fun unconfirmedNativeCloseKeepsTheSlotSealedAndDoesNotPretendCleanupSucceeded():Unit=runBlocking {
        val bridge=ReminderOperationBridge()
        val owners=ReminderProcessOwner(bridge,{true},{true},{throw ReminderRuntimeClosureUnconfirmed(Any())},{error("No driver")},{100}){error("No fingerprint")}
        try{
            val ticket=owners.capture()!!;assertTrue(runCatching{owners.execute(ticket)}.exceptionOrNull() is ReminderRuntimeClosureUnconfirmed)
            assertTrue(runCatching{owners.closeCold()}.exceptionOrNull() is ReminderRuntimeClosureUnconfirmed)
            assertThrows(IllegalStateException::class.java){bridge.gate.openOwner(true)}
            owners.frontOpening();assertNull(owners.capture())
        }finally{bridge.close()}
    }

}
