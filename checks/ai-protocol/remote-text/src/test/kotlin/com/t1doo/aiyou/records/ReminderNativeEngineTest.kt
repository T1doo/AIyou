package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class ReminderNativeEngineTest {
    @Test fun realSqlRecoveryPagesKeepPastPlansAndAdvanceFailedPrefixesWithOneGlobalCommandBudget():Unit=RebuildFixture().use{f->
        val past=(0 until 20).map{"a-past-%02d".format(it)};past.forEach{f.pending(it)}
        val old=past.associateWith(f::taskBytes)
        fun future(id:String){val t=f.tasks.create(ManualTaskCreate(id,"合成未来",55,"UTC","create-$id",RuntimeFixture.date(1)));f.tasks.changeReminder(TaskReminderMutation(t,TaskReminderMode.EXACT,99,"confirm-$id"))}
        repeat(17){future("b-failure-%02d".format(it))};future("z-future")
        f.pending("cancelled");val cancel=f.db.transaction{f.tasks.readWithin(it,"cancelled")!!};f.tasks.changeReminder(TaskReminderMutation(cancel,null,100,"revoke"))
        val bridge=ReminderOperationBridge();val owner=bridge.gate.openOwner(true);val codec=ReminderEnvelopeCipher(LocalCandidateFixture.KEY)
        var calls=0;var schedules=0;var cancels=0
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=RuntimeFixture.AVAILABLE
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase{calls++;if(command.token.taskId.startsWith("b-failure"))throw ManualTaskStorageFailure();schedules++;return TaskReminderPhase.SCHEDULED}
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase{calls++;cancels++;return TaskReminderPhase.REVOKED}
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("A scan cannot publish")
        }
        val engine=ReminderNativeEngine(ReminderRuntimeResources(f.tasks,f.meta,codec),owner,driver,{RuntimeFixture.DUE_MILLIS}){f.fingerprint}
        try{
            engine.kick(ReminderRebuildRequest.BOOT);var more=true;var turns=0
            while(more&&turns<40){val before=calls;more=engine.turn(3);assertTrue(calls-before<=3);turns++}
            assertFalse(more);assertTrue(turns>3);assertEquals(1,schedules);assertEquals(1,cancels)
            old.forEach{(id,b)->assertArrayEquals(b,f.taskBytes(id))}
            assertEquals(17,f.tasks.reminderCommands().count{it.kind==TaskReminderCommandKind.SCHEDULE&&it.token.taskId.startsWith("b-failure")})
        }finally{owner.closeAndAwait();codec.close();bridge.close()}
    }
    @Test fun freshPostAndSqlCommandPageSpendOneSharedTurnBudgetAndStopAfterCapabilityWaitKeepsCursor():Unit=RebuildFixture().use{f->
        repeat(3){n->val id="future-$n";val t=f.tasks.create(ManualTaskCreate(id,"合成未来",55,"UTC","create-$id",RuntimeFixture.date(1)));f.tasks.changeReminder(TaskReminderMutation(t,TaskReminderMode.EXACT,99,"confirm-$id"))}
        val trigger=f.pending("trigger").task.let{f.db.transaction{s->f.tasks.readWithin(s,it.id)!!}}
        val owner=ReminderExecutionGate().openOwner(true);val codec=ReminderEnvelopeCipher(LocalCandidateFixture.KEY)
        var schedules=0;var posts=0;var stopOnAvailability=false;var active=true
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=RuntimeFixture.AVAILABLE.also{if(stopOnAvailability)active=false}
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase{schedules++;return TaskReminderPhase.SCHEDULED}
            override fun cancel(command:TaskReminderCommand)=TaskReminderPhase.REVOKED
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{posts++;return ReminderPostObservation.POST_RETURNED}
        }
        val engine=ReminderNativeEngine(ReminderRuntimeResources(f.tasks,f.meta,codec),owner,driver,{RuntimeFixture.DUE_MILLIS}){f.fingerprint}
        try{
            engine.kick(ReminderRebuildRequest.BOOT);engine.turn()
            val token=TaskReminderToken(trigger.id,trigger.reminder!!.id,trigger.reminder!!.scheduleVersion)
            val budget=ReminderPortBudget(2)
            engine.handle(codec.seal(token,ReminderEnvelopePurpose.ALARM),RuntimeFixture.DUE_MILLIS,{},budget)
            assertTrue(engine.turn(16,budget=budget));assertEquals(2,budget.spent);assertEquals(1,posts);assertEquals(1,schedules)
            assertTrue(engine.turn(16,budget=budget));assertEquals(1,schedules)
            val before=f.meta.read();stopOnAvailability=true
            assertFalse(engine.turn(proceed={active}));assertEquals(before,f.meta.read());assertEquals(1,schedules)
            active=true;stopOnAvailability=false
            var more=true;repeat(8){if(more)more=engine.turn()};assertFalse(more);assertEquals(3,schedules)
        }finally{owner.closeAndAwait();codec.close()}
    }

    @Test fun duplicateKnownBootAndProcessOnlyUnknownBootFallbackDoNotCreateUnboundedRebuildEpochs():Unit=RebuildFixture().use{f->
        val process=ReminderBootAdmission();var fingerprint=f.fingerprint
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=fingerprint.availability
            override fun schedule(command:TaskReminderCommand)=TaskReminderPhase.SCHEDULED
            override fun cancel(command:TaskReminderCommand)=TaskReminderPhase.REVOKED
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("No callback")
        }
        val codec=ReminderEnvelopeCipher(LocalCandidateFixture.KEY)
        fun ownerTurn(reasons:Int):String{
            val owner=ReminderExecutionGate().openOwner(true)
            try{
                val engine=ReminderNativeEngine(ReminderRuntimeResources(f.tasks,f.meta,codec),owner,driver,{RuntimeFixture.DUE_MILLIS},process){fingerprint}
                engine.kick(reasons);return f.meta.read().request!!.id
            }finally{owner.closeAndAwait()}
        }
        try{
            val known=ownerTurn(ReminderRebuildRequest.BOOT);assertEquals(known,ownerTurn(ReminderRebuildRequest.BOOT))
            fingerprint=fingerprint.copy(bootCount=null)
            val unknown=ownerTurn(ReminderRebuildRequest.ADMISSION);assertNotEquals(known,unknown)
            assertEquals(unknown,ownerTurn(ReminderRebuildRequest.ADMISSION));assertEquals(unknown,ownerTurn(ReminderRebuildRequest.BOOT))
            assertNotEquals(unknown,ownerTurn(ReminderRebuildRequest.CLOCK))
        }finally{codec.close()}
    }

    @Test fun userRetiredSlotsAreCollectedOnlyAfterPreciseCancellationSoMoreThanFourRealGenerationsCanRun():Unit=RebuildFixture().use{f->
        f.pending("recurring-user");var now=RuntimeFixture.DUE_MILLIS;var posts=0;var cancels=0
        val owner=ReminderExecutionGate().openOwner(true);val codec=ReminderEnvelopeCipher(LocalCandidateFixture.KEY)
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=RuntimeFixture.AVAILABLE
            override fun schedule(command:TaskReminderCommand)=TaskReminderPhase.SCHEDULED
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase{cancels++;return TaskReminderPhase.REVOKED}
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{posts++;return ReminderPostObservation.POST_RETURNED}
        }
        val engine=ReminderNativeEngine(ReminderRuntimeResources(f.tasks,f.meta,codec),owner,driver,{now}){f.fingerprint}
        try{repeat(5){cycle->
            val task=f.tasks.runtimeView("recurring-user")!!.task;val r=task.reminder!!
            val old=codec.seal(TaskReminderToken(task.id,r.id,r.scheduleVersion),ReminderEnvelopePurpose.ALARM)
            engine.handle(old,now,{})
            assertEquals(cycle+1,posts)
            val current=f.tasks.runtimeView(task.id)!!.task
            val change=ManualTaskScheduleMutation(task.id,current.version,ManualTaskDateOperation.SET,RuntimeFixture.date(cycle+1L),now+1,"next-date-$cycle")
            f.tasks.reschedule(change)
            engine.kick(ReminderRebuildRequest.USER);var more=true;repeat(8){if(more)more=engine.turn()};assertFalse(more)
            val retired=f.tasks.runtimeView(task.id)!!
            assertTrue(retired.receipts.isEmpty());assertEquals(cycle+1,cancels)
            val before=f.taskBytes(task.id);f.tasks.reschedule(change);assertArrayEquals(before,f.taskBytes(task.id))
            f.tasks.changeReminder(TaskReminderMutation(retired.task,TaskReminderMode.EXACT,now+2,"confirm-next-$cycle"))
            engine.handle(old,now,{}) // Collected old generation remains stale against the current token.
            assertEquals(cycle+1,posts)
            now=RuntimeFixture.date(cycle+1L).instant()!!.toEpochMilli()
        }}finally{owner.closeAndAwait();codec.close()}
    }

}
