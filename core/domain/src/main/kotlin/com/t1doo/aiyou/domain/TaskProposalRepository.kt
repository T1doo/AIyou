package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

enum class TaskProposalAction { SAVE, REJECT }
data class TaskProposalWrite(val id:String,val expectedVersion:Long,val binding:TaskSourceBinding,
    val titleInput:String,val dateInput:ManualTaskDateInput,val createdAtMillis:Long,val creationTimeZone:String,
    val atMillis:Long,val mutationId:String,val action:TaskProposalAction=TaskProposalAction.SAVE) {
    init {
        require(expectedVersion in 0 until Long.MAX_VALUE&&atMillis>=createdAtMillis)
        ManualTags.identifier(mutationId)
        TaskProposal(id,expectedVersion+1,binding,titleInput,dateInput,createdAtMillis,creationTimeZone,atMillis)
        require(action!=TaskProposalAction.REJECT||expectedVersion>0)
    }
    override fun toString()="TaskProposalWrite(redacted)"
}
class TaskProposalConfirm(val proposal:TaskProposal,val atMillis:Long,val mutationId:String) {
    val task=ManualTaskCreate(TaskProposals.taskId(proposal.id),proposal.titleInput,atMillis,
        proposal.creationTimeZone,mutationId,proposal.dateInput.parse())
    init {require(proposal.state==TaskProposalState.DRAFT&&atMillis>=proposal.createdAtMillis)}
    override fun toString()="TaskProposalConfirm(redacted)"
}
interface TaskProposalRepository {
    fun list():List<TaskProposalItem>
    fun write(request:TaskProposalWrite):TaskProposal
    fun confirm(request:TaskProposalConfirm):ManualTaskSnapshot
}
class TaskProposalConflict:IllegalStateException("Task proposal or source changed")
class TaskProposalStorageFailure:IllegalStateException("Encrypted task proposals unavailable")
class TaskProposalLimit:IllegalStateException("Task proposal limit reached")

object TaskProposals {
    const val PREFIX="TASK_PROPOSAL:"
    const val MAX_DRAFTS=256
    fun storageId(id:String)=PREFIX+id.also(ManualTags::identifier)
    fun taskId(id:String)="TASK_FROM_PROPOSAL:"+hash {writeUTF(id)}
    fun writeHash(r:TaskProposalWrite)=hash {
        writeUTF("AIyou proposal write v1");writeUTF(r.id);writeLong(r.expectedVersion);binding(r.binding)
        fields(r.titleInput,r.dateInput,r.createdAtMillis,r.creationTimeZone);writeLong(r.atMillis);writeUTF(r.mutationId);writeUTF(r.action.name)
    }
    fun receipt(r:TaskProposalConfirm)=SourceTaskReceipt(r.proposal.binding,hash {
        writeUTF("AIyou proposal confirmation v1");val p=r.proposal
        writeUTF(p.id);writeLong(p.version);binding(p.binding)
        fields(p.titleInput,p.dateInput,p.createdAtMillis,p.creationTimeZone);writeLong(p.updatedAtMillis)
        writeLong(r.atMillis);writeUTF(r.mutationId)
    })
    private fun DataOutputStream.binding(b:TaskSourceBinding) {
        writeUTF(b.source.sourceId);writeLong(b.source.revision);writeUTF(b.sourceHash);writeLong(b.generation);writeInt(b.start);writeInt(b.end)
    }
    private fun DataOutputStream.fields(title:String,date:ManualTaskDateInput,created:Long,zone:String) {
        writeUTF(title);writeUTF(date.date);writeUTF(date.time);writeUTF(date.timeZone);writeUTF(date.occurrence?.name.orEmpty());writeLong(created);writeUTF(zone)
    }
    private fun hash(write:DataOutputStream.()->Unit):String {
        val raw=ByteArrayOutputStream().also {DataOutputStream(it).use(write)}.toByteArray()
        return try {MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {"%02x".format(it)}} finally {raw.fill(0)}
    }
}
