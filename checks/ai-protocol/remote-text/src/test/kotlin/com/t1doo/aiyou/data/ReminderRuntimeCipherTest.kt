package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.time.ZoneId
import java.util.Base64
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class ReminderRuntimeCipherTest {
    @Test fun independentFrozenV1ThroughV5CellsReadWithoutRewritingAndV4ConfirmationStillReplays(): Unit = LocalCandidateFixture().use { f ->
        val properties = fixtures(); val store = SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY)
        try {
            for (format in 1..5) {
                val id="legacy-$format"; val version=properties.getProperty("legacy$format.version").toLong()
                val mutation=properties.getProperty("legacy$format.mutation");val bytes=Base64.getDecoder().decode(properties.getProperty("legacy$format"))
                f.db.transaction { it.execute("INSERT INTO manual_tasks VALUES(?,?,?,?)",listOf(id,version,mutation,bytes)) }
                val task=store.snapshots().single{it.id==id};assertEquals("合成旧格式🙂",task.title)
                assertEquals(if(format==1)null else UTC,task.schedule)
                assertArrayEquals(bytes,f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",id).single().single() as ByteArray)
                if(format==4) {
                    val before=ManualTaskSnapshot(id,1,task.title,ManualTaskStatus.OPEN,55,"UTC",55,UTC)
                    assertEquals(task,store.changeReminder(TaskReminderMutation(before,TaskReminderMode.EXACT,99,mutation)))
                    assertArrayEquals(bytes,f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",id).single().single() as ByteArray)
                }
            }
        } finally {store.wipeKey()}
    }

    @Test fun independentR1DigestsAndR2IdsBindTheirFrozenNamespacesAndFieldOrder() {
        val p=fixtures();val key=ManualTaskCipher(LocalCandidateFixture.KEY)
        try {
            val original=ManualTaskSnapshot("gold-task",1,"合成固定摘要🙂",ManualTaskStatus.OPEN,55,"UTC",55,UTC)
            val old=original.copy(version=2,updatedAtMillis=99,reminder=TaskReminders.confirm(original,TaskReminderMode.EXACT))
            val request=TaskReminderMutation(old,TaskReminderMode.APPROXIMATE,110,"gold-confirm")
            assertEquals(p.getProperty("r1ReminderProof"),key.reminderRequestProof(request))
            assertEquals(p.getProperty("r1ReminderUserProof"),key.requestProof(request))
            val at=UTC.instant()!!.toEpochMilli();val scope=ReminderRuntimeScope(TaskReminderToken(old.id,TaskReminders.id(old.id),1),UTC,TaskReminderMode.EXACT)
            val event=ReminderEventRequest.create(scope,2,at,ReminderCapabilityObservation(at,RuntimeFixture.AVAILABLE),"11".repeat(16))
            val claim=ReminderClaimRequest.create(event,3,at+1,ReminderCapabilityObservation(at+1,RuntimeFixture.AVAILABLE),"22".repeat(16))
            val result=ReminderResultRequest.create(claim,4,at+2,ReminderPostObservation.POST_RETURNED,nonce="33".repeat(16))
            assertEquals(p.getProperty("eventId"),event.id);assertEquals(p.getProperty("claimId"),claim.id);assertEquals(p.getProperty("resultId"),result.id)
        } finally {key.wipe()}
    }

    @Test fun sameRuntimeIdentityCannotChangeAnyPersistentInputEvenWithoutAStoredSlot() {
        val token=TaskReminderToken("one",TaskReminders.id("one"),1);val at=UTC.instant()!!.toEpochMilli()
        val event=ReminderEventRequest.create(ReminderRuntimeScope(token,UTC,TaskReminderMode.EXACT),2,at,ReminderCapabilityObservation(at,RuntimeFixture.AVAILABLE))
        val claim=ReminderClaimRequest.create(event,3,at,event.capability)
        val result=ReminderResultRequest.create(claim,4,at,ReminderPostObservation.POST_RETURNED)
        val other=ReminderEventRequest.create(event.scope,2,at+1,event.capability)
        val actions=listOf<()->Unit>(
            {event.copy(scope=event.scope.copy(token=TaskReminderToken("other",TaskReminders.id("other"),1)))},
            {event.copy(scope=event.scope.copy(token=token.copy(scheduleVersion=2)))},
            {event.copy(scope=event.scope.copy(mode=TaskReminderMode.APPROXIMATE))},
            {event.copy(scope=event.scope.copy(schedule=UTC.copy(date=UTC.date.plusDays(1))))},
            {event.copy(expectedVersion=3)}, {event.copy(observedAtMillis=at+1)},
            {event.copy(capability=event.capability.copy(checkedAtMillis=at+1))},
            {event.copy(capability=event.capability.copy(availability=RuntimeFixture.DENIED))},
            {claim.copy(event=other)}, {claim.copy(expectedVersion=4)}, {claim.copy(claimAtMillis=at+1)},
            {claim.copy(capability=claim.capability.copy(availability=RuntimeFixture.DENIED))},
            {result.copy(claim=ReminderClaimRequest.create(other,3,at,other.capability))},
            {result.copy(expectedVersion=5)}, {result.copy(observedAtMillis=at+1)},
            {result.copy(observation=ReminderPostObservation.CALL_FAILED,reason=ReminderResultReason.CALL_FAILED)}
        )
        actions.forEach {assertThrows(ReminderRuntimeConflict::class.java,it)}
        listOf(event,claim,result).forEach {assertFalse(it.toString().contains("one"))}
    }

    @Test fun actualMaximumWriterReaderWithFourSlotsAndThirtyTwoCommandsStillAllowsSqlCompletion(): Unit = LocalCandidateFixture().use { f ->
        val c=ManualTaskCipher(LocalCandidateFixture.KEY);val sample=maximum(c)
        try {
            val bytes=c.seal(sample.task,"physical-ack",sample.at,"RM_ACK",userReceipt=sample.user,runtime=sample.slots)
            assertEquals(6.toByte(),bytes[0]);assertTrue(bytes.size<65536)
            val decoded=c.open(sample.task.id,sample.task.version,"physical-ack",bytes)
            assertEquals(sample.task,decoded.task);assertEquals(sample.slots,decoded.runtime);assertEquals(4,decoded.runtime.size)
            assertThrows(UnsupportedOperationException::class.java){(decoded.runtime as MutableList).clear()}
            val again=c.seal(sample.task,"physical-ack",sample.at,"RM_ACK",userReceipt=sample.user,runtime=sample.slots)
            assertFalse(bytes.contentEquals(again));assertEquals(decoded.runtime,c.open(sample.task.id,100,"physical-ack",again).runtime)
            f.db.transaction{it.execute("INSERT INTO manual_tasks VALUES(?,?,?,?)",listOf(sample.task.id,100,"physical-ack",bytes))}
            val tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY)
            try {
                val done=tasks.change(ManualTaskMutation(sample.task.id,100,ManualTaskStatus.COMPLETED,sample.at+1,"完".repeat(128)))
                assertEquals(32,done.reminder!!.commands.size);assertTrue(done.reminder!!.commands.all{it.kind==TaskReminderCommandKind.CANCEL})
                assertEquals(4,tasks.runtimeView(done.id)!!.receipts.size);assertTrue(tasks.runtimeView(done.id)!!.receipts.all{it.retired!=null})
                val stored=f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?",done.id).single().single() as ByteArray
                assertTrue(stored.size<65536);assertEquals(6.toByte(),stored[0])
            } finally {tasks.wipeKey()}
        } finally {c.wipe()}
    }

    @Test fun authenticatedV6ReaderRejectsEofOversizeInvalidCountsReferencesAndMalformedUtf8() {
        val c=ManualTaskCipher(LocalCandidateFixture.KEY);val sample=maximum(c)
        try {
            val raw=c.seal(sample.task,"physical-ack",sample.at,"RM_ACK",userReceipt=sample.user,runtime=sample.slots)
            val portable=c.portable(sample.task.id,100,"physical-ack",raw,false)
            fun reject(bytes:ByteArray){assertThrows(Exception::class.java){c.portable(sample.task.id,100,"physical-ack",bytes,true)}}
            reject(portable+byteArrayOf(7))
            reject(portable.copyOf().also{it[5]=0xff.toByte()}) // First authenticated title byte, after header and i32 length.
            val ref=sample.slots.first().event.requestHash.toByteArray(Charsets.US_ASCII)
            val marker=java.nio.ByteBuffer.allocate(4).putInt(64).array()+ref
            val index=find(portable,marker);assertTrue(index>0)
            reject(portable.copyOf().also{it[index+4]=if(it[index+4]=='0'.code.toByte())'1'.code.toByte() else '0'.code.toByte()})
            val ledger=ledgerOffset(portable)
            reject(portable.copyOf().also{java.nio.ByteBuffer.wrap(it,ledger,4).putInt(5)})
            reject(portable.copyOf().also{java.nio.ByteBuffer.wrap(it,ledger+4,4).putInt(3073)})
            reject(portable.copyOf().also{it[ledger+8]=2})
            val slotSize=java.nio.ByteBuffer.wrap(portable,ledger+4,4).int
            val first=portable.copyOfRange(ledger+4,ledger+8+slotSize)
            reject(portable.copyOfRange(0,ledger)+java.nio.ByteBuffer.allocate(4).putInt(2).array()+first+first)
            for((id,v,m) in listOf(Triple("other",100L,"physical-ack"),Triple(sample.task.id,101L,"physical-ack"),Triple(sample.task.id,100L,"other")))
                assertThrows(Exception::class.java){c.open(id,v,m,raw)}
            assertThrows(IllegalArgumentException::class.java){c.open(sample.task.id,100,"physical-ack",ByteArray(65537).also{it[0]=6})}
            assertThrows(IllegalArgumentException::class.java){c.open(sample.task.id,100,"physical-ack",ByteArray(29).also{it[0]=7})}
        } finally {c.wipe()}
    }

    @Test fun crossKeyPortableRestoreClearsAllRuntimeAndUserExecutionReceipts() {
        val source=ManualTaskCipher(LocalCandidateFixture.KEY);val target=ManualTaskCipher(ByteArray(32){(it+12).toByte()})
        try {
            val sample=maximum(source);val raw=source.seal(sample.task,"physical-ack",sample.at,"RM_ACK",userReceipt=sample.user,runtime=sample.slots)
            val portable=source.portable(sample.task.id,100,"physical-ack",raw,false)
            val restored=target.portable(sample.task.id,100,"physical-ack",portable,true)
            val decoded=target.open(sample.task.id,100,"physical-ack",restored)
            assertTrue(decoded.runtime.isEmpty());assertNull(decoded.userReceipt)
            assertEquals(TaskReminderPhase.REVOKED,decoded.task.reminder!!.phase);assertTrue(decoded.task.reminder!!.commands.isEmpty())
            assertEquals(sample.task.sourceReceipt,decoded.task.sourceReceipt)
        } finally {source.wipe();target.wipe()}
    }

    @Test fun impossibleChildVersionsAndHistoricalCausesCannotBeAuthenticatedAsValidRuntimeState() {
        val c=ManualTaskCipher(LocalCandidateFixture.KEY)
        try {
            val sample=maximum(c);val slot=sample.slots.first()
            assertThrows(IllegalArgumentException::class.java){slot.copy(closed=ReminderRuntimeClosure(sample.at,ReminderClosureReason.OUTCOME_UNCONFIRMED))}
            val backwards=ReminderClaimRequest.create(slot.event,1,sample.at,slot.event.capability)
            assertThrows(IllegalArgumentException::class.java){slot.copy(claim=backwards,result=null,closed=null,retired=null)}
            val badResult=ReminderResultRequest.create(slot.claim!!,2,sample.at,ReminderPostObservation.POST_RETURNED)
            assertThrows(IllegalArgumentException::class.java){slot.copy(result=badResult)}
        } finally {c.wipe()}
    }

    private fun maximum(c:ManualTaskCipher):Maximum {
        val id="任".repeat(128);val rid=TaskReminders.id(id)
        val zone=ZoneId.getAvailableZoneIds().maxBy{it.length};val due=ManualTaskDateInput("2042-11-19","09:30",zone,ManualTaskOccurrence.FIRST).parse()!!
        val at=due.instant()!!.toEpochMilli()+3;val capability=ReminderCapabilityObservation(at,RuntimeFixture.AVAILABLE)
        val slots=(29L..32L).map{v->
            val scope=ReminderRuntimeScope(TaskReminderToken(id,rid,v),due,TaskReminderMode.EXACT)
            val e=ReminderEventRequest.create(scope,if(v==32L)97 else 2,at,capability,v.toString(16).padStart(32,'0'))
            if(v==32L)ReminderRuntimeReceipt(e,ReminderEventDecision.ACCEPTED) else {
                val claim=ReminderClaimRequest.create(e,3,at,capability,v.toString(16).padStart(32,'0'))
                val result=ReminderResultRequest.create(claim,4,at,ReminderPostObservation.POST_RETURNED,nonce=v.toString(16).padStart(32,'0'))
                ReminderRuntimeReceipt(e,ReminderEventDecision.ACCEPTED,claim,ReminderClaimDecision.GRANTED,result,
                    ReminderRuntimeClosure(at,ReminderClosureReason.OPERATION_FINISHED),ReminderRuntimeRetirement(at))
            }
        }
        val commands=(1L..31L).map{TaskReminderCommand(TaskReminderToken(id,rid,it),TaskReminderCommandKind.CANCEL,due,TaskReminderMode.EXACT)}+
            TaskReminderCommand(TaskReminderToken(id,rid,32),TaskReminderCommandKind.DELIVER,due,TaskReminderMode.EXACT)
        val receipt=SourceTaskReceipt(TaskSourceBinding(SourceVersion("源".repeat(128),1),"a".repeat(64),1,0,2),"b".repeat(64))
        val task=ManualTaskSnapshot(id,100,"🙂".repeat(240),ManualTaskStatus.OPEN,55,zone,at,due,receipt,
            TaskReminderSnapshot(rid,32,TaskReminderMode.EXACT,due,TaskReminderPhase.TRIGGER_ACCEPTED,commands))
        val rename=ManualTaskRename(id,96,task.title,55,"变".repeat(128))
        return Maximum(task,slots,c.receipt(task,"RENAME",rename.mutationId,96,55,c.requestProof(rename)),at)
    }
    private class Maximum(val task:ManualTaskSnapshot,val slots:List<ReminderRuntimeReceipt>,val user:ManualTaskCipher.UserReceipt,val at:Long)
    private fun ledgerOffset(portable:ByteArray):Int {
        val i=DataInputStream(ByteArrayInputStream(portable,1,portable.size-1))
        fun text(){val n=i.readInt();require(n>0);i.skipNBytes(n.toLong())}
        fun schedule(){if(i.readBoolean()){text();text();if(i.readBoolean()){text();i.readInt()}}}
        text();text();i.readLong();text();i.readLong();i.readLong();text();schedule()
        if(i.readBoolean()){text();i.readLong();text();i.readLong();i.readInt();i.readInt();text()}
        if(i.readBoolean()){text();i.readLong();text();schedule();text();repeat(i.readInt()){text();text();i.readLong();text();schedule();text()}}
        if(i.readBoolean())text()
        if(i.readBoolean()){text();text();text();i.readLong();i.readLong();text();text()}
        return portable.size-i.available()
    }
    private fun find(raw:ByteArray,marker:ByteArray):Int=raw.indices.firstOrNull{start->start+marker.size<=raw.size&&marker.indices.all{raw[start+it]==marker[it]}}?:-1
    private fun fixtures()=Properties().also{p->javaClass.getResourceAsStream("/reminder-runtime-fixtures.properties").use{p.load(requireNotNull(it))}}
    companion object {val UTC=ManualTaskDateInput("2042-11-19","09:30","UTC").parse()!!}
}
