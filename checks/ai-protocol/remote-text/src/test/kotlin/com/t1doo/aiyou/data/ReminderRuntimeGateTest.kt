package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReminderRuntimeGateTest {
    @Test fun noAccessAndOldIngressCannotReadTheRepositoryOrRebindToANewSession(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.now = RuntimeFixture.DUE_MILLIS
        val token = f.event().scope.token; val old = requireNotNull(f.owner.capture(token, f.now))
        f.owner.closeAndAwait(); f.owner = f.gate.openOwner(false)
        val loads = AtomicInteger()
        fun coordinator() = ReminderRuntimeCoordinator(f.owner, { loads.incrementAndGet(); f.store }, f.driver) { f.now }
        assertNull(f.owner.capture(token, f.now)); assertEquals(0, coordinator().drain())
        assertEquals(ReminderRuntimeOutcome.NO_ACCESS, coordinator().handle(old).outcome); assertEquals(0, loads.get())
        f.owner.closeAndAwait(); f.owner = f.gate.openOwner(accessAllowed = true)
        assertEquals(ReminderRuntimeOutcome.NO_ACCESS, coordinator().handle(old).outcome); assertEquals(0, loads.get())
        assertEquals(0, f.posts.get()); assertTrue(f.view().receipts.isEmpty())
    }

    @Test fun ingressIsBoundedCoalescesTokensAndIsDiscardedWhenTheOwnerSeals(): Unit = RuntimeFixture().use { f ->
        val values = (0 until 64).map { i -> TaskReminderToken("synthetic-$i", TaskReminders.id("synthetic-$i"), 1) }
        val first = values.map { requireNotNull(f.owner.capture(it, 1)) }
        assertSame(first[0], f.owner.capture(values[0], 2))
        assertNull(f.owner.capture(TaskReminderToken("overflow", TaskReminders.id("overflow"),1), 1))
        f.owner.seal(); assertNull(f.owner.capture(values[0], 1))
        first.forEach { assertEquals(ReminderRuntimeOutcome.NO_ACCESS, f.coordinator().handle(it).outcome) }
        assertEquals(0, f.posts.get()); assertEquals(0, f.availabilityCalls.get())
    }

    @Test fun failedFactoryConsumesIngressAdmissionAndRepeatedWorkCannotRetryKeyLoading(): Unit = RuntimeFixture().use{f->
        val loads=AtomicInteger();val coordinator=ReminderRuntimeCoordinator(f.owner,{loads.incrementAndGet();throw StorageKeyUnavailable()},f.driver){1}
        val ingress=f.owner.capture(TaskReminderToken("one",TaskReminders.id("one"),1),1)!!
        assertEquals(ReminderRuntimeOutcome.UNKNOWN,coordinator.handle(ingress).outcome)
        assertEquals(ReminderRuntimeOutcome.STALE,coordinator.handle(ingress).outcome)
        repeat(3){assertEquals(ReminderRuntimeOutcome.RETIRED,coordinator.handle(ingress).outcome)}
        assertEquals(1,loads.get());assertEquals(0,f.availabilityCalls.get());assertEquals(0,f.posts.get())
    }

    @Test fun alreadyObservedFreshIngressIsReadOnlyAndCannotRetireAnotherEventReceipt(): Unit = RuntimeFixture().use{f->
        f.confirm();f.now=RuntimeFixture.DUE_MILLIS
        val original=f.event();f.store.acceptRuntimeEvent(original);val bytes=f.blob()
        assertEquals(ReminderRuntimeOutcome.ALREADY_OBSERVED,f.fire().outcome)
        assertArrayEquals(bytes,f.blob());assertNull(f.view().event(original.id)!!.closed)
        assertEquals(TaskReminderPhase.TRIGGER_ACCEPTED,f.current().reminder!!.phase);assertEquals(0,f.posts.get())
        f.coordinator().reconcile("one");assertEquals(TaskReminderPhase.TRIGGER_UNDELIVERED,f.current().reminder!!.phase)
        assertEquals(0,f.posts.get())
    }

    @Test fun pastScanningDoesNotRescheduleAndKnownLostPastIsNotARealLateCallback(): Unit {
        for (lost in listOf(false,true)) RuntimeFixture().use { f ->
            f.confirm()
            val t = f.current(); f.store.changeReminder(TaskReminderMutation(t, TaskReminderMode.APPROXIMATE, f.now, "explicit-approximate"))
            assertTrue(f.coordinator().drain() > 0); assertEquals(1, f.schedules.get())
            f.now = RuntimeFixture.DUE_MILLIS + 60_000
            assertEquals(0, f.coordinator().drain()); assertEquals(1, f.schedules.get())
            val read = requireNotNull(f.coordinator().reconcile("one", lost)); assertTrue(read.unverifiedPast(f.now))
            if (lost) {
                assertEquals(TaskReminderPhase.TIME_PASSED, read.task.reminder!!.phase)
                f.fire(); assertEquals(0, f.posts.get()); assertTrue(f.view().receipts.isEmpty())
            } else {
                assertEquals(TaskReminderPhase.SCHEDULED, read.task.reminder!!.phase)
                f.fire(); assertEquals(1, f.posts.get()); assertEquals(TaskReminderMode.APPROXIMATE, f.current().reminder!!.mode)
            }
            assertEquals(1, f.schedules.get())
        }
    }

    @Test(timeout=15_000) fun schedulingAndExplicitRevocationShareTheGateAcrossTheExternalCall(): Unit = RuntimeFixture().use { f ->
        f.confirm(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val sequence = CopyOnWriteArrayList<String>(); val pool = Executors.newFixedThreadPool(2)
        f.scheduleEffect = { sequence += "schedule-enter"; entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); sequence += "schedule-return"; TaskReminderPhase.SCHEDULED }
        try {
            val scheduling = pool.submit<Int> { f.coordinator().drain() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val revocation = pool.submit<Unit> { f.coordinator().mutate { p ->
                val tasks = p as ManualTaskRepository; val current = tasks.snapshots().single()
                tasks.let { (it as TaskReminderRepository).changeReminder(TaskReminderMutation(current,null,f.now,"explicit-later-revoke")) }
                sequence += "user-commit"
            } }
            assertThrows(TimeoutException::class.java) { revocation.get(100,TimeUnit.MILLISECONDS) }
            release.countDown(); assertEquals(1,scheduling.get(3,TimeUnit.SECONDS)); revocation.get(3,TimeUnit.SECONDS)
            assertEquals(listOf("schedule-enter","schedule-return","user-commit"), sequence.toList())
            assertEquals(TaskReminderPhase.REVOKED, f.current().reminder!!.phase)
            f.coordinator().drain(); assertEquals(1,f.cancels.get()); assertEquals(1,f.schedules.get())
            f.now=RuntimeFixture.DUE_MILLIS; f.fire(); assertEquals(0,f.posts.get())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)) }
    }

    @Test fun revocationCommittedBeforeDrainPreventsTheOldScheduleCall(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.coordinator().mutate { p ->
            val t=(p as ManualTaskRepository).snapshots().single(); p.changeReminder(TaskReminderMutation(t,null,f.now,"revoke-first"))
        }
        f.coordinator().drain(); assertEquals(0,f.schedules.get()); assertEquals(1,f.cancels.get())
        f.now=RuntimeFixture.DUE_MILLIS; f.fire(); assertEquals(0,f.posts.get())
    }

    @Test(timeout=15_000) fun postAndCompletionHaveOneOrderAndTheLaterCompletionCancelsThatToken(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.now=RuntimeFixture.DUE_MILLIS
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val order=CopyOnWriteArrayList<String>();val pool=Executors.newFixedThreadPool(2)
        f.postEffect={ order+="post-enter";entered.countDown();check(release.await(3,TimeUnit.SECONDS));order+="post-return";ReminderPostObservation.POST_RETURNED }
        try {
            val posting=pool.submit<ReminderRuntimeReply> { f.fire() };assertTrue(entered.await(3,TimeUnit.SECONDS))
            val completion=pool.submit<Unit> { f.coordinator().mutate { p ->
                val tasks=p as ManualTaskRepository;val t=tasks.snapshots().single()
                tasks.change(ManualTaskMutation(t.id,t.version,ManualTaskStatus.COMPLETED,f.now,"complete-after-post"));order+="complete-commit"
            } }
            assertThrows(TimeoutException::class.java) { completion.get(100,TimeUnit.MILLISECONDS) }
            release.countDown();posting.get(3,TimeUnit.SECONDS);completion.get(3,TimeUnit.SECONDS)
            assertEquals(listOf("post-enter","post-return","complete-commit"),order.toList())
            assertEquals(ManualTaskStatus.COMPLETED,f.current().status);assertTrue(f.view().receipts.single().retired!=null)
            f.coordinator().drain();assertEquals(1,f.posts.get());assertEquals(1,f.cancels.get())
            f.fire();assertEquals(1,f.posts.get())
        } finally { release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)) }
    }

    @Test(timeout=15_000) fun ownerCloseCannotWipeKeysOrAcknowledgeBeforeTheExternalOperationEnds(): Unit = RuntimeFixture().use { f ->
        f.confirm();f.now=RuntimeFixture.DUE_MILLIS
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closeStarted=CountDownLatch(1)
        val closed=AtomicBoolean();val pool=Executors.newFixedThreadPool(3)
        f.postEffect={entered.countDown();check(release.await(3,TimeUnit.SECONDS));assertFalse(closed.get());ReminderPostObservation.POST_RETURNED}
        try {
            val post=pool.submit<ReminderRuntimeReply>{f.fire()};assertTrue(entered.await(3,TimeUnit.SECONDS))
            val close=pool.submit<Unit>{closeStarted.countDown();f.owner.closeAndAwait { f.local.gateway.stopAndAwaitIdle();f.store.wipeKey();closed.set(true) }}
            assertTrue(closeStarted.await(3,TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java){close.get(100,TimeUnit.MILLISECONDS)};assertFalse(closed.get())
            val next=pool.submit<ReminderRuntimeOwner>{f.gate.openOwner(accessAllowed = true)}
            assertThrows(TimeoutException::class.java){next.get(100,TimeUnit.MILLISECONDS)}
            release.countDown();post.get(3,TimeUnit.SECONDS);close.get(3,TimeUnit.SECONDS)
            f.owner=next.get(3,TimeUnit.SECONDS);assertTrue(closed.get());assertEquals(1,f.posts.get())
        } finally { release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)) }
    }

    @Test fun closeFailureKeepsAdmissionSealedAndBlocksAReplacementOwner(): Unit = RuntimeFixture().use { f ->
        assertThrows(IllegalStateException::class.java){f.owner.closeAndAwait { error("Synthetic close not confirmed") }}
        assertNull(f.owner.capture(TaskReminderToken("one",TaskReminders.id("one"),1),1))
        assertThrows(IllegalStateException::class.java){f.gate.openOwner(accessAllowed = true)}
        assertThrows(IllegalStateException::class.java){f.coordinator().mutate { error("must not run") }}
    }

    @Test fun callbacksCanOnlyQueueAndCannotReenterTheGateOrRunInsideSql(): Unit = RuntimeFixture().use { f ->
        f.confirm();f.now=RuntimeFixture.DUE_MILLIS;var queued:ReminderRuntimeIngress?=null
        f.postEffect={
            assertEquals(0,f.sqlDepth.get())
            queued=f.owner.capture(TaskReminderToken("other-synthetic",TaskReminders.id("other-synthetic"),1),f.now)
            assertThrows(IllegalStateException::class.java){f.coordinator().mutate { f.store.snapshots() }}
            ReminderPostObservation.POST_RETURNED
        }
        f.fire();assertEquals(1,f.posts.get())
        assertEquals(ReminderRuntimeOutcome.MISSING,f.coordinator().handle(requireNotNull(queued)).outcome)
        assertEquals(1,f.store.snapshots().size);assertEquals(0,f.sqlDepth.get())
    }

    @Test fun persistentBlockedClaimWriteFailureCannotTurnRecoveryIntoASecondAttempt(): Unit = RuntimeFixture().use { f ->
        f.confirm();f.now=RuntimeFixture.DUE_MILLIS
        f.capability={n->if(n==2) RuntimeFixture.DENIED else RuntimeFixture.AVAILABLE}
        f.port=object:ReminderRuntimeRepository by f.store {
            override fun decideRuntimeClaim(request:ReminderClaimRequest):ReminderRuntimeReply=throw ManualTaskStorageFailure()
            override fun endRuntime(token:TaskReminderToken,expectedVersion:Long,atMillis:Long,reason:ReminderClosureReason):Boolean=throw ManualTaskStorageFailure()
        }
        assertEquals(ReminderRuntimeOutcome.UNKNOWN,f.fire().outcome)
        assertEquals(TaskReminderPhase.TRIGGER_ACCEPTED,f.current().reminder!!.phase);assertEquals(0,f.posts.get())
        f.reopen();f.capability={RuntimeFixture.AVAILABLE};f.coordinator().reconcile("one");f.fire()
        assertEquals(TaskReminderPhase.TRIGGER_UNDELIVERED,f.current().reminder!!.phase)
        assertTrue(f.current().reminder!!.commands.none{it.kind==TaskReminderCommandKind.DELIVER});assertEquals(0,f.posts.get())
    }

    @Test fun aCommittedUserTerminalDecisionRejectsQueuedOldIngressBeforeAnyPost(): Unit {
        for(op in listOf("COMPLETE","CANCEL","REVOKE","SET","CLEAR")) RuntimeFixture().use{f->
            f.confirm();f.now=RuntimeFixture.DUE_MILLIS;val token=f.event().scope.token
            val ingress=f.owner.capture(token,f.now)!!
            f.coordinator().mutate{p->
                val tasks=p as ManualTaskRepository;val t=tasks.snapshots().single()
                when(op){
                    "COMPLETE","CANCEL"->tasks.change(ManualTaskMutation("one",t.version,if(op=="COMPLETE")ManualTaskStatus.COMPLETED else ManualTaskStatus.CANCELLED,f.now,"user"))
                    "REVOKE"->p.changeReminder(TaskReminderMutation(t,null,f.now,"user"))
                    else->tasks.reschedule(ManualTaskScheduleMutation("one",t.version,ManualTaskDateOperation.valueOf(op),
                        if(op=="CLEAR")null else t.schedule!!.copy(date=t.schedule!!.date.plusDays(1)),f.now,"user"))
                }
            }
            val bytes=f.blob();assertEquals(ReminderRuntimeOutcome.STALE,f.coordinator().handle(ingress).outcome)
            assertArrayEquals(bytes,f.blob());assertEquals(0,f.posts.get());assertTrue(f.view().receipts.isEmpty())
        }
    }

    @Test fun recyclingTheOriginalDoesNotEraseAnIndependentlyConfirmedFormalReminder(): Unit = RuntimeFixture().use{f->
        val tags=SqlManualTagRepository(f.local.gateway,LocalCandidateFixture.KEY)
        try{
            val bin=SqlRecycleBinRepository(f.local.gateway,tags,SqlErasureFileHooks::fenced);val old=bin.read("source")
            val receipt=SourceTaskReceipt(TaskSourceBinding(f.local.source,OriginalExports.hash(f.local.original),old.generation,0,2),"c".repeat(64))
            val t=f.local.db.transaction{f.store.createWithin(it,ManualTaskCreate("one","合成独立正式待办",55,"UTC","create",RuntimeFixture.date(0)),receipt)}
            f.store.changeReminder(TaskReminderMutation(t,TaskReminderMode.EXACT,99,"confirm"))
            bin.apply(RecycleMutation(f.local.source,old.generation,false,"trash-original"))
            assertTrue(bin.read("source").deleted);assertEquals(receipt,f.current().sourceReceipt)
            f.now=RuntimeFixture.DUE_MILLIS;f.fire();assertEquals(1,f.posts.get())
            assertEquals(TaskReminderPhase.NOTIFICATION_POSTED,f.current().reminder!!.phase);assertEquals(receipt,f.current().sourceReceipt)
        }finally{tags.wipeKey()}
    }

    @Test fun unknownResultWriteNeverRepeatsNotifyOrReconstructsAuthorityOnColdRead(): Unit = RuntimeFixture().use { f ->
        f.confirm();f.now=RuntimeFixture.DUE_MILLIS
        var result:ReminderResultRequest?=null;var authority:ReminderResultAuthority?=null
        f.port=object:ReminderRuntimeRepository by f.store {
            override fun recordRuntimeResult(request:ReminderResultRequest,a:ReminderResultAuthority):ReminderRuntimeReply {
                result=request;authority=a;throw ManualTaskStorageFailure()
            }
        }
        f.fire();assertEquals(1,f.posts.get());assertEquals(TaskReminderPhase.POST_UNKNOWN,f.current().reminder!!.phase)
        assertNull(f.view().receipts.single().result);assertFalse(authority!!.authorizes(result!!))
        assertEquals(ReminderRuntimeOutcome.RETIRED,f.store.recordRuntimeResult(result!!,authority!!).outcome)
        f.reopen();f.fire();f.coordinator().reconcile("one");assertEquals(1,f.posts.get());assertNull(f.view().receipts.single().result)
    }

    @Test fun expiredSchedulesAndResidualDeliveryCannotStarveFutureSchedulesOrCancellations(): Unit {
        for (limit in listOf(1, 16)) RuntimeFixture().use { f ->
            val expired = (0 until 20).map { pending(f, "a-expired-%02d".format(it)) }
            val delivery = pending(f, "b-delivery")
            val future = pending(f, "z-future", 1)
            val cancelled = pending(f, "z-cancelled")
            f.store.changeReminder(TaskReminderMutation(cancelled, null, 100, "revoke-cancelled"))
            f.now = RuntimeFixture.DUE_MILLIS
            val reminder = requireNotNull(delivery.reminder)
            val event = ReminderEventRequest.create(ReminderRuntimeScope(
                TaskReminderToken(delivery.id, reminder.id, reminder.scheduleVersion), reminder.schedule, reminder.mode),
                delivery.version, f.now, f.observation())
            f.store.acceptRuntimeEvent(event)
            val preserved = expired.map { it.id } + delivery.id
            val before = preserved.associateWith { taskBytes(f, it) }
            var snapshots = 0
            var currentChecks = 0
            f.port = object : ReminderRuntimeRepository by f.store {
                override fun reminderCommands(): List<TaskReminderCommand> { snapshots++; return f.store.reminderCommands() }
                override fun isReminderCommandCurrent(command: TaskReminderCommand): Boolean {
                    currentChecks++; return f.store.isReminderCommandCurrent(command)
                }
            }
            var acknowledged = 0
            repeat(2) {
                val portsBefore = f.schedules.get() + f.cancels.get()
                val checksBefore = currentChecks
                acknowledged += f.coordinator().drain(limit)
                assertTrue(f.schedules.get() + f.cancels.get() - portsBefore <= limit)
                assertTrue(currentChecks - checksBefore <= limit)
            }
            assertEquals(2, acknowledged); assertEquals(2, snapshots)
            assertEquals(1, f.schedules.get()); assertEquals(1, f.cancels.get()); assertEquals(0, f.posts.get())
            assertEquals(TaskReminderPhase.SCHEDULED, f.store.runtimeView(future.id)!!.task.reminder!!.phase)
            assertTrue(f.store.runtimeView(cancelled.id)!!.task.reminder!!.commands.isEmpty())
            before.forEach { (id, bytes) -> assertArrayEquals(bytes, taskBytes(f, id)) }
            assertNull(f.store.runtimeView(delivery.id)!!.receipts.single().closed)
            assertEquals(20, f.store.reminderCommands().count { it.kind == TaskReminderCommandKind.SCHEDULE })
            assertEquals(1, f.store.reminderCommands().count { it.kind == TaskReminderCommandKind.DELIVER })
        }
    }

    @Test fun failedEligibleCommandsCannotStarveLaterWorkAcrossCoordinatorInstancesForTheSameOwner(): Unit {
        for (limit in listOf(1, 16)) RuntimeFixture().use { f ->
            val stuck = (0 until 17).map { pending(f, "a-stuck-%02d".format(it)) }
            val cancelled = pending(f, "z-cancelled")
            f.store.changeReminder(TaskReminderMutation(cancelled, null, 100, "revoke-cancelled"))
            val future = pending(f, "z-future")
            val attempts = mutableListOf<String>()
            var snapshots = 0
            f.port = object : ReminderRuntimeRepository by f.store {
                override fun reminderCommands(): List<TaskReminderCommand> { snapshots++; return f.store.reminderCommands() }
            }
            val controlled = object : ReminderRuntimeDriver by f.driver {
                override fun schedule(command: TaskReminderCommand): TaskReminderPhase {
                    attempts += command.token.taskId
                    assertEquals(0, f.sqlDepth.get())
                    if (command.token.taskId.startsWith("a-stuck-")) throw ManualTaskStorageFailure()
                    return f.driver.schedule(command)
                }
                override fun cancel(command: TaskReminderCommand): TaskReminderPhase {
                    attempts += command.token.taskId
                    return f.driver.cancel(command)
                }
            }
            val rounds = (19 + limit - 1) / limit
            repeat(rounds) {
                val before = attempts.size
                ReminderRuntimeCoordinator(f.owner, { f.port }, controlled) { f.now }.drain(limit)
                assertTrue(attempts.size - before <= limit)
            }
            assertEquals(rounds, snapshots)
            assertTrue(attempts.containsAll(stuck.map { it.id }))
            assertEquals(1, f.schedules.get()); assertEquals(1, f.cancels.get()); assertEquals(0, f.posts.get())
            assertEquals(TaskReminderPhase.SCHEDULED, f.store.runtimeView(future.id)!!.task.reminder!!.phase)
            assertTrue(f.store.runtimeView(cancelled.id)!!.task.reminder!!.commands.isEmpty())
            stuck.forEach { assertEquals(it, f.store.runtimeView(it.id)!!.task) }
        }
    }

    @Test fun capabilityQueryCrossingDueCannotScheduleOrAckPastAlarmAndLateCallbackStillWorks(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.now = RuntimeFixture.DUE_MILLIS - 1
        val before = f.blob()
        f.capability = { n -> if (n == 1) f.now = RuntimeFixture.DUE_MILLIS; RuntimeFixture.AVAILABLE }
        assertEquals(0, f.coordinator().drain())
        assertEquals(1, f.availabilityCalls.get()); assertEquals(0, f.schedules.get()); assertEquals(0, f.posts.get())
        assertArrayEquals(before, f.blob())
        assertEquals(TaskReminderPhase.PENDING, f.current().reminder!!.phase)
        assertEquals(TaskReminderCommandKind.SCHEDULE, f.current().reminder!!.commands.single().kind)
        assertTrue(f.view().receipts.isEmpty())
        f.fire(); assertEquals(0, f.schedules.get()); assertEquals(1, f.posts.get())
        assertEquals(TaskReminderPhase.NOTIFICATION_POSTED, f.current().reminder!!.phase)
    }

    private fun pending(f: RuntimeFixture, id: String, days: Long = 0): ManualTaskSnapshot {
        val task = f.store.create(ManualTaskCreate(id, "合成排空回归", 55, "UTC", "create-$id", RuntimeFixture.date(days)))
        return f.store.changeReminder(TaskReminderMutation(task, TaskReminderMode.EXACT, 99, "confirm-$id"))
    }
    private fun taskBytes(f: RuntimeFixture, id: String): ByteArray = f.local.db.read(
        "SELECT ciphertext FROM manual_tasks WHERE id='" + id + "'").single().single() as ByteArray
}
