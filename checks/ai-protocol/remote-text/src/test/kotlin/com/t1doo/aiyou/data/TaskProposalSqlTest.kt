package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskProposalSqlTest {
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();val tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY)
        val port=SqlTaskProposalRepository(f.gateway,LocalCandidateFixture.KEY,tasks)
        fun binding():TaskSourceBinding {
            val p=SqlLocalProcessingRepository(f.gateway).read(f.source)
            return TaskSourceBinding(p.source,p.sourceHash,p.generation,0,5)
        }
        fun write(id:String="one",title:String="合成人工待办",version:Long=0,b:TaskSourceBinding=binding(),action:TaskProposalAction=TaskProposalAction.SAVE)=
            TaskProposalWrite(id,version,b,title,ManualTaskDateInput(),50,"UTC",50+version,"write-$id-$version",action)
        fun blob(id:String)=f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",TaskProposals.storageId(id)).single().single() as ByteArray
        override fun close() {port.wipeKey();tasks.wipeKey();f.close()}
    }
    @Test fun aiNoneEncryptedUnfinishedColdDraftNeverCountsAsFormalOrCallsAi()=Harness().use {h ->
        val policy=SqlLocalProcessingRepository(h.f.gateway);policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"none"))
        val raw=h.write(title="").copy(dateInput=ManualTaskDateInput("2026-"))
        val p=h.port.write(raw);assertTrue(h.tasks.snapshots().isEmpty());assertNull(p.dateInput.occurrence)
        assertEquals(p,SqlTaskProposalRepository(h.f.gateway,LocalCandidateFixture.KEY,h.tasks).list().single().proposal)
        assertFalse(h.blob(p.id).toString(Charsets.UTF_8).contains("2026-"))
        assertThrows(IllegalArgumentException::class.java) {TaskProposalConfirm(p,60,"confirm")}
        assertEquals(ProcessingPolicy.AI_NONE,policy.read(h.f.source).policy)
        assertTrue(h.f.db.read("SELECT id FROM remote_text_jobs").isEmpty());assertTrue(h.f.db.read("SELECT id FROM local_candidate_batches").isEmpty())
    }
    @Test fun actualWriteCommitAckLossReplaysSameCipherAndStaleEditCannotOverwrite()=Harness().use {h ->
        val r=h.write();h.f.db.afterSql={if(it=="COMMIT") throw IllegalStateException("Synthetic lost ACK")}
        assertThrows(TaskProposalStorageFailure::class.java) {h.port.write(r)};h.f.db.afterSql=null
        val bytes=h.blob("one");assertEquals(1,h.port.list().size);val p=h.port.write(r);assertArrayEquals(bytes,h.blob("one"))
        val edit=h.write(title="合成修改标题",version=p.version);val edited=h.port.write(edit)
        assertThrows(TaskProposalConflict::class.java) {h.port.write(edit.copy(titleInput="合成另一个内容"))}
        assertThrows(TaskProposalConflict::class.java) {h.port.write(r)}
        assertEquals(edited,h.port.list().single().proposal)
    }
    @Test fun atomicConfirmationLostAckColdReplayCompletionAndOldSaveCannotResurrectDraft()=Harness().use {h ->
        val initial=h.write();val p=h.port.write(initial);val r=TaskProposalConfirm(p,60,"confirm")
        h.f.db.afterSql={if(it=="COMMIT") throw IllegalStateException("Synthetic confirmation ACK loss")}
        assertThrows(TaskProposalStorageFailure::class.java) {h.port.confirm(r)};h.f.db.afterSql=null
        assertTrue(h.port.list().isEmpty());assertEquals(1,h.tasks.snapshots().size)
        val cold=SqlTaskProposalRepository(h.f.gateway,LocalCandidateFixture.KEY,h.tasks)
        val saved=cold.confirm(r);assertNull(saved.schedule)
        val completed=h.tasks.change(ManualTaskMutation(saved.id,1,ManualTaskStatus.COMPLETED,70,"done"))
        val bytes=h.f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",saved.id).single().single() as ByteArray
        assertEquals(completed,cold.confirm(r));assertArrayEquals(bytes,h.f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",saved.id).single().single() as ByteArray)
        assertThrows(TaskProposalConflict::class.java) {cold.write(initial)}
        assertThrows(TaskProposalConflict::class.java) {cold.confirm(TaskProposalConfirm(p,60,"other"))}
        assertTrue(cold.list().isEmpty());cold.wipeKey()
    }
    @Test fun sameExcerptAllowsIndependentHumanIntentIdsAndLegacyTaskDoesNotBlock()=Harness().use {h ->
        val b=h.binding();val legacy=SqlSourceTaskRepository(h.f.gateway,h.tasks)
        legacy.create(SourceTaskCreate(legacy.preview(b),ManualTaskCreate(SourceTasks.id(b),"合成旧任务",40,"UTC","legacy")))
        val a=h.port.write(h.write("one","合成给家人打电话"));val other=h.port.write(h.write("two","合成查找电话号码"))
        val first=h.port.confirm(TaskProposalConfirm(a,60,"first"));val second=h.port.confirm(TaskProposalConfirm(other,60,"second"))
        assertNotEquals(first.id,second.id);assertEquals(first.sourceReceipt!!.binding,second.sourceReceipt!!.binding)
        assertEquals(3,h.tasks.snapshots().size);assertTrue(h.port.list().isEmpty())
    }
    @Test(timeout=15_000) fun independentConnectionsConcurrentConfirmationsCreateExactlyOneTask()=Harness().use {h ->
        val p=h.port.write(h.write());val first=TaskProposalConfirm(p,60,"one");val other=TaskProposalConfirm(p,60,"two")
        val secondDb=h.f.second();val secondTasks=SqlManualTaskRepository(secondDb,LocalCandidateFixture.KEY)
        val secondPort=SqlTaskProposalRepository(secondDb,LocalCandidateFixture.KEY,secondTasks)
        val start=CountDownLatch(1);val workers=Executors.newFixedThreadPool(2)
        try {
            val results=listOf(workers.submit<Boolean> {start.await();try {h.port.confirm(first);true} catch(_:TaskProposalConflict) {false}},
                workers.submit<Boolean> {start.await();try {secondPort.confirm(other);true} catch(_:TaskProposalConflict) {false}})
            start.countDown();assertEquals(1,results.count {it.get(5,TimeUnit.SECONDS)})
            assertEquals(1,h.tasks.snapshots().size);assertTrue(h.port.list().isEmpty())
        } finally {workers.shutdownNow();secondPort.wipeKey();secondTasks.wipeKey();secondDb.close()}
    }
    @Test fun sourceRevisionAndGenerationChangesBlockSaveConfirmButPermitExplicitRejection()=Harness().use {h ->
        val p=h.port.write(h.write());val policy=SqlLocalProcessingRepository(h.f.gateway)
        val none=policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"none"))
        policy.change(LocalProcessingChange(none,LocalProcessingChoice.LOCAL_ONLY,"local"))
        assertFalse(h.port.list().single().sourceAvailable)
        assertThrows(TaskProposalConflict::class.java) {h.port.confirm(TaskProposalConfirm(p,60,"confirm"))}
        assertThrows(TaskProposalConflict::class.java) {h.port.write(h.write(version=1,b=p.binding))}
        val rejected=h.port.write(h.write(version=1,b=p.binding,action=TaskProposalAction.REJECT))
        assertEquals(TaskProposalState.REJECTED,rejected.state);assertTrue(h.tasks.snapshots().isEmpty())
        assertThrows(TaskProposalConflict::class.java) {h.port.write(h.write(version=2,b=p.binding))}
        h.f.revise();assertFalse(h.port.list().single().sourceAvailable)
    }
    @Test fun failureAfterTaskInsertOrProposalDeleteRollsBackBothLedgers()=Harness().use {h ->
        val p=h.port.write(h.write());val before=h.blob(p.id);val r=TaskProposalConfirm(p,60,"confirm")
        h.f.db.transaction {it.execute("CREATE TRIGGER synthetic_confirmation_abort AFTER DELETE ON manual_tasks WHEN OLD.id='TASK_PROPOSAL:one' BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")}
        assertThrows(TaskProposalStorageFailure::class.java) {h.port.confirm(r)}
        assertTrue(h.tasks.snapshots().isEmpty());assertArrayEquals(before,h.blob(p.id));assertEquals(p,h.port.list().single().proposal)
        h.f.db.transaction {it.execute("DROP TRIGGER synthetic_confirmation_abort")}
        assertEquals(r.task.id,h.port.confirm(r).id);assertTrue(h.port.list().isEmpty())
    }
    @Test fun reservedNamespacesAndCorruptProposalCannotBeConfusedWithFormalTasks()=Harness().use {h ->
        val p=h.port.write(h.write())
        for(id in listOf(TaskProposals.storageId(p.id),TaskProposals.taskId(p.id)))
            assertThrows(ManualTaskConflict::class.java) {h.tasks.create(ManualTaskCreate(id,"合成非法入口",50,"UTC","manual"))}
        assertThrows(ManualTaskConflict::class.java) {h.tasks.change(ManualTaskMutation(TaskProposals.storageId(p.id),1,ManualTaskStatus.CANCELLED,60,"bad"))}
        h.f.db.transaction {sql ->sql.execute("UPDATE manual_tasks SET version=2,lastMutationId='bad',ciphertext=? WHERE id=?",listOf(byteArrayOf(1,2,3),TaskProposals.storageId(p.id)))}
        assertTrue(h.tasks.snapshots().isEmpty());assertThrows(TaskProposalStorageFailure::class.java) {h.port.list()}
        assertEquals(1L,h.f.db.read("SELECT COUNT(*) FROM manual_tasks").single().single())
    }
    @Test fun actualErasureCleansDraftsAndRejectionsButKeepsIndependentConfirmedReceipt():Unit=PermanentErasureFixture().use {f ->runBlocking {
        val id=f.entry();val source=SourceVersion(id,1);val access=SqlLocalProcessingRepository(f.gateway).read(source)
        val binding=TaskSourceBinding(source,access.sourceHash,access.generation,0,5)
        val port=SqlTaskProposalRepository(f.gateway,PermanentErasureFixture.KEY,f.tasks)
        fun request(name:String)=TaskProposalWrite(name,0,binding,"合成独立草稿",ManualTaskDateInput(),50,"UTC",50,"save-$name")
        val accepted=port.write(request("confirmed"));val confirm=TaskProposalConfirm(accepted,60,"confirm");val task=port.confirm(confirm)
        port.write(request("draft"));val rejected=port.write(request("rejected"))
        port.write(request("rejected").copy(expectedVersion=1,atMillis=51,mutationId="reject",action=TaskProposalAction.REJECT))
        val other=f.entry(text="合成另一条来源原文")
        val otherAccess=SqlLocalProcessingRepository(f.gateway).read(SourceVersion(other,1))
        val otherBinding=TaskSourceBinding(otherAccess.source,otherAccess.sourceHash,otherAccess.generation,0,5)
        val otherDraft=port.write(request("other").copy(binding=otherBinding))
        val erase=SqlPermanentErasureRepository(f.gateway,f.media,f.edits,f.packages,port::readAllWithin)
        assertTrue(erase.erase(f.intent(id)).complete);assertEquals(otherDraft,port.list().single().proposal)
        assertEquals(task,port.confirm(confirm));assertEquals(1,f.tasks.snapshots().size)
        assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isEmpty())
        assertThrows(TaskProposalConflict::class.java) {port.write(request("late"))}
        port.wipeKey()
    }}
    @Test fun corruptedProposalPreventsFalseErasureDoneAndPreservesCipherEvidence():Unit=PermanentErasureFixture().use {f ->runBlocking {
        val id=f.entry();val access=SqlLocalProcessingRepository(f.gateway).read(SourceVersion(id,1))
        val b=TaskSourceBinding(access.source,access.sourceHash,access.generation,0,5)
        val port=SqlTaskProposalRepository(f.gateway,PermanentErasureFixture.KEY,f.tasks)
        port.write(TaskProposalWrite("broken",0,b,"合成损坏草稿",ManualTaskDateInput(),50,"UTC",50,"save"))
        val broken=byteArrayOf(1,2,3)
        f.db.transaction {it.execute("UPDATE manual_tasks SET version=2,lastMutationId='bad',ciphertext=? WHERE id=?",listOf(broken,TaskProposals.storageId("broken")))}
        val erase=SqlPermanentErasureRepository(f.gateway,f.media,f.edits,f.packages,port::readAllWithin)
        val intent=f.intent(id)
        try {erase.erase(intent);fail("corrupt draft must prevent DONE")} catch(_:PermanentErasureIncomplete) {}
        assertFalse(erase.read(id)!!.complete);assertFalse(f.db.read("SELECT id FROM entries WHERE id=?",id).isEmpty())
        assertArrayEquals(broken,f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",TaskProposals.storageId("broken")).single().single() as ByteArray)
        port.wipeKey()
    }}
    @Test(timeout=20_000) fun rejectionReleasesActiveDraftCapacityWithoutRevivingRejectedIdentity()=Harness().use {h ->
        val cipher=TaskProposalCipher(LocalCandidateFixture.KEY);val b=h.binding()
        h.f.db.transaction {sql ->repeat(TaskProposals.MAX_DRAFTS) {i ->
            val r=h.write("draft-$i",b=b);val p=TaskProposal(r.id,1,b,r.titleInput,r.dateInput,50,"UTC",50)
            val hash=TaskProposals.writeHash(r)
            sql.execute("INSERT INTO manual_tasks VALUES(?,1,?,?)",listOf(TaskProposals.storageId(r.id),hash,cipher.seal(p,hash)))
        }}
        assertThrows(TaskProposalLimit::class.java) {h.port.write(h.write("over"))}
        h.port.write(h.write("draft-0",version=1,b=b,action=TaskProposalAction.REJECT))
        h.port.write(h.write("over"));assertEquals(256,h.port.list().count {it.proposal.state==TaskProposalState.DRAFT})
        assertThrows(TaskProposalConflict::class.java) {h.port.write(h.write("draft-0",b=b))};cipher.wipe()
    }
}
