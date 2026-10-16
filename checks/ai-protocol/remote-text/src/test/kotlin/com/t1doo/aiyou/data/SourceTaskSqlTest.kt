package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SourceTaskSqlTest {
    @Test fun actualPermanentErasureRemovesOriginalAndColdReplayKeepsOnlyIndependentManualTask():Unit=runBlocking { PermanentErasureFixture().use { f ->
        val text="合成待删除的原始正文🙂";val id=f.entry(text=text)
        var port=SqlSourceTaskRepository(f.gateway,f.tasks)
        val binding=TaskSourceBinding(SourceVersion(id,1),OriginalExports.hash(text),1,0,text.codePointCount(0,text.length))
        val preview=port.preview(binding)
        val request=SourceTaskCreate(preview,ManualTaskCreate(SourceTasks.id(binding),"合成由用户独立填写的标题",50,"UTC","create"))
        val saved=port.create(request)
        val bytes=f.db.read("SELECT ciphertext FROM manual_tasks").single().single() as ByteArray
        assertTrue(f.erase.erase(f.intent(id)).complete)
        assertTrue(f.db.read("SELECT * FROM entries WHERE id=?",id).isEmpty())
        assertTrue(f.db.read("SELECT * FROM entry_revisions WHERE entryId=?",id).isEmpty())
        assertEquals("DONE",f.db.read("SELECT phase FROM permanent_erasure_receipts WHERE entryId=?",id).single().single())
        f.reopen();port=SqlSourceTaskRepository(f.gateway,f.tasks)
        assertEquals(saved,port.create(request));assertEquals(listOf(saved),f.tasks.snapshots())
        assertArrayEquals(bytes,f.db.read("SELECT ciphertext FROM manual_tasks").single().single() as ByteArray)
        assertThrows(SourceTaskConflict::class.java) { port.preview(binding) }
        assertThrows(SourceTaskConflict::class.java) { port.locate(saved.id,saved.version) { fail("erased source") } }
    } }
    @Test fun localAndAiNoneManualSourcesCreateOnlyAfterExplicitRequestWithoutChangingOriginals():Unit {
        for(policy in listOf(ProcessingPolicy.LOCAL_ONLY,ProcessingPolicy.AI_NONE)) Harness().use { h ->
            if(policy==ProcessingPolicy.AI_NONE) h.scope(LocalProcessingChoice.AI_NONE,"none")
            val p=h.preview();assertNull(p.existing);assertTrue(h.tasks.snapshots().isEmpty())
            val originals=h.f.db.read("SELECT * FROM entry_revisions ORDER BY entryId,revision")
            val capture=h.f.db.read("SELECT * FROM capture_draft")
            val saved=h.port.create(h.request(p))
            assertEquals(ManualTaskStatus.OPEN,saved.status);assertNull(saved.schedule)
            assertEquals(p.binding,saved.sourceReceipt!!.binding);assertEquals(policy,h.policy.read(h.f.source).policy)
            assertEquals(originals,h.f.db.read("SELECT * FROM entry_revisions ORDER BY entryId,revision"))
            assertEquals(capture,h.f.db.read("SELECT * FROM capture_draft"))
            assertTrue(h.f.db.read("SELECT * FROM remote_text_jobs").isEmpty());assertTrue(h.f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
            assertFalse(String(h.blob(),Charsets.ISO_8859_1).contains(saved.title))
        }
    }
    @Test fun actualLostAckColdReopenAndLaterTaskMutationsKeepOneDurableCreationReceipt():Unit=Harness().use { h ->
        val request=h.request();var lost=false
        h.f.db.afterSql={ if(it=="COMMIT"&&!lost) { lost=true;throw IllegalStateException("Synthetic lost ACK") } }
        assertThrows(SourceTaskStorageFailure::class.java) { h.port.create(request) };h.f.db.afterSql=null
        val bytes=h.blob();h.reopen()
        val created=h.port.create(request);assertArrayEquals(bytes,h.blob());assertEquals(1L,created.version)
        val scheduled=h.tasks.reschedule(ManualTaskScheduleMutation(created.id,1,ManualTaskDateOperation.SET,
            ManualTaskSchedule(LocalDate.of(2026,10,20),null,"UTC"),60,"set-date"))
        val completed=h.tasks.change(ManualTaskMutation(created.id,scheduled.version,ManualTaskStatus.COMPLETED,70,"complete"))
        val terminal=h.blob().copyOf();h.reopen()
        assertEquals(completed,h.port.create(request));assertArrayEquals(terminal,h.blob())
        assertEquals(1,h.tasks.snapshots().size);assertEquals(completed,h.preview().existing)
        h.f.revise()
        assertEquals(completed,h.port.create(request));assertArrayEquals(terminal,h.blob())
        assertThrows(SourceTaskConflict::class.java) { h.port.locate(created.id,completed.version) { fail("changed source") } }
    }
    @Test fun changedTitleDateQuoteOrIdentityCannotReuseAConfirmedCreationReceipt():Unit=Harness().use { h ->
        val request=h.request();h.port.create(request);val bytes=h.blob()
        val t=request.task
        val variants=listOf(
            h.request(request.preview,title="合成另一标题"),
            h.request(request.preview,mutation="another"),
            h.request(request.preview,at=51),
            h.request(request.preview,schedule=ManualTaskSchedule(LocalDate.of(2026,10,21),null,"UTC")),
            SourceTaskCreate(request.preview.copy(quote="替".repeat(request.preview.binding.end-request.preview.binding.start)),t))
        variants.forEach { assertThrows(SourceTaskConflict::class.java) { h.port.create(it) } }
        assertEquals(1,h.tasks.snapshots().size);assertArrayEquals(bytes,h.blob())
    }
    @Test fun sameRevisionUnobservedPolicyAbaAndSourceRevisionChangeRejectOldPreview():Unit=Harness().use { h ->
        val old=h.request()
        h.f.db.transaction { h.scope(LocalProcessingChoice.AI_NONE,"off");h.scope(LocalProcessingChoice.LOCAL_ONLY,"on") }
        assertThrows(SourceTaskConflict::class.java) { h.port.create(old) };assertTrue(h.tasks.snapshots().isEmpty())
        val current=h.request();h.f.revise()
        assertThrows(SourceTaskConflict::class.java) { h.port.create(current) };assertTrue(h.tasks.snapshots().isEmpty())
    }
    @Test fun recycledRestoredAndPermanentPreparedOrDoneSourcesCannotCreateFromOldPreview():Unit {
        Harness().use { h ->
            val old=h.request();val tags=SqlManualTagRepository(h.f.gateway,LocalCandidateFixture.KEY)
            try {
                val bin=SqlRecycleBinRepository(h.f.gateway,tags,SqlErasureFileHooks::fenced)
                val before=bin.read("source");val moved=bin.apply(RecycleMutation(h.f.source,before.generation,false,"move"))
                assertThrows(SourceTaskConflict::class.java) { h.port.create(old) }
                bin.apply(RecycleMutation(h.f.source,moved.generation,true,"restore"))
                assertThrows(SourceTaskConflict::class.java) { h.port.create(old) };assertTrue(h.tasks.snapshots().isEmpty())
            } finally { tags.wipeKey() }
        }
        for(phase in listOf("PREPARED","DONE")) Harness().use { h ->
            val old=h.request()
            h.f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',1,0,'erase',?,'','')",listOf(phase)) }
            assertThrows(SourceTaskConflict::class.java) { h.port.create(old) };assertTrue(h.tasks.snapshots().isEmpty())
        }
    }
    @Test fun sourceErasureDoesNotEraseManualTaskReceiptOrRedirectItsSource():Unit=Harness().use { h ->
        val request=h.request();val saved=h.port.create(request);val bytes=h.blob()
        var located:TaskSourceBinding?=null;assertTrue(h.port.locate(saved.id,1) { located=it });assertEquals(request.preview.binding,located)
        h.f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',1,0,'erase','PREPARED','','')") }
        h.reopen();assertEquals(saved,h.port.create(request));assertArrayEquals(bytes,h.blob())
        assertThrows(SourceTaskConflict::class.java) { h.port.locate(saved.id,1) { fail("fenced source") } }
    }
    @Test fun actualInsertAbortRollsBackWholeCreateAndIndependentOwnerCannotDuplicateFragment():Unit=Harness().use { h ->
        val request=h.request()
        h.f.db.transaction { it.execute("CREATE TRIGGER synthetic_task_abort AFTER INSERT ON manual_tasks BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
        assertThrows(SourceTaskStorageFailure::class.java) { h.port.create(request) };assertTrue(h.tasks.snapshots().isEmpty())
        h.f.db.transaction { it.execute("DROP TRIGGER synthetic_task_abort") }
        h.f.second().use { db ->
            val tasks=SqlManualTaskRepository(db,LocalCandidateFixture.KEY);val other=SqlSourceTaskRepository(db,tasks)
            try {
                val saved=h.port.create(request)
                assertEquals(saved,other.create(request))
                assertThrows(SourceTaskConflict::class.java) { other.create(h.request(mutation="competitor")) }
                assertEquals(1,tasks.snapshots().size)
            } finally { tasks.wipeKey() }
        }
    }
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();var tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY);private set
        val policy get()=SqlLocalProcessingRepository(f.gateway)
        val port get()=SqlSourceTaskRepository(f.gateway,tasks)
        fun scope(target:LocalProcessingChoice,id:String)=policy.change(LocalProcessingChange(policy.read(f.source),target,id))
        fun preview():SourceTaskPreview { val p=policy.read(f.source);return port.preview(TaskSourceBinding(f.source,p.sourceHash,p.generation,0,f.original.codePointCount(0,f.original.length))) }
        fun request(p:SourceTaskPreview=preview(),title:String="合成明确人工待办",mutation:String="create",at:Long=50,schedule:ManualTaskSchedule?=null)=
            SourceTaskCreate(p,ManualTaskCreate(SourceTasks.id(p.binding),title,at,"UTC",mutation,schedule))
        fun blob()=f.db.read("SELECT ciphertext FROM manual_tasks").single().single() as ByteArray
        fun reopen() { f.gateway.stopAndAwaitIdle();tasks.wipeKey();f.reopen();tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY) }
        override fun close() { f.gateway.stopAndAwaitIdle();tasks.wipeKey();f.close() }
    }
}
