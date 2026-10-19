package com.t1doo.aiyou.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.ModelConnectionStorageFailure
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compile/inventory evidence only until an actual native runner executes this method. */
@RunWith(AndroidJUnit4::class)
class ModelConnectionRecordsIntegrationTest {
    @Test fun actualTwelveMigrationAndColdMetadataRemainSeparateFromKeysPoliciesAndNativeOriginals():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.noBackupFilesDir,"synthetic-connections-"+UUID.randomUUID()).also { check(it.mkdirs()) }
        val file=File(directory,"records.db");val key=ByteArray(32){(it+71).toByte()}
        val stores=mutableListOf<EncryptedEntryRepository>()
        System.loadLibrary("sqlcipher")
        try {
            NativeRecordsSchemaFixture.create(file,key,12){sql->
                sql.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
                sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,'合成原文',?,55)",arrayOf(OriginalExports.hash("合成原文")))
                sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
                sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            }
            fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
            val first=open();NativeRecordsSchemaFixture.assertCurrentSchema(first.database.openHelper.writableDatabase)
            val original=first.entries().single();val draft=first.readDraft()
            val d=ModelConnectionDetails("合成服务","https://example.test/custom/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION))
            val create=ModelConnectionChange("synthetic-config",0,"synthetic-create",d)
            val saved=first.modelConnections.change(create)!!;first.closeAndAwait()
            val cold=open();assertEquals(listOf(saved),cold.modelConnections.configurations());assertEquals(saved,cold.modelConnections.change(create))
            cold.database.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER synthetic_config_abort BEFORE UPDATE ON model_configurations BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
            assertThrows(ModelConnectionStorageFailure::class.java){cold.modelConnections.change(ModelConnectionChange(saved.id,1,"synthetic-delete",null))}
            assertEquals(listOf(saved),cold.modelConnections.configurations());assertEquals(original,cold.entries().single());assertEquals(draft,cold.readDraft())
            cold.database.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_config_abort")
            assertNull(cold.modelConnections.change(ModelConnectionChange(saved.id,1,"synthetic-delete",null)))
            cold.closeAndAwait();val empty=open();assertTrue(empty.modelConnections.configurations().isEmpty())
            assertEquals(ProcessingPolicy.LOCAL_ONLY,empty.entries().single().policy)
        } finally { stores.forEach { it.closeAndAwait() };key.fill(0);directory.deleteRecursively() }
    }
}
