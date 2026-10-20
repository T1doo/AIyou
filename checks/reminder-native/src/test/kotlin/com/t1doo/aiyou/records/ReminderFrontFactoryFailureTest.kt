package com.t1doo.aiyou.records

import android.database.sqlite.SQLiteDatabase
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual foreground openProtected + failure cleanup protocol + admission gate, with only the
 * native DB/key boundaries controlled. SQLite is real; this does not execute device SQLCipher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ReminderFrontFactoryFailureTest {
    private fun owners(f:NativeSqlFixture,bridge:ReminderOperationBridge)=ReminderProcessOwner(bridge,{true},{true},{error("No cold open")},{object:ReminderRuntimeDriver {
        override fun availability()=f.available
        override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule")
        override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel")
        override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("No post")
    }},{f.now}){f.fingerprint}
    @Test fun unreturnedRepositoryKeepsDatabaseAndMasterKeyQuarantinedUntilExplicitCleanupIsConfirmed():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val owners=owners(f,bridge);val root=Files.createTempDirectory("aiyou-failed-front-").toFile()
            val key=ByteArray(32){7};var factories=0;var closes=0;var allowClose=false;var db:SQLiteDatabase?=null
            try{
                val first=runCatching{owners.prepareFront<EntryRepository>{
                    factories++
                    EncryptedEntryRepository.openProtected(root,loadKey={purpose,existing->assertEquals(RepositoryKeyPurpose.DATABASE,purpose);assertFalse(existing);key}){_,raw,_,_->
                        db=SQLiteDatabase.create(null);db!!.execSQL("CREATE TABLE synthetic_partial (id INTEGER PRIMARY KEY)")
                        try{error("Synthetic constructor failure after DB open")}
                        finally{RecordOpenFailureProtocol.cleanup(raw,db){closes++;if(!allowClose)error("Synthetic database close failed");db!!.close()}}
                    }
                }}.exceptionOrNull()
                assertTrue(first is ReminderRuntimeClosureUnconfirmed);assertEquals(1,factories);assertTrue(db!!.isOpen);assertArrayEquals(ByteArray(32){7},key)
                assertTrue(runCatching{owners.prepareFront{factories++;f.foregroundRecords()}}.exceptionOrNull() is ReminderRuntimeClosureUnconfirmed)
                assertEquals(1,factories);assertTrue(db!!.isOpen);assertArrayEquals(ByteArray(32){7},key)
                assertThrows(IllegalStateException::class.java){bridge.gate.openOwner(true)}
                allowClose=true
                val prepared=owners.prepareFront{factories++;f.foregroundRecords()}
                assertEquals(2,factories);assertEquals(3,closes);assertFalse(db!!.isOpen);assertTrue(key.all{it==0.toByte()})
                prepared.second.closeStore{}
            }finally{db?.close();key.fill(0);root.deleteRecursively();bridge.close()}
        }
    }
    @Test fun factoryCanBeRetriedAfterRealCleanupReturnsButNeverBeforeIt():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val owners=owners(f,bridge);val key=ByteArray(32){8};var factories=0;var closed=false
            try{
                assertTrue(runCatching{owners.prepareFront<EntryRepository>{
                    factories++
                    val db=SQLiteDatabase.create(null)
                    try{error("Synthetic repository constructor failure")}
                    finally{RecordOpenFailureProtocol.cleanup(key,db){db.close();closed=!db.isOpen}}
                }}.isFailure)
                assertTrue(closed);assertTrue(key.all{it==0.toByte()})
                val prepared=owners.prepareFront{factories++;f.foregroundRecords()};assertEquals(2,factories)
                prepared.second.closeStore{}
            }finally{key.fill(0);bridge.close()}
        }
    }
    @Test fun cancellationDuringFactoryClosesAndClearsOnTheProductionWorkerBeforeSameProcessReopen():Unit=runBlocking {
        cancellationDuringFactory(failClose=false)
    }
    @Test fun cancellationDuringFactoryWithActualCloseFailureKeepsSealedResourcesAndRejectsAnotherFactory():Unit=runBlocking {
        cancellationDuringFactory(failClose=true)
    }
    private suspend fun cancellationDuringFactory(failClose:Boolean){
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val owners=owners(f,bridge)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val factories=AtomicInteger();val closes=AtomicInteger();val returned=AtomicBoolean()
            val failure=AtomicReference<Throwable?>();val openThread=AtomicReference<Thread>();val closeThread=AtomicReference<Thread>()
            val key=ByteArray(32){11};val db=AtomicReference<SQLiteDatabase>()
            val opening=scope.async {
                try{
                    owners.prepareFront {
                        factories.incrementAndGet();openThread.set(Thread.currentThread());db.set(SQLiteDatabase.create(null))
                        entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                        object:EntryRepository by f.foregroundRecords(),AutoCloseable,AwaitableRecordStorage {
                            override fun close(){closes.incrementAndGet();closeThread.set(Thread.currentThread())}
                            override fun awaitClosed(){
                                if(failClose)error("Synthetic actual close failure after opening cancellation")
                                db.get().close();key.fill(0)
                            }
                        }
                    }.also{returned.set(true)}
                }catch(caught:Throwable){failure.set(caught);throw caught}
            }
            try{
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                opening.cancel(CancellationException("Synthetic factory caller cancelled"));release.countDown()
                withTimeout(5_000){opening.join()}
                assertTrue(opening.isCancelled);assertFalse(returned.get());assertNotNull(failure.get())
                assertEquals(1,factories.get());assertEquals(1,closes.get())
                assertSame(openThread.get(),closeThread.get());assertTrue(closeThread.get().name.startsWith("AIyou-reminder-operations"))
                if(failClose){
                    assertTrue(db.get().isOpen);assertArrayEquals(ByteArray(32){11},key)
                    assertTrue(runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}.isFailure)
                    assertEquals(1,factories.get());assertTrue(db.get().isOpen);assertArrayEquals(ByteArray(32){11},key)
                    assertThrows(IllegalStateException::class.java){bridge.gate.openOwner(true)}
                }else{
                    assertFalse(db.get().isOpen);assertTrue(key.all{it==0.toByte()})
                    val fresh=owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}
                    assertEquals(2,factories.get());fresh.second.closeStore{}
                    assertTrue(failure.get() is CancellationException)
                }
            }finally{release.countDown();opening.cancel();opening.join();scope.cancel();db.get()?.close();key.fill(0);bridge.close()}
        }
    }
}
