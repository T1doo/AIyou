package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual ViewModel serial queue, people controller, product SQL and existing original storage shell. */
class ManualPeopleViewModelTest {
    @Test(timeout=12_000) fun actualUserAddRemoveFailureAndExactRetryKeepInputUntilReadbackWithoutChangingOriginal(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open();val original=h.f.base.originalState()
        h.failWrite=true;h.vm.people.add(editor.session,0,"小林🙂")
        val failed=h.editor{it.error!=null&&!it.busy};assertEquals("小林🙂",failed.input);assertNull(failed.notice)
        assertTrue(h.f.people.read(h.id).names.isEmpty())
        h.failWrite=false;h.vm.people.retry(editor.session)
        h.editor{it.notice!=null&&!it.busy};assertEquals(listOf("小林🙂"),h.f.people.read(h.id).names)
        assertEquals(1,h.mutations.distinct().size);assertEquals(2,h.mutations.size)
        assertEquals(h.f.search.epoch(),h.vm.state.value.localSearchEpoch)
        h.vm.people.remove(editor.session,1,"小林🙂","");h.editor{it.snapshot?.version==2L&&!it.busy}
        assertTrue(h.f.people.read(h.id).names.isEmpty());assertEquals(original,h.f.base.originalState())
        assertEquals("合成独立草稿",h.vm.state.value.text)
    } }

    @Test(timeout=12_000) fun conflictingAssociationRetainsInputAndReloadRequiresExplicitAddAtWinningVersion(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open()
        h.f.people.replace(h.f.mutation(h.id,listOf("另一手工选择")))
        h.vm.people.add(editor.session,0,"我的称呼")
        val failed=h.editor{it.conflict&&!it.busy};assertEquals("我的称呼",failed.input)
        h.vm.people.retry(editor.session);val reread=h.editor{!it.conflict&&it.snapshot?.version==1L&&!it.busy}
        assertEquals("我的称呼",reread.input)
        h.vm.people.add(editor.session,1,reread.input);h.editor{it.snapshot?.version==2L&&!it.busy}
        assertEquals(listOf("另一手工选择","我的称呼"),h.f.people.read(h.id).names)
        assertEquals(1,h.vm.state.value.entries.single().currentRevision)
    } }

    @Test(timeout=12_000) fun originalRevisionInFrontOfQueuedPeopleWriteFencesOldEditorAndRetainsManualNames(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.holdWrite={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        h.vm.people.add(editor.session,0,"先提交的称呼");assertTrue(entered.await(5,TimeUnit.SECONDS))
        h.vm.changeText("并行本机输入");assertEquals("并行本机输入",h.vm.state.value.text)
        release.countDown();h.editor{it.notice!=null&&!it.busy};h.holdWrite=null
        h.f.base.append(h.id);h.vm.refreshLocalSearch()
        withTimeout(5_000){h.vm.state.first{it.entries.singleOrNull()?.currentRevision==2}}
        h.vm.people.add(editor.session,1,"迟到操作")
        assertEquals(listOf("先提交的称呼"),h.f.people.read(h.id).names)
        assertTrue(h.vm.state.value.manualPeopleEditor?.conflict==true)
        assertEquals("并行本机输入",h.vm.state.value.text)
    } }

    @Test(timeout=12_000) fun closeDuringAcknowledgementCannotReopenEditorAndConfirmedSnapshotStillRefreshesFilter(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.holdReadback={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        h.vm.people.add(editor.session,0,"已提交称呼");assertTrue(entered.await(5,TimeUnit.SECONDS))
        h.vm.people.close(editor.session);assertNull(h.vm.state.value.manualPeopleEditor)
        release.countDown();withTimeout(5_000){h.vm.state.first{it.manualPeople[h.id]?.version==1L}}
        assertNull(h.vm.state.value.manualPeopleEditor);assertEquals(listOf("已提交称呼"),h.f.people.read(h.id).names)
    } }

    @Test(timeout=12_000) fun acknowledgedButFailedReadbackUsesSameIntentAndCannotOverwriteLaterWinner(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open();h.failReadback=true;h.vm.people.add(editor.session,0,"待确认称呼")
        h.editor{it.error!=null&&!it.busy};assertEquals(1L,h.f.people.read(h.id).version)
        h.failReadback=false;h.vm.people.retry(editor.session);h.editor{it.notice!=null&&!it.busy}
        assertEquals(1L,h.f.people.read(h.id).version);assertEquals(1,h.mutations.distinct().size)
        h.f.people.replace(h.f.mutation(h.id,listOf("后来明确选择")))
        h.vm.people.add(editor.session,1,"旧编辑回调");h.editor{it.conflict&&!it.busy}
        assertEquals(listOf("后来明确选择"),h.f.people.read(h.id).names)
    } }

    @Test fun accountOwnerSwapAndRetainedCallbacksCannotWriteOrBackfillAnotherOwner(): Unit = runBlocking {
        ManualPeopleFixture().use { f ->
            val id=f.base.entry();var local=LocalRecordUiState(ready=true,opening=false,entries=f.base.search.candidates(LocalSearchRequest("")).entries,
                supportsManualPeople=true,manualPeopleOwner=f.people)
            val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
            val controller=ManualPeopleController({local},{local=it(local)},{queue.add(it);true})
            val base=SqliteVmEntryRepository(f.base.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)){}
            val store=object:EntryRepository by base {override val manualPeople=f.people}
            controller.open(id,1);queue.removeFirst()(store)
            val editor=checkNotNull(local.manualPeopleEditor);controller.add(editor.session,0,"旧账号输入")
            val foreign=object:ManualPeopleRepository by f.people{}
            val next=local.copy(manualPeopleOwner=foreign,manualPeople=emptyMap(),manualPeopleEditor=null);local=next
            queue.removeFirst()(store);assertSame(next,local);assertTrue(f.people.read(id).names.isEmpty())
            controller.add(editor.session,0,"迟到");controller.retry(editor.session);assertTrue(queue.isEmpty());controller.dispose()
        }
    }

    @Test(timeout=12_000) fun trashedAndRestoredSourceRemovesOldFilterSnapshotAndNeverAdmitsRetainedMutation(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open();h.vm.people.add(editor.session,0,"小林");h.editor{it.notice!=null}
        val old=h.f.people.read(h.id)
        h.f.base.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",h.id)
        h.vm.refreshLocalSearch();withTimeout(5_000){h.vm.state.first{it.entries.isEmpty()}}
        assertFalse(h.vm.state.value.manualPeople.containsKey(h.id))
        h.vm.people.add(editor.session,1,"迟到删除回调");assertEquals(listOf("小林"),old.names)
        h.f.base.db.write("UPDATE remote_source_access SET deleted=0,generation=generation+1 WHERE entryId=?",h.id)
        h.vm.refreshLocalSearch();withTimeout(5_000){h.vm.state.first{it.entries.size==1&&it.manualPeople[h.id]?.names==listOf("小林")}}
        assertThrows(ManualPeopleConflict::class.java){h.f.people.replace(ManualPeopleMutation(old,emptyList(),UUID.randomUUID().toString()))}
        h.vm.people.close(editor.session)
        val reopened=h.open();assertEquals(listOf("小林"),reopened.snapshot!!.names)
    } }

    @Test(timeout=12_000) fun staleReaderCannotAdoptNewOriginalAuthorityWhileOpeningPeopleEditor(): Unit = Harness().use { h -> runBlocking {
        h.ready();h.reviseIndependently()
        assertEquals(1,h.vm.state.value.entries.single().currentRevision)
        h.vm.people.open(h.id,1);val editor=h.editor{!it.busy}
        h.vm.people.add(editor.session,editor.snapshot?.version ?: 0,"旧页面添加的人物")
        h.refreshToRevision(2)
        assertTrue("Old reader must never acquire the new original's mutation authority",h.f.people.read(h.id).names.isEmpty())
        assertTrue(h.mutations.isEmpty());assertTrue(editor.conflict);assertNull(editor.notice)
        h.vm.people.close(editor.session)
        val fresh=h.open();h.vm.people.add(fresh.session,0,"看过新原文后明确添加")
        h.editor{it.notice!=null&&!it.busy};assertEquals(listOf("看过新原文后明确添加"),h.f.people.read(h.id).names)
    } }

    @Test(timeout=12_000) fun staleRetryCannotRetargetRetainedInputToAnUnseenNewOriginal(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open();h.vm.people.edit(editor.session,0,"仍待确认的私密输入")
        h.reviseIndependently();h.vm.people.retry(editor.session);val retry=h.editor{!it.busy}
        h.vm.people.add(editor.session,retry.snapshot?.version ?: 0,retry.input)
        h.refreshToRevision(2)
        assertTrue("Reload must not silently bind an existing editor to an unseen original",h.f.people.read(h.id).names.isEmpty())
        assertTrue(h.mutations.isEmpty());assertTrue(retry.conflict);assertNull(retry.notice)
        assertEquals("仍待确认的私密输入",retry.input);assertEquals("合成独立草稿",h.vm.state.value.text)
    } }

    @Test(timeout=12_000) fun staleRetryAfterTrashRestoreAbaCannotAdoptNewGenerationOrLoseInput(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.open();val old=checkNotNull(editor.snapshot)
        h.vm.people.edit(editor.session,0,"删除前保留的人物输入")
        val bin=SqlRecycleBinRepository(h.f.base.gateway,h.f.base.tags)
        val first=bin.read(h.id);val source=SourceVersion(h.id,1)
        val trashed=bin.apply(RecycleMutation(source,first.generation,false,UUID.randomUUID().toString()))
        bin.apply(RecycleMutation(source,trashed.generation,true,UUID.randomUUID().toString()))
        assertTrue(h.f.people.read(h.id).generation>old.generation)
        h.vm.people.retry(editor.session);val retry=h.editor{!it.busy}
        h.vm.people.add(editor.session,retry.snapshot?.version ?: 0,retry.input)
        h.refreshToRevision(1)
        assertTrue("Old editor must not acquire restored source generation",h.f.people.read(h.id).names.isEmpty())
        assertTrue(h.mutations.isEmpty());assertTrue(retry.conflict);assertEquals(old.generation,retry.snapshot!!.generation)
        assertEquals("删除前保留的人物输入",retry.input)
        h.vm.people.retry(editor.session);val repeat=h.editor{!it.busy};assertTrue(repeat.conflict)
        assertEquals(old.generation,repeat.snapshot!!.generation)
        h.vm.people.close(editor.session);val fresh=h.open()
        h.vm.people.add(fresh.session,0,"恢复后新会话明确添加");h.editor{it.notice!=null&&!it.busy}
        assertEquals(listOf("恢复后新会话明确添加"),h.f.people.read(h.id).names)
    } }

    private class Harness:AutoCloseable {
        val f=ManualPeopleFixture();val id=f.base.entry()
        @Volatile var failWrite=false
        @Volatile var failReadback=false
        @Volatile var holdWrite:(()->Unit)?=null
        @Volatile var holdReadback:(()->Unit)?=null
        private var wrote=false
        val mutations=CopyOnWriteArrayList<String>()
        val people:ManualPeopleRepository=object:ManualPeopleRepository by f.people {
            override fun replace(mutation:ManualPeopleMutation):ManualPeopleSnapshot {
                mutations+=mutation.mutationId;holdWrite?.invoke();if(failWrite)throw ManualPeopleStorageFailure()
                return f.people.replace(mutation).also{wrote=true}
            }
            override fun snapshots():Map<String,ManualPeopleSnapshot> {
                if(wrote){holdReadback?.invoke();if(failReadback)throw ManualPeopleStorageFailure()}
                return f.people.snapshots()
            }
        }
        val original=SqliteVmEntryRepository(f.base.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)){
            f.base.gateway.stopAndAwaitIdle();f.people.wipeKey()
        }
        private val store=object:EntryRepository by original,AutoCloseable {
            override val manualPeople=people
            override val localSearch=f.search
            override suspend fun prepare(){check(f.base.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=original.close()
        }
        init{runBlocking{original.updateDraft("合成独立草稿")}}
        val vm=LocalRecordsViewModel(Application(),{store})
        fun reviseIndependently(){f.base.second().use{other->other.transaction{sql->
            val text="合成独立所有者改写后的原文"
            sql.execute("INSERT INTO entry_revisions VALUES(?,2,?,?,17)",listOf(id,text,OriginalExports.hash(text)))
            sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?",listOf(id))
        }}}
        suspend fun refreshToRevision(revision:Int){val before=vm.state.value.entries;vm.refreshLocalSearch();withTimeout(5_000){
            vm.state.first{it.entries!==before&&it.entries.singleOrNull()?.currentRevision==revision&&it.manualPeopleAvailable}
        }}
        suspend fun ready()=withTimeout(5_000){vm.state.first{it.ready&&!it.opening}}
        suspend fun editor(p:(ManualPeopleEditor)->Boolean)=withTimeout(5_000){checkNotNull(vm.state.first{it.manualPeopleEditor?.let(p)==true}.manualPeopleEditor)}
        suspend fun open():ManualPeopleEditor {vm.people.open(id,vm.state.value.entries.single().currentRevision);return editor{!it.busy&&it.snapshot!=null}}
        override fun close(){holdWrite=null;holdReadback=null;failReadback=false;vm.clearForHostTest();check(original.closed.await(5,TimeUnit.SECONDS));f.close()}
    }
}
