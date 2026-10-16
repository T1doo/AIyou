package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
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
import com.t1doo.aiyou.model.ManualTasks

internal class ManualTaskTitleActions(val confirm: () -> Unit, val back: () -> Unit, val close: () -> Unit)
internal fun interface ManualTaskTitleObserver { fun onActions(actions: ManualTaskTitleActions) }
internal val LocalManualTaskTitleObserver = staticCompositionLocalOf<ManualTaskTitleObserver?> { null }

/** Private input and the exact confirmation live in the controller, never in an Android Bundle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualTaskTitleDialog(local: LocalRecordUiState, controller: ManualTaskController, canUse: () -> Boolean) {
    val tasks = local.tasks
    val editor = tasks.titleEditor ?: return
    val request = editor.confirmation
    val input = remember(editor.session) { TextFieldState(editor.input) }
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    val ready = local.ready && !local.opening && local.error == null
    val matches = tasks.items.singleOrNull { it.id == editor.task.id } == editor.task
    val available = ready && tasks.available && !tasks.loading && !tasks.busy && tasks.pending == null && matches
    val close = { if (canUse()) controller.closeTitle(editor.session) }
    val actions = ManualTaskTitleActions(
        { if (canUse() && request != null) controller.saveTitle(editor.session, request) },
        { if (canUse() && request != null) controller.cancelTitleConfirmation(editor.session, request) }, close)
    LocalManualTaskTitleObserver.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    DisposableEffect(controller, editor.session) { onDispose { controller.closeTitle(editor.session) } }
    val latestCanUse by rememberUpdatedState(canUse)
    LaunchedEffect(input) { snapshotFlow { input.text.toString() }.collect {
        if (latestCanUse()) controller.editTitle(editor.session, it)
    } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow)
                .widthIn(max = AIyouDimensions.contentMaxWidth).heightIn(max = height), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text(if (request == null) "修改待办标题" else "确认此待办的新标题", style = MaterialTheme.typography.titleLarge)
                    Text("当前标题", style = MaterialTheme.typography.labelLarge)
                    Text(editor.task.title, Modifier.testTag("manual-task-title-original"))
                    Text("${manualTaskDateLabel(editor.task)} · 待办版本 ${editor.task.version}", style = MaterialTheme.typography.bodySmall)
                    Text("只修改这条待办的标题。日期、完成状态和已有提醒计划保持原样，不从新标题推断时间。")
                    if (editor.task.sourceReceipt != null) Text("来源仍指向当时的原文摘录；新标题是人工修改，不代表原文字面内容。")
                    if (request == null) {
                        OutlinedTextField(state = input, enabled = available,
                            inputTransformation = InputTransformation.maxLength(ManualTasks.MAX_RAW_LENGTH),
                            label = { Text("新标题，最多240个字符") }, lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.fillMaxWidth().testTag("manual-task-title-input"))
                        Button(onClick = { if (canUse()) {
                            controller.editTitle(editor.session, input.text.toString()); controller.prepareTitle(editor.session)
                        } }, enabled = available, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                            .testTag("manual-task-title-prepare")) { Text("核对标题修改") }
                    } else {
                        Text("修改为", style = MaterialTheme.typography.labelLarge)
                        Text(request.title, Modifier.testTag("manual-task-title-new"))
                        Button(onClick = actions.confirm, enabled = available,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-title-confirm")) { Text("确认保存标题") }
                        TextButton(onClick = actions.back, enabled = !tasks.busy && tasks.pending == null,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-title-back")) { Text("返回修改") }
                    }
                    if (!ready) Text("本机记录暂不可读取，输入已保留，请先恢复本机读取。")
                    else if (tasks.available && !matches) Text("待办或提醒状态已变化，请关闭并重新读取后再选这条待办。输入不会覆盖新版本。")
                    if (tasks.busy) Text("正在保存并核对标题，尚未确认成功…")
                    tasks.notice?.let { Text(it) }
                    tasks.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (tasks.error != null) TextButton(onClick = { if (canUse()) controller.retry() },
                        enabled = ready && !tasks.busy && !tasks.loading,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-title-retry")) {
                        Text(if (tasks.conflict) "重新读取待办" else "重试确认")
                    }
                    TextButton(onClick = actions.close, enabled = !tasks.busy && tasks.pending == null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-title-close")) { Text("关闭，放弃未保存修改") }
                }
            }
        }
    }
}
