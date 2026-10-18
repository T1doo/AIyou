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
        val timeline = answer.excerptTimeline
        Text(if (timeline) "所选出处时间线" else "合成回答验证", style = MaterialTheme.typography.titleMedium)
        Text(if (timeline) "仅整理当前选择的原文摘录，按记录时间排列；不是事件发生时间，也不能判断是否回答了问题。AI 回答尚未接入。"
            else "以下仅验证本机回答与来源流程；不是模型事实核验，内容需结合原文核对。", style = MaterialTheme.typography.bodySmall)
        if (timeline && evidence.limited) Text("候选出处已达显示上限；这里不代表全部匹配记录。", style = MaterialTheme.typography.bodySmall)
        if (answer.stage == LocalEvidenceAnswerStage.RUNNING) {
            StatusNotice("正在处理所选出处", if (timeline) "正在核对所选摘录。" else "尚未形成完整回答。", state = ComponentState.LOADING)
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag(if (timeline) "local-timeline-stop" else "local-answer-stop")) { Text(if (timeline) "停止整理" else "停止回答") }
        } else {
            TextButton(onClick = onAsk, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag(if (timeline) "local-timeline-generate" else "local-answer-ask")) { Text(if (timeline) "生成所选出处时间线" else "基于选定出处回答") }
        }
        when (answer.stage) {
            LocalEvidenceAnswerStage.READY -> answer.paragraphs.forEachIndexed { index, paragraph ->
                if (!timeline) Text(paragraph.text, modifier = Modifier.testTag("local-answer-paragraph-$index"))
                paragraph.references.forEach { reference ->
                    evidence.selection.cards.singleOrNull { it.reference == reference }?.let { card ->
                        SourceCitationCard((if (timeline) "记录时间 · " else "") + originalMetadata(card.hit.entry), reference.quote,
                            if (timeline) "所选原文摘录"
                            else "原文版本 ${reference.source.revision} · Unicode 码点 [${reference.start}, ${reference.end})",
                            modifier = Modifier.testTag(if (timeline) "local-timeline-source-$index-${reference.source.sourceId}" else "local-answer-source-$index-${reference.source.sourceId}"), onOpen = { onOpen(reference) })
                    }
                }
            }
            LocalEvidenceAnswerStage.NO_EVIDENCE -> StatusNotice("选定出处不足以回答",
                "没有生成回答。可以调整问题或重新选择出处；这不能证明某件事从未发生。", state = ComponentState.EMPTY)
            LocalEvidenceAnswerStage.FAILED -> StatusNotice(if (timeline) "摘录未通过校验" else "回答未通过校验",
                "本次内容未显示。可以核对原文或主动重试，未保存不完整内容。", state = ComponentState.ERROR)
            LocalEvidenceAnswerStage.CANCELLED -> StatusNotice(if (timeline) "摘录整理已停止" else "回答已停止", "未完成内容不会显示，可主动重新发起。")
            LocalEvidenceAnswerStage.STALE -> StatusNotice(if (timeline) "本次摘录已失效" else "本次回答已失效", "来源或本次选择已变化，请重新选择并确认。", state = ComponentState.ERROR)
            else -> Unit
        }
    }
}
