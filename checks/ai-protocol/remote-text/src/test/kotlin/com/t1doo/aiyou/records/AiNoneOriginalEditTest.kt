package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite schema/triggers + production VM and encrypted draft files.
 * Formal revision IO uses the explicitly scoped SqliteVmEntryRepository adapter, not native Room.
 */
class AiNoneOriginalEditTest {
    @Test(timeout=10_000) fun aiNoneManualRevisionKeepsOldLocalResultInvalidatedAndNeverRestoresItsPayload():Unit=Harness(lateLocal=true).use { h -> runBlocking {
        h.ready();val job=h.lateJob!!;val edit=h.edit()
        assertFalse(edit.conflict);h.vm.changeOriginalEdit(edit.sessionId,"合成人工新版本，不重用旧候选")
        h.state { it.originalEdit?.draftNotice=="编辑草稿已加密保存在本机" }
        h.vm.saveOriginalEdit(edit.sessionId);h.state { it.originalEdit==null }
        assertEquals(ProcessingPolicy.AI_NONE,h.policy(2).policy)
        assertThrows(LocalCandidateConflict::class.java) { h.f.store.complete(job,LocalExcerptRules.extract(job)) }
        assertThrows(LocalCandidateConflict::class.java) { h.f.store.batches(SourceVersion("source",2)) }
        val invalid=h.f.db.read("SELECT state,payload FROM local_candidate_batches").single()
        assertEquals("INVALIDATED",invalid[0]);assertTrue((invalid[1] as ByteArray).isEmpty())
        assertTrue(h.f.db.read("SELECT * FROM remote_text_jobs").isEmpty())
    } }
    @Test(timeout=10_000) fun aiNoneManualEditKeepColdRecoveryAndSavePreservePolicyHistoryAndCapture():Unit=Harness().use { h -> runBlocking {
        h.ready();val capture=h.base.readDraft();val generation=h.policy().generation
        val edit=h.edit();assertFalse(edit.conflict)
        h.vm.changeOriginalEdit(edit.sessionId,"合成人工补充🙂")
        h.state { it.originalEdit?.draftNotice=="编辑草稿已加密保存在本机" }
        h.vm.keepOriginalEdit(edit.sessionId);h.state { it.originalEdit==null }
        assertEquals(h.f.original,h.base.entries().single { it.id=="source" }.originalText)
        val ref=h.vm.state.value.pendingEdits.single();val oldVm=h.vm;h.cold();h.ready()
        h.vm.resumeOriginalEdit(ref.entryId,ref.id)
        val recovered=h.state { it.originalEdit?.loadingDraft==false }.originalEdit!!
        assertFalse(recovered.conflict);assertEquals("合成人工补充🙂",recovered.text)
        oldVm.changeOriginalEdit(edit.sessionId,"迟到旧会话");oldVm.saveOriginalEdit(edit.sessionId)
        assertEquals(recovered.sessionId,h.vm.state.value.originalEdit!!.sessionId)
        h.vm.saveOriginalEdit(recovered.sessionId);h.state { it.originalEdit==null }
        assertEquals(listOf(h.f.original,"合成人工补充🙂"),h.base.revisions("source").map { it.originalText })
        assertEquals(ProcessingPolicy.AI_NONE,h.policy(2).policy);assertEquals(generation+1,h.policy(2).generation)
        assertEquals(capture,h.base.readDraft());assertEquals(OriginalEditDraftState.COMMITTED,h.drafts.read("source")!!.state)
        h.noAi()
    } }
    @Test(timeout=10_000) fun aiNoneHistoryRequiresExplicitNewVersionAndRejectsRetainedHistorySession():Unit=Harness().use { h -> runBlocking {
        h.ready();h.base.revise("source",1,"合成当前第二版",30);h.vm.refreshLocalSearch()
        h.state { it.entries.any { e -> e.id=="source"&&e.currentRevision==2 } }
        val capture=h.base.readDraft();val generation=h.policy(2).generation
        h.vm.beginOriginalHistory("source",2)
        var history=h.state { it.originalHistory?.loading==false }.originalHistory!!
        h.vm.selectOriginalRevision(history.sessionId,1)
        history=h.state { it.originalHistory?.let { x -> !x.loading&&x.selectedRevision==1 }==true }.originalHistory!!
        assertNull(h.drafts.read("source"));assertEquals(2,h.base.revisions("source").size)
        h.vm.editOriginalHistory(history.sessionId,history.requestId,1,2)
        val edit=h.state { it.originalEdit?.loadingDraft==false }.originalEdit!!
        assertFalse(edit.conflict);assertEquals(1,edit.sourceRevision);assertEquals(2,edit.baseRevision)
        assertEquals(h.f.original,h.drafts.read("source")!!.text)
        h.vm.editOriginalHistory(history.sessionId,history.requestId,1,2)
        assertEquals(edit.sessionId,h.vm.state.value.originalEdit!!.sessionId)
        h.vm.saveOriginalEdit(edit.sessionId);h.state { it.originalEdit==null }
        assertEquals(listOf(h.f.original,"合成当前第二版",h.f.original),h.base.revisions("source").map { it.originalText })
        assertEquals(ProcessingPolicy.AI_NONE,h.policy(3).policy);assertEquals(generation+1,h.policy(3).generation)
        assertEquals(capture,h.base.readDraft());h.noAi()
    } }
    @Test(timeout=10_000) fun changedAiNoneOriginalKeepsConflictingEncryptedInputAndCannotOverwriteCurrentVersion():Unit=Harness().use { h -> runBlocking {
        h.ready();val edit=h.edit();h.vm.changeOriginalEdit(edit.sessionId,"合成冲突输入")
        h.state { it.originalEdit?.draftNotice=="编辑草稿已加密保存在本机" }
        h.base.revise("source",1,"合成并发当前原文",40)
        h.vm.keepOriginalEdit(edit.sessionId);h.state { it.originalEdit==null }
        val ref=h.vm.state.value.pendingEdits.single();h.vm.resumeOriginalEdit(ref.entryId,ref.id)
        val conflict=h.state { it.originalEdit?.loadingDraft==false }.originalEdit!!
        assertTrue(conflict.conflict);assertEquals("合成冲突输入",conflict.text)
        h.vm.saveOriginalEdit(conflict.sessionId)
        assertEquals(2,h.base.revisions("source").size);assertEquals("合成冲突输入",h.drafts.read("source")!!.text)
        assertEquals(ProcessingPolicy.AI_NONE,h.policy(2).policy);h.noAi()
    } }
    @Test(timeout=10_000) fun aiNonePostCommitReadbackRetryCreatesOnlyOneRevisionAndDoesNotRegenerate():Unit=Harness().use { h -> runBlocking {
        h.ready();val edit=h.edit();h.vm.changeOriginalEdit(edit.sessionId,"合成待核对新版本")
        h.state { it.originalEdit?.draftNotice=="编辑草稿已加密保存在本机" }
        h.failReadback=true;h.vm.saveOriginalEdit(edit.sessionId)
        val failed=h.state { it.originalEdit?.let { x -> !x.saving&&x.awaitingReadback&&x.error!=null }==true }.originalEdit!!
        assertEquals(2,h.base.revisions("source").size);assertEquals(1,h.revisions)
        h.vm.saveOriginalEdit(failed.sessionId);h.state { it.originalEdit==null }
        assertEquals(1,h.revisions);assertEquals(2,h.base.revisions("source").size)
        assertEquals(ProcessingPolicy.AI_NONE,h.policy(2).policy);h.noAi()
    } }
    @Test(timeout=10_000) fun permanentFenceRejectsAiNoneAutosaveAndPreservesExistingEncryptedEvidence():Unit=Harness().use { h -> runBlocking {
        h.ready();val edit=h.edit();h.vm.changeOriginalEdit(edit.sessionId,"合成被保护的草稿")
        h.state { it.originalEdit?.draftNotice=="编辑草稿已加密保存在本机" }
        val before=Files.list(h.editDirectory).use { paths -> paths.filter { it.toString().endsWith(".edit") }.findFirst().get() }.toFile().readBytes()
        h.f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',1,?,'erase','PREPARED','','')",listOf(h.policy().generation)) }
        h.vm.changeOriginalEdit(edit.sessionId,"合成迟到输入")
        h.state { it.originalEdit?.let { x -> !x.saving&&x.conflict&&x.error!=null }==true }
        h.vm.saveOriginalEdit(edit.sessionId)
        assertEquals(1,h.base.revisions("source").size)
        val after=Files.list(h.editDirectory).use { paths -> paths.filter { it.toString().endsWith(".edit") }.findFirst().get() }.toFile().readBytes()
        assertArrayEquals(before,after);h.noAi()
    } }
    private class Harness(lateLocal:Boolean=false):AutoCloseable {
        val f=LocalCandidateFixture();val editDirectory=Files.createDirectory(f.directory.resolve("original-edits"))
        val lateJob=if(lateLocal) f.store.begin(f.start("late-candidate")) else null
        lateinit var drafts:EncryptedOriginalEditStore;lateinit var base:SqliteVmEntryRepository;lateinit var vm:LocalRecordsViewModel
        @Volatile var failReadback=false;var revisions=0;private var closed=false
        init {
            val port=SqlLocalProcessingRepository(f.gateway)
            port.change(LocalProcessingChange(port.read(f.source),LocalProcessingChoice.AI_NONE,UUID.randomUUID().toString()))
            start()
        }
        private fun start() {
            drafts=EncryptedOriginalEditStore(editDirectory.toFile(),LocalCandidateFixture.KEY.copyOf()).also { it.installErasureHooks(SqlErasureFileHooks(f.gateway)) }
            base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)) { f.gateway.stopAndAwaitIdle();drafts.close();f.store.wipeKey() }
            val owner=object:EntryRepository by base,AutoCloseable {
                override val editDrafts=drafts
                override val localProcessing=SqlLocalProcessingRepository(f.gateway)
                override val localCandidates=f.store
                override val sourceBoundText:SourceBoundTextRepository? get()=error("No remote capability")
                override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single()==10L) }
                override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=base.revise(entryId,expectedRevision,text,createdAtMillis).also { revisions++ }
                override suspend fun reviseFromDraft(draft:OriginalEditDraft,createdAtMillis:Long)=revise(draft.entryId,draft.baseRevision,draft.text,createdAtMillis)
                override suspend fun entries():List<StoredEntry> {
                    if(failReadback&&revisions>0) { failReadback=false;throw IllegalStateException("Synthetic readback failure") }
                    return base.entries()
                }
                override fun close()=base.close()
            }
            vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={error("No remote exchange")})
        }
        suspend fun ready()=state { it.ready&&!it.opening&&it.supportsEditDrafts }
        suspend fun state(test:(LocalRecordUiState)->Boolean)=withTimeout(2_000) { vm.state.first(test) }
        suspend fun edit():OriginalEditUiState { vm.beginOriginalEdit("source",1);return state { it.originalEdit?.loadingDraft==false }.originalEdit!! }
        fun policy(revision:Long=1)=SqlLocalProcessingRepository(f.gateway).read(SourceVersion("source",revision))
        fun noAi() { assertTrue(f.db.read("SELECT * FROM remote_text_jobs").isEmpty());assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty()) }
        private fun stop() { vm.clearForHostTest();assertTrue(base.closed.await(2,TimeUnit.SECONDS));base.closeFailure.get()?.let { throw it } }
        fun cold() { stop();f.reopen();start() }
        override fun close() { if(!closed) { closed=true;stop();f.close() } }
    }
}
