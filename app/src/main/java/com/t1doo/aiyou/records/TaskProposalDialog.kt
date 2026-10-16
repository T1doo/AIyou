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
import com.t1doo.aiyou.model.TaskProposalState

internal class TaskProposalActions(val confirm:()->Unit,val cancel:()->Unit)
internal fun interface TaskProposalObserver {fun actions(value:TaskProposalActions)}
internal val LocalTaskProposalObserver=staticCompositionLocalOf<TaskProposalObserver?> {null}

@Composable
internal fun TaskProposalDialog(local:LocalRecordUiState,controller:TaskProposalController,canUse:()->Boolean) {
    val e=local.taskProposals.editor ?: return
    DisposableEffect(controller,e.session) {onDispose {controller.close(e.session)}}
    val available=local.canUseProposalSource(e)
    val saveState=local.proposalSaveState(e)
    val confirmation=e.confirmation.takeIf {available&&!e.closing}
    val actions=TaskProposalActions({if(canUse()&&confirmation!=null) controller.confirm(e.session,confirmation)},
        {if(canUse()&&confirmation!=null) controller.cancel(e.session,confirmation)})
    LocalTaskProposalObserver.current?.let {observer -> SideEffect {observer.actions(actions)}}
    val maxHeight=with(LocalDensity.current) {LocalWindowInfo.current.containerSize.height.toDp()*0.9f}
    val fields=remember(e.session) {TaskDateFields(e.date)}
    val editable=available&&canUse()&&!e.closing&&e.confirmation==null&&e.notice==null&&!e.conflict&&
        (e.pending==null||e.busy&&e.pending is ProposalPending.Write&&e.pending.request.action==com.t1doo.aiyou.domain.TaskProposalAction.SAVE)
    Dialog(onDismissRequest={controller.close(e.session)},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow).widthIn(max=AIyouDimensions.contentMaxWidth)
                .heightIn(max=maxHeight),shape=MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow).testTag("task-proposal-dialog"),
                    verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("人工待确认草稿",style=MaterialTheme.typography.titleLarge)
                    Text("由你从字面摘录发起，不是语义 AI 建议。输入自动加密保存；另行确认才创建正式待办，不推断时间，没有系统提醒。")
                    Text("来源：原文版本 ${e.binding.source.revision} · 字符 ${e.binding.start}–${e.binding.end}")
                    if(available) local.entries.singleOrNull {it.id==e.binding.source.sourceId}?.originalText?.let {text ->
                        if(text.codePointCount(0,text.length)>=e.binding.end) Text(text.substring(text.offsetByCodePoints(0,e.binding.start),text.offsetByCodePoints(0,e.binding.end)),
                            modifier=Modifier.testTag("task-proposal-quote"))
                    } else Text(if(e.notice!=null) "草稿已处理；请从待办清单查看当前状态和来源。"
                        else "来源或草稿暂不可核对；未展示旧摘录，不能确认。",modifier=Modifier.testTag("task-proposal-source-conflict"))
                    when(saveState) {
                        TaskProposalSaveState.SAVING -> Text("正在保存或等待核对；尚不能保证最新输入可冷恢复。",modifier=Modifier.testTag("task-proposal-saving"))
                        TaskProposalSaveState.UNKNOWN -> Text("草稿读取状态待核对；当前输入仍保留，尚不能保证可从清单恢复。请重新读取。",modifier=Modifier.testTag("task-proposal-save-unknown"))
                        TaskProposalSaveState.MISSING -> Text("清单中已没有这条草稿；当前输入仍保留，不能保证可从草稿清单恢复。请核对清单和正式待办。",modifier=Modifier.testTag("task-proposal-save-missing"))
                        TaskProposalSaveState.CHANGED -> Text("清单中的草稿快照已变化；当前输入仍保留，不能保证恢复为当前内容。请关闭后重新打开清单中的版本。",modifier=Modifier.testTag("task-proposal-save-changed"))
                        TaskProposalSaveState.SAVED -> Text("草稿已加密保存在本机，可从待确认清单恢复。",modifier=Modifier.testTag("task-proposal-saved"))
                        TaskProposalSaveState.SAVED_SOURCE_UNAVAILABLE -> Text("草稿仍在本机清单，可从待确认清单恢复；来源已失效或待核对，不能确认。",modifier=Modifier.testTag("task-proposal-saved-source-unavailable"))
                        TaskProposalSaveState.PROCESSED -> Unit
                    }
                    e.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("task-proposal-error"))}
                    e.notice?.let {Text(it,modifier=Modifier.testTag("task-proposal-notice"))}
                    if(confirmation!=null) {
                        Text("确认创建正式应用内待办？",style=MaterialTheme.typography.titleMedium)
                        Text(confirmation.task.title,modifier=Modifier.testTag("task-proposal-confirm-title"))
                        Text(confirmation.task.schedule?.let {it.date.toString()+(it.time?.let {t -> " "+t} ?: " · 仅日期")+" · "+it.timeZone} ?: "未设日期和时刻")
                        Text("确认后待办独立存在；来源以后修改或删除不会撤回待办，来源跳转会重新核对。")
                        Button(enabled=!e.busy&&canUse(),onClick=actions.confirm,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-confirm")) {Text("确认创建正式待办")}
                        TextButton(onClick=actions.cancel,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-cancel-confirm")) {Text("返回修改")}
                    } else if(e.notice==null&&e.saved?.state!=TaskProposalState.REJECTED) {
                        OutlinedTextField(value=e.title,onValueChange={if(canUse()) controller.editTitle(e.session,it)},enabled=editable,
                            label={Text("由你填写待办标题")},modifier=Modifier.fillMaxWidth().testTag("task-proposal-title"))
                        TaskDateFieldsView(fields,editable) {if(canUse()) controller.editDate(e.session,it)}
                        TextButton(enabled=editable&&!e.busy&&e.pending==null&&e.savedEpoch==e.epoch&&e.saved!=null,
                            onClick={if(canUse()) {controller.editDate(e.session,fields.current());controller.prepare(e.session)}},
                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-prepare")) {Text("预览并确认")}
                        TextButton(enabled=local.ready&&!local.opening&&local.error==null&&canUse()&&!e.busy&&e.pending==null&&e.saved!=null&&e.savedEpoch==e.epoch,
                            onClick={if(canUse()) controller.reject(e.session)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-reject")) {Text("拒绝这条草稿")}
                    }
                    if(e.error!=null&&!e.conflict) TextButton(enabled=!e.busy&&canUse(),onClick={if(canUse()) controller.retry(e.session)},
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-retry")) {Text("重试同一请求")}
                    if(e.error!=null) Text("未确认保存的输入可能无法冷恢复；关闭后请从清单核对已保存草稿与正式待办。")
                    TextButton(enabled=!e.closing,onClick={controller.close(e.session)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-close")) {
                        Text(if(e.closing) "正在核对后关闭…" else if(saveState==TaskProposalSaveState.SAVED||saveState==TaskProposalSaveState.SAVED_SOURCE_UNAVAILABLE)
                            "关闭并保留草稿" else "关闭并核对已保存内容")}
                }
            }
        }
    }
}
