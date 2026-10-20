package com.t1doo.aiyou.records

import android.database.sqlite.SQLiteDatabase
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
}
