package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.model.OriginalTextReference

@Composable
internal fun LocalEvidenceQuestionSection(evidence: LocalEvidenceState, answer: LocalEvidenceAnswerState,
    available: Boolean, onReview: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit,
    onOpen: (OriginalTextReference) -> Unit) {
    if (evidence.stage != LocalEvidenceStage.READY || evidence.selection.stage != LocalEvidenceSelectionStage.CONFIRMED) return
    Column(Modifier.testTag("selected-question")) {
        Text("AI 问答", style = MaterialTheme.typography.titleMedium)
        if (!available) {
            Text("模型问答尚未接入。可以查看所选原文或使用本机摘录视图。", modifier = Modifier.testTag("selected-question-unavailable"))
            return@Column
        }
        Text("本机协议假服务 · 仅内存验证，不连接外部模型。", modifier = Modifier.testTag("selected-question-fixture"))
        val review = answer.questionReview
        if (answer.stage == LocalEvidenceAnswerStage.REVIEW && review != null) {
            Text("核对本次问题和范围", style = MaterialTheme.typography.titleSmall)
            Text(review.question, modifier = Modifier.testTag("selected-question-reviewed-question"))
            Text("${review.target.protocol} · ${review.target.modelId} · 配置版本 ${review.target.version}")
            Text("本次仅使用以下 ${review.sources.size} 段摘录。确认只用于本机假服务，不授权外发。")
            review.sources.forEachIndexed { index, source ->
                evidence.selection.cards.singleOrNull { it.reference == source.reference }?.let { card ->
                    Text(originalMetadata(card.hit.entry), style = MaterialTheme.typography.bodySmall)
                    Text(source.reference.quote, modifier = Modifier.testTag("selected-question-scope-$index"))
                }
            }
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("selected-question-confirm")) { Text("确认本次范围并提问") }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("selected-question-cancel-review")) { Text("取消本次提问") }
        } else if (answer.stage == LocalEvidenceAnswerStage.RUNNING) {
            StatusNotice("正在等待本机假服务", "完整响应及来源校验通过后才显示内容。", state = ComponentState.LOADING)
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("selected-question-stop")) { Text("停止提问") }
        } else {
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("selected-question-review")) { Text("核对问题和所选范围") }
        }
        when (answer.stage) {
            LocalEvidenceAnswerStage.READY -> {
                Text("生成内容 · 待核对。引用绑定有效不等于论断被原文支持；不会写入事实或任务。",
                    modifier = Modifier.testTag("selected-question-unverified"), style = MaterialTheme.typography.bodySmall)
                answer.paragraphs.forEachIndexed { index, paragraph ->
                    Text(paragraph.text, modifier = Modifier.testTag("selected-question-paragraph-$index"))
                    paragraph.references.forEach { reference ->
                        evidence.selection.cards.singleOrNull { it.reference == reference }?.let { card ->
                            SourceCitationCard(originalMetadata(card.hit.entry), reference.quote, "所选原文出处",
                                modifier = Modifier.testTag("selected-question-source-$index-${reference.source.sourceId}"),
                                onOpen = { onOpen(reference) })
                        }
                    }
                }
            }
            LocalEvidenceAnswerStage.NO_EVIDENCE -> StatusNotice("当前所选出处不足以形成回答",
                "可以调整关键词或选择；这不能证明某件事从未发生。", state = ComponentState.EMPTY)
            LocalEvidenceAnswerStage.FAILED -> StatusNotice("本次问答未通过校验",
                "未显示不完整或引用无效的内容。可查看原文，或重新核对范围后主动重试。", state = ComponentState.ERROR)
            LocalEvidenceAnswerStage.CANCELLED -> StatusNotice("本次提问已停止", "未完成内容不会显示。")
            LocalEvidenceAnswerStage.STALE -> StatusNotice("本次提问已失效", "问题、连接、选择或来源已变化，请重新核对范围。", state = ComponentState.ERROR)
            else -> Unit
        }
    }
}
