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

/** SOURCE ONLY until actual KSP v11/v12 exports, approved SDK and native runner exist. */
@RunWith(AndroidJUnit4::class)
class ManualPeopleRecordsIntegrationTest {
    private lateinit var context:Context
    private lateinit var directory:File
    private val key=ByteArray(32){(it+59).toByte()}
    private val stores=mutableListOf<EncryptedEntryRepository>()
    private val file get()=File(directory,"records.db")
    @Before fun before(){context=InstrumentationRegistry.getInstrumentation().targetContext
        directory=File(context.noBackupFilesDir,"manual-people-"+UUID.randomUUID()).also{check(it.mkdirs())};System.loadLibrary("sqlcipher")}
    @After fun after(){try{stores.forEach{it.closeAndAwait()}}finally{key.fill(0);if(::directory.isInitialized)directory.deleteRecursively()}}
    @Test fun actualElevenMigrationAndColdManualNamesPreserveOriginalCaptureAndIndependentDraft():Unit=runBlocking {
        val text="合成第十一版原文"
        NativeRecordsSchemaFixture.create(file,key,11){sql->
            sql.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)",arrayOf(text,OriginalExports.hash(text)))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO remote_source_access VALUES('legacy',1,0,NULL,NULL)")
            sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
        }
        val first=open();NativeRecordsSchemaFixture.assertCurrentSchema(first.database.openHelper.writableDatabase)
        val original=first.entries().single();val history=first.revisions("legacy")
        assertEquals(0L,first.manualPeople.read("legacy").version)
        val add=ManualPeopleMutation(first.manualPeople.read("legacy"),listOf("小林"),"synthetic-add")
        first.manualPeople.replace(add);first.closeAndAwait()
        val cold=open();assertEquals(listOf("小林"),cold.manualPeople.read("legacy").names)
        assertEquals(1L,cold.manualPeople.replace(add).version)
        cold.manualPeople.replace(ManualPeopleMutation(cold.manualPeople.read("legacy"),emptyList(),"synthetic-remove"));cold.closeAndAwait()
        val cleared=open();assertTrue(cleared.manualPeople.read("legacy").names.isEmpty());assertEquals(2L,cleared.manualPeople.read("legacy").version)
        assertEquals(original,cleared.entries().single());assertEquals(history,cleared.revisions("legacy"));assertEquals(TextDraft("draft",7,"合成独立草稿"),cleared.readDraft())
    }
    @Test fun nativeAbortAndIndependentOwnerConflictFencePersonProofWithoutRemoteGrant():Unit=runBlocking {
        val first=open();val original=first.saveDraft(first.updateDraft("合成 needle 原文"),55,"UTC");val second=open()
        val intent=ManualPeopleMutation(first.manualPeople.read(original.id),listOf("明确称呼"),"synthetic-winner")
        val sql=first.database.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TRIGGER synthetic_people_abort BEFORE INSERT ON entry_manual_people BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(ManualPeopleStorageFailure::class.java){first.manualPeople.replace(intent)}
        assertTrue(second.manualPeople.read(original.id).names.isEmpty());sql.execSQL("DROP TRIGGER synthetic_people_abort")
        first.manualPeople.replace(intent);assertEquals(1L,second.manualPeople.replace(intent).version)
        assertThrows(ManualPeopleConflict::class.java){second.manualPeople.replace(ManualPeopleMutation(intent.before,emptyList(),"synthetic-stale"))}
        val hit=second.localSearch.candidates(LocalSearchRequest("needle",person="明确称呼"))
        assertEquals(listOf(original.id),hit.entries.map{it.id});assertTrue(second.localSearch.publishCurrent(hit){})
        first.manualPeople.replace(ManualPeopleMutation(first.manualPeople.read(original.id),emptyList(),"synthetic-clear"))
        assertFalse(second.localSearch.publishCurrent(hit){});assertTrue(second.localSearch.candidates(LocalSearchRequest("needle",person="明确称呼")).entries.isEmpty())
        assertEquals(ProcessingPolicy.LOCAL_ONLY,second.entries().single().policy)
    }
    private fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
}
