package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite/JCA and cold owners. The driver is controlled; no Android permission or alarm runs. */
class TaskUserReceiptSqlTest {
    private class F : AutoCloseable {
        val local = LocalCandidateFixture()
        var store = SqlManualTaskRepository(local.gateway, LocalCandidateFixture.KEY)
        val calls = mutableListOf<TaskReminderCommand>()
        val driver = object : TaskReminderDriver {
            override fun availability() = AVAILABLE
            override fun schedule(command: TaskReminderCommand): TaskReminderPhase { calls += command; return TaskReminderPhase.SCHEDULED }
            override fun cancel(command: TaskReminderCommand): TaskReminderPhase { calls += command; return TaskReminderPhase.REVOKED }
        }
        fun create(id: String = "one") = store.create(ManualTaskCreate(id, "合成明确待办🙂", 55, "UTC", "create-" + id, DUE))
        fun current() = store.snapshots().single { it.id == "one" }
        fun blob() = local.db.read("SELECT ciphertext FROM manual_tasks WHERE id='one'").single().single() as ByteArray
        fun reopen() { store.wipeKey(); local.reopen(); store = SqlManualTaskRepository(local.gateway, LocalCandidateFixture.KEY) }
        fun drain() = TaskReminderDispatcher(store, driver) { 110 }.drain()
        fun lost(action: () -> Unit) {
            var committed = false
            local.db.afterSql = { if (it == "COMMIT" && !committed) { committed = true; error("Synthetic lost user ACK") } }
            try { assertThrows(ManualTaskStorageFailure::class.java, action) } finally { local.db.afterSql = null }
            assertTrue(committed)
        }
        override fun close() { local.db.afterSql = null; store.wipeKey(); local.close() }
    }
    @Test fun lostConfirmationSurvivesControlledSystemAckTriggerAndColdReopenWithoutAnotherWrite(): Unit = F().use { f ->
        val request = TaskReminderMutation(f.create(), TaskReminderMode.EXACT, 99, "confirm")
        f.lost { f.store.changeReminder(request) }
        assertEquals(1, f.drain()); assertEquals(1, f.calls.size)
        f.reopen(); val current = f.current(); val bytes = f.blob()
        assertEquals(current, f.store.changeReminder(request)); assertArrayEquals(bytes, f.blob())
        assertEquals(1L, current.reminder!!.scheduleVersion)
        val token = TaskReminderToken(current.id, current.reminder!!.id, current.reminder!!.scheduleVersion)
        assertTrue(f.store.acceptReminderTrigger(token, DUE.instant()!!.toEpochMilli(), AVAILABLE))
        assertFalse(f.store.acceptReminderTrigger(token, DUE.instant()!!.toEpochMilli(), AVAILABLE))
        f.reopen(); val fired = f.current(); val firedBytes = f.blob()
        assertEquals(TaskReminderPhase.TRIGGER_ACCEPTED, fired.reminder!!.phase)
        assertEquals(fired, f.store.changeReminder(request)); assertArrayEquals(firedBytes, f.blob())
        assertTrue(f.store.reminderCommands().isEmpty()); assertEquals(1, f.calls.size)
    }
    @Test fun statusDateRenameAndRevocationRetainOnlyTheirOwnExactReceiptAcrossSystemWrites(): Unit {
        for (op in listOf("COMPLETE", "CANCEL", "SET", "POSTPONE", "CLEAR", "RENAME", "REVOKE")) F().use { f ->
            val original = TaskReminderMutation(f.create(), TaskReminderMode.APPROXIMATE, 60, "earlier-confirm")
            val before = f.store.changeReminder(original)
            val replay: () -> ManualTaskSnapshot = when (op) {
                "COMPLETE", "CANCEL" -> {
                    val request = ManualTaskMutation(before.id, before.version,
                        if (op == "COMPLETE") ManualTaskStatus.COMPLETED else ManualTaskStatus.CANCELLED, 100, "user");
                    val action: () -> ManualTaskSnapshot = { f.store.change(request) }
                    action
                }
                "RENAME" -> { val request = ManualTaskRename(before.id, before.version, "合成纠正🙂", 100, "user"); { f.store.rename(request) } }
                "REVOKE" -> { val request = TaskReminderMutation(before, null, 100, "user"); { f.store.changeReminder(request) } }
                else -> { val request = ManualTaskScheduleMutation(before.id, before.version, ManualTaskDateOperation.valueOf(op),
                    if (op == "CLEAR") null else LATER, 100, "user"); { f.store.reschedule(request) } }
            }
            f.lost { replay() }; assertEquals(1, f.drain()); f.reopen()
            val current = f.current(); val bytes = f.blob()
            assertEquals(op, current, replay()); assertArrayEquals(bytes, f.blob())
            assertTrue(f.store.reminderCommands().isEmpty())
            assertThrows(ManualTaskConflict::class.java) { f.store.changeReminder(original) }
            assertArrayEquals(bytes, f.blob()); assertEquals(1, f.calls.size)
        }
    }
    @Test fun changedRequestFieldsAndANewerUserDecisionCannotReplayOrRewriteSystemProgress(): Unit = F().use { f ->
        val initial = f.create(); val request = TaskReminderMutation(initial, TaskReminderMode.EXACT, 99, "confirm")
        f.store.changeReminder(request); f.drain(); f.reopen(); val before = f.blob()
        val others = listOf(initial.copy(title = "合成不同标题"), initial.copy(version = 2),
            initial.copy(createdAtMillis = 54), initial.copy(updatedAtMillis = 56), initial.copy(creationTimeZone = "Asia/Shanghai"),
            initial.copy(schedule = LATER), initial.copy(status = ManualTaskStatus.CANCELLED))
        for (task in others) assertThrows(ManualTaskConflict::class.java) { f.store.changeReminder(TaskReminderMutation(task, request.mode, 99, "confirm")) }
        for (bad in listOf(TaskReminderMutation(initial, null, 99, "confirm"),
            TaskReminderMutation(initial, TaskReminderMode.APPROXIMATE, 99, "confirm"), TaskReminderMutation(initial, request.mode, 100, "confirm")))
            assertThrows(ManualTaskConflict::class.java) { f.store.changeReminder(bad) }
        assertArrayEquals(before, f.blob())
        val current = f.current(); val rename = ManualTaskRename(current.id, current.version, "合成后续决定", 120, "new-decision")
        f.store.rename(rename); f.reopen(); val changed = f.blob()
        assertThrows(ManualTaskConflict::class.java) { f.store.changeReminder(request) }; assertArrayEquals(changed, f.blob())
        f.local.db.transaction { it.execute("DELETE FROM manual_tasks WHERE id='one'") }
        assertThrows(ManualTaskConflict::class.java) { f.store.changeReminder(request) }
        assertTrue(f.store.snapshots().isEmpty())
    }
    @Test fun normalizedProofsBindEveryAvailableInputAndSeparateOperations(): Unit = F().use { f ->
        val c = ManualTaskCipher(LocalCandidateFixture.KEY)
        try {
            val create = ManualTaskCreate("one", "合成标题", 55, "UTC", "m", DUE)
            val base = c.requestProof(create)
            val hashes = listOf(c.requestProof(ManualTaskCreate("two", create.title,55,"UTC","m",DUE)),
                c.requestProof(ManualTaskCreate("one","合成其他",55,"UTC","m",DUE)),
                c.requestProof(ManualTaskCreate("one",create.title,56,"UTC","m",DUE)),
                c.requestProof(ManualTaskCreate("one",create.title,55,"Asia/Shanghai","m",DUE)),
                c.requestProof(ManualTaskCreate("one",create.title,55,"UTC","n",DUE)),
                c.requestProof(ManualTaskCreate("one",create.title,55,"UTC","m",LATER)),
                c.requestProof(create, SourceTaskReceipt(TaskSourceBinding(f.local.source,OriginalExports.hash(f.local.original),0,0,2),"a".repeat(64))))
            hashes.forEach { assertNotEquals(base,it) }; assertEquals(hashes.size,hashes.distinct().size)
            val rename = ManualTaskRename("one", 3, "合成标题", 99, "m"); val renameHash = c.requestProof(rename)
            for (bad in listOf(ManualTaskRename("two",3,rename.title,99,"m"),ManualTaskRename("one",4,rename.title,99,"m"),
                ManualTaskRename("one",3,"合成其他",99,"m"),ManualTaskRename("one",3,rename.title,100,"m"),ManualTaskRename("one",3,rename.title,99,"n")))
                assertNotEquals(renameHash,c.requestProof(bad))
            val status = ManualTaskMutation("one",3,ManualTaskStatus.COMPLETED,99,"m"); val statusHash=c.requestProof(status)
            for (bad in listOf(ManualTaskMutation("two",3,status.status,99,"m"),ManualTaskMutation("one",4,status.status,99,"m"),
                ManualTaskMutation("one",3,ManualTaskStatus.CANCELLED,99,"m"),ManualTaskMutation("one",3,status.status,100,"m"),ManualTaskMutation("one",3,status.status,99,"n")))
                assertNotEquals(statusHash,c.requestProof(bad))
            val date = ManualTaskScheduleMutation("one",3,ManualTaskDateOperation.SET,DUE,99,"m"); val dateHash=c.requestProof(date)
            for (bad in listOf(ManualTaskScheduleMutation("two",3,date.operation,DUE,99,"m"),ManualTaskScheduleMutation("one",4,date.operation,DUE,99,"m"),
                ManualTaskScheduleMutation("one",3,ManualTaskDateOperation.POSTPONE,DUE,99,"m"),ManualTaskScheduleMutation("one",3,date.operation,LATER,99,"m"),
                ManualTaskScheduleMutation("one",3,date.operation,DUE,100,"m"),ManualTaskScheduleMutation("one",3,date.operation,DUE,99,"n")))
                assertNotEquals(dateHash,c.requestProof(bad))
            assertEquals(4,listOf(base,renameHash,statusHash,dateHash).distinct().size)
        } finally { c.wipe() }
    }
    @Test fun legacyV4ConfirmationCanAcquireReceiptOnlyBeforeItsFirstSystemWrite(): Unit = F().use { f ->
        val initial = ManualTaskSnapshot("one",1,"合成旧格式",ManualTaskStatus.OPEN,55,"UTC",55,DUE)
        val request=TaskReminderMutation(initial,TaskReminderMode.EXACT,99,"legacy-confirm")
        val saved=initial.copy(version=2,updatedAtMillis=99,reminder=TaskReminders.confirm(initial,request.mode!!))
        val cipher=ManualTaskCipher(LocalCandidateFixture.KEY)
        try {
            val bytes=cipher.seal(saved,request.mutationId,99,"REMIND",cipher.reminderRequestProof(request))
            assertEquals(4.toByte(),bytes[0])
            f.local.db.transaction { it.execute("INSERT INTO manual_tasks VALUES(?,?,?,?)",listOf("one",2,request.mutationId,bytes)) }
            f.drain(); assertEquals(5.toByte(),f.blob()[0]); f.reopen()
            val current=f.current();val after=f.blob();assertEquals(current,f.store.changeReminder(request));assertArrayEquals(after,f.blob())
            assertEquals(1L,current.reminder!!.scheduleVersion)
        } finally { cipher.wipe() }
    }
    @Test fun reminderProofBindsCompleteOldSnapshotAndEveryNestedInputAfterSystemProgress(): Unit = F().use { f ->
        val c = ManualTaskCipher(LocalCandidateFixture.KEY)
        try {
            f.store.changeReminder(TaskReminderMutation(f.create(), TaskReminderMode.EXACT, 60, "first"))
            f.drain(); val before = f.current()
            val request = TaskReminderMutation(before, TaskReminderMode.APPROXIMATE, 120, "second")
            val receipt = SourceTaskReceipt(TaskSourceBinding(f.local.source,OriginalExports.hash(f.local.original),0,0,2),"b".repeat(64))
            val old = before.reminder!!
            val variants = listOf(before.copy(id="two",reminder=old.copy(id=TaskReminders.id("two"))),before.copy(version=before.version+1),before.copy(title="合成另一个标题"),
                before.copy(createdAtMillis=54),before.copy(updatedAtMillis=111),before.copy(creationTimeZone="Asia/Shanghai"),
                before.copy(status=ManualTaskStatus.CANCELLED),before.copy(schedule=LATER),before.copy(sourceReceipt=receipt),before.copy(reminder=null),
                before.copy(reminder=old.copy(scheduleVersion=old.scheduleVersion+1)),
                before.copy(reminder=old.copy(mode=TaskReminderMode.APPROXIMATE)),before.copy(reminder=old.copy(schedule=LATER)),
                before.copy(reminder=old.copy(phase=TaskReminderPhase.NOTIFICATION_DENIED)),
                before.copy(reminder=old.copy(scheduleVersion=old.scheduleVersion+1,
                    commands=listOf(TaskReminderCommand(TaskReminderToken(before.id,old.id,old.scheduleVersion),TaskReminderCommandKind.CANCEL,DUE,old.mode)))))
            val hash=c.requestProof(request)
            for (t in variants) assertNotEquals(hash,c.requestProof(TaskReminderMutation(t,request.mode,120,request.mutationId)))
            f.store.changeReminder(request); f.drain(); f.reopen(); val bytes=f.blob()
            for (t in variants) assertThrows(ManualTaskConflict::class.java) { f.store.changeReminder(TaskReminderMutation(t,request.mode,120,request.mutationId)) }
            assertArrayEquals(bytes,f.blob()); assertEquals(f.current(),f.store.changeReminder(request))
        } finally { c.wipe() }
    }
    @Test fun authenticatedReceiptBindsUserDecisionAndV5RemainsBoundedStrictAndPortable(): Unit = F().use { f ->
        val original=f.create();val c=ManualTaskCipher(LocalCandidateFixture.KEY)
        try {
            val payload=c.open(original.id,original.version,"create-one",f.blob());val receipt=checkNotNull(payload.userReceipt)
            assertEquals(5.toByte(),f.blob()[0]);assertFalse(receipt.toString().contains(original.title))
            assertThrows(IllegalArgumentException::class.java) { c.seal(original.copy(title="合成篡改"),"create-one",55,userReceipt=receipt) }
            assertThrows(IllegalArgumentException::class.java) { c.seal(original,"create-one",55,userReceipt=receipt.copy(requestedAtMillis=54)) }
            assertThrows(IllegalArgumentException::class.java) { c.seal(original,"create-one",55,userReceipt=receipt.copy(requestHash="f".repeat(64))) }
            assertThrows(Exception::class.java) { c.open(original.id,1,"other",f.blob()) }
            assertThrows(IllegalArgumentException::class.java) { c.open(original.id,1,"create-one",ByteArray(65537).also { it[0]=5 }) }
            val portable=c.portable(original.id,1,"create-one",f.blob(),false)
            assertThrows(Exception::class.java) { c.portable(original.id,1,"create-one",portable+byteArrayOf(7),true) }
            val restored=c.portable(original.id,1,"create-one",portable,true)
            assertNull(c.open(original.id,1,"create-one",restored).userReceipt)
            assertEquals(original,c.open(original.id,1,"create-one",restored).task)
        } finally { c.wipe() }
    }
    @Test fun legacyV2AndV3ReadWithoutRewriteAndUpgradeOnlyOnAnExplicitUserOperation(): Unit {
        for (source in listOf<SourceTaskReceipt?>(null,SourceTaskReceipt(TaskSourceBinding(SourceVersion("source",1),"a".repeat(64),0,0,2),"b".repeat(64)))) F().use { f ->
            val c=ManualTaskCipher(LocalCandidateFixture.KEY)
            try {
                val task=ManualTaskSnapshot("one",1,"合成旧格式待办",ManualTaskStatus.OPEN,55,"UTC",55,DUE,source)
                val bytes=c.seal(task,"legacy-create",55)
                assertEquals((if(source==null) 2 else 3).toByte(),bytes[0])
                f.local.db.transaction { it.execute("INSERT INTO manual_tasks VALUES(?,?,?,?)",listOf("one",1,"legacy-create",bytes)) }
                f.reopen();assertEquals(task,f.current());assertArrayEquals(bytes,f.blob())
                val request=ManualTaskRename("one",1,"合成用户明确纠正",99,"rename")
                val saved=f.store.rename(request);assertEquals(source,saved.sourceReceipt);assertEquals(5.toByte(),f.blob()[0])
                f.reopen();val changed=f.blob();assertEquals(saved,f.store.rename(request));assertArrayEquals(changed,f.blob())
            } finally { c.wipe() }
        }
    }
    @Test fun legacyV4DateStatusRenameAndRevokeReceiptsSurviveTheirFirstSystemAck(): Unit {
        for (op in listOf("COMPLETE","CANCEL","SET","POSTPONE","CLEAR","RENAME","REVOKE")) F().use { source ->
            val before=source.store.changeReminder(TaskReminderMutation(source.create(),TaskReminderMode.EXACT,60,"first"))
            val apply: (SqlManualTaskRepository) -> ManualTaskSnapshot = when(op) {
                "COMPLETE","CANCEL" -> {
                    val r=ManualTaskMutation("one",before.version,if(op=="COMPLETE") ManualTaskStatus.COMPLETED else ManualTaskStatus.CANCELLED,100,"legacy-user");
                    val action: (SqlManualTaskRepository) -> ManualTaskSnapshot = { it.change(r) }; action
                }
                "RENAME" -> { val r=ManualTaskRename("one",before.version,"合成旧版纠正",100,"legacy-user"); { it.rename(r) } }
                "REVOKE" -> { val r=TaskReminderMutation(before,null,100,"legacy-user"); { it.changeReminder(r) } }
                else -> { val r=ManualTaskScheduleMutation("one",before.version,ManualTaskDateOperation.valueOf(op),if(op=="CLEAR") null else LATER,100,"legacy-user"); { it.reschedule(r) } }
            }
            val result=apply(source.store)
            val wireOperation=when(op) { "COMPLETE","CANCEL" -> "STATUS";"REVOKE" -> "REMIND";else -> op }
            val c=ManualTaskCipher(LocalCandidateFixture.KEY)
            try {
                val proof=if(op=="REVOKE") c.reminderRequestProof(TaskReminderMutation(before,null,100,"legacy-user")) else null
                val bytes=c.seal(result,"legacy-user",100,wireOperation,proof)
                assertEquals(4.toByte(),bytes[0])
                F().use { target ->
                    target.local.db.transaction { it.execute("INSERT INTO manual_tasks VALUES(?,?,?,?)",listOf("one",result.version,"legacy-user",bytes)) }
                    assertEquals(result,target.current());assertArrayEquals(bytes,target.blob())
                    assertEquals(1,target.drain());target.reopen();val current=target.current();val upgraded=target.blob()
                    assertEquals(5.toByte(),upgraded[0]);assertEquals(op,current,apply(target.store));assertArrayEquals(upgraded,target.blob())
                    assertEquals(1,target.calls.size)
                }
            } finally { c.wipe() }
        }
    }
    companion object {
        val DUE = ManualTaskDateSqlTest.date("2042-11-19","09:30","Pacific/Chatham")
        val LATER = ManualTaskDateSqlTest.date("2042-11-20","09:30","Pacific/Chatham")
        val AVAILABLE = TaskReminderAvailability(true,TaskReminderPermission.GRANTED,TaskReminderPermission.GRANTED)
    }
}
