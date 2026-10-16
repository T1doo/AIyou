package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class TaskProposalsTest {
    private val binding=TaskSourceBinding(SourceVersion("source",1),"a".repeat(64),3,0,5)
    @Test fun unfinishedDraftAndExplicitScheduleAreSeparateFromFormalTaskValidation() {
        val draft=TaskProposal("one",1,binding,"",ManualTaskDateInput("2026-"),5,"UTC",5)
        assertThrows(IllegalArgumentException::class.java) {TaskProposalConfirm(draft,6,"confirm")}
        val ready=draft.copy(titleInput="合成明天三点只是引用",dateInput=ManualTaskDateInput())
        assertNull(TaskProposalConfirm(ready,6,"confirm").task.schedule)
        assertThrows(IllegalArgumentException::class.java) {draft.copy(titleInput="\uD800")}
        assertFalse(draft.toString().contains("source"))
    }
    @Test fun independentIntentIdentityAndExactReceiptBindProposalVersionInputAndOperation() {
        val p=TaskProposal("one",1,binding,"合成人工标题",ManualTaskDateInput(),5,"UTC",5)
        fun receipt(q:TaskProposal=p,at:Long=6,mutation:String="confirm")=TaskProposals.receipt(TaskProposalConfirm(q,at,mutation))
        assertEquals(receipt(),receipt())
        for(q in listOf(p.copy(id="two"),p.copy(version=2),p.copy(titleInput="合成另一人工意图"),
            p.copy(dateInput=ManualTaskDateInput("2026-10-20")),p.copy(binding=binding.copy(generation=4)),p.copy(updatedAtMillis=6)))
            assertNotEquals(receipt(),receipt(q))
        assertNotEquals(receipt(),receipt(at=7));assertNotEquals(receipt(),receipt(mutation="other"))
        assertNotEquals(TaskProposals.taskId("one"),TaskProposals.taskId("two"))
    }
}
