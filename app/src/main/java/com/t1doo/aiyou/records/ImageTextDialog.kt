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
import com.t1doo.aiyou.model.ImageTextSource

@Composable internal fun ImageTextDialog(u:ImageTextUi,c:ImageTextController,canUse:()->Boolean,onLocate:(ImageTextSource)->Unit){
    DisposableEffect(c,u.session){onDispose{c.close(u.session)}}
    val editable=!u.busy&&u.pending==null
    Dialog(onDismissRequest={if(canUse())c.close(u.session)},properties=DialogProperties(usePlatformDefaultWidth=false)){
        CompositionLocalProvider(LocalSaveableStateRegistry provides null){
            Surface(Modifier.fillMaxWidth().padding(16.dp).fillMaxHeight(0.9f),shape=MaterialTheme.shapes.large){
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("image-text-dialog"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("图片文字 · 本地提取与纠正",style=MaterialTheme.typography.titleLarge)
                    Text("只处理你主动选择的已保存原图；提取文字与原图、正文分开保存，不自动推断人物、偏好或待办。")
                    if(u.busy)Text(when(u.phase){"QUEUED"->"等待核对原图后开始提取。";"PROCESSING"->"正在提取此张图片文字。";"CANCELLING"->"等待提取资源实际结束；迟到文字不会保存。";"COMMITTING"->"正在保存并核对本机结果。";else->"正在核对此原图与派生文字。"})
                    u.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("image-text-error"))}
                    u.notice?.let{Text(it,Modifier.testTag("image-text-notice"))}
                    if(u.selected!=null){
                        u.anchor?.let{Text("绑定原文版本 ${it.intent.revision} · 整张原图；不提供文字框定位。")}
                        val snapshot=u.snapshot;val deletion=u.deletion
                        if(snapshot==null){
                            Text("尚无已保存的提取文字。需要本机具备可用的中文 OCR 能力。")
                            TextButton({if(canUse())c.extract(u.session)},enabled=editable&&u.anchor!=null,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-extract")){Text("主动提取此图文字")}
                            if(u.phase in setOf("QUEUED","PROCESSING","CANCELLING"))TextButton({if(canUse())c.cancelExtraction(u.session,u.request)},modifier=Modifier.heightIn(min=48.dp).testTag("image-text-cancel")){Text("取消本次提取")}
                        }else{
                            Text(if(snapshot.engine.synthetic)"合成端口测试结果 · 非真实 OCR" else "本地 OCR 结果 · 需要你核对",Modifier.testTag("image-text-origin"))
                            Text("引擎 ${snapshot.engine.id} · ${snapshot.engine.version}；当前内容版本 ${snapshot.current.revision}"+if(snapshot.current.humanCorrection)" · 人工纠正"else " · 初次提取")
                            if(deletion!=null){
                                Text("删除此派生文字及全部纠正历史？原图与正文保留；已另存的外部备份不会自动改变。")
                                TextButton({if(canUse())c.delete(u.session,deletion)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-delete-confirm")){Text("确认删除文字")}
                                TextButton({if(canUse())c.cancelDelete(u.session,deletion)},modifier=Modifier.heightIn(min=48.dp).testTag("image-text-delete-cancel")){Text("取消，保持原样")}
                            }else{
                                OutlinedTextField(u.text,{if(canUse())c.edit(u.session,it)},enabled=editable,label={Text("纠正图片文字（最多1000字）")},modifier=Modifier.fillMaxWidth().testTag("image-text-value"))
                                TextButton({if(canUse())c.save(u.session)},enabled=editable&&snapshot.history.size<8,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-save")){Text("保存人工纠正")}
                                TextButton({if(canUse())c.delete(u.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-delete")){Text("删除派生文字与历史")}
                            }
                        }
                        Text("每张图片最多1000字、8个内容版本；超限不截断保存。来源版本、处理授权变化或删除后，派生文字与历史会失效并清除。未保存输入只保留在本次页面，关闭不会撤销已开始的保存。",style=MaterialTheme.typography.bodySmall)
                    }else{
                        Text("按当前有效图片文字逐字包含查找，区分大小写；不是语义检索。留空浏览已保存的有效结果。")
                        OutlinedTextField(u.keyword,{if(canUse())c.keyword(u.session,it)},label={Text("关键词（最多64字）")},modifier=Modifier.fillMaxWidth().testTag("image-text-keyword"))
                        TextButton({if(canUse())c.search(u.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-search")){Text("查找图片文字")}
                        if(u.page!=null)Text("当前匹配页 ${u.items.size} 项",Modifier.testTag("image-text-count"))
                        u.items.forEach{item->
                            Text(item.current.text)
                            Text((if(item.engine.synthetic)"合成测试文字 · 非真实 OCR"else if(item.current.humanCorrection)"人工纠正的图片文字"else"本地 OCR 文字")+" · 原文版本 ${item.anchor.intent.revision}")
                            TextButton({if(canUse())c.locate(u.session,item,onLocate)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-source:"+item.id)){Text("查回精确原图")}
                        }
                        TextButton({if(canUse())c.next(u.session,true)},enabled=editable&&u.canPrevious,modifier=Modifier.heightIn(min=48.dp)){Text("上一页")}
                        TextButton({if(canUse())c.next(u.session)},enabled=editable&&u.page?.next!=null,modifier=Modifier.heightIn(min=48.dp)){Text("下一页")}
                    }
                    if(u.pending!=null)TextButton({if(canUse())c.retry(u.session)},enabled=!u.busy,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-retry")){Text("核对同次保存")}
                    TextButton({if(canUse())c.refresh(u.session)},enabled=!u.busy,modifier=Modifier.heightIn(min=48.dp).testTag("image-text-refresh")){Text("刷新实际状态")}
                    TextButton({if(canUse())c.close(u.session)},modifier=Modifier.heightIn(min=48.dp).testTag("image-text-close")){Text("关闭图片文字")}
                }
            }
        }
    }
}
