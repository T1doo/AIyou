package com.t1doo.aiyou.model

/** User-authored draft, never a semantic AI suggestion or a confirmed task. */
enum class TaskProposalState { DRAFT, REJECTED, CONFIRMED }
data class TaskProposal(val id:String,val version:Long,val binding:TaskSourceBinding,
    val titleInput:String,val dateInput:ManualTaskDateInput,val createdAtMillis:Long,
    val creationTimeZone:String,val updatedAtMillis:Long,val state:TaskProposalState=TaskProposalState.DRAFT) {
    init {
        ManualTags.identifier(id);require(id.length<=128&&version>0&&createdAtMillis>=0&&updatedAtMillis>=createdAtMillis)
        ManualTasks.timeZone(creationTimeZone);require(titleInput.length<=ManualTasks.MAX_RAW_LENGTH)
        // An incomplete IME surrogate remains session input, never a lossy durable snapshot.
        require(listOf(titleInput,dateInput.date,dateInput.time,dateInput.timeZone).all {Charsets.UTF_8.newEncoder().canEncode(it)})
    }
    override fun toString()="TaskProposal(version=$version,state=$state,redacted)"
}
data class TaskProposalItem(val proposal:TaskProposal,val sourceAvailable:Boolean) {
    override fun toString()="TaskProposalItem(redacted)"
}
