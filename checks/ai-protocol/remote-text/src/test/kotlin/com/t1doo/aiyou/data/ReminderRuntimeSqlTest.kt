package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Actual SQL/JCA and cold SQLite owners. Every platform port is synthetic and synchronous. */
class ReminderRuntimeSqlTest {
    @Test fun pendingOnlyTriggerAfterSuccessfulScheduleAndMissingAckPostsOnceWithoutReschedulingPast(): Unit = RuntimeFixture().use { f ->
        val request = f.confirm()
        f.port = object : ReminderRuntimeRepository by f.store {
            override fun acknowledgeReminder(command: TaskReminderCommand, result: TaskReminderPhase, atMillis: Long): Boolean = throw ManualTaskStorageFailure()
        }
        assertEquals(0, f.coordinator().drain()); assertEquals(1, f.schedules.get())
        assertEquals(TaskReminderPhase.PENDING, f.current().reminder!!.phase)
        f.reopen(); f.now = RuntimeFixture.DUE_MILLIS
        assertEquals(0, f.coordinator().drain()); assertEquals(1, f.schedules.get())
        f.fire(); val current = f.current(); val bytes = f.blob()
        assertEquals(TaskReminderPhase.NOTIFICATION_POSTED, current.reminder!!.phase)
        assertEquals(1, f.posts.get()); assertEquals(1L, current.reminder!!.scheduleVersion)
        assertEquals(current, f.store.changeReminder(request)); assertArrayEquals(bytes, f.blob())
        f.fire(); assertEquals(1, f.posts.get()); assertArrayEquals(bytes, f.blob())
        assertTrue(f.store.reminderCommands().none { it.kind != TaskReminderCommandKind.CANCEL })
    }

    @Test fun eventAndClaimReceiptsSurviveUnrelatedCancelAcksAndColdReadWithoutGrantingAPermit(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.nextPlan(); f.nextPlan()
        val cancels = f.store.reminderCommands().filter { it.kind == TaskReminderCommandKind.CANCEL }
        assertEquals(2, cancels.size)
        f.now = f.current().schedule!!.instant()!!.toEpochMilli()
        val e = f.event(); f.lostCommit { f.store.acceptRuntimeEvent(e) }
        assertTrue(f.store.acknowledgeReminder(cancels[0], TaskReminderPhase.REVOKED, f.now))
        var bytes = f.blob(); assertEquals(ReminderRuntimeOutcome.EXACT_REPLAY, f.store.acceptRuntimeEvent(e).outcome); assertArrayEquals(bytes, f.blob())
        val c = ReminderClaimRequest.create(e, f.current().version, f.now, f.observation())
        f.lostCommit { f.store.decideRuntimeClaim(c) }
        assertTrue(f.store.acknowledgeReminder(cancels[1], TaskReminderPhase.REVOKED, f.now))
        bytes = f.blob(); assertEquals(ReminderRuntimeOutcome.EXACT_REPLAY, f.store.decideRuntimeClaim(c).outcome); assertArrayEquals(bytes, f.blob())
        val receipt = f.view().receipts.single(); assertEquals(e, receipt.event); assertEquals(c, receipt.claim)
        f.reopen(); assertEquals(receipt, f.view().receipts.single())
        f.coordinator().reconcile("one"); assertEquals(TaskReminderPhase.POST_UNKNOWN, f.current().reminder!!.phase)
        f.fire(); assertEquals(0, f.posts.get()); assertEquals(c, f.view().receipts.single().claim)
    }

    @Test fun resultAckUnknownAndLaterCancelAckPreserveTheExactResultForPassiveReplay(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.nextPlan(); val cancel = f.store.reminderCommands().single { it.kind == TaskReminderCommandKind.CANCEL }
        var result: ReminderResultRequest? = null; var authority: ReminderResultAuthority? = null; var once = true
        f.port = object : ReminderRuntimeRepository by f.store {
            override fun recordRuntimeResult(request: ReminderResultRequest, a: ReminderResultAuthority): ReminderRuntimeReply {
                result = request; authority = a
                val response = f.store.recordRuntimeResult(request, a)
                if (once) { once = false; throw ManualTaskStorageFailure() }; return response
            }
        }
        f.now = f.current().schedule!!.instant()!!.toEpochMilli(); f.fire()
        assertEquals(1, f.posts.get()); assertFalse(authority!!.authorizes(result!!))
        val before = f.view().receipts.single()
        assertTrue(f.store.acknowledgeReminder(cancel, TaskReminderPhase.REVOKED, f.now))
        val bytes = f.blob(); assertEquals(ReminderRuntimeOutcome.EXACT_REPLAY, f.store.recordRuntimeResult(result!!, authority!!).outcome)
        assertArrayEquals(bytes, f.blob()); assertEquals(before, f.view().receipts.single())
        f.reopen(); f.fire(); assertEquals(1, f.posts.get()); assertEquals(result, f.view().receipts.single().result)
        assertThrows(ReminderRuntimeConflict::class.java) { result!!.copy(observedAtMillis = result!!.observedAtMillis + 1) }
    }

    @Test fun availableThenUnavailableBeforeClaimThenRestoredNeverPublishesOrRevivesDelivery(): Unit = RuntimeFixture().use { f ->
        val original = f.confirm(); f.now = RuntimeFixture.DUE_MILLIS
        f.capability = { n -> if (n == 2) RuntimeFixture.DENIED else RuntimeFixture.AVAILABLE }
        f.fire(); val view = f.view(); val slot = view.receipts.single(); val bytes = f.blob()
        assertEquals(TaskReminderPhase.DELIVERY_BLOCKED, view.task.reminder!!.phase)
        assertEquals(ReminderClaimDecision.BLOCKED_CAPABILITY, slot.claimDecision)
        assertEquals(ReminderClosureReason.CAPABILITY_UNAVAILABLE_BEFORE_POST, slot.closed!!.reason)
        assertEquals(RuntimeFixture.DENIED, slot.claim!!.capability.availability)
        assertTrue(view.task.reminder!!.commands.none { it.kind == TaskReminderCommandKind.DELIVER }); assertEquals(0, f.posts.get())
        f.capability = { RuntimeFixture.AVAILABLE }; f.fire(); f.coordinator().drain()
        assertEquals(view.task, f.store.changeReminder(original)); assertArrayEquals(bytes, f.blob())
        val fresh = ReminderClaimRequest.create(slot.event, view.task.version, f.now + 1, f.observation())
        assertEquals(ReminderRuntimeOutcome.RETIRED, f.store.decideRuntimeClaim(fresh).outcome)
        assertThrows(ReminderRuntimeConflict::class.java) { slot.claim!!.copy(capability = f.observation()) }
        f.reopen(); f.coordinator().reconcile("one"); f.fire(); assertEquals(0, f.posts.get()); assertArrayEquals(bytes, f.blob())
    }

    @Test fun blockedClaimRollbackRetriesOnlyTheOriginalNegativeObservationEvenAfterRecovery(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.now = RuntimeFixture.DUE_MILLIS
        f.capability = { n -> if (n == 2) RuntimeFixture.DENIED else RuntimeFixture.AVAILABLE }
        val requests = mutableListOf<ReminderClaimRequest>(); var first = true
        f.port = object : ReminderRuntimeRepository by f.store {
            override fun decideRuntimeClaim(request: ReminderClaimRequest): ReminderRuntimeReply {
                requests += request
                if (first) {
                    first = false; f.local.db.beforeCommit = { error("Synthetic blocked claim rollback") }
                    try { return f.store.decideRuntimeClaim(request) } finally { f.local.db.beforeCommit = null; f.capability = { RuntimeFixture.AVAILABLE } }
                }
                return f.store.decideRuntimeClaim(request)
            }
        }
        f.fire(); assertEquals(2, requests.size); assertSame(requests[0], requests[1])
        assertEquals(TaskReminderPhase.DELIVERY_BLOCKED, f.current().reminder!!.phase)
        assertEquals(0, f.posts.get()); assertTrue(f.current().reminder!!.commands.none { it.kind == TaskReminderCommandKind.DELIVER })
        f.fire(); assertEquals(0, f.posts.get())
    }

    @Test fun availabilityLostAfterGrantedClaimRecordsNoAttemptAndDoesNotCallPost(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.now = RuntimeFixture.DUE_MILLIS
        f.capability = { n -> if (n >= 3) RuntimeFixture.DENIED else RuntimeFixture.AVAILABLE }
        f.fire(); val receipt = f.view().receipts.single()
        assertEquals(ReminderClaimDecision.GRANTED, receipt.claimDecision)
        assertEquals(ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST, receipt.result!!.observation)
        assertEquals(TaskReminderPhase.DELIVERY_BLOCKED, f.current().reminder!!.phase); assertEquals(0, f.posts.get())
        f.capability = { RuntimeFixture.AVAILABLE }; f.reopen(); f.fire(); assertEquals(0, f.posts.get())
    }

    @Test fun triggerTransactionRollbackAndUnknownCommitAreDistinctAndNeverBecomeColdPublication(): Unit {
        for (committed in listOf(false, true)) RuntimeFixture().use { f ->
            f.confirm(); f.now = RuntimeFixture.DUE_MILLIS; val e = f.event(); val bytes = f.blob()
            if (committed) f.local.db.afterSql = { if (it == "COMMIT") error("Synthetic unknown trigger commit") }
            else f.local.db.beforeCommit = { error("Synthetic trigger rollback") }
            try { assertThrows(ManualTaskStorageFailure::class.java) { f.store.acceptRuntimeEvent(e) } }
            finally { f.local.db.afterSql = null; f.local.db.beforeCommit = null }
            if (!committed) { assertArrayEquals(bytes, f.blob()); assertTrue(f.view().receipts.isEmpty()); f.store.acceptRuntimeEvent(e) }
            f.reopen(); val accepted = f.blob()
            assertEquals(ReminderRuntimeOutcome.EXACT_REPLAY, f.store.acceptRuntimeEvent(e).outcome); assertArrayEquals(accepted, f.blob())
            f.coordinator().reconcile("one"); assertEquals(TaskReminderPhase.TRIGGER_UNDELIVERED, f.current().reminder!!.phase)
            f.fire(); assertEquals(0, f.posts.get()); assertEquals(e, f.view().receipts.single().event)
        }
    }

    @Test fun earlyStaleWrongPlanAndMissingTargetsCannotCreateTriggerFactsOrTasks(): Unit = RuntimeFixture().use { f ->
        f.confirm(); f.now = RuntimeFixture.DUE_MILLIS; val e = f.event(); val bytes = f.blob()
        val early = ReminderEventRequest.create(e.scope, e.expectedVersion, f.now - 1, f.observation())
        assertEquals(ReminderRuntimeOutcome.TOO_EARLY, f.store.acceptRuntimeEvent(early).outcome)
        for (scope in listOf(e.scope.copy(mode = TaskReminderMode.APPROXIMATE), e.scope.copy(token = e.scope.token.copy(scheduleVersion = 99)),
            e.scope.copy(schedule = RuntimeFixture.date(1)))) {
            val bad = ReminderEventRequest.create(scope, e.expectedVersion, f.now, f.observation())
            assertEquals(ReminderRuntimeOutcome.STALE, f.store.acceptRuntimeEvent(bad).outcome)
        }
        assertArrayEquals(bytes, f.blob()); assertTrue(f.view().receipts.isEmpty())
        f.local.db.transaction { it.execute("DELETE FROM manual_tasks WHERE id='one'") }
        assertEquals(ReminderRuntimeOutcome.MISSING, f.store.acceptRuntimeEvent(e).outcome)
        assertTrue(f.store.snapshots().isEmpty()); assertEquals(0, f.posts.get())
    }

    @Test fun fourUnresolvedSlotsPreventNewConfirmationButAllowCompletionAndExplicitSafeCollection(): Unit = RuntimeFixture().use { f ->
        val events = mutableListOf<ReminderEventRequest>()
        f.confirm()
        repeat(4) { index ->
            if (index > 0) f.nextPlan()
            f.now = f.current().schedule!!.instant()!!.toEpochMilli()
            val e = f.event(RuntimeFixture.DENIED); events += e; f.store.acceptRuntimeEvent(e)
        }
        assertEquals(4, f.view().receipts.size)
        val fourth = f.current()
        f.store.reschedule(ManualTaskScheduleMutation("one", fourth.version, ManualTaskDateOperation.SET,
            fourth.schedule!!.copy(date = fourth.schedule!!.date.plusDays(1)), f.now, "future-fifth-date"))
        val current = f.current(); val bytes = f.blob()
        assertThrows(ReminderRuntimeLimit::class.java) { f.store.changeReminder(TaskReminderMutation(current, TaskReminderMode.EXACT, f.now, "fifth")) }
        assertArrayEquals(bytes, f.blob())
        f.store.change(ManualTaskMutation("one", current.version, ManualTaskStatus.COMPLETED, f.now, "complete"))
        assertEquals(4, f.view().receipts.size); assertTrue(f.view().receipts.all { it.retired != null })
        assertEquals(0, f.coordinator().collect("one"))
        for (c in f.store.reminderCommands()) assertTrue(f.store.acknowledgeReminder(c, TaskReminderPhase.REVOKED, f.now))
        assertEquals(4, f.view().receipts.size) // ACK did not erase identities.
        assertEquals(4, f.coordinator().collect("one")); assertTrue(f.view().receipts.isEmpty())
        assertEquals(ReminderRuntimeOutcome.STALE, f.store.acceptRuntimeEvent(events.first()).outcome)
        assertThrows(ReminderRuntimeConflict::class.java) { events.first().copy(observedAtMillis = events.first().observedAtMillis + 1) }
        assertEquals(0, f.posts.get())
    }

    @Test fun triggerCapabilityFailuresAreDurableAndNeverSilentlyDowngradeExactMode(): Unit {
        for (a in listOf(RuntimeFixture.AVAILABLE.copy(platformConnected = false), RuntimeFixture.DENIED,
            RuntimeFixture.AVAILABLE.copy(notifications = TaskReminderPermission.NOT_REQUESTED), RuntimeFixture.AVAILABLE.copy(channelEnabled = false),
            RuntimeFixture.AVAILABLE.copy(exactAlarms = TaskReminderPermission.DENIED), RuntimeFixture.AVAILABLE.copy(exactAlarms = TaskReminderPermission.NOT_REQUESTED))) RuntimeFixture().use { f ->
            f.confirm(); f.now = RuntimeFixture.DUE_MILLIS; f.capability = { a }; f.fire()
            assertEquals(TaskReminderPhase.TRIGGER_BLOCKED, f.current().reminder!!.phase)
            assertEquals(TaskReminderMode.EXACT, f.current().reminder!!.mode); assertTrue(f.current().reminder!!.commands.isEmpty())
            f.capability = { RuntimeFixture.AVAILABLE }; f.reopen(); f.fire(); assertEquals(0, f.posts.get())
        }
    }

    @Test fun everyUserDecisionKeepsItsExactR1ReplayAfterRuntimeReceiptsAndIndependentCancelAcks(): Unit {
        for(op in listOf("RENAME","SET","POSTPONE","CLEAR","COMPLETE","CANCEL","REVOKE")) RuntimeFixture().use{f->
            f.confirm();f.nextPlan();f.now=f.current().schedule!!.instant()!!.toEpochMilli();f.fire()
            val old=f.current()
            val replay:()->ManualTaskSnapshot=when(op){
                "RENAME"->{val r=ManualTaskRename("one",old.version,"合成新标题",f.now,"user");{f.store.rename(r)}}
                "COMPLETE","CANCEL"->{val r=ManualTaskMutation("one",old.version,if(op=="COMPLETE")ManualTaskStatus.COMPLETED else ManualTaskStatus.CANCELLED,f.now,"user");{f.store.change(r)}}
                "REVOKE"->{val r=TaskReminderMutation(old,null,f.now,"user");{f.store.changeReminder(r)}}
                else->{val due=if(op=="CLEAR")null else old.schedule!!.copy(date=old.schedule!!.date.plusDays(1))
                    val r=ManualTaskScheduleMutation("one",old.version,ManualTaskDateOperation.valueOf(op),due,f.now,"user");{f.store.reschedule(r)}}
            }
            f.lostCommit{replay()};assertTrue(f.coordinator().drain()>0)
            f.reopen();val current=f.current();val bytes=f.blob()
            assertEquals(op,current,replay());assertArrayEquals(bytes,f.blob());assertEquals(1,f.posts.get())
            assertEquals(1,f.view().receipts.size)
        }
    }
}

internal class RuntimeFixture : AutoCloseable {
    val local = LocalCandidateFixture()
    val sqlDepth = AtomicInteger()
    private val sql = object : RecordsSqlDatabase {
        override fun <T> transaction(block: (RecordsSqlConnection) -> T): T = local.gateway.transaction { connection ->
            sqlDepth.incrementAndGet(); try { block(connection) } finally { sqlDepth.decrementAndGet() }
        }
    }
    var store = SqlManualTaskRepository(sql, LocalCandidateFixture.KEY)
    var port: ReminderRuntimeRepository = store
    val gate = ReminderExecutionGate()
    var owner = gate.openOwner(accessAllowed = true)
    var now = 110L
    val availabilityCalls = AtomicInteger(); val schedules = AtomicInteger(); val cancels = AtomicInteger(); val posts = AtomicInteger()
    var capability: (Int) -> TaskReminderAvailability = { AVAILABLE }
    var postEffect: () -> ReminderPostObservation = { ReminderPostObservation.POST_RETURNED }
    var scheduleEffect: () -> TaskReminderPhase = { TaskReminderPhase.SCHEDULED }
    val driver = object : ReminderRuntimeDriver {
        override fun availability(): TaskReminderAvailability { assertEquals(0, sqlDepth.get()); return capability(availabilityCalls.incrementAndGet()) }
        override fun schedule(command: TaskReminderCommand): TaskReminderPhase { assertEquals(0, sqlDepth.get()); schedules.incrementAndGet(); return scheduleEffect() }
        override fun cancel(command: TaskReminderCommand): TaskReminderPhase { assertEquals(0, sqlDepth.get()); cancels.incrementAndGet(); return TaskReminderPhase.REVOKED }
        override fun post(token: TaskReminderToken, specification: String): ReminderPostObservation { assertEquals(0, sqlDepth.get()); assertEquals("GENERIC_V1", specification); posts.incrementAndGet(); return postEffect() }
    }
    fun coordinator() = ReminderRuntimeCoordinator(owner, { port }, driver) { now }
    fun current() = store.snapshots().single { it.id == "one" }
    fun view() = requireNotNull(store.runtimeView("one"))
    fun blob() = local.db.read("SELECT ciphertext FROM manual_tasks WHERE id='one'").single().single() as ByteArray
    fun confirm(): TaskReminderMutation {
        val t = store.create(ManualTaskCreate("one", "合成明确提醒🙂", 55, "UTC", "create", date(0)))
        return TaskReminderMutation(t, TaskReminderMode.EXACT, 99, "confirm").also { store.changeReminder(it) }
    }
    fun nextPlan() {
        val old = current(); val due = requireNotNull(old.schedule).copy(date = old.schedule!!.date.plusDays(1))
        val next = store.reschedule(ManualTaskScheduleMutation("one", old.version, ManualTaskDateOperation.SET, due, maxOf(now, old.updatedAtMillis), "date-" + old.version))
        store.changeReminder(TaskReminderMutation(next, TaskReminderMode.EXACT, maxOf(now, next.updatedAtMillis), "confirm-" + next.version))
    }
    fun observation(a: TaskReminderAvailability = AVAILABLE) = ReminderCapabilityObservation(now, a)
    fun lostCommit(action:()->Unit) {
        var once=true
        local.db.afterSql={if(it=="COMMIT"&&once){once=false;error("Synthetic lost runtime/user COMMIT acknowledgement")}}
        try{assertThrows(ManualTaskStorageFailure::class.java,action)}finally{local.db.afterSql=null}
        assertFalse(once)
    }
    fun event(a: TaskReminderAvailability = AVAILABLE): ReminderEventRequest {
        val t = current(); val r = requireNotNull(t.reminder)
        return ReminderEventRequest.create(ReminderRuntimeScope(TaskReminderToken(t.id, r.id, r.scheduleVersion), r.schedule, r.mode), t.version, now, observation(a))
    }
    fun fire(): ReminderRuntimeReply {
        val r = requireNotNull(current().reminder)
        val ingress = requireNotNull(owner.capture(TaskReminderToken("one", r.id, r.scheduleVersion), now))
        return coordinator().handle(ingress)
    }
    fun reopen() {
        owner.closeAndAwait { store.wipeKey(); local.reopen() }
        store = SqlManualTaskRepository(sql, LocalCandidateFixture.KEY); port = store; owner = gate.openOwner(accessAllowed = true)
    }
    override fun close() { local.db.beforeSql = null; local.db.afterSql = null; local.db.beforeCommit = null; owner.closeAndAwait { store.wipeKey(); local.close() } }
    companion object {
        val AVAILABLE = TaskReminderAvailability(true, TaskReminderPermission.GRANTED, TaskReminderPermission.GRANTED)
        val DENIED = AVAILABLE.copy(notifications = TaskReminderPermission.DENIED)
        fun date(days: Long) = ManualTaskDateInput(LocalDate.of(2042,11,19).plusDays(days).toString(), "09:30", "Pacific/Chatham").parse()!!
        val DUE_MILLIS = date(0).instant()!!.toEpochMilli()
    }
}
