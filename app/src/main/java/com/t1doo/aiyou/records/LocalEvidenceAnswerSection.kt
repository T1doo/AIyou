package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.SourceCitationCard
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.model.OriginalTextReference

@Composable
internal fun LocalEvidenceAnswerSection(evidence: LocalEvidenceState, answer: LocalEvidenceAnswerState,
    available: Boolean, onAsk: () -> Unit, onCancel: () -> Unit, onOpen: (OriginalTextReference) -> Unit) {
    if (evidence.stage != LocalEvidenceStage.READY || evidence.selection.stage != LocalEvidenceSelectionStage.CONFIRMED) return
    Column(Modifier.testTag("local-answer")) {
        if (!available) {
            Text("回答功能尚未接入。可以继续核对所选出处并逐条打开原文。", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text("合成回答验证", style = MaterialTheme.typography.titleMedium)
        Text("以下仅验证本机回答与来源流程；不是模型事实核验，内容需结合原文核对。", style = MaterialTheme.typography.bodySmall)
        if (answer.stage == LocalEvidenceAnswerStage.RUNNING) {
            StatusNotice("正在处理所选出处", "尚未形成完整回答。", state = ComponentState.LOADING)
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-answer-stop")) { Text("停止回答") }
        } else {
            TextButton(onClick = onAsk, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-answer-ask")) { Text("基于选定出处回答") }
        }
        when (answer.stage) {
            LocalEvidenceAnswerStage.READY -> answer.paragraphs.forEachIndexed { index, paragraph ->
                Text(paragraph.text, modifier = Modifier.testTag("local-answer-paragraph-$index"))
                paragraph.references.forEach { reference ->
                    evidence.selection.cards.singleOrNull { it.reference == reference }?.let { card ->
                        SourceCitationCard(originalMetadata(card.hit.entry), reference.quote,
                            "原文版本 ${reference.source.revision} · Unicode 码点 [${reference.start}, ${reference.end})",
                            modifier = Modifier.testTag("local-answer-source-$index-${reference.source.sourceId}"), onOpen = { onOpen(reference) })
                    }
                }
            }
            LocalEvidenceAnswerStage.NO_EVIDENCE -> StatusNotice("选定出处不足以回答",
                "没有生成回答。可以调整问题或重新选择出处；这不能证明某件事从未发生。", state = ComponentState.EMPTY)
            LocalEvidenceAnswerStage.FAILED -> StatusNotice("回答未通过校验",
                "本次回答未显示。可以核对原文或主动重试，未保存不完整内容。", state = ComponentState.ERROR)
            LocalEvidenceAnswerStage.CANCELLED -> StatusNotice("回答已停止", "未完成内容不会显示，可主动重新发起。")
            LocalEvidenceAnswerStage.STALE -> StatusNotice("本次回答已失效", "来源或本次选择已变化，请重新选择并确认。", state = ComponentState.ERROR)
            else -> Unit
        }
    }
}
