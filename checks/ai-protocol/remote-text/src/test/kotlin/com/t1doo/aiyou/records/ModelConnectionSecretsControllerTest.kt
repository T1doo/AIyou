package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual controller and encrypted-cell SQL, synthetic input only; no Android/device/provider claim. */
class ModelConnectionSecretsControllerTest {
    @Test fun saveReplaceAndExplicitRevokeKeepOriginalsLocalAndNeverGrantCapabilitiesOrConsent():Unit=runBlocking {
        Harness().use { h ->
            val session=h.open();h.begin(session)
            h.controller.begin(session);assertNull(h.controller.visible().editor)
            val input=material();h.controller.saveSecret(session,h.editor(),input);h.drain();assertWiped(input)
            val first=h.configuration();assertEquals(2L,first.version);assertNotNull(first.secretRef)
            assertFalse(String(h.payload(),Charsets.ISO_8859_1).contains(MATERIAL))
            val lease=h.dataVault.resolve(first.secretRef!!)!!
            h.begin(session);val replacement=material();h.controller.saveSecret(session,h.editor(),replacement);h.drain();assertWiped(replacement)
            val second=h.configuration();assertEquals(3L,second.version);assertNotEquals(first.secretRef,second.secretRef)
            assertTrue(lease.isClosed);assertFalse(h.dataVault.current(first.secretRef!!))
            h.begin(session);h.controller.revokeSecret(session,h.editor());h.drain();assertEquals(second,h.configuration())
            h.controller.confirmSecretRevoke(session,h.editor());h.controller.revokeSecret(session,h.editor());h.drain()
            assertEquals(4L,h.configuration().version);assertNull(h.configuration().secretRef)
            assertEquals("LOCAL_ONLY",h.fixture.db.read("SELECT privacyPolicy FROM entries").single().single())
            assertEquals("合成独立草稿",h.fixture.db.read("SELECT text FROM capture_draft").single().single())
            for(table in listOf("remote_source_access","remote_text_jobs","text_annotations","local_jobs"))
                assertTrue(h.fixture.db.read("SELECT * FROM $table").isEmpty())
        }
    }
    @Test fun lostApplyAcknowledgementRetainsOnlySameSealedIntentAcrossSameOwnerRecreation():Unit=runBlocking {
        Harness().use { h ->
            val first=h.open();h.begin(first);h.loseNextUpdateAcknowledgement()
            val input=material();h.controller.saveSecret(first,h.editor(),input);h.drain();assertWiped(input)
            val uncertain=h.editor();val operation=uncertain.pending;assertNotNull(operation);assertFalse(uncertain.busy)
            val before=h.payload();assertEquals(2L,h.configuration().version);assertEquals(1,h.vault.prepares)
            assertFalse(h.controller.state.value.toString().contains(MATERIAL));assertFalse(uncertain.toString().contains(MATERIAL))
            h.controller.close(first,retainEditor=true);val second=h.controller.open(h.catalog)
            assertSame(operation,h.editor().pending);h.controller.retrySecret(second,h.editor())
            assertEquals(1,h.vault.applied.size) // Fresh read is still busy, so retry is not admitted.
            h.drain();h.controller.retrySecret(first,uncertain);h.drain();assertEquals(1,h.vault.applied.size)
            h.controller.retrySecret(second,h.editor());h.drain()
            assertNull(h.controller.visible().secretEditor);assertEquals(1,h.vault.prepares)
            assertEquals(2,h.vault.applied.size);assertSame(h.vault.applied[0],h.vault.applied[1])
            assertEquals(2L,h.configuration().version);assertArrayEquals(before,h.payload())
        }
    }
    @Test fun postWriteReadFailureAndRevokeAcknowledgementLossRetryTheirOriginalOperations():Unit=runBlocking {
        Harness().use { h ->
            val session=h.open();h.begin(session)
            h.vault.afterApply={h.catalog.failNextRead=true;h.vault.afterApply=null}
            h.controller.saveSecret(session,h.editor(),material());h.drain()
            val put=h.editor().pending;assertNotNull(put);assertEquals(2L,h.configuration().version)
            h.controller.retrySecret(session,h.editor());h.drain();assertEquals(1,h.vault.prepares)
            assertSame(h.vault.applied[0],h.vault.applied[1]);assertEquals(2L,h.configuration().version)
            h.begin(session);h.controller.confirmSecretRevoke(session,h.editor());h.loseNextUpdateAcknowledgement()
            h.controller.revokeSecret(session,h.editor());h.drain();val revoke=h.editor().pending
            assertTrue(revoke is ModelConnectionSecretMutation.Revoke);assertNull(h.configuration().secretRef)
            val bytes=h.payload();h.controller.retrySecret(session,h.editor());h.drain()
            assertEquals(h.vault.revoked[0],h.vault.revoked[1]);assertEquals(3L,h.configuration().version)
            assertArrayEquals(bytes,h.payload());assertNull(h.controller.visible().secretEditor)
        }
    }
    @Test fun failedPrepareAndConflictingVersionsNeverOverwriteAnotherConfiguration():Unit=runBlocking {
        Harness().use { h ->
            val session=h.open();h.begin(session);h.vault.beforePrepare={throw IOException("Synthetic prepare unavailable")}
            val failed=material();h.controller.saveSecret(session,h.editor(),failed);h.drain();assertWiped(failed)
            assertNull(h.editor().pending);assertFalse(h.editor().conflict);assertFalse(h.editor().busy);assertEquals(1L,h.configuration().version)
            h.vault.beforePrepare=null
            h.catalog.change(ModelConnectionChange("config",1,"other",ModelConnectionCatalogTest.details(name="合成其他修改")))
            val stale=material();h.controller.saveSecret(session,h.editor(),stale);h.drain();assertWiped(stale)
            assertTrue(h.editor().conflict);assertNull(h.editor().pending);assertEquals(2L,h.configuration().version)
            h.controller.cancelSecret(session,h.editor());h.drain();h.begin(session)
            h.vault.afterPrepare={h.catalog.change(ModelConnectionChange("config",2,"later",ModelConnectionCatalogTest.details(name="合成稍后版本")))}
            val later=material();h.controller.saveSecret(session,h.editor(),later);h.drain();assertWiped(later)
            val conflict=h.editor();assertTrue(conflict.conflict);assertNotNull(conflict.pending)
            h.controller.retrySecret(session,conflict);h.drain();assertEquals(3L,h.configuration().version)
            assertNull(h.configuration().secretRef);assertEquals("合成稍后版本",h.configuration().details.name)
        }
    }
    @Test fun retiredQueuedInputIsWipedAndRetirementAfterPrepareCannotApplyOrFillNewPage():Unit=runBlocking {
        Harness().use { h ->
            val first=h.open();h.begin(first);val old=h.editor();val queued=material()
            h.controller.saveSecret(first,old,queued);h.controller.close(first);assertWiped(queued)
            val second=h.controller.open(h.catalog);h.drain();assertEquals(0,h.vault.prepares);assertNull(h.configuration().secretRef)
            h.begin(second);val during=material();var replacement=0L
            h.vault.beforePrepare={input ->
                h.controller.close(second);replacement=h.controller.open(h.catalog)
                assertTrue("Retirement must not alter input already handed to the worker",
                    input.size==MATERIAL.length && input.indices.all { input[it]==MATERIAL[it] })
            }
            h.controller.saveSecret(second,h.editor(),during);h.one();assertWiped(during)
            assertEquals(0,h.vault.applied.size);h.drain()
            val third=replacement;h.begin(third);val input=material();h.vault.beforePrepare=null
            h.vault.afterPrepare={h.controller.close(third);replacement=h.controller.open(h.catalog)}
            h.controller.saveSecret(third,h.editor(),input);h.one();assertWiped(input)
            assertEquals(0,h.vault.applied.size);assertTrue(h.controller.visible().busy);assertTrue(h.controller.visible().configurations.isEmpty())
            h.drain();assertEquals(replacement,h.controller.visible().session);assertNull(h.controller.visible().secretEditor)
            val rejected=material();h.controller.saveSecret(first,old,rejected);assertWiped(rejected)
            assertEquals(1L,h.configuration().version);assertNull(h.configuration().secretRef)
        }
    }
    @Test fun admittedWriteMayCompleteAfterLeaveButItsLateCallbackCannotFillReplacementPage():Unit=runBlocking {
        Harness().use { h ->
            val first=h.open();h.begin(first);var updated=false;var replacement=0L
            h.fixture.db.afterSql={sql ->
                if(sql.startsWith("UPDATE model_configurations")) updated=true
                if(sql=="COMMIT"&&updated) { updated=false;h.controller.close(first);replacement=h.controller.open(h.catalog) }
            }
            val input=material();h.controller.saveSecret(first,h.editor(),input);h.one();assertWiped(input)
            assertTrue(h.controller.visible().busy);assertTrue(h.controller.visible().configurations.isEmpty())
            assertNull(h.controller.visible().secretEditor);assertEquals(2L,h.configuration().version)
            h.fixture.db.afterSql=null;h.drain();assertEquals(replacement,h.controller.visible().session)
            assertEquals(2L,h.controller.visible().configurations.single().version)
        }
    }
    @Test fun eitherCatalogOrVaultReplacementRejectsRetainedPendingAndOldActions():Unit=runBlocking {
        for(replaceVault in listOf(false,true)) Harness().use { h ->
            val first=h.open();h.begin(first);h.loseNextUpdateAcknowledgement()
            h.controller.saveSecret(first,h.editor(),material());h.drain();val old=h.editor();assertNotNull(old.pending)
            h.controller.close(first,retainEditor=true)
            if(replaceVault) h.replaceVault() else h.catalog=ObservedCatalog(h.dataVault)
            h.local=h.local.copy(modelConnectionOwner=h.catalog,modelSecretOwner=h.vault)
            val second=h.controller.open(h.catalog);assertNull(h.controller.visible().secretEditor);h.drain()
            val count=h.vault.applied.size;h.controller.retrySecret(first,old);h.drain();assertEquals(count,h.vault.applied.size)
            h.begin(second);val input=material();h.controller.saveSecret(second,h.editor(),input)
            h.local=h.local.copy(error="合成资料读取失败");assertNull(h.controller.visible().secretEditor);assertWiped(input)
            h.drain();assertEquals(2L,h.configuration().version)
        }
    }
    @Test fun invalidInputRejectedQueueAndDisposeClearEveryTransferredArrayWithoutWriting():Unit=runBlocking {
        Harness().use { h ->
            val session=h.open();h.begin(session)
            for(input in listOf(CharArray(0),"SYNTHETIC\nINVALID".toCharArray(),CharArray(2049){'X'})) {
                h.controller.saveSecret(session,h.editor(),input);assertWiped(input);assertFalse(h.editor().busy)
            }
            h.rejectQueue=true;val rejected=material();h.controller.saveSecret(session,h.editor(),rejected);assertWiped(rejected)
            assertFalse(h.editor().busy);assertNull(h.editor().pending);h.rejectQueue=false
            val queued=material();h.controller.saveSecret(session,h.editor(),queued);h.controller.dispose();assertWiped(queued)
            h.drain();assertEquals(0,h.vault.prepares);assertEquals(1L,h.configuration().version)
        }
    }
    private class ObservedCatalog(val delegate:SqlModelConnectionCatalog):ModelConnectionCatalog by delegate {
        var failNextRead=false
        override fun configurations():List<ModelConnectionConfiguration> {
            if(failNextRead) { failNextRead=false;throw IOException("Synthetic read acknowledgement loss") }
            return delegate.configurations()
        }
    }
    private class ObservedVault(val delegate:SqlModelConnectionCatalog):ConnectionSecretVault by delegate {
        var prepares=0;val applied=mutableListOf<PreparedConnectionSecret>()
        val revoked=mutableListOf<Pair<ModelConnectionConfiguration,String>>()
        var beforePrepare:((CharArray)->Unit)?=null;var afterPrepare:(()->Unit)?=null;var afterApply:(()->Unit)?=null
        override fun prepare(expected:ModelConnectionConfiguration,mutationId:String,ownedCharacters:CharArray):PreparedConnectionSecret {
            prepares++;beforePrepare?.invoke(ownedCharacters)
            return delegate.prepare(expected,mutationId,ownedCharacters).also { afterPrepare?.invoke() }
        }
        override fun apply(prepared:PreparedConnectionSecret):ModelConnectionConfiguration {
            applied+=prepared;return delegate.apply(prepared).also { afterApply?.invoke() }
        }
        override fun revoke(expected:ModelConnectionConfiguration,mutationId:String):ModelConnectionConfiguration {
            revoked+=expected to mutationId;return delegate.revoke(expected,mutationId)
        }
    }
    private class Harness:AutoCloseable {
        val fixture=ConnectionCatalogFixture()
        var dataVault=SqlModelConnectionCatalog(fixture.db,ConnectionSecretVaultTest.key());private set
        private val vaults=mutableListOf(dataVault)
        var catalog=ObservedCatalog(dataVault)
        var vault=ObservedVault(dataVault);private set
        var local=LocalRecordUiState(ready=true,opening=false,text="合成独立输入",modelConnectionOwner=catalog,modelSecretOwner=vault)
        var rejectQueue=false
        private val queue=ArrayDeque<suspend (EntryRepository)->Unit>()
        val controller=ModelConnectionsController({local},{if(rejectQueue)false else {queue.addLast(it);true}})
        private val store=object:EntryRepository {
            override val modelConnections:ModelConnectionCatalog get()=catalog
            override val localModelConnections get()=LocalModelConnectionServices(vault,
                RemoteTextTransactions(fixture.db,connectionCurrent=dataVault::connectionCurrent))
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String)=TextDraft("synthetic",1,text)
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No record save")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No record edit")
        }
        init { catalog.change(ModelConnectionCatalogTest.change("config",0,"create")) }
        suspend fun open():Long=controller.open(catalog).also { drain() }
        fun begin(session:Long) { controller.beginSecret(session,configuration()) }
        fun editor()=checkNotNull(controller.visible().secretEditor)
        fun configuration()=dataVault.configurations().single()
        fun payload()=ConnectionSecretVaultTest.payload(fixture)
        suspend fun one(){queue.removeFirst()(store)}
        suspend fun drain(){while(queue.isNotEmpty())one()}
        fun loseNextUpdateAcknowledgement() {
            var wrote=false
            fixture.db.afterSql={sql ->if(sql.startsWith("UPDATE model_configurations"))wrote=true
                if(sql=="COMMIT"&&wrote) { wrote=false;fixture.db.afterSql=null;throw IOException("Synthetic committed ACK loss") } }
        }
        fun replaceVault() {
            dataVault=SqlModelConnectionCatalog(fixture.db,ConnectionSecretVaultTest.key());vaults+=dataVault;vault=ObservedVault(dataVault)
        }
        override fun close(){controller.dispose();vaults.forEach { it.close() };fixture.close()}
    }
    companion object {
        private const val MATERIAL="SYNTHETIC_ONLY_SETTINGS_MATERIAL_456"
        private fun material()=MATERIAL.toCharArray()
        private fun assertWiped(value:CharArray)=assertTrue("Transferred input must be cleared",value.all { it=='\u0000' })
    }
}
