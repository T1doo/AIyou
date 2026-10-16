package com.t1doo.aiyou.model

import java.util.Collections

/** Index into the selected originals; -1 is an explicitly unavailable source, never a target-owner lookup. */
data class RecordPackageTask(val task: ManualTaskSnapshot, val sourceRecord: Int = -1)
data class RecordPackageDraft(val proposal: TaskProposal, val sourceRecord: Int = -1)
class RecordPackageActivities(tasks: List<RecordPackageTask>, drafts: List<RecordPackageDraft>, sourceGenerations: List<Long> = emptyList()) {
    val tasks = Collections.unmodifiableList(tasks.toList())
    val drafts = Collections.unmodifiableList(drafts.toList())
    val sourceGenerations = Collections.unmodifiableList(sourceGenerations.toList())
    init {
        require(tasks.size <= MAX_ITEMS && drafts.size <= MAX_ITEMS)
        require(sourceGenerations.size <= 20 && sourceGenerations.all { it >= 0 })
        require(tasks.map { it.task.id }.distinct().size == tasks.size && drafts.map { it.proposal.id }.distinct().size == drafts.size)
        require(tasks.all { it.sourceRecord in -1..19 && (it.task.sourceReceipt != null || it.sourceRecord == -1) })
        require(drafts.all { it.sourceRecord in -1..19 && it.proposal.state == TaskProposalState.DRAFT })
    }
    val unavailableSources get() = tasks.count { it.task.sourceReceipt != null && it.sourceRecord < 0 } + drafts.count { it.sourceRecord < 0 }
    override fun toString() = "RecordPackageActivities(tasks=${tasks.size},drafts=${drafts.size},redacted)"
    companion object {
        const val MAX_ITEMS = 256
        const val UNAVAILABLE_PREFIX = "PACKAGE_UNAVAILABLE:"
    }
}
