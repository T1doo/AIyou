package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.DerivedTextCandidate
import com.t1doo.aiyou.model.OriginalTextReference
import com.t1doo.aiyou.model.SourceVersion
import com.t1doo.aiyou.model.StoredTextAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same production state mapping rendered by RecordAiSection; real SQL timing is tested separately. */
class RecordAiContentLabelTest {
    @Test fun onlyTheStreamingPhaseCanClaimAnUnsavedPreviewWithoutAReceipt() {
        for (phase in RecordAiPhase.entries) {
            val state = RecordAiUiState(7, SourceVersion("synthetic-source", 1), phase,
                "synthetic status", preview = "合成预览")
            assertEquals(phase.name, if (phase == RecordAiPhase.RUNNING) "预览 · 尚未保存"
                else "预览 · 保存状态待确认", state.contentLabel)
        }
    }

    @Test fun anActualAnnotationIsLabelledAsACandidateRatherThanAnUnconfirmedFact() {
        val source = SourceVersion("synthetic-source", 1)
        val annotation = StoredTextAnnotation("synthetic-job", source, 1,
            DerivedTextCandidate("合成已保存整理", listOf(OriginalTextReference(source, 0, 4, "合成原文"))), 20)
        val state = RecordAiUiState(7, source, RecordAiPhase.SUCCEEDED, "synthetic saved status", annotation = annotation)
        assertEquals("AI 整理候选", state.contentLabel)
    }
}
