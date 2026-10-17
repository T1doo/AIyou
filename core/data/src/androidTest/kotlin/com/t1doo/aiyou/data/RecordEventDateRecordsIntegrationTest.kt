package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SQLCipher migration and cold owners. Compilation alone does not execute these checks. */
@RunWith(AndroidJUnit4::class)
class RecordEventDateRecordsIntegrationTest {
    private lateinit var context:Context
    private lateinit var directory:File
    private val key=ByteArray(32){(it+47).toByte()}
    private val stores=mutableListOf<EncryptedEntryRepository>()
    private val file get()=File(directory,"records.db")
    @Before fun before(){
        context=InstrumentationRegistry.getInstrumentation().targetContext
        directory=File(context.noBackupFilesDir,"event-dates-"+UUID.randomUUID()).also{check(it.mkdirs())}
        System.loadLibrary("sqlcipher")
    }
    @After fun after(){try{stores.forEach{it.closeAndAwait()}}finally{key.fill(0);if(::directory.isInitialized)directory.deleteRecursively()}}

    @Test fun nativeTenMigrationAndColdSetCorrectClearKeepCaptureOriginalAndDraft():Unit=runBlocking {
        val text="合成第十版原文🙂"
        NativeRecordsSchemaFixture.create(file,key,10){sql ->
            sql.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)",arrayOf(text,OriginalExports.hash(text)))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
        }
        val store=open();NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        val original=store.entries().single();val revisions=store.revisions(original.id)
        assertNull(store.eventDates.read(original.id).date);assertEquals(0L,store.eventDates.read(original.id).version)
        val first=intent(store,original,0,RecordEventDate("2024-02-29","Pacific/Kiritimati"),"synthetic-first")
        store.eventDates.set(first);store.closeAndAwait()
        val cold=open();assertEquals(first.date,cold.eventDates.read(original.id).date)
        assertEquals(1L,cold.eventDates.set(first).version)
        cold.eventDates.set(intent(cold,original,1,RecordEventDate("2001-01-01","Asia/Shanghai"),"synthetic-correct"))
        val clear=intent(cold,original,2,null,"synthetic-clear");cold.eventDates.set(clear);cold.closeAndAwait()
        val cleared=open();assertEquals(3L,cleared.eventDates.set(clear).version);assertNull(cleared.eventDates.read(original.id).date)
        assertEquals(original,cleared.entries().single());assertEquals(revisions,cleared.revisions(original.id))
        assertEquals(TextDraft("draft",7,"合成独立草稿"),cleared.readDraft())
    }

    @Test fun nativeAbortIndependentOwnerConflictAndExplicitEventSearchRemainLocal():Unit=runBlocking {
        val first=open();val original=first.saveDraft(first.updateDraft("合成 needle 原文"),55,"UTC");val second=open()
        val sql=first.database.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TRIGGER synthetic_date_abort BEFORE INSERT ON entry_event_dates BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        val winner=intent(first,original,0,RecordEventDate("2000-01-01","UTC"),"synthetic-winner")
        assertThrows(RecordEventDateStorageFailure::class.java){first.eventDates.set(winner)}
        assertNull(second.eventDates.read(original.id).date);sql.execSQL("DROP TRIGGER synthetic_date_abort")
        first.eventDates.set(winner)
        assertThrows(RecordEventDateConflict::class.java){second.eventDates.set(intent(second,original,0,null,"synthetic-stale"))}
        assertEquals(winner.date,second.eventDates.set(winner).date)
        val event=second.localSearch.candidates(LocalSearchRequest("needle",from="2000-01-01",through="2000-01-01",dateBasis=RecordDateBasis.EVENT))
        assertEquals(listOf(original.id),event.entries.map{it.id});assertEquals(winner.date,event.eventDates!!.getValue(original.id).date)
        assertTrue(second.localSearch.candidates(LocalSearchRequest("needle",from="2000-01-01",through="2000-01-01")).entries.isEmpty())
        assertEquals(ProcessingPolicy.LOCAL_ONLY,second.entries().single().policy)
        first.closeAndAwait();second.closeAndAwait();assertEquals(original,open().entries().single())
    }
    private fun intent(store:EncryptedEntryRepository,record:StoredEntry,version:Long,date:RecordEventDate?,id:String)=RecordEventDateMutation(record.id,
        record.currentRevision,OriginalExports.hash(record.originalText),version,date,id,store.localProcessing.versions().getValue(record.id).generation)
    private fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
}
