package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.util.Collections

enum class ReminderEnvelopePurpose { ALARM, CLICK }
class ReminderEnvelope(val identity: String, bytes: ByteArray) {
    private val value=bytes.copyOf()
    init { require(identity.matches(Regex("[a-f0-9]{64}")) && value.size in 29..2048) }
    fun bytes()=value.copyOf()
    override fun toString()="ReminderEnvelope(redacted)"
}
interface ReminderEnvelopePort : AutoCloseable {
    fun identity(token:TaskReminderToken):String
    fun seal(token:TaskReminderToken,purpose:ReminderEnvelopePurpose):ReminderEnvelope
    fun open(envelope:ReminderEnvelope,purpose:ReminderEnvelopePurpose):TaskReminderToken
}
class ReminderCommandPage(val metaVersion:Long,val passId:String,commands:List<TaskReminderCommand>,
    val next:ReminderScanCursor?,val done:Boolean,val scannedRows:Int,val failedRows:Int,unownedRuntime:List<String> = emptyList()) {
    val commands:List<TaskReminderCommand> = Collections.unmodifiableList(commands.toList())
    val unownedRuntime:List<String> = Collections.unmodifiableList(unownedRuntime.toList())
    init { require(scannedRows in 0..16 && commands.size<=16 && failedRows in 0..scannedRows && unownedRuntime.size<=scannedRows);unownedRuntime.forEach(ManualTags::identifier) }
    override fun toString()="ReminderCommandPage(scannedRows=$scannedRows, done=$done)"
}
class ReminderRuntimeClosureUnconfirmed(private val quarantine:Any?=null):IllegalStateException("Reminder storage closure unconfirmed")
class ReminderMetaStorageFailure : IllegalStateException("Reminder runtime metadata unavailable")
interface ReminderRebuildRepository {
    fun read():ReminderRuntimeMeta
    fun request(request:ReminderRebuildRequest):ReminderRuntimeMeta
    fun requestDrain(expectedVersion:Long,mutationId:String):ReminderRuntimeMeta
    fun planPage(expectedVersion:Long,nowMillis:Long,limit:Int=16):ReminderRuntimeMeta
    fun commandPage(nowMillis:Long,rowLimit:Int=16,commandLimit:Int=16):ReminderCommandPage?
    fun checkpoint(page:ReminderCommandPage):ReminderRuntimeMeta
}
class ReminderRuntimeResources(val tasks:ReminderRuntimeRepository,val rebuild:ReminderRebuildRepository,val envelopes:ReminderEnvelopePort)
interface OwnedReminderRuntime : AwaitableRecordStorage { val resources:ReminderRuntimeResources }
