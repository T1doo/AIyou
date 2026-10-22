package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.SourceMemoryAssertion
import com.t1doo.aiyou.model.SourceMemoryKind
import com.t1doo.aiyou.designsystem.aiyouFocusOutline

internal fun unifiedScopeLabel(s:UnifiedKeywordScope)=when(s){
    UnifiedKeywordScope.ALL->"全部内容";UnifiedKeywordScope.ORIGINAL->"原文";UnifiedKeywordScope.AUDIO->"人工转写"
    UnifiedKeywordScope.IMAGE->"图片文字";UnifiedKeywordScope.PREFERENCE->"已采纳普通偏好"
    UnifiedKeywordScope.ACCEPTED_MEMORY->"已采纳事件与当次情绪"
}
private fun evidenceText(e:UnifiedKeywordEvidence)=when(e){
    is UnifiedKeywordEvidence.Original->e.entry.originalText
    is UnifiedKeywordEvidence.Audio->e.value.current.text
    is UnifiedKeywordEvidence.Image->e.value.current.text
    is UnifiedKeywordEvidence.Preference->e.value.topicLabel.orEmpty()+"\n"+e.value.current?.content?.reference?.quote.orEmpty()
    is UnifiedKeywordEvidence.AcceptedMemory->e.value.content.reference.quote
}
@Composable private fun KeywordExcerpt(e:UnifiedKeywordEvidence,keyword:String){
    if(e is UnifiedKeywordEvidence.Original){
        val hit=unifiedOriginalHit(e,keyword)?:return
        val preview=originalSearchPreview(e.entry,hit)?:return
        val spans=if(preview.highlightEnd>preview.highlightStart)listOf(AnnotatedString.Range(SpanStyle(fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary),preview.highlightStart,preview.highlightEnd))else emptyList()
        Text(AnnotatedString(preview.text,spans),Modifier.testTag("unified-excerpt:"+e.key));return
    }
    if(e is UnifiedKeywordEvidence.AcceptedMemory){
        val m=e.value
        Text("人工分类 · "+when(m.content.assertion){SourceMemoryAssertion.SELF_REPORT->"明确自述";SourceMemoryAssertion.CONTEXT->"上下文";SourceMemoryAssertion.UNCERTAIN->"含义未确认"})
        Text("采纳是人工审阅决定，不证明事件发生或人格特征。")
        Text(if(m.quoteMatch)"逐字依据命中"else"逐字依据（用于回原文）",Modifier.testTag("unified-memory-quote-label:"+e.key))
        KeywordText(m.content.reference.quote,if(m.quoteMatch)keyword else"","unified-memory-quote:"+e.key)
        if(m.noteMatch){
            Text("人工备注命中 · 非原文",Modifier.testTag("unified-memory-note-label:"+e.key))
            KeywordText(m.content.note,keyword,"unified-memory-note:"+e.key)
            if(!m.quoteMatch)Text("原文定位的是依据，不是备注文字。",Modifier.testTag("unified-memory-location:"+e.key))
        }
        return
    }
    KeywordText(evidenceText(e),keyword,"unified-excerpt:"+e.key)
}
@Composable private fun KeywordText(text:String,keyword:String,tag:String){
    val at=if(keyword.isEmpty())-1 else text.indexOf(keyword)
    val point=if(at<0)0 else text.codePointCount(0,at)
    val start=text.offsetByCodePoints(0,(point-24).coerceAtLeast(0))
    val end=text.offsetByCodePoints(start,minOf(240,text.codePointCount(start,text.length)))
    val prefix=if(start>0)"…"else"";val shown=prefix+text.substring(start,end)+(if(end<text.length)"…"else"")
    val hit=at-start+prefix.length
    val spans=if(at>=start&&at+keyword.length<=end&&keyword.isNotEmpty())listOf(AnnotatedString.Range(SpanStyle(fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary),hit,hit+keyword.length))else emptyList()
    Text(AnnotatedString(shown,spans),Modifier.testTag(tag))
}
@Composable internal fun UnifiedKeywordDialog(u:UnifiedKeywordUi,c:UnifiedKeywordController,canUse:()->Boolean,onLocate:(UnifiedKeywordEvidence,UnifiedKeywordHandoff)->Unit){
    DisposableEffect(c,u.session){onDispose{c.close(u.session)}}
    Dialog(onDismissRequest={if(canUse())c.close(u.session)},properties=DialogProperties(usePlatformDefaultWidth=false)){
        CompositionLocalProvider(LocalSaveableStateRegistry provides null){UnifiedKeywordContent(u,c,canUse,onLocate)}
    }
}

/** The exact production body also feeds native render evidence; it has no storage/lifecycle owner. */
@Composable fun UnifiedKeywordContent(u:UnifiedKeywordUi,c:UnifiedKeywordController,canUse:()->Boolean,
    onLocate:(UnifiedKeywordEvidence,UnifiedKeywordHandoff)->Unit,listState:LazyListState=rememberLazyListState()){
    if(u.tagPicker){UnifiedTagChooser(u,c,canUse);return}
    var menu by remember{mutableStateOf(false)}
    Surface(Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.94f),shape=MaterialTheme.shapes.large){
            LazyColumn(Modifier.padding(12.dp).testTag("unified-results"),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)){
                item{
                    Text("统一关键词找回",style=MaterialTheme.typography.titleLarge)
                    Text("逐字查找当前内容，区分大小写，不理解语义；不联网、不自动播放或提取。")
                    OutlinedTextField(u.keyword,{if(canUse())c.keyword(u.session,it)},label={Text("关键词（最多64字）")},modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("unified-query"))
                    Box{TextButton({if(canUse())menu=true},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-scope")){Text("范围："+unifiedScopeLabel(u.scope))}
                        DropdownMenu(menu,{menu=false}){UnifiedKeywordScope.entries.forEach{s->DropdownMenuItem(text={Text(unifiedScopeLabel(s))},onClick={menu=false;if(canUse())c.scope(u.session,s)},modifier=Modifier.testTag("unified-scope:"+s.name))}}}
                }
                item{
                    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium){
                        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("捕获日期",style=MaterialTheme.typography.titleSmall)
                            Text("按每条原件保存的时区；不是事件发生日期。",style=MaterialTheme.typography.bodySmall)
                            OutlinedTextField(u.from,{if(canUse())c.from(u.session,it)},singleLine=true,label={Text("起始日期（含）")},
                                supportingText={Text("YYYY-MM-DD；留空不限")},modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("unified-from"))
                            OutlinedTextField(u.through,{if(canUse())c.through(u.session,it)},singleLine=true,label={Text("结束日期（含）")},
                                supportingText={Text("YYYY-MM-DD；留空不限")},modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("unified-through"))
                            TextButton({if(canUse())c.unlimitedDates(u.session)},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-dates-clear")){Text("不限日期")}
                            HorizontalDivider()
                            Text("所属原件的人工标签",style=MaterialTheme.typography.titleSmall)
                            Text("与关键词、捕获日期共同筛选，不改变原件或标签。",style=MaterialTheme.typography.bodySmall)
                            Text(u.tag?.name?:"不限标签",modifier=Modifier.testTag("unified-tag-selected"))
                            OutlinedButton({if(canUse())c.openTags(u.session)},enabled=!u.busy&&!u.tagBusy,
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-tags-open")){Text("选择人工标签")}
                            if(u.tag!=null)TextButton({if(canUse())c.clearTag(u.session)},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-tag-clear")){Text("不限标签")}
                            if(u.tagBusy)Text("正在重新核对人工标签。",Modifier.testTag("unified-tag-status"))
                            if(u.tagError!=null)Text(u.tagError,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("unified-tag-error"))
                        }
                    }
                }
                item{
                    FilledTonalButton({if(canUse())c.search(u.session)},enabled=!u.busy&&!u.tagBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-search")){Text("查找当前内容")}
                    TextButton({if(canUse())c.clear(u.session)},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-clear")){Text("清空查询和结果")}
                    TextButton({if(canUse())c.close(u.session)},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-close")){Text("返回回忆时间线")}
                    when{u.busy->Text("正在认证所选内容与原件。",Modifier.testTag("unified-status"))
                        u.error!=null->Text(u.error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("unified-error"))
                        u.snapshot==null->Text("请输入中文词句，再主动查找。",Modifier.testTag("unified-status"))
                        u.snapshot.groups.isEmpty()->Text("当前所选内容没有找到逐字匹配。可修改关键词或范围。",Modifier.testTag("unified-empty"))
                        else->Text("第 ${u.page+1} 页 · 共 ${u.snapshot.groups.size} 个原件组",Modifier.testTag("unified-count"))}
                }
                items(u.visibleGroups,key={it.key}){g->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("unified-group:"+g.key)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("原件版本 ${g.binding.source.revision}",style=MaterialTheme.typography.labelLarge)
                        Text("捕获日期："+java.time.Instant.ofEpochMilli(g.binding.captured).atZone(java.time.ZoneId.of(g.binding.zone)).toLocalDate()+" · "+g.binding.zone,
                            modifier=Modifier.testTag("unified-capture-date:"+g.key),style=MaterialTheme.typography.bodySmall)
                        g.items.forEach{e->key(e.key){
                            Text(when(e){is UnifiedKeywordEvidence.Original->"原文命中";is UnifiedKeywordEvidence.Audio->"人工转写 · 非自动识别";is UnifiedKeywordEvidence.Image->if(e.value.engine.synthetic)"合成端口测试结果 · 非真实 OCR"else if(e.value.current.humanCorrection)"图片文字 · 人工纠正"else"图片提取文字";is UnifiedKeywordEvidence.Preference->"已采纳普通偏好 · 人工确认";is UnifiedKeywordEvidence.AcceptedMemory->if(e.value.content.kind==SourceMemoryKind.EVENT)"已采纳事件线索"else"已采纳当次情绪线索"})
                            KeywordExcerpt(e,u.keyword)
                            TextButton({if(canUse())c.locate(u.session,e,onLocate)},enabled=!u.busy,modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-source:"+e.key)){Text(when(e){is UnifiedKeywordEvidence.Audio->"查看精确原音";is UnifiedKeywordEvidence.Image->"查看精确原图";else->"查看逐字原文出处"})}
                        }}
                    }}
                }
                if(!u.busy&&u.error==null&&u.snapshot!=null)item{
                    TextButton({if(canUse())c.next(u.session,true)},enabled=u.page>0,modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-previous")){Text("上一页")}
                    TextButton({if(canUse())c.next(u.session)},enabled=(u.page+1)*20<u.snapshot.groups.size,modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-next")){Text("下一页")}
                }
            }
    }
}

/** Kept inside the same private dialog; the tag list has its own explicit scroll owner. */
@Composable private fun UnifiedTagChooser(u:UnifiedKeywordUi,c:UnifiedKeywordController,canUse:()->Boolean){
    Surface(Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.94f),shape=MaterialTheme.shapes.large){
        LazyColumn(Modifier.padding(12.dp).testTag("unified-tag-options"),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item {
                Text("选择人工标签",style=MaterialTheme.typography.titleLarge)
                Text("来自当前原件的人工标签；同名标签合并显示。选择后仍需主动查找。")
                if(u.tagBusy)Text("正在完整核对标签与来源。",Modifier.testTag("unified-tag-status"))
                else if(u.tagError!=null)Text(u.tagError,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("unified-tag-error"))
                else if(u.tagPage?.choices?.isEmpty()==true)Text("当前没有可选的人工标签。",Modifier.testTag("unified-tags-empty"))
            }
            if(!u.tagBusy&&u.tagError==null)items(u.tagPage?.choices.orEmpty(),key={it.id}){choice->
                OutlinedButton({if(canUse())c.selectTag(u.session,choice)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-tag-choice:"+choice.id)){Text(choice.name)}
            }
            item {
                TextButton({if(canUse())c.loadTags(u.session)},enabled=!u.tagBusy,modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-tags-first")){Text(if(u.tagError!=null)"重新核对标签"else"重新查看第一页")}
                if(u.tagPage?.hasNext==true)FilledTonalButton({if(canUse())c.loadTags(u.session,true)},enabled=!u.tagBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-tags-next")){Text("下一页标签")}
                TextButton({if(canUse())c.closeTags(u.session)},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-tags-cancel")){Text("取消选择，返回查询")}
            }
        }
    }
}
