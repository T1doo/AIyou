package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.designsystem.AIyouDimensions

/** No model placeholder answer, source excerpt, credential, SavedState or automatic record write. */
@Composable internal fun SavedQuestionsContent(u:SavedQuestionsUi,c:SavedQuestionsController,onUse:(()->Unit)?=null) {
    Dialog(onDismissRequest={c.close(u)},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),contentAlignment=Alignment.Center) {
        val availableHeight=maxHeight
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=availableHeight).verticalScroll(rememberScrollState())
                .testTag("saved-questions-scroll").padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("问题收藏",Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge)
                Text("只保存你写的问题，不含回答、出处选择或发送授权；不会自动成为原文、稳定事实或待办。")
                if(u.busy)Text("正在确认本机存储…",Modifier.testTag("saved-questions-busy"))
                u.error?.let { Text(it,Modifier.testTag("saved-questions-error"),color=MaterialTheme.colorScheme.error) }
                val editable=u.available&&!u.busy&&u.pending==null&&u.confirmation==null&&u.replacement==null
                if(u.showEmpty)Text("还没有已保存的问题。",Modifier.testTag("saved-questions-empty"))
                if(u.selectionStale)Text("请先重新选择当前问题；先前输入保留，不会自动另建问题。")
                for(item in u.items) {
                    val codePoints=checkNotNull(item.question).codePoints().limit(40).toArray()
                    TextButton(onClick={c.select(u,item)},enabled=editable,
                        modifier=Modifier.fillMaxWidth().heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-${item.id}")) {
                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(String(codePoints,0,codePoints.size),style=MaterialTheme.typography.titleMedium)
                            Text("修改于 "+java.time.Instant.ofEpochMilli(item.updatedAtMillis).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))+" · 编辑问题",
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                }
                Text(if(u.selected==null)"新问题" else "编辑已保存的问题",Modifier.semantics { heading() })
                OutlinedTextField(value=u.text,onValueChange={c.edit(u,it)},enabled=editable,
                    label={Text("问题正文")},modifier=Modifier.fillMaxWidth().testTag("saved-question-input"),minLines=2,maxLines=6,
                    isError=u.rejectedInput,supportingText={Text("最多512码点／2048字节。确认保存才写入；删除不提供撤销，已有备份不会被收回。")})
                if(u.pending!=null) {
                    Text("这次操作的结果尚未确认。关闭会保留当前输入和同一次重试意图，不能另建请求。")
                    TextButton(onClick={c.retry(u)},enabled=!u.busy,
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-retry")) {Text("确认同一次操作")}
                } else if(u.replacement!=null) {
                    Text("当前输入尚未保存。放弃并切换只会替换这份输入，不会删除已保存的问题。")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={c.cancelSwitch(u)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                            .testTag("saved-question-keep-input")) {Text("保留当前输入")}
                        Button(onClick={c.confirmSwitch(u)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                            .testTag("saved-question-confirm-switch")) {Text("放弃输入并切换")}
                    }
                } else if(u.confirmation!=null) {
                    Text(if(u.confirmation.question==null)"确认从当前问题历史删除？已有备份或曾发送到服务端的内容不会被收回。" else
                        "确认只把这个问题保存在本机？此操作不会发送内容，也不会保存模型回答。")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={c.confirm(u)},enabled=!u.busy,
                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-confirm")) {Text("确认")}
                        TextButton(onClick={c.cancelReview(u)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                            .testTag("saved-question-cancel-review")) {Text("取消")}
                    }
                } else FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={c.review(u)},enabled=editable&&!u.rejectedInput&&!u.selectionStale&&u.text.isNotBlank(),
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-save")) {Text("保存问题")}
                    TextButton(onClick={c.select(u,null)},enabled=editable,
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-new")) {Text("新问题")}
                    TextButton(onClick={c.review(u,delete=true)},enabled=editable&&!u.selectionStale&&u.selected!=null,
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-delete")) {Text("删除问题")}
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(onUse!=null)TextButton(onClick=onUse,enabled=editable&&!u.rejectedInput&&!u.selectionStale&&u.text.isNotBlank(),
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-use")) {Text("填入本次问题，不发送")}
                    TextButton(onClick={c.refresh(u)},enabled=!u.busy&&(u.pending==null||u.pendingConflict)&&u.confirmation==null&&u.replacement==null,
                        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("saved-question-refresh")) {Text("重新读取")}
                    TextButton(onClick={c.close(u)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                        .testTag("saved-question-close")) {Text("关闭")}
                }
            }
        }
        }
    }
}
