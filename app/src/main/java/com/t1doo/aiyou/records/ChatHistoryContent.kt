package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.t1doo.aiyou.model.ChatMessageState

/** Reads only repository-authenticated history; no implicit resend or memory conversion. */
@Composable internal fun ChatHistoryContent(u:ChatHistoryUi,c:ChatHistoryController,
    canUseQuestion:Boolean=false,beforeDelete:()->Unit={}) {
    Dialog(onDismissRequest={c.close(u)}) {
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.widthIn(max=560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .testTag("chat-history-scroll").padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("本机问答历史",Modifier.semantics{heading()},style=MaterialTheme.typography.titleLarge)
                Text("保存你确认发送的问题与已接纳的回答，不自动成为原文、稳定事实或待办，也不会自动重发。出处失效时隐藏回答，问题仍保留。")
                if(u.busy)Text("正在核对本机历史…",Modifier.testTag("chat-history-busy"))
                u.error?.let{Text(it,Modifier.testTag("chat-history-error"),color=MaterialTheme.colorScheme.error)}
                if(u.showEmpty)Text("还没有本机问答历史。",Modifier.testTag("chat-history-empty"))
                val enabled=u.available&&!u.busy&&u.pending==null&&u.confirmation==null
                for(item in u.items) {
                    TextButton(onClick={c.select(u,item)},enabled=enabled,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("chat-history-item-${item.id}")) {
                        Text(checkNotNull(item.question)+" · "+when(item.state){
                            ChatMessageState.PENDING->"未接纳回答，不自动恢复发送"
                            ChatMessageState.ANSWERED->"已保存回答"
                            ChatMessageState.CANCELLED->"发送已取消"
                            ChatMessageState.INVALIDATED->"出处已变化，回答已退休"
                            ChatMessageState.DELETED->"已删除"
                        })
                    }
                }
                u.selected?.let {item->
                    Text("问题",Modifier.semantics{heading()})
                    SelectionContainer {Text(checkNotNull(item.question),Modifier.fillMaxWidth().testTag("chat-history-question"))}
                    val reply=u.view?.reply
                    if(reply!=null&&!u.stale) {
                        Text("当时的模型回答（不等同事实）",Modifier.semantics{heading()})
                        if(reply.noEvidence)Text("当时未获得有依据的回答。")
                        for((i,p) in reply.paragraphs.withIndex()) {
                            SelectionContainer {Text(p.text,Modifier.fillMaxWidth().testTag("chat-history-answer-$i"))}
                        }
                        for((i,citation) in checkNotNull(u.view).citations.withIndex()) {
                            val r=citation.reference
                            Text("记录时间："+java.time.Instant.ofEpochMilli(citation.capturedAtMillis).atZone(java.time.ZoneId.of(citation.captureTimeZone)).toString()+
                                "；来源版本 "+r.source.revision,Modifier.fillMaxWidth())
                            SelectionContainer {Text(r.quote,Modifier.fillMaxWidth())}
                            TextButton(onClick={c.openSource(u,r)},enabled=enabled&&reply.paragraphs.any{r in it.references},
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("chat-history-source-$i")){Text("查看此出处原文")}
                        }
                    } else Text("当前没有可复核的回答正文；不会用问题或本地检索结果冒充模型回答。")
                    if(canUseQuestion)TextButton(onClick={c.useQuestion(u)},enabled=enabled&&!u.stale&&u.view!=null,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("chat-history-use")){Text("填入当前问题，不发送")}
                    TextButton(onClick={c.reviewDelete(u)},enabled=enabled,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("chat-history-delete")){Text("删除这条本机历史")}
                }
                if(u.confirmation!=null) {
                    Text("确认删除问题与回答？不可撤销；已有备份和曾发送到服务端的内容不会被收回。")
                    Button(onClick={c.confirmDelete(u,beforeDelete)},enabled=!u.busy,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("chat-history-confirm-delete")){Text("确认删除")}
                    TextButton(onClick={c.cancelDelete(u)},modifier=Modifier.heightIn(min=48.dp).testTag("chat-history-cancel-delete")){Text("取消")}
                }
                if(u.pending!=null)TextButton(onClick={c.retryDelete(u)},enabled=!u.busy,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("chat-history-retry-delete")){Text("核对同一次删除")}
                TextButton(onClick={c.refresh(u)},enabled=!u.busy&&u.pending==null&&u.confirmation==null,
                    modifier=Modifier.heightIn(min=48.dp).testTag("chat-history-refresh")){Text("重新读取")}
                TextButton(onClick={c.close(u)},modifier=Modifier.heightIn(min=48.dp).testTag("chat-history-close")){Text("关闭")}
            }
        }
    }
}
