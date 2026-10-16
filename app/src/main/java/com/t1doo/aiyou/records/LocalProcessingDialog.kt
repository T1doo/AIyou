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
import com.t1doo.aiyou.domain.LocalProcessingChoice
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry

internal class LocalProcessingDialogActions(val confirm: () -> Unit, val cancel: () -> Unit)
internal fun interface LocalProcessingDialogObserver { fun onActions(actions: LocalProcessingDialogActions) }
internal val LocalProcessingDialogObservation = staticCompositionLocalOf<LocalProcessingDialogObserver?> { null }

@Composable
internal fun LocalProcessingDialog(ui: LocalProcessingUiState, entry: StoredEntry,
    controller: LocalProcessingController, canUse: () -> Boolean) {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    DisposableEffect(controller, ui.session) { onDispose { controller.close(ui.session) } }
    val confirmation = ui.confirmation
    val actions = LocalProcessingDialogActions(
        { if (canUse() && confirmation != null) controller.confirm(ui.session, confirmation) },
        { if (canUse() && confirmation != null) controller.cancel(ui.session, confirmation) })
    LocalProcessingDialogObservation.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    Dialog(onDismissRequest = { if (canUse()) controller.close(ui.session) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow).widthIn(max = AIyouDimensions.contentMaxWidth)
                .heightIn(max = height), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow)
                    .testTag("local-processing-dialog"), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("这条记录的处理范围", style = MaterialTheme.typography.titleLarge)
                    Text("原文版本 ${ui.source.revision} · ${entry.captureTimeZone} · ${java.time.Instant.ofEpochMilli(entry.capturedAtMillis)}")
                    val excerpt = entry.originalText.let { it.substring(0, it.offsetByCodePoints(0, minOf(160, it.codePointCount(0, it.length)))) }
                    Text(excerpt.ifBlank { "图片记录 · 无文字正文" }, modifier = Modifier.testTag("local-processing-source"))
                    Text("仅影响这条记录。原文、附件、收藏、手工标签及人工待办保留。切换会清除相关 AI 派生内容并使旧候选失效。")
                    Text("本操作不发送记录，也不会自动生成或恢复旧结论。已另存的文件和服务商数据无法由此撤回。")
                    if (ui.busy) Text("正在核对本机处理范围…")
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("local-processing-error")) }
                    ui.notice?.let { Text(it, modifier = Modifier.testTag("local-processing-notice")) }
                    if (confirmation != null) {
                        Text(if (confirmation.target == LocalProcessingChoice.AI_NONE) "确认不要据此推断？" else "确认仅在本机处理？",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (confirmation.target == LocalProcessingChoice.AI_NONE) "保存与人工浏览，不参与任何 AI；相关派生内容将清除。"
                            else "允许已安装的端侧处理，不能送到远端；需要你重新主动生成。")
                        Button(enabled = !ui.busy, onClick = actions.confirm,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-confirm")) { Text("确认修改这条记录") }
                        TextButton(enabled = !ui.busy, onClick = actions.cancel,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-cancel")) { Text("取消修改") }
                    } else ui.snapshot?.let { snapshot ->
                        Text("当前：" + when (snapshot.policy) {
                            ProcessingPolicy.AI_NONE -> "不要据此推断"
                            ProcessingPolicy.LOCAL_ONLY -> "仅本机"
                            ProcessingPolicy.REMOTE_ALLOWED -> "已有其他授权，请先撤销原授权后再核对"
                        }, modifier = Modifier.testTag("local-processing-current"))
                        if (snapshot.policy != ProcessingPolicy.REMOTE_ALLOWED) {
                            TextButton(enabled = !ui.busy && ui.pending == null && snapshot.policy != ProcessingPolicy.AI_NONE && snapshot.generation < Long.MAX_VALUE,
                                onClick = { if (canUse()) controller.prepare(ui.session, LocalProcessingChoice.AI_NONE) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-none")) { Text("不要据此推断") }
                            TextButton(enabled = !ui.busy && ui.pending == null && snapshot.policy != ProcessingPolicy.LOCAL_ONLY && snapshot.generation < Long.MAX_VALUE,
                                onClick = { if (canUse()) controller.prepare(ui.session, LocalProcessingChoice.LOCAL_ONLY) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-local")) { Text("仅本机") }
                        }
                    }
                    if (ui.error != null) TextButton(enabled = !ui.busy, onClick = { if (canUse()) controller.retry(ui.session, ui.request) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-retry")) { Text("重新核对处理范围") }
                    if (ui.pending != null || ui.busy) Text("关闭不会撤回已经提交的修改；可重新打开核对实际范围。")
                    TextButton(onClick = { if (canUse()) controller.close(ui.session) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-close")) { Text("关闭") }
                }
            }
        }
    }
}
