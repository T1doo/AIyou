package com.t1doo.aiyou.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.ModelConnectionStorageFailure
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compile/inventory evidence only until an actual native runner executes this method. */
@RunWith(AndroidJUnit4::class)
class ModelConnectionRecordsIntegrationTest {
    @Test fun actualThirteenMigrationAndColdCapabilityArchiveKeepConnectionVersionAndRevokeOldResults():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.noBackupFilesDir,"synthetic-capabilities-"+UUID.randomUUID()).also {check(it.mkdirs())}
        val file=File(directory,"records.db");val key=ByteArray(32){(it+39).toByte()};val stores=mutableListOf<EncryptedEntryRepository>()
        System.loadLibrary("sqlcipher")
        try {
            NativeRecordsSchemaFixture.create(file,key,13){sql ->
                sql.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
                sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,'合成原文',?,55)",arrayOf(OriginalExports.hash("合成原文")))
                sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
                sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            }
            fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
            val first=open();NativeRecordsSchemaFixture.assertCurrentSchema(first.database.openHelper.writableDatabase)
            assertTrue(first.modelCapabilities.profiles().isEmpty())
            val c=first.modelConnections.change(ModelConnectionChange("native-profile",0,"create",ModelConnectionDetails("合成服务",
                "https://example.test/custom/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION))))!!
            val bound=first.localModelConnections.secrets.let {it.apply(it.prepare(c,"key","SYNTHETIC_ONLY_NATIVE_PROBE".toCharArray()))}
            fun bytes(owner:EncryptedEntryRepository)=owner.database.openHelper.writableDatabase.query("SELECT payload FROM model_configurations WHERE id='native-profile'")
                .use {check(it.moveToFirst());it.getBlob(0)}
            val before=bytes(first);val ticket=first.modelCapabilities.begin(bound,"native-probe",100)
            assertTrue(first.modelCapabilities.admit(ticket));assertFalse(first.modelCapabilities.admit(ticket))
            val profile=first.modelCapabilities.finish(ticket,TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,101))
            assertArrayEquals(before,bytes(first));assertEquals(bound,first.modelConnections.configurations().single());first.closeAndAwait()
            val cold=open();assertEquals(listOf(profile),cold.modelCapabilities.profiles());assertArrayEquals(before,bytes(cold))
            val resumed=cold.modelCapabilities.begin(bound,"native-probe",100);assertFalse(cold.modelCapabilities.admit(resumed))
            assertEquals(profile,cold.modelCapabilities.finish(resumed,profile.observation))
            cold.localModelConnections.secrets.revoke(bound,"revoke");assertTrue(cold.modelCapabilities.profiles().isEmpty())
            assertThrows(ModelConnectionConflict::class.java){cold.modelCapabilities.finish(resumed,profile.observation)}
            assertEquals("合成原文",cold.entries().single().originalText);assertEquals(ProcessingPolicy.LOCAL_ONLY,cold.entries().single().policy)
            assertEquals("合成独立草稿",cold.readDraft()!!.text)
        } finally {stores.forEach {it.closeAndAwait()};key.fill(0);directory.deleteRecursively()}
    }
    @Test fun nativeColdVaultReferenceAndSameOwnerStrictCommitRejectRevocationWithoutChangingOriginals():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.noBackupFilesDir,"synthetic-vault-"+UUID.randomUUID()).also { check(it.mkdirs()) }
        val file=File(directory,"records.db");val key=ByteArray(32){(it+91).toByte()};val stores=mutableListOf<EncryptedEntryRepository>()
        try {
            fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
            val first=open();val draft=first.updateDraft("合成原文");val saved=first.saveDraft(draft,55,"UTC")
            assertEquals(ProcessingPolicy.LOCAL_ONLY,saved.policy)
            val config=first.modelConnections.change(ModelConnectionChange("native-config",0,"create",
                ModelConnectionDetails("合成本机服务","https://example.test/custom/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.ORGANIZATION))))!!
            val input="SYNTHETIC_ONLY_NATIVE_MATERIAL".toCharArray();val vault=first.localModelConnections.secrets
            val bound=vault.apply(vault.prepare(config,"put",input));assertTrue(input.all { it=='\u0000' })
            val lease=vault.resolve(bound.secretRef!!)!!;first.closeAndAwait();assertTrue(lease.isClosed)
            val cold=open();val local=cold.localModelConnections;assertTrue(local.secrets.current(bound.secretRef!!))
            val source=SourceVersion(saved.id,1);val now=Instant.parse("2026-10-19T09:00:00Z");val connection=bound.textConnection(TextPurpose.ORGANIZATION)
            assertNull(cold.sourceBoundText.updateAuthorization(source,0,ProcessingPolicy.REMOTE_ALLOWED,connection,
                ConsentGrant("synthetic-native-consent",1,source,connection,TextPurpose.ORGANIZATION,now.minusSeconds(1),now.plusSeconds(60))))
            val job=(local.text.queue(RemoteSendIntent(source,TextPurpose.ORGANIZATION),"synthetic-native-vault",{now}) as SourceQueueOutcome.Queued).job
            assertNull(local.text.admit(job){now});local.secrets.revoke(bound,"revoke")
            val result=local.text.commit(job,DerivedTextCandidate("合成候选",listOf(OriginalTextReference(source,0,4,"合成原文")))){now}
            assertEquals(SourceCommitDenial.AUTHORIZATION_CHANGED,(result as SourceCommitOutcome.Denied).reason)
            assertEquals("合成原文",cold.entries().single().originalText);assertNull(cold.readDraft())
            cold.database.openHelper.writableDatabase.query("SELECT count(*) FROM text_annotations").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
        } finally { stores.forEach { it.closeAndAwait() };key.fill(0);directory.deleteRecursively() }
    }
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
