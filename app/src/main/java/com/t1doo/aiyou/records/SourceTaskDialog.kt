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

internal class SourceTaskDialogActions(val confirm:()->Unit,val cancel:()->Unit)
internal fun interface SourceTaskDialogObserver { fun actions(value:SourceTaskDialogActions) }
internal val LocalSourceTaskDialogObserver=staticCompositionLocalOf<SourceTaskDialogObserver?> { null }

@Composable
internal fun SourceTaskDialog(ui:SourceTaskUiState,controller:SourceTaskController,sourceAvailable:Boolean,canUse:()->Boolean) {
    val maxHeight=with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp()*0.9f }
    DisposableEffect(controller,ui.session) { onDispose { controller.close(ui.session) } }
    val confirmation=ui.confirmation.takeIf { sourceAvailable }
    val actions=SourceTaskDialogActions(
        { if(canUse()&&confirmation!=null) controller.confirm(ui.session,confirmation) },
        { if(canUse()&&confirmation!=null) controller.cancel(ui.session,confirmation) })
    LocalSourceTaskDialogObserver.current?.let { observer -> SideEffect { observer.actions(actions) } }
    val fields=remember(ui.session) { TaskDateFields(ui.date) }
    Dialog(onDismissRequest={controller.close(ui.session)},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow).widthIn(max=AIyouDimensions.contentMaxWidth)
                .heightIn(max=maxHeight),shape=MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow).testTag("source-task-dialog"),
                    verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("从原文字面摘录创建待办",style=MaterialTheme.typography.titleLarge)
                    Text("这不是语义 AI 建议。由你填写标题与日期，再单独确认；不推断时间，没有系统提醒。")
                    Text("来源：原文版本 ${ui.binding.source.revision} · 字符 ${ui.binding.start}–${ui.binding.end}")
                    if(sourceAvailable) ui.preview?.let { Text(it.quote,modifier=Modifier.testTag("source-task-quote")) }
                    else Text("来源暂不可核对，已隐藏摘录与创建确认；输入和待核对请求已保留。",modifier=Modifier.testTag("source-task-unavailable"))
                    if(ui.busy) Text("正在核对本机待办，尚未确认…")
                    ui.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("source-task-error")) }
                    ui.notice?.let { Text(it,modifier=Modifier.testTag("source-task-notice")) }
                    if(confirmation!=null) {
                        Text("确认创建这条正式应用内待办？",style=MaterialTheme.typography.titleMedium)
                        Text(confirmation.task.title,modifier=Modifier.testTag("source-task-confirm-title"))
                        Text(confirmation.task.schedule?.let { it.date.toString()+(it.time?.let { t -> " "+t } ?: " · 仅日期")+" · "+it.timeZone } ?: "未设日期和时刻")
                        Text("确认后是独立的人工待办；来源之后修改或删除不会撤回这条待办，来源跳转会重新核对。")
                        Button(enabled=!ui.busy,onClick=actions.confirm,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-task-confirm")) { Text("确认创建正式待办") }
                        TextButton(enabled=!ui.busy,onClick=actions.cancel,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-task-cancel-confirm")) { Text("返回修改") }
                    } else if(ui.notice==null&&ui.preview?.existing==null) {
                        val editable=sourceAvailable&&canUse()&&!ui.busy&&ui.pending==null
                        OutlinedTextField(value=ui.title,onValueChange={if(canUse()) controller.editTitle(ui.session,it)},enabled=editable,
                            label={Text("由你填写待办标题")},modifier=Modifier.fillMaxWidth().testTag("source-task-title"))
                        TaskDateFieldsView(fields,editable) { if(canUse()) controller.editDate(ui.session,it) }
                        TextButton(enabled=editable&&ui.preview!=null,onClick={if(canUse()) { controller.editDate(ui.session,fields.current());controller.prepare(ui.session) }},
                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-task-prepare")) { Text("预览并确认") }
                    }
                    if(ui.error!=null) TextButton(enabled=!ui.busy,onClick={if(canUse()) controller.retry(ui.session,ui.request)},
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-task-retry")) { Text("重新核对") }
                    if(ui.pending!=null||ui.busy) Text("关闭不会撤回已提交的创建。可从相同片段重新核对，已有待办不会重复创建。")
                    TextButton(onClick={controller.close(ui.session)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-task-close")) { Text("关闭") }
                }
            }
        }
    }
}
