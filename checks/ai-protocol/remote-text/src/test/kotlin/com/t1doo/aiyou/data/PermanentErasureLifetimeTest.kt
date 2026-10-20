package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual production lifetime helper, real SQL gateway and both real file leases. */
class PermanentErasureLifetimeTest {
    @Test(timeout=12000) fun ownerSealsImmediatelyButWaitsBothErasureLeasesBeforeDatabaseAndKeyRetirement(): Unit = PermanentErasureFixture().use { f ->
        val id=f.entry(); val intent=f.intent(id); val mediaKey=PermanentErasureFixture.KEY.copyOf(); val editKey=PermanentErasureFixture.KEY.copyOf()
        val media=EncryptedAttachmentStore(f.mediaRoot,PermanentErasureFixture.Ledger(f.gateway),mediaKey).also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
        val edits=EncryptedOriginalEditStore(f.editRoot,editKey).also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
        val closed=AtomicBoolean(); val wiped=AtomicBoolean(); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val owner=DrainingRecordsStorage(f.gateway,media,edits,{ closed.set(true) },{ wiped.set(true) })
        val erase=SqlPermanentErasureRepository(f.gateway,media,edits,f.packages) { if(it=="leased") { entered.countDown(); check(release.await(3,TimeUnit.SECONDS)) } }
        val pool=Executors.newFixedThreadPool(2)
        try {
            val operation=pool.submit { runBlocking { assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { erase.erase(intent) } } } }
            assertTrue(entered.await(3,TimeUnit.SECONDS)); owner.close()
            assertThrows(IllegalStateException::class.java) { f.gateway.transaction {} }
            assertThrows(IllegalStateException::class.java) { runBlocking { media.recover() } }
            assertThrows(IllegalStateException::class.java) { runBlocking { edits.pending() } }
            assertTrue(mediaKey.any { it!=0.toByte() }); assertTrue(editKey.any { it!=0.toByte() })
            val awaiting=CountDownLatch(1); val drained=pool.submit { awaiting.countDown(); owner.awaitClosed() }
            assertTrue(awaiting.await(2,TimeUnit.SECONDS)); assertFalse(closed.get()); assertFalse(wiped.get()); assertFalse(drained.isDone)
            release.countDown(); operation.get(3,TimeUnit.SECONDS); drained.get(3,TimeUnit.SECONDS)
            assertTrue(closed.get()); assertTrue(wiped.get()); assertTrue(mediaKey.all { it==0.toByte() }); assertTrue(editKey.all { it==0.toByte() })
            owner.awaitClosed()
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)); media.close(); edits.close() }
    }
    @Test(timeout=12000) fun countedSqlCallbackRejectsSelfWaitBeforeAnAlreadyStartedFileWaiterCanDeadlock(): Unit = PermanentErasureFixture().use { f ->
        val id=f.entry(); val locked=CountDownLatch(1); val closed=AtomicBoolean()
        val media=EncryptedAttachmentStore(f.mediaRoot,PermanentErasureFixture.Ledger(f.gateway),PermanentErasureFixture.KEY.copyOf()) { if(it=="locked") locked.countDown() }
            .also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
        val edits=EncryptedOriginalEditStore(f.editRoot,PermanentErasureFixture.KEY.copyOf()).also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
        val owner=DrainingRecordsStorage(f.gateway,media,edits,{closed.set(true)},{}); val pool=Executors.newSingleThreadExecutor()
        var waiter: Future<*>?=null
        try {
            f.gateway.transaction {
                waiter=pool.submit { runBlocking { try { media.list(id,1) } catch (_: IllegalStateException) {} } }
                assertTrue(locked.await(3,TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { owner.awaitClosed() }
                assertFalse(closed.get())
            }
            waiter!!.get(3,TimeUnit.SECONDS); owner.awaitClosed(); assertTrue(closed.get())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)); media.close(); edits.close() }
    }
    @Test(timeout=12000) fun countedEditCallbackMaySealButCannotSelfWaitAndExternalOwnerCompletesRetirement(): Unit = PermanentErasureFixture().use { f ->
        val id=f.entry(); val closed=AtomicBoolean(); lateinit var owner: DrainingRecordsStorage
        val media=EncryptedAttachmentStore(f.mediaRoot,PermanentErasureFixture.Ledger(f.gateway),PermanentErasureFixture.KEY.copyOf())
            .also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
        val edits=EncryptedOriginalEditStore(f.editRoot,PermanentErasureFixture.KEY.copyOf()) { if(it=="locked") {
            owner.close(); assertThrows(IllegalStateException::class.java) { owner.awaitClosed() }; assertFalse(closed.get())
        } }.also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
        owner=DrainingRecordsStorage(f.gateway,media,edits,{closed.set(true)},{}); val pool=Executors.newSingleThreadExecutor()
        try {
            pool.submit { runBlocking { assertThrows(IllegalStateException::class.java) { runBlocking { edits.write(null,f.draft(id)) } } } }.get(3,TimeUnit.SECONDS)
            owner.awaitClosed(); assertTrue(closed.get())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)); media.close(); edits.close() }
    }
    @Test fun failedDatabaseCloseNeverWipesSharedKeyOrTurnsASecondAwaitIntoAnAcknowledgement():Unit=PermanentErasureFixture().use{f->
        val media=EncryptedAttachmentStore(f.mediaRoot,PermanentErasureFixture.Ledger(f.gateway),PermanentErasureFixture.KEY.copyOf())
        val edits=EncryptedOriginalEditStore(f.editRoot,PermanentErasureFixture.KEY.copyOf())
        val shared=ByteArray(32){9};var closes=0;var wipes=0
        val owner=DrainingRecordsStorage(f.gateway,media,edits,{closes++;error("Synthetic native close outcome unknown")},{wipes++;shared.fill(0)})
        try{
            val first=assertThrows(ReminderRuntimeClosureUnconfirmed::class.java){owner.awaitClosed()}
            assertArrayEquals(ByteArray(32){9},shared);assertEquals(0,wipes)
            assertSame(first,assertThrows(ReminderRuntimeClosureUnconfirmed::class.java){owner.awaitClosed()})
            assertEquals(1,closes);assertEquals(0,wipes)
            assertThrows(IllegalStateException::class.java){f.gateway.transaction{}}
        }finally{media.close();edits.close();shared.fill(0)}
    }

}
