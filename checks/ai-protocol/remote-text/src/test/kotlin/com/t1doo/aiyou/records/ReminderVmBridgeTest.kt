package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ReminderVmBridgeTest {
    @Test(timeout=25_000) fun actualVmTaskAndPackageSuspensionsHoldGateThroughCommitBeforeCloseAndWipe():Unit=runBlocking {
        for(packageOperation in listOf(false,true))RebuildFixture().use{f->
            f.pending("one");val bridge=ReminderOperationBridge();val owner=bridge.gate.openOwner(true)
            val wiped=AtomicBoolean();val codec=ReminderEnvelopeCipher(LocalCandidateFixture.KEY)
            val base=SqliteVmEntryRepository(f.db,f.db,RemoteTextTransactions(f.db)){codec.close();f.meta.close();f.tasks.wipeKey();wiped.set(true)}
            val resources=ReminderRuntimeResources(f.tasks,f.meta,codec)
            val store=object:EntryRepository by base,AutoCloseable {
                override val manualTasks=f.tasks;override val reminders=f.tasks;override val reminderRuntime=resources
                override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==15L)}
                override fun close()=base.close()
            }
            val runtime=ReminderVmRuntime(bridge,owner,{owner.isAdmitted},{false})
            val vm=LocalRecordsViewModel(Application(),{store},reminderRuntime=runtime)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            try{
                withTimeout(5_000){vm.awaitInitialOpen();vm.appAccessIdle.first{it}}
                val action:suspend(EntryRepository)->Unit={entry->
                    val physical=Thread.currentThread()
                    withContext(Dispatchers.IO){entered.countDown();check(release.await(5,TimeUnit.SECONDS));assertFalse(wiped.get())}
                    assertSame(physical,Thread.currentThread())
                    val t=entry.manualTasks!!.snapshots().single();entry.manualTasks!!.change(ManualTaskMutation(t.id,t.version,ManualTaskStatus.COMPLETED,110,"complete-under-gate"))
                }
                assertTrue(if(packageOperation)vm.enqueuePackageOperation(action)else vm.enqueueTaskOperation(action))
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                assertFalse(vm.retireForAppAccess());vm.clearForHostTest()
                assertNull(withTimeoutOrNull(100){vm.awaitAppAccessClosed()});assertFalse(wiped.get());assertEquals(1L,base.closingEntered.count)
                release.countDown();withTimeout(5_000){vm.awaitAppAccessClosed()};assertTrue(wiped.get())
                f.reopen() // The production VM closure also closed the original owned SQLite transport.
                val fresh=SqlManualTaskRepository(f.db,LocalCandidateFixture.KEY)
                try{assertEquals(ManualTaskStatus.COMPLETED,fresh.snapshots().single().status)}finally{fresh.wipeKey()}
                val refused=runtime.dispatch{error("Retired foreground action must not run")}
                try{refused.await();fail("old port accepted")}catch(_:IllegalStateException){}
                val next=bridge.gate.openOwner(true);next.closeAndAwait()
            }finally{release.countDown();vm.clearForHostTest();withTimeout(5_000){vm.awaitAppAccessClosed()};bridge.close()}
        }
    }
    @Test(timeout=15_000) fun admittedStoreClosesEvenWhenVmIsClearedBeforeItsInitialActorGetsIoTime():Unit=runBlocking {
        RebuildFixture().use{f->
            val bridge=ReminderOperationBridge();val owner=bridge.gate.openOwner(true);val wiped=AtomicBoolean()
            val base=SqliteVmEntryRepository(f.db,f.db,RemoteTextTransactions(f.db)){f.meta.close();f.tasks.wipeKey();wiped.set(true)}
            val runtime=ReminderVmRuntime(bridge,owner,{owner.isAdmitted},{false})
            val vm=LocalRecordsViewModel(Application(),{error("Admitted store must not be opened twice")},reminderRuntime=runtime,admittedStore=base)
            try{
                vm.clearForHostTest();withTimeout(5_000){vm.awaitAppAccessClosed()};assertTrue(wiped.get())
                val next=bridge.gate.openOwner(true);next.closeAndAwait()
            }finally{vm.clearForHostTest();bridge.close()}
        }
    }

}
