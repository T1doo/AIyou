package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.model.*

/** Observe the exact memory-only business actions, independently of a clickable node's lifetime. */
internal class CandidateDialogActions(val confirm: () -> Unit, val cancelConfirmation: () -> Unit)
internal fun interface CandidateDialogObserver { fun onActions(actions: CandidateDialogActions) }
internal val LocalCandidateDialogObserver = staticCompositionLocalOf<CandidateDialogObserver?> { null }

@Composable
internal fun LocalCandidateDialog(ui: LocalCandidateUiState, controller: LocalCandidateController,
    canUse: () -> Boolean, onLocate: (OriginalReadingLocation) -> Unit) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { windowHeight.toDp() * 0.9f }
    DisposableEffect(controller, ui.session) { onDispose { controller.close(ui.session) } }
    fun allowed() = canUse()
    val confirmation = ui.confirmation
    val actions = CandidateDialogActions(
        confirm = { if (allowed() && confirmation != null) controller.confirm(ui.session, confirmation) },
        cancelConfirmation = { if (allowed() && confirmation != null) controller.cancelConfirmation(ui.session, confirmation) })
    LocalCandidateDialogObserver.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    Dialog(onDismissRequest = { if (allowed()) controller.close(ui.session) },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow)
                .widthIn(max = AIyouDimensions.contentMaxWidth)
                .heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow)
                    .testTag("local-candidates-dialog"),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("本机摘录候选", style = MaterialTheme.typography.titleLarge)
                    Text("由本机规则截取原文。采纳仅保留这段原文说法，不建立稳定事实、标签或待办。", style = MaterialTheme.typography.bodySmall)
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("local-candidates-error")) }
                    ui.notice?.let { Text(it, modifier = Modifier.testTag("local-candidates-notice")) }
                    if (ui.loading) Text("正在读取已保存的候选与审阅结果。")
                    if (ui.busy) Text("正在确认本机存储回执。")
                    if (ui.computing) Text("正在本机生成摘录，原文保存可继续。")
                    if (confirmation != null) {
                        val batch = ui.batches.singleOrNull { it.id == confirmation.batchId && it.version == confirmation.version }
                        val item = batch?.items?.singleOrNull { it.id == confirmation.itemId }
                        if (batch != null && item != null) {
                            Text("确认采纳这段原文摘录？", style = MaterialTheme.typography.titleMedium)
                            Text(item.proposal.quote, modifier = Modifier.testTag("local-candidate-confirm-quote"))
                            Text("来源：原文版本 " + batch.source.revision + " · 本机规则摘录")
                            Button(enabled = !ui.busy && ui.pending == null,
                                onClick = actions.confirm,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-candidate-confirm")) {
                                Text("确认采纳摘录")
                            }
                            TextButton(enabled = !ui.busy,
                                onClick = actions.cancelConfirmation,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-candidate-cancel-confirm")) { Text("取消确认") }
                        }
                    } else {
                        if (!ui.loading && ui.error == null && ui.batches.isEmpty()) Text("还没有保存的本机摘录候选。")
                        ui.batches.forEach { batch ->
                            key(batch.id) {
                                when (batch.state) {
                                    LocalCandidateState.INVALIDATED -> Text("一批旧候选已因来源或处理资格变化失效，摘录内容已清除。")
                                    LocalCandidateState.CANCELLED -> Text("一批候选已取消。")
                                    LocalCandidateState.RUNNING -> Text("这批提取尚未完成，可明确取消后重新生成。")
                                    LocalCandidateState.READY -> batch.items.forEach { item ->
                                        key(batch.id, item.id) {
                                            val suffix = batch.id + "-" + item.id
                                            HorizontalDivider()
                                            Text(item.proposal.quote, modifier = Modifier.testTag("local-candidate-quote-" + suffix))
                                            Text("来源：原文版本 " + batch.source.revision + " · 本机规则摘录")
                                            Text(when (item.review) {
                                                ExcerptReview.PROPOSED -> "待确认摘录"
                                                ExcerptReview.ACCEPTED -> "已采纳摘录"
                                                ExcerptReview.REJECTED -> "已拒绝摘录"
                                            }, modifier = Modifier.testTag("local-candidate-review-" + suffix))
                                            TextButton(enabled = !ui.busy && ui.pending == null,
                                                onClick = {
                                                    if (allowed()) controller.sourceReference(ui.session, batch.id, batch.version, item.id)?.let { reference ->
                                                        controller.close(ui.session)
                                                        onLocate(OriginalReadingLocation(reference.source.sourceId, reference.source.revision.toInt(),
                                                            batch.sourceHash, reference.start, reference.end))
                                                    }
                                                }, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                                                    .testTag("local-candidate-source-" + suffix)) { Text("查看原文片段") }
                                            if (item.review == ExcerptReview.PROPOSED) {
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                                    TextButton(enabled = !ui.busy && !ui.computing && ui.pending == null,
                                                        onClick = { if (allowed()) controller.requestAccept(ui.session, batch.id, batch.version, item.id) },
                                                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                                                            .testTag("local-candidate-accept-" + suffix)) { Text("采纳摘录") }
                                                    TextButton(enabled = !ui.busy && !ui.computing && ui.pending == null,
                                                        onClick = { if (allowed()) controller.reject(ui.session, batch.id, batch.version, item.id) },
                                                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                                                            .testTag("local-candidate-reject-" + suffix)) { Text("拒绝摘录") }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (batch.state in setOf(LocalCandidateState.RUNNING, LocalCandidateState.READY) &&
                                    batch.items.all { it.review == ExcerptReview.PROPOSED }) {
                                    TextButton(enabled = !ui.busy,
                                        onClick = { if (allowed()) controller.cancelBatch(ui.session, batch.id, batch.version) },
                                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                                            .testTag("local-candidate-cancel-" + batch.id)) { Text("取消本批候选") }
                                }
                            }
                        }
                        if (!ui.loading && ui.error == null && ui.batches.none {
                            it.state == LocalCandidateState.RUNNING || it.state == LocalCandidateState.READY }) {
                            Button(enabled = !ui.busy && !ui.computing && ui.pending == null,
                                onClick = { if (allowed()) controller.generate(ui.session) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-candidates-generate")) { Text("生成本机摘录候选") }
                        }
                    }
                    if (ui.error != null) TextButton(enabled = !ui.busy && !ui.computing,
                        onClick = { if (allowed()) controller.retry(ui.session, ui.request) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-candidates-retry")) { Text("重试确认") }
                    TextButton(onClick = { if (allowed()) controller.close(ui.session) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-candidates-close")) { Text("关闭候选") }
                }
            }
        }
    }
}
