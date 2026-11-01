package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.model.OriginalTextReference
import com.t1doo.aiyou.model.SelectedQuestionSendReview
import com.t1doo.aiyou.model.ConnectionProtocol
import com.t1doo.aiyou.ai.ProtocolRequests
import com.t1doo.aiyou.ai.Protocol
import java.time.Instant
import java.time.ZoneId

/** Explicit send review is separate from local-selection confirmation. No saved state. */
@Composable internal fun SelectedQuestionConsentSection(evidence: LocalEvidenceState, state: QuestionConsentState,
    fixture: SelectedQuestionConsentFixture?, available: Boolean, onReview: () -> Unit,
    onConfirm: (SelectedQuestionSendReview) -> Unit, onStop: () -> Unit, onWithdraw: () -> Unit,
    onOpen: (OriginalTextReference) -> Unit, remote:ProductionQuestionBinding?=null,
    production:Boolean=false,unavailableNotice:String?=null) {
    require(fixture==null||remote==null)
    val rehearsal=fixture!=null
    if (evidence.stage != LocalEvidenceStage.READY || evidence.selection.stage != LocalEvidenceSelectionStage.CONFIRMED) return
    var showResult by remember(state.requestId) { mutableStateOf(false) }
    Column(Modifier.testTag("question-consent")) {
        Text("单次发送授权", style = MaterialTheme.typography.titleMedium)
        if (!available || fixture == null&&remote==null) {
            Text(unavailableNotice ?: if(production) "请先选择已检查对话能力的连接并确认出处；当前会话未就绪时不会创建请求。"
                else "单次模型发送尚未接入。原文仍可本机查看。", Modifier.testTag("question-consent-unavailable"))
            return@Column
        }
        if(rehearsal)Text("本机假传输演练 · 不连接外部模型，仅使用合成验证能力。", Modifier.testTag("question-consent-fixture"))
        else Text("仅发送所选出处和本次问题；模型回答待核对，不自动永久记忆。",Modifier.testTag("question-consent-production"))
        when (state.stage) {
            QuestionConsentStage.PREPARING -> StatusNotice("正在核对问题、所选范围与既有授权", if(rehearsal)"尚未建立本次授权，也未运行假传输。"else"尚未建立本次发送授权，也未发送内容。", state = ComponentState.LOADING)
            QuestionConsentStage.AUTHORIZING -> StatusNotice("正在记录本次授权", if(rehearsal)"确认持久化完成后才运行假传输。"else"确认持久化完成后才发送本次问题。", state = ComponentState.LOADING)
            QuestionConsentStage.RUNNING -> StatusNotice(if(rehearsal)"本次范围已授权，正在运行本机假传输"else"正在向所选服务提问", "完整响应与当前来源回执通过后才显示内容。", state = ComponentState.LOADING)
            QuestionConsentStage.READY, QuestionConsentStage.NO_EVIDENCE -> {
                Text(if(rehearsal)"演练完成 · 生成内容待核对，引用有效不等于论断得到支持。"else"模型回答 · 内容待核对，引用有效不等于论断得到支持。", Modifier.testTag("question-consent-completed"))
                TextButton({ showResult = true }, Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                    .testTag("question-consent-show-result")) { Text(if(rehearsal)"查看演练结果与出处"else"查看模型回答与出处") }
            }
            QuestionConsentStage.WITHDRAWING -> StatusNotice("正在撤回本次授权", "已隐藏本次结果，等待持久化确认。", state = ComponentState.LOADING)
            QuestionConsentStage.WITHDRAWN -> StatusNotice("本次授权已撤回", "未改变原记录的处理范围。", modifier = Modifier.testTag("question-consent-withdrawn"))
            QuestionConsentStage.WITHDRAW_FAILED -> StatusNotice("本次撤回尚未确认", if(rehearsal)"结果保持隐藏。可明确重试撤回，不会重新运行假传输。"else"结果保持隐藏。可明确重试撤回，不会重新发送问题。", state = ComponentState.ERROR)
            QuestionConsentStage.BLOCKED -> StatusNotice("本次范围无法核准", "来源、存储或当前服务的对话用途授权未通过核对。本机选取确认不会创建外发同意。", state = ComponentState.ERROR)
            QuestionConsentStage.FAILED -> StatusNotice(if(rehearsal)"本次演练未完成"else"本次问答未完成或结果待确认", "未显示未经当前来源与回执核验的内容，不自动重发，也不覆盖原文。", state = ComponentState.ERROR)
            QuestionConsentStage.STALE -> StatusNotice("本次审阅或结果已失效", "问题、范围、服务、账号或来源已变化，请重新核对。", state = ComponentState.ERROR)
            QuestionConsentStage.CANCELLED -> StatusNotice(if(rehearsal)"本次演练已停止"else"本次问答已停止", "停止只清理未完成请求，不修改原记录授权。")
            else -> Unit
        }
        if (state.stage in setOf(QuestionConsentStage.PREPARING, QuestionConsentStage.AUTHORIZING, QuestionConsentStage.RUNNING)) {
            TextButton(onStop, Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("question-consent-stop")) { Text(if(rehearsal)"停止本次演练"else"停止本次问答") }
        }
        if (state.stage in setOf(QuestionConsentStage.RUNNING, QuestionConsentStage.READY, QuestionConsentStage.NO_EVIDENCE, QuestionConsentStage.WITHDRAW_FAILED)) {
            TextButton(onWithdraw, Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("question-consent-withdraw")) {
                Text(if (state.stage == QuestionConsentStage.WITHDRAW_FAILED) "重试撤回本次授权" else "撤回本次授权")
            }
        }
        if (state.stage in setOf(QuestionConsentStage.WAITING, QuestionConsentStage.CANCELLED, QuestionConsentStage.WITHDRAWN,
                QuestionConsentStage.BLOCKED, QuestionConsentStage.FAILED, QuestionConsentStage.STALE,
                QuestionConsentStage.READY, QuestionConsentStage.NO_EVIDENCE)) {
            TextButton(onReview, Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("question-consent-review")) { Text(if(rehearsal)"核对单次授权演练"else"核对本次问题与发送范围") }
        }
    }
    val review = state.review
    if (state.stage == QuestionConsentStage.REVIEW && review != null && (fixture != null||remote!=null)) AlertDialog(
        onDismissRequest = onStop, modifier = Modifier.fillMaxWidth(), title = { Text(if(rehearsal)"仅本次授权演练 · 无外发"else"仅本次发送授权") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("question-consent-scope-scroll")) {
                Text("本机选取确认不是本次发送授权。", style = MaterialTheme.typography.titleSmall)
                Text(review.request.question, Modifier.testTag("question-consent-question"))
                Text("问题时刻：${Instant.ofEpochMilli(review.request.askedAtMillis).atZone(ZoneId.of(review.request.questionTimeZone))}")
                Text(if(rehearsal)"合成服务：${review.connection.providerId}"else"所选服务：${remote?.configuration?.details?.name}")
                Text("${review.connection.protocol} · ${review.connection.modelId} · 配置版本 ${review.connection.version}")
                val endpoint=fixture?.endpoint ?: ProtocolRequests.endpoint(review.connection.apiRoot,when(review.connection.protocol){
                    ConnectionProtocol.CHAT_COMPLETIONS->Protocol.CHAT_COMPLETIONS
                    ConnectionProtocol.ANTHROPIC_MESSAGES->Protocol.ANTHROPIC_MESSAGES
                }).toString()
                Text("操作地址：$endpoint", Modifier.testTag("question-consent-destination"))
                Text(if(rehearsal)"用途：CONVERSATION。此演练不读取密钥，也不验证真实凭据。"else"用途：CONVERSATION。密钥由当前资料会话的加密密钥库注入，不展示密钥内容。")
                Text("这些记录已有对当前${if(rehearsal)"合成"else"所选"}服务的原文版本授权。本次另限以下 ${review.request.sources.size} 段摘录及问题的一次使用，不扩大或修改原授权。")
                review.request.sources.forEachIndexed { index, source ->
                    evidence.selection.cards.singleOrNull { it.reference == source.reference }?.let { card ->
                        Text(originalMetadata(card.hit.entry), style = MaterialTheme.typography.bodySmall)
                        Text("版本 ${source.reference.source.revision} · 码点 [${source.reference.start}, ${source.reference.end})")
                        Text(source.reference.quote, Modifier.fillMaxWidth().testTag("question-consent-quote-$index"))
                    }
                }
                Text(if(rehearsal)"仅内存假传输，不发送给外部服务。真实发送接入后，撤回不能追回服务商已收到的数据或费用。不会保存问题或回答为事实、任务或答案缓存。"
                    else"本次请求可能产生服务商费用；撤回不能追回服务商已收到的数据或费用。不会自动将问题或回答保存为事实、任务或答案缓存。")
            }
        },
        confirmButton = { TextButton({ onConfirm(review) }, Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
            .testTag("question-consent-confirm")) { Text(if(rehearsal)"仅本次授权并运行假传输"else"仅本次授权并发送") } },
        dismissButton = { TextButton(onStop, Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("question-consent-cancel-review")) { Text("取消审阅") } })
    val reply = state.receipt?.reply
    if (showResult && reply != null && state.stage in setOf(QuestionConsentStage.READY, QuestionConsentStage.NO_EVIDENCE)) AlertDialog(
        onDismissRequest = { showResult = false }, modifier = Modifier.fillMaxWidth(), title = { Text(if(rehearsal)"本机演练结果 · 待核对"else"模型回答 · 待核对") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("question-consent-result-scroll")) {
            if (reply.noEvidence) Text("当前所选出处不足以形成回答，不能据此断言某件事从未发生。", Modifier.testTag("question-consent-no-evidence"))
            reply.paragraphs.forEachIndexed { index, paragraph ->
                Text(paragraph.text, Modifier.testTag("question-consent-paragraph-$index"))
                paragraph.references.forEach { reference ->
                    evidence.selection.cards.singleOrNull { it.reference == reference }?.let { card ->
                        SourceCitationCard(originalMetadata(card.hit.entry), reference.quote, "原始出处 · 版本 ${reference.source.revision}",
                            modifier = Modifier.testTag("question-consent-source-$index-${reference.source.sourceId}"),
                            onOpen = { showResult = false; onOpen(reference) })
                    }
                }
            }
        } },
        confirmButton = { TextButton({ showResult = false }, Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("question-consent-close-result")) { Text("关闭结果") } },
        dismissButton = { TextButton(onWithdraw, Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("question-consent-result-withdraw")) { Text("撤回本次授权") } })
}
