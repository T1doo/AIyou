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

internal class SourceTagDialogActions(val confirm: () -> Unit, val cancel: () -> Unit)
internal fun interface SourceTagDialogObserver { fun onActions(actions: SourceTagDialogActions) }
internal val LocalSourceTagDialogObserver = staticCompositionLocalOf<SourceTagDialogObserver?> { null }

@Composable
internal fun SourceTagDialog(ui: SourceTagUiState, controller: SourceTagController, canUse: () -> Boolean,
    onLocate: (OriginalReadingLocation) -> Unit, onManage: () -> Unit) {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    DisposableEffect(controller, ui.session) { onDispose { controller.close(ui.session) } }
    val confirmation = ui.confirmation
    val actions = SourceTagDialogActions(
        { if (canUse() && confirmation != null) controller.confirm(ui.session, confirmation) },
        { if (canUse() && confirmation != null) controller.cancelConfirmation(ui.session, confirmation) })
    LocalSourceTagDialogObserver.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    Dialog(onDismissRequest = { if (canUse()) controller.close(ui.session) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow).widthIn(max = AIyouDimensions.contentMaxWidth)
                .heightIn(max = height), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow).testTag("source-tags-dialog"),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("从原文添加标签", style = MaterialTheme.typography.titleLarge)
                    Text("只读取“标签：#旅行 #生活”格式的独立行，不推断事实或偏好。确认后成为可独立编辑的记录标签。")
                    Text("预览关闭后不保留，需重新读取。原文变化不会自动删除已确认的标签。", style = MaterialTheme.typography.bodySmall)
                    if (ui.busy) Text("正在核对本机标签。")
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("source-tags-error")) }
                    ui.notice?.let { Text(it, modifier = Modifier.testTag("source-tags-notice")) }
                    if (confirmation != null) {
                        Text("将“" + confirmation.proposal.name + "”加入记录标签？")
                        Text(confirmation.proposal.reference.quote, modifier = Modifier.testTag("source-tag-confirm-quote"))
                        Text("来源：原文版本 " + ui.source.revision)
                        Button(enabled = !ui.busy && ui.pending == null, onClick = actions.confirm,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tag-confirm")) { Text("确认加入标签") }
                        TextButton(enabled = !ui.busy, onClick = actions.cancel,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tag-cancel-confirm")) { Text("取消确认") }
                    } else ui.preview?.let { p ->
                        if (p.proposals.isEmpty()) Text("没有符合格式的标签行。示例：标签：#旅行 #生活")
                        if (p.tags.names.size == ManualTags.MAX_COUNT) Text("记录已有12个标签，可先管理标签。")
                        p.proposals.forEachIndexed { index, item ->
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(item.reference.quote)
                            if (item.name in p.tags.names) Text("记录标签已存在", modifier = Modifier.testTag("source-tag-present-" + index))
                            else TextButton(enabled = !ui.busy && ui.pending == null && p.tags.names.size < ManualTags.MAX_COUNT &&
                                p.epoch < Long.MAX_VALUE && p.tags.version < Long.MAX_VALUE,
                                onClick = { if (canUse()) controller.requestConfirmation(ui.session, item) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tag-add-" + index)) { Text("加入记录标签") }
                            TextButton(enabled = !ui.busy && ui.pending == null, onClick = {
                                if (canUse()) controller.reference(ui.session, item)?.let { ref ->
                                    controller.close(ui.session)
                                    onLocate(OriginalReadingLocation(ref.source.sourceId, ref.source.revision.toInt(), ui.sourceHash, ref.start, ref.end))
                                }
                            }, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tag-source-" + index)) { Text("查看原文记号") }
                        }
                    }
                    if (ui.error != null) TextButton(enabled = !ui.busy, onClick = { if (canUse()) controller.retry(ui.session, ui.request) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tags-retry")) { Text("重新核对") }
                    TextButton(enabled = !ui.busy && ui.pending == null, onClick = { if (canUse()) { controller.close(ui.session); onManage() } },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tags-manage")) { Text("管理记录标签") }
                    TextButton(onClick = { if (canUse()) controller.close(ui.session) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("source-tags-close")) { Text("关闭预览") }
                }
            }
        }
    }
}
