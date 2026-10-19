package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Product controller + real SQL, with only the owning command queue explicitly driven. */
class ModelConnectionsControllerTest {
    @Test fun retainingRecreationKeepsInputAndSameUnconfirmedRequestWithoutApplyingOldActions():Unit=runBlocking {
        ConnectionCatalogFixture().use { f ->
            val h=Harness(f);val first=h.controller.open(h.catalog);h.drain();h.controller.begin(first);h.fill(first)
            val old=h.editor();h.controller.close(first,retainEditor=true)
            val second=h.controller.open(h.catalog);assertEquals(old.form,h.editor().form);assertNotSame(old,h.editor())
            h.controller.save(first,old);h.controller.save(second,h.editor());h.drain()
            assertTrue(h.catalog.configurations().isEmpty()) // The fresh read must finish before Save is admitted.
            var inserted=false;var third=0L
            f.db.afterSql={sql->if(sql.startsWith("INSERT INTO model_configurations"))inserted=true
                if(sql=="COMMIT"&&inserted){inserted=false;h.controller.close(second,retainEditor=true);third=h.controller.open(h.catalog)} }
            h.controller.save(second,h.editor());val inFlight=h.editor();val request=inFlight.pending
            h.one();val retained=h.editor();assertSame(request,retained.pending);assertFalse(retained.busy)
            f.db.afterSql=null;h.drain();assertNotNull(h.editor().pending);assertEquals(1L,h.catalog.configurations().single().version)
            h.controller.retry(second,inFlight);h.drain();assertNotNull(h.editor().pending)
            h.controller.retry(third,h.editor());h.drain();assertNull(h.controller.visible().editor)
            assertEquals(1L,h.catalog.configurations().single().version)
        }
    }
    @Test fun retainedFormReadFailureDisablesOldCallbacksAndDifferentOwnerDoesNotAdoptIt():Unit=runBlocking {
        ConnectionCatalogFixture().use { f ->
            hReadFailure(f)
        }
    }
    private suspend fun hReadFailure(f:ConnectionCatalogFixture) {
        val h=Harness(f);h.catalog.change(ModelConnectionCatalogTest.change("existing",0,"create"))
        val first=h.controller.open(h.catalog);h.drain();h.controller.begin(first);h.fill(first);val form=h.editor().form
        val raw=f.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray
        f.db.write("UPDATE model_configurations SET version=2,lastMutationId='corrupt',payload=?",byteArrayOf(0,0,0,99))
        h.controller.close(first,retainEditor=true);val second=h.controller.open(h.catalog);h.drain()
        val kept=h.editor();assertEquals(form,kept.form);assertNotNull(h.controller.visible().error)
        h.controller.save(second,kept);h.controller.edit(second,kept,kept.form.copy(name="不可写"));h.drain();assertSame(kept,h.editor())
        f.db.write("UPDATE model_configurations SET version=3,lastMutationId='synthetic-repair',payload=?",raw)
        h.controller.reload(second);h.drain();assertEquals(form,h.editor().form);assertEquals(0L,h.editor().version)
        h.controller.close(second,retainEditor=true);f.reopen();h.catalog=f.catalog;h.local=h.local.copy(modelConnectionOwner=h.catalog)
        h.controller.open(h.catalog);h.drain();assertNull(h.controller.visible().editor)
        assertEquals(3L,h.controller.visible().configurations.single().version)
    }
    @Test fun closingUnconfirmedWriteRequiresFreshReadBeforeAnotherEditor():Unit=runBlocking {
        ConnectionCatalogFixture().use { f ->
            val h=Harness(f);val session=h.controller.open(h.catalog);h.drain();h.controller.begin(session);h.fill(session)
            var inserted=false
            f.db.afterSql={sql ->if(sql.startsWith("INSERT INTO model_configurations"))inserted=true
                if(sql=="COMMIT"&&inserted){inserted=false;throw IOException("Synthetic ACK loss")} }
            h.controller.save(session,h.editor());h.drain();assertNotNull(h.editor().pending)
            f.db.afterSql=null;h.controller.cancel(session,h.editor())
            assertTrue(h.controller.visible().busy);assertNull(h.controller.visible().editor)
            h.controller.begin(session);assertNull(h.controller.visible().editor)
            h.drain();assertEquals(1,h.controller.visible().configurations.size)
            assertEquals(1L,h.controller.visible().configurations.single().version)
            h.controller.begin(session);assertNotNull(h.controller.visible().editor)
            assertEquals("合成当前草稿",h.local.text)
        }
    }
    @Test fun settingsCreateEditDeleteAndColdReopenKeepLocalRecordAndRejectRetainedActions():Unit=runBlocking {
        ConnectionCatalogFixture().use { f ->
            val h=Harness(f);val session=h.controller.open(h.catalog);h.drain()
            h.controller.begin(session);h.fill(session)
            val old=h.editor();h.controller.save(session,old);h.drain()
            val saved=h.controller.visible().configurations.single();assertEquals(1L,saved.version)
            h.controller.save(session,old);h.drain();assertEquals(1L,h.catalog.configurations().single().version)
            h.controller.begin(session,saved);val edit=h.editor()
            h.controller.edit(session,edit,edit.form.copy(name="合成改名"));h.controller.save(session,h.editor());h.drain()
            assertEquals(2L,h.catalog.configurations().single().version)
            h.controller.begin(session,saved);assertNull(h.controller.visible().editor)
            h.controller.begin(session,h.catalog.configurations().single());h.controller.confirmDelete(session,h.editor())
            val deletion=h.editor();h.controller.delete(session,deletion);h.drain();assertTrue(h.catalog.configurations().isEmpty())
            h.controller.close(session);h.controller.delete(session,deletion);h.drain()
            assertEquals("合成当前草稿",h.local.text);assertEquals("LOCAL_ONLY",f.db.read("SELECT privacyPolicy FROM entries").single().single())
            f.reopen();assertTrue(f.catalog.configurations().isEmpty());assertEquals(3L,f.db.read("SELECT version FROM model_configurations").single().single())
        }
    }
    @Test fun retiredPageQueuedWritesAndLateCommittedCallbacksCannotFillReplacementOwner():Unit=runBlocking {
        ConnectionCatalogFixture().use { f ->
            val h=Harness(f);val first=h.controller.open(h.catalog);h.drain();h.controller.begin(first);h.fill(first)
            h.controller.save(first,h.editor());h.controller.close(first)
            val second=h.controller.open(h.catalog);h.drain();assertTrue(h.catalog.configurations().isEmpty())
            h.controller.begin(second);h.fill(second);var inserted=false;var replacement=0L
            f.db.afterSql={sql ->if(sql.startsWith("INSERT INTO model_configurations"))inserted=true
                if(sql=="COMMIT"&&inserted){inserted=false;h.controller.close(second);replacement=h.controller.open(h.catalog)} }
            h.controller.save(second,h.editor());h.one()
            assertTrue(h.controller.visible().busy);assertTrue(h.controller.visible().configurations.isEmpty())
            f.db.afterSql=null;h.drain();assertEquals(replacement,h.controller.visible().session)
            assertEquals(1,h.controller.visible().configurations.size)
            h.local=h.local.copy(error="合成本机读取失败")
            assertTrue(h.controller.visible().configurations.isEmpty());assertNull(h.controller.visible().editor)
            h.controller.begin(replacement);assertNull(h.controller.visible().editor)
        }
    }
    @Test fun lostAcknowledgementFreezesInputRetriesSameMutationAndLaterConflictPreservesTheForm():Unit=runBlocking {
        ConnectionCatalogFixture().use { f ->
            val h=Harness(f);val session=h.controller.open(h.catalog);h.drain();h.controller.begin(session);h.fill(session)
            var inserted=false
            f.db.afterSql={sql ->if(sql.startsWith("INSERT INTO model_configurations"))inserted=true
                if(sql=="COMMIT"&&inserted){inserted=false;throw IOException("Synthetic ACK loss")} }
            h.controller.save(session,h.editor());h.drain();val uncertain=h.editor()
            assertNotNull(uncertain.pending);assertFalse(uncertain.busy);assertTrue(uncertain.error!!.contains("尚未确认"))
            h.controller.edit(session,uncertain,uncertain.form.copy(name="不可取代待确认值"));assertSame(uncertain,h.editor())
            f.db.afterSql=null;h.controller.retry(session,uncertain);h.drain()
            val saved=h.catalog.configurations().single();assertEquals(1L,saved.version)
            h.controller.begin(session,saved);val before=h.editor();h.controller.edit(session,before,before.form.copy(name="合成保留输入"))
            h.catalog.change(ModelConnectionChange(saved.id,1,"other-owner",ModelConnectionCatalogTest.details(name="另一所有者")))
            h.controller.save(session,h.editor());h.drain();val conflict=h.editor()
            assertTrue(conflict.conflict);assertEquals("合成保留输入",conflict.form.name)
            h.controller.retry(session,conflict);h.drain();assertEquals(2L,h.catalog.configurations().single().version)
            h.controller.cancel(session,conflict);h.controller.reload(session);h.drain()
            assertEquals("另一所有者",h.controller.visible().configurations.single().details.name)
        }
    }
    private class Harness(f:ConnectionCatalogFixture) {
        var catalog:ModelConnectionCatalog=f.catalog
        var local=LocalRecordUiState(ready=true,opening=false,text="合成当前草稿",modelConnectionOwner=catalog)
        private val queue=ArrayDeque<suspend (EntryRepository)->Unit>()
        val controller=ModelConnectionsController({local},{queue.addLast(it);true})
        private val store=object:EntryRepository {
            override val modelConnections:ModelConnectionCatalog get()=catalog
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String)=TextDraft("local",1,text)
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No record save in settings")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No record edit in settings")
        }
        fun editor()=checkNotNull(controller.visible().editor)
        fun fill(session:Long){val e=editor();controller.edit(session,e,ModelConnectionForm("合成连接","https://example.test/custom/v1/",modelId="synthetic-model"))}
        suspend fun one(){queue.removeFirst()(store)}
        suspend fun drain(){while(queue.isNotEmpty())one()}
    }
}
