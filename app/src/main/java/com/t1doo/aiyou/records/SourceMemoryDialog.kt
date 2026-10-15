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

internal class SourceMemoryDialogActions(val confirm:()->Unit,val cancel:()->Unit)
internal fun interface SourceMemoryDialogObserver { fun onActions(actions:SourceMemoryDialogActions) }
internal val LocalSourceMemoryDialogObserver=staticCompositionLocalOf<SourceMemoryDialogObserver?> { null }

internal fun SourceMemoryKind.label()=when(this) { SourceMemoryKind.EVENT -> "事件线索";SourceMemoryKind.OCCASION_EMOTION -> "当次情绪线索" }
internal fun SourceMemoryAssertion.label()=when(this) { SourceMemoryAssertion.SELF_REPORT -> "自述（人工分类）";SourceMemoryAssertion.CONTEXT -> "引用／假设／上下文";SourceMemoryAssertion.UNCERTAIN -> "含义未确认" }
internal fun SourceMemoryReview.label()=when(this) { SourceMemoryReview.PROPOSED -> "待审候选";SourceMemoryReview.ACCEPTED -> "人工已采纳线索";SourceMemoryReview.REJECTED -> "已拒绝";SourceMemoryReview.SUPERSEDED -> "已被纠正取代";SourceMemoryReview.REVOKED -> "已撤回采纳" }

@Composable
internal fun SourceMemoryDialog(ui:SourceMemoryUiState,controller:SourceMemoryController,canUse:()->Boolean,onLocate:(OriginalReadingLocation)->Unit) {
    val height=with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp()*0.9f }
    DisposableEffect(controller,ui.session) { onDispose { controller.close(ui.session) } }
    val confirmation=ui.confirmation
    val actions=SourceMemoryDialogActions({ if(canUse() && confirmation!=null) controller.confirm(ui.session,confirmation) },
        { if(canUse() && confirmation!=null) controller.cancelConfirmation(ui.session,confirmation) })
    LocalSourceMemoryDialogObserver.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    val editable=!ui.busy && ui.pending==null && confirmation==null
    Dialog(onDismissRequest={ if(canUse()) controller.close(ui.session) },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow).widthIn(max=AIyouDimensions.contentMaxWidth).heightIn(max=height),shape=MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow).testTag("source-memories-dialog"),verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("事件与当次情绪",style=MaterialTheme.typography.titleLarge)
                    Text("手选原文并人工分类，先存为待审候选。没有自动抽取、事实核验或心理诊断；当次情绪不成为稳定偏好或人格。")
                    Text("自述、上下文和含义未确认是你的分类；人工备注不等于引文已证实。这里的时间是记录时间，不擅自推断事件发生时间。",style=MaterialTheme.typography.bodySmall)
                    Text("关闭后未保存的输入与确认会清除；已保存的候选和审阅可重新打开查看。",style=MaterialTheme.typography.bodySmall)
                    if(ui.busy) Text("正在核对本机候选。")
                    ui.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("source-memories-error")) }
                    ui.notice?.let { Text(it,modifier=Modifier.testTag("source-memories-notice")) }
                    if(confirmation!=null) {
                        val intent=confirmation.intent
                        val action=(intent as? SourceMemoryIntent.Mutate)?.request?.action
                        Text(when(action) {
                            null -> "确认保存为待审候选？"
                            SourceMemoryAction.ACCEPT -> "确认采纳这条当次线索？"
                            SourceMemoryAction.REJECT -> "确认拒绝此候选？"
                            SourceMemoryAction.REVOKE -> "确认撤回采纳？"
                            SourceMemoryAction.REOPEN -> "确认重新待审？"
                            SourceMemoryAction.CORRECT -> "确认纠正并重新待审？"
                        },style=MaterialTheme.typography.titleMedium)
                        val content=when(intent) {
                            is SourceMemoryIntent.Create -> intent.request.content
                            is SourceMemoryIntent.Mutate -> intent.request.content ?: ui.memories.singleOrNull { it.id==intent.request.id }?.current?.content
                        }
                        content?.let {
                            Text(it.reference.quote,modifier=Modifier.testTag("source-memory-confirm-quote"))
                            Text(it.kind.label()+" · "+it.assertion.label())
                            if(it.note.isNotEmpty()) Text("人工备注："+it.note)
                        }
                        Text(if(action==SourceMemoryAction.CORRECT) "旧内容保留为已取代，新内容还需另行确认采纳。" else "仅保存你的候选或审阅结果，不证明事件发生，也不会创建任务。")
                        Button(enabled=!ui.busy,onClick=actions.confirm,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-confirm")) { Text("确认此操作") }
                        TextButton(enabled=!ui.busy,onClick=actions.cancel,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-cancel-confirm")) { Text("取消确认") }
                    } else {
                        Text(if(ui.editing==null) "新增待审候选" else "纠正内容，保存后重新待审",style=MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value=ui.quote,onValueChange={ if(canUse()) controller.edit(ui.session,it,ui.kind,ui.assertion,ui.note) },enabled=editable,
                            label={ Text("原文摘录（逐字、唯一匹配，最多320字符）") },modifier=Modifier.fillMaxWidth().testTag("source-memory-quote"))
                        SourceMemoryKind.entries.forEach { kind ->
                            TextButton(enabled=editable,onClick={ if(canUse()) controller.edit(ui.session,ui.quote,kind,ui.assertion,ui.note) },
                                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-kind-"+kind.name)) { Text((if(ui.kind==kind) "已选：" else "选择：")+kind.label()) }
                        }
                        SourceMemoryAssertion.entries.forEach { assertion ->
                            TextButton(enabled=editable,onClick={ if(canUse()) controller.edit(ui.session,ui.quote,ui.kind,assertion,ui.note) },
                                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-assertion-"+assertion.name)) { Text((if(ui.assertion==assertion) "已选：" else "选择：")+assertion.label()) }
                        }
                        OutlinedTextField(value=ui.note,onValueChange={ if(canUse()) controller.edit(ui.session,ui.quote,ui.kind,ui.assertion,it) },enabled=editable,
                            label={ Text("人工备注（可空，最多200字符）") },modifier=Modifier.fillMaxWidth().testTag("source-memory-note"))
                        Button(enabled=editable && ui.preview!=null,onClick={ if(canUse()) controller.prepareSave(ui.session) },
                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-save")) { Text(if(ui.editing==null) "保存待审候选" else "保存纠正，重新待审") }
                        if(ui.editing!=null) TextButton(enabled=editable,onClick={ if(canUse()) controller.cancelCorrection(ui.session) },
                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-cancel-edit")) { Text("取消本次纠正") }
                        if(ui.preview!=null && ui.memories.isEmpty()) Text("当前原文尚无已保存候选；旧版本或撤销处理后的候选不能继续使用。")
                        ui.memories.forEachIndexed { index,item ->
                            HorizontalDivider()
                            val v=item.current
                            if(item.state==SourceMemoryState.INVALIDATED || v==null) Text("来源或处理范围已变化，此候选已失效。",modifier=Modifier.testTag("source-memory-invalid-"+index))
                            else {
                                Text(v.review.label(),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("source-memory-review-"+index))
                                Text(v.content.reference.quote,modifier=Modifier.testTag("source-memory-stored-quote-"+index))
                                Text(v.content.kind.label()+" · "+v.content.assertion.label())
                                Text("原文版本 "+item.source.revision+" · 内容版本 "+v.revision)
                                Text("记录时间："+java.time.Instant.ofEpochMilli(checkNotNull(item.capturedAtMillis))
                                    .atZone(java.time.ZoneId.of(checkNotNull(item.captureTimeZone)))
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+" "+item.captureTimeZone)
                                if(v.content.note.isNotEmpty()) Text("人工备注："+v.content.note,modifier=Modifier.testTag("source-memory-stored-note-"+index))
                                val available=editable && ui.editing==null && item.version<Long.MAX_VALUE
                                val options=when(v.review) {
                                    SourceMemoryReview.PROPOSED -> listOf(SourceMemoryAction.ACCEPT to "采纳这条线索",SourceMemoryAction.REJECT to "拒绝候选")
                                    SourceMemoryReview.ACCEPTED -> listOf(SourceMemoryAction.REVOKE to "撤回采纳")
                                    SourceMemoryReview.REJECTED,SourceMemoryReview.REVOKED -> listOf(SourceMemoryAction.REOPEN to "重新待审")
                                    SourceMemoryReview.SUPERSEDED -> emptyList()
                                }
                                options.forEach { (action,label) -> TextButton(enabled=available,onClick={ if(canUse()) controller.prepareAction(ui.session,item.id,action) },
                                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-"+action.name+"-"+index)) { Text(label) } }
                                TextButton(enabled=available && item.versions.size<8,onClick={ if(canUse()) controller.beginCorrection(ui.session,item.id) },
                                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-correct-"+index)) { Text("纠正分类或备注") }
                                TextButton(enabled=editable,onClick={ if(canUse()) controller.reference(ui.session,item.id)?.let { ref ->
                                    controller.close(ui.session);onLocate(OriginalReadingLocation(ref.source.sourceId,ref.source.revision.toInt(),ui.sourceHash,ref.start,ref.end))
                                } },modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-source-"+index)) { Text("查看原文依据") }
                                if(item.versions.size>1) {
                                    TextButton(enabled=editable,onClick={ if(canUse()) controller.history(ui.session,item.id) },
                                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memory-history-"+index)) { Text("查看取代历史") }
                                    if(ui.historyId==item.id) item.versions.dropLast(1).forEach { old ->
                                        Text("内容版本 "+old.revision+" 已被纠正取代；原状态："+checkNotNull(old.priorReview).label())
                                        Text(old.content.reference.quote)
                                        Text(old.content.kind.label()+" · "+old.content.assertion.label())
                                        if(old.content.note.isNotEmpty()) Text("原人工备注："+old.content.note,modifier=Modifier.testTag("source-memory-old-note-"+old.revision))
                                    }
                                }
                            }
                        }
                    }
                    if(ui.error!=null) TextButton(enabled=!ui.busy,onClick={ if(canUse()) controller.retry(ui.session,ui.request) },
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memories-retry")) { Text("重新读取核对") }
                    TextButton(onClick={ if(canUse()) controller.close(ui.session) },modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("source-memories-close")) { Text("关闭") }
                }
            }
        }
    }
}
