package com.t1doo.aiyou.records
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.model.AudioTranscriptSource
@Composable internal fun AudioTranscriptDialog(u:AudioTranscriptUi,c:AudioTranscriptController,canUse:()->Boolean,onLocate:(AudioTranscriptSource)->Unit){
    DisposableEffect(c,u.session){onDispose{c.close(u.session)}}
    val editable=!u.busy&&u.pending==null
    Dialog(onDismissRequest={if(canUse())c.close(u.session)},properties=DialogProperties(usePlatformDefaultWidth=false)){
        CompositionLocalProvider(LocalSaveableStateRegistry provides null){Surface(Modifier.fillMaxWidth().padding(16.dp).fillMaxHeight(0.9f),shape=MaterialTheme.shapes.large){Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("transcript-dialog"),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("人工转写 · 非自动识别",style=MaterialTheme.typography.titleLarge)
            Text("由你填写和核对。文字与原音、原文分开保存，不联网，不自动生成记忆或待办。")
            if(u.busy)Text("正在核对精确原音与本机文字。")
            u.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("transcript-error"))};u.notice?.let{Text(it)}
            if(u.selected!=null){
                u.anchor?.let{Text("绑定原文版本 ${it.intent.revision} · 整段原音")}
                u.snapshot?.let{Text(if(it.withdrawn)"已撤回，不参与查找。历史仍加密保留；重新填写会新建内容版本。"else "人工内容版本 ${it.current.revision}",Modifier.testTag("transcript-state"))}
                val withdrawal=u.withdrawal
                if(withdrawal!=null){Text("确认撤回这份人工转写？原音不变，撤回后不参与关键词查找。")
                    TextButton({if(canUse())c.withdraw(u.session,withdrawal)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("transcript-withdraw-confirm")){Text("确认撤回")}
                    TextButton({if(canUse())c.cancelWithdraw(u.session,withdrawal)},modifier=Modifier.heightIn(min=48.dp).testTag("transcript-withdraw-cancel")){Text("取消，保持原样")}
                }else{
                    OutlinedTextField(u.text,{if(canUse())c.edit(u.session,it)},enabled=editable&&u.anchor!=null,label={Text("人工文字（最多1000字）")},modifier=Modifier.fillMaxWidth().testTag("transcript-text"))
                    TextButton({if(canUse())c.save(u.session)},enabled=editable&&u.anchor!=null&&(u.snapshot?.history?.size?:0)<8,modifier=Modifier.heightIn(min=48.dp).testTag("transcript-save")){Text("保存人工转写")}
                    if(u.snapshot!=null&&!u.snapshot.withdrawn)TextButton({if(canUse())c.withdraw(u.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("transcript-withdraw")){Text("撤回人工转写")}
                    Text("纠正保留版本，最多8版。未保存输入只在本次页面中；关闭或进程结束不保留。关闭不会撤销已经开始的保存，请重开核对。",style=MaterialTheme.typography.bodySmall)
                }
            }else{
                Text("按当前人工文字逐字包含查找，区分大小写；不是语义检索。留空浏览有效转写。")
                OutlinedTextField(u.keyword,{if(canUse())c.keyword(u.session,it)},label={Text("关键词（最多64字）")},modifier=Modifier.fillMaxWidth().testTag("transcript-keyword"))
                TextButton({if(canUse())c.search(u.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("transcript-search")){Text("查找人工转写")}
                if(u.page!=null)Text("当前匹配页 ${u.items.size} 项",Modifier.testTag("transcript-count"))
                u.items.forEach{item->Text(item.current.text);Text("人工文字 · 原文版本 ${item.anchor.intent.revision}");TextButton({if(canUse())c.locate(u.session,item,onLocate)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("transcript-source:"+item.id)){Text("查回关联原音")}}
                TextButton({if(canUse())c.next(u.session,true)},enabled=editable&&u.canPrevious,modifier=Modifier.heightIn(min=48.dp)){Text("上一页")}
                TextButton({if(canUse())c.next(u.session)},enabled=editable&&u.page?.next!=null,modifier=Modifier.heightIn(min=48.dp)){Text("下一页")}
            }
            if(u.pending!=null)TextButton({if(canUse())c.retry(u.session)},enabled=!u.busy,modifier=Modifier.heightIn(min=48.dp).testTag("transcript-retry")){Text("核对同次保存")}
            TextButton({if(canUse())c.refresh(u.session)},enabled=!u.busy,modifier=Modifier.heightIn(min=48.dp)){Text("仅刷新实际状态，不重复保存")}
            TextButton({if(canUse())c.close(u.session)},modifier=Modifier.heightIn(min=48.dp).testTag("transcript-close")){Text("关闭人工转写")}
        }}}
    }
}
