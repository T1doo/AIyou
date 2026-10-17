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

/** Actual SQLCipher/Room owners. Compiling these methods does not execute them. */
@RunWith(AndroidJUnit4::class)
class RecordFavoriteRecordsIntegrationTest {
    private lateinit var context:Context
    private lateinit var directory:File
    private val key=ByteArray(32){(it+81).toByte()}
    private val stores=mutableListOf<EncryptedEntryRepository>()
    private val file get()=File(directory,"records.db")
    @Before fun before(){
        context=InstrumentationRegistry.getInstrumentation().targetContext
        directory=File(context.noBackupFilesDir,"favorites-"+UUID.randomUUID()).also{check(it.mkdirs())}
        System.loadLibrary("sqlcipher")
    }
    @After fun after(){try{stores.forEach{it.closeAndAwait()}}finally{key.fill(0);if(::directory.isInitialized)directory.deleteRecursively()}}

    @Test fun encryptedVersionEightMigrationPreservesRowsAndColdFavoriteDefaults():Unit=runBlocking {
        val text="合成第八版原文🙂"
        NativeRecordsSchemaFixture.create(file,key,8){sql->
            sql.execSQL("INSERT INTO entries VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)",arrayOf(text,OriginalExports.hash(text)))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            sql.execSQL("INSERT INTO record_package_receipts VALUES('independent','legacy',1,'PACKAGE_RESTORE','CONFIRMED:synthetic')")
        }
        val store=open();NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        assertEquals(text,store.entries().single().originalText)
        assertEquals(TextDraft("draft",7,"合成独立草稿"),store.readDraft())
        assertFalse(store.favorites.read("legacy").isFavorite);assertEquals(0L,store.favorites.read("legacy").version)
        store.database.openHelper.writableDatabase.query("SELECT state FROM record_package_receipts WHERE id='independent'").use{
            assertTrue(it.moveToFirst());assertEquals("CONFIRMED:synthetic",it.getString(0))
        }
        store.closeAndAwait();val cold=open();NativeRecordsSchemaFixture.assertCurrentSchema(cold.database.openHelper.writableDatabase)
        assertEquals(text,cold.entries().single().originalText);assertFalse(cold.favorites.read("legacy").isFavorite)
    }

    @Test fun nativeFavoriteReplayCombinesFiltersAndSurvivesEditTrashRestoreAndReopen():Unit=runBlocking {
        val store=open();val record=store.saveDraft(store.updateDraft("合成 needle 原文🙂"),55,"UTC")
        store.manualTags.replace(ManualTagMutation(record.id,0,listOf("合成标签"),"synthetic-tag"))
        var batches=0
        do{check(++batches<=100);val batch=store.localSearch.batch()}while(batch.remaining)
        val held=store.localSearch.candidates(LocalSearchRequest("needle"))
        val first=intent(store,record,0,true,"synthetic-favorite")
        assertTrue(store.favorites.set(first).isFavorite)
        assertFalse(store.localSearch.resolve(held,record.id){fail("Old proof opened")})
        val query=LocalSearchRequest("needle",from="1970-01-01",through="1970-01-01",tag="合成标签",onlyFavorites=true)
        assertEquals(listOf(record.id),store.localSearch.candidates(query).entries.map{it.id})
        val revised=store.revise(record.id,1,"合成 needle 新版",60)
        assertTrue(store.favorites.set(first).isFavorite);assertEquals(1L,store.favorites.read(record.id).version)
        var access=store.recycleBin.read(record.id)
        store.recycleBin.apply(RecycleMutation(SourceVersion(record.id,2),access.generation,false,"synthetic-trash"))
        assertFalse(store.favorites.snapshots().containsKey(record.id))
        access=store.recycleBin.read(record.id)
        store.recycleBin.apply(RecycleMutation(SourceVersion(record.id,2),access.generation,true,"synthetic-restore"))
        store.closeAndAwait();val cold=open()
        assertTrue(cold.favorites.read(record.id).isFavorite);assertEquals(revised,cold.entries().single())
        assertTrue(cold.favorites.set(intent(cold,revised,1,false,"synthetic-unfavorite")).let{!it.isFavorite})
        assertTrue(cold.localSearch.candidates(query).entries.isEmpty())
    }

    @Test fun nativeFavoriteAbortAndIndependentOwnerConflictPreserveChoiceAndOriginal():Unit=runBlocking {
        val first=open();val record=first.saveDraft(first.updateDraft("合成并发原文"),55,"UTC");val second=open()
        val sql=first.database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER synthetic_favorite_abort BEFORE UPDATE OF favoriteVersion ON entries BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(RecordFavoriteStorageFailure::class.java){first.favorites.set(intent(first,record,0,true,"synthetic-abort"))}
        assertEquals(0L,second.favorites.read(record.id).version)
        sql.execSQL("DROP TRIGGER synthetic_favorite_abort")
        val winner=intent(first,record,0,true,"synthetic-winner");first.favorites.set(winner)
        assertThrows(RecordFavoriteConflict::class.java){second.favorites.set(intent(second,record,0,false,"synthetic-stale"))}
        assertTrue(second.favorites.set(winner).isFavorite);assertEquals(record,second.entries().single())
        first.closeAndAwait();second.closeAndAwait();assertEquals(1L,open().favorites.read(record.id).version)
    }
    private fun intent(store:EncryptedEntryRepository,record:StoredEntry,version:Long,value:Boolean,id:String)=RecordFavoriteMutation(record.id,
        record.currentRevision,OriginalExports.hash(record.originalText),version,value,id,store.localProcessing.versions().getValue(record.id).generation)
    private fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
}
