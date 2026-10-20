package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReminderRebuildSqlTest {
    @Test fun actualExportedFourteenMigratesOnlyTheSingletonAndKeepsOriginalTablesAndRows(): Unit = RebuildFixture(false).use { f ->
        val before=f.db.read("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name")
        f.db.transaction { s->s.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('synthetic',1,5,'UTC','LOCAL_ONLY')");s.execute("INSERT INTO entry_revisions VALUES('synthetic',1,'合成原文','synthetic',5)") }
        val original=f.db.read("SELECT * FROM entry_revisions")
        f.migrate()
        assertEquals(before,f.db.read("SELECT name,sql FROM sqlite_master WHERE type='table' AND name!='reminder_runtime_meta' ORDER BY name"))
        assertEquals(original,f.db.read("SELECT * FROM entry_revisions"));assertEquals(15L,f.db.read("PRAGMA user_version").single().single())
        assertEquals(listOf("slot","version","ciphertext"),f.db.read("PRAGMA table_info(reminder_runtime_meta)").map{it[1]})
        assertEquals(ReminderRuntimeMeta(),f.meta.read())
    }

    @Test fun immutableEpochSurvivesProgressAndUnknownCommitWhileModifiedIdAndOldCasCannotOverwrite(): Unit = RebuildFixture().use{f->
        f.pending("one");val request=ReminderRebuildRequest.create(0,ReminderRebuildRequest.BOOT,f.fingerprint)
        f.lost{f.meta.request(request)};val accepted=f.meta.read();assertEquals(request,accepted.request)
        f.meta.planPage(accepted.version,100);val later=f.meta.read();val bytes=f.metaBytes()
        assertEquals(later,f.meta.request(request));assertArrayEquals(bytes,f.metaBytes())
        assertThrows(ReminderRuntimeConflict::class.java){request.copy(reasons=ReminderRebuildRequest.USER)}
        val stale=ReminderRebuildRequest.create(0,ReminderRebuildRequest.USER,f.fingerprint)
        assertThrows(ReminderRuntimeConflict::class.java){f.meta.request(stale)};assertArrayEquals(bytes,f.metaBytes())
    }

    @Test fun futureScheduledRebuildKeepsUserReceiptAndGenerationAndNeverRearmsObservedOrPastPlans(): Unit = RebuildFixture().use{f->
        val request=f.pending("future");var t=f.tasks.readWithinForTest(f,"future")
        val schedule=t.reminder!!.commands.single();assertTrue(f.tasks.acknowledgeReminder(schedule,TaskReminderPhase.SCHEDULED,100))
        f.pending("past");val pastBefore=f.taskBytes("past")
        f.pending("observed");val o=f.tasks.readWithinForTest(f,"observed");val r=o.reminder!!
        val at=RuntimeFixture.DUE_MILLIS
        f.tasks.acceptRuntimeEvent(ReminderEventRequest.create(ReminderRuntimeScope(TaskReminderToken(o.id,r.id,r.scheduleVersion),r.schedule,r.mode),o.version,at,ReminderCapabilityObservation(at,RuntimeFixture.AVAILABLE)))
        // Move only the future task to a distinct future day by an explicit user decision.
        t=f.tasks.readWithinForTest(f,"future")
        val changed=f.tasks.reschedule(ManualTaskScheduleMutation(t.id,t.version,ManualTaskDateOperation.SET,RuntimeFixture.date(1),101,"future-date"))
        val latest=TaskReminderMutation(changed,TaskReminderMode.EXACT,102,"future-confirm");f.tasks.changeReminder(latest)
        val current=f.tasks.readWithinForTest(f,"future");f.tasks.acknowledgeReminder(current.reminder!!.commands.single{it.kind==TaskReminderCommandKind.SCHEDULE},TaskReminderPhase.SCHEDULED,103)
        val observedBefore=f.taskBytes("observed")
        f.meta.request(ReminderRebuildRequest.create(0,ReminderRebuildRequest.BOOT,f.fingerprint));f.meta.planPage(1,at)
        val rebuilt=f.tasks.readWithinForTest(f,"future");assertEquals(TaskReminderPhase.PENDING,rebuilt.reminder!!.phase)
        assertEquals(current.reminder!!.scheduleVersion,rebuilt.reminder!!.scheduleVersion)
        val bytes=f.taskBytes("future");assertEquals(rebuilt,f.tasks.changeReminder(latest));assertArrayEquals(bytes,f.taskBytes("future"))
        assertArrayEquals(pastBefore,f.taskBytes("past"));assertArrayEquals(observedBefore,f.taskBytes("observed"))
        assertEquals(0,f.meta.read().failedRows);assertNotNull(request)
    }

    @Test fun unicodePagesAndThirtyTwoCommandsInOneRowAdvanceAcrossColdReopensWithoutLosingFailures(): Unit = RebuildFixture().use{f->
        val ids=(0 until 20).map{"a-expired-%02d".format(it)}+listOf("\uE000-bmp","🙂-astral","z-many")
        ids.forEach{f.pending(it)}
        repeat(31){n->val t=f.tasks.readWithinForTest(f,"z-many");val next=f.tasks.changeReminder(TaskReminderMutation(t,null,101L+n*2,"revoke-$n"));f.tasks.changeReminder(TaskReminderMutation(next,TaskReminderMode.EXACT,102L+n*2,"confirm-$n"))}
        assertEquals(32,f.tasks.readWithinForTest(f,"z-many").reminder!!.commands.size)
        f.meta.requestDrain(0,"initial-drain")
        val seen=mutableSetOf<TaskReminderToken>();var pages=0;var withinRow=false
        while(f.meta.read().drain?.done==false && pages<20){
            val page=f.meta.commandPage(RuntimeFixture.DUE_MILLIS,16,16)!!;assertTrue(page.scannedRows<=16);assertTrue(page.commands.size<=16)
            seen+=page.commands.map{it.token};withinRow=withinRow || page.next?.rowExhausted==false
            // All ports could fail; still checkpoint the finite scan so no prefix can starve later tasks.
            f.meta.checkpoint(page);f.reopen();pages++
        }
        assertTrue(pages<20);assertTrue(f.meta.read().drain!!.done);assertTrue(withinRow)
        val expected=f.tasks.reminderCommands().filter{it.kind==TaskReminderCommandKind.CANCEL}.map{it.token}.toSet()
        assertEquals(expected,seen);assertEquals(31,seen.size)
        // At an earlier time the next finite round must include both Unicode future schedules.
        val m=f.meta.read();f.meta.requestDrain(m.version,"next-drain");val unicode=mutableSetOf<String>()
        repeat(20){if(f.meta.read().drain?.done==false){val p=f.meta.commandPage(110)!!;unicode+=p.commands.map{it.token.taskId};f.meta.checkpoint(p)}}
        assertTrue(unicode.containsAll(listOf("\uE000-bmp","🙂-astral")))
    }

    @Test fun failedPrefixRowsDoNotStopLaterSchedulesAndCheckpointUnknownCommitCannotResetTheCursor(): Unit = RebuildFixture().use{f->
        repeat(18){f.pending("a-failure-%02d".format(it))};f.pending("z-future")
        f.meta.requestDrain(0,"drain")
        val page=f.meta.commandPage(110)!!;assertEquals(16,page.commands.size)
        f.lost{f.meta.checkpoint(page)};val after=f.meta.read();assertEquals(after,f.meta.checkpoint(page))
        f.reopen();val next=f.meta.commandPage(110)!!;assertTrue(next.commands.any{it.token.taskId=="z-future"})
        assertTrue(next.commands.none{it.token in page.commands.map{c->c.token}.toSet()})
    }
    private fun SqlManualTaskRepository.readWithinForTest(f:RebuildFixture,id:String)=f.db.transaction{readWithin(it,id)!!}
}

internal class RebuildFixture(migrate:Boolean=true):AutoCloseable {
    private val directory=Files.createTempDirectory("aiyou-reminder-rebuild-")
    private val bridge=directory.resolve("bridge.py");private val file=directory.resolve("records.sqlite")
    var db:PythonSqliteDatabase;private set
    var tasks:SqlManualTaskRepository;private set
    var meta:SqlReminderRebuildRepository;private set
    val fingerprint=ReminderPlatformFingerprint(12,"UTC",2,RuntimeFixture.AVAILABLE)
    init{javaClass.getResourceAsStream("/sqlite_bridge.py").use{Files.copy(checkNotNull(it),bridge)}
        db=PythonSqliteDatabase(file,bridge,true,14);db.transaction{it.execute(ManualTaskSqlSchema.guard)}
        tasks=SqlManualTaskRepository(db,LocalCandidateFixture.KEY);meta=SqlReminderRebuildRepository(db,LocalCandidateFixture.KEY,tasks)
        if(migrate)migrate()
    }
    fun migrate()=db.transaction{s->ReminderRuntimeMetaSqlSchema.migration14To15.forEach{s.execute(it)};s.execute("PRAGMA user_version=15")}
    fun pending(id:String):TaskReminderMutation {
        val t=tasks.create(ManualTaskCreate(id,"合成分页任务",55,"UTC","create-$id",RuntimeFixture.date(0)))
        return TaskReminderMutation(t,TaskReminderMode.EXACT,99,"confirm-$id").also{tasks.changeReminder(it)}
    }
    fun metaBytes()=db.read("SELECT ciphertext FROM reminder_runtime_meta").single().single() as ByteArray
    fun taskBytes(id:String)=db.transaction{(it.query("SELECT ciphertext FROM manual_tasks WHERE id=?",listOf(id)).single().single() as ByteArray)}
    fun lost(action:()->Unit){var once=true;db.afterSql={if(it=="COMMIT"&&once){once=false;error("Synthetic lost metadata COMMIT")}};try{assertThrows(ReminderMetaStorageFailure::class.java,action)}finally{db.afterSql=null};assertFalse(once)}
    fun reopen(){meta.close();tasks.wipeKey();db.close();db=PythonSqliteDatabase(file,bridge,false,14);tasks=SqlManualTaskRepository(db,LocalCandidateFixture.KEY);meta=SqlReminderRebuildRepository(db,LocalCandidateFixture.KEY,tasks)}
    override fun close(){db.afterSql=null;db.beforeSql=null;db.beforeCommit=null;meta.close();tasks.wipeKey();db.close();Files.walk(directory).use{it.sorted(Comparator.reverseOrder()).forEach(Files::delete)}}
}
