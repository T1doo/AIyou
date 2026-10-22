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
import com.t1doo.aiyou.designsystem.aiyouFocusOutline

internal fun unifiedScopeLabel(s:UnifiedKeywordScope)=when(s){
    UnifiedKeywordScope.ALL->"全部内容";UnifiedKeywordScope.ORIGINAL->"原文";UnifiedKeywordScope.AUDIO->"人工转写"
    UnifiedKeywordScope.IMAGE->"图片文字";UnifiedKeywordScope.PREFERENCE->"已采纳普通偏好"
}
private fun evidenceText(e:UnifiedKeywordEvidence)=when(e){
    is UnifiedKeywordEvidence.Original->e.entry.originalText
    is UnifiedKeywordEvidence.Audio->e.value.current.text
    is UnifiedKeywordEvidence.Image->e.value.current.text
    is UnifiedKeywordEvidence.Preference->e.value.topicLabel.orEmpty()+"\n"+e.value.current?.content?.reference?.quote.orEmpty()
}
@Composable private fun KeywordExcerpt(e:UnifiedKeywordEvidence,keyword:String){
    if(e is UnifiedKeywordEvidence.Original){
        val hit=unifiedOriginalHit(e,keyword)?:return
        val preview=originalSearchPreview(e.entry,hit)?:return
        val spans=if(preview.highlightEnd>preview.highlightStart)listOf(AnnotatedString.Range(SpanStyle(fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary),preview.highlightStart,preview.highlightEnd))else emptyList()
        Text(AnnotatedString(preview.text,spans),Modifier.testTag("unified-excerpt:"+e.key));return
    }
    val text=evidenceText(e);val at=text.indexOf(keyword)
    val point=if(at<0)0 else text.codePointCount(0,at)
    val start=text.offsetByCodePoints(0,(point-24).coerceAtLeast(0))
    val end=text.offsetByCodePoints(start,minOf(240,text.codePointCount(start,text.length)))
    val prefix=if(start>0)"…"else"";val shown=prefix+text.substring(start,end)+(if(end<text.length)"…"else"")
    val hit=at-start+prefix.length
    val spans=if(at>=start&&at+keyword.length<=end&&keyword.isNotEmpty())listOf(AnnotatedString.Range(SpanStyle(fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary),hit,hit+keyword.length))else emptyList()
    Text(AnnotatedString(shown,spans),Modifier.testTag("unified-excerpt:"+e.key))
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
    var menu by remember{mutableStateOf(false)}
    Surface(Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.94f),shape=MaterialTheme.shapes.large){
            LazyColumn(Modifier.padding(12.dp).testTag("unified-results"),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)){
                item{
                    Text("统一关键词找回",style=MaterialTheme.typography.titleLarge)
                    Text("逐字查找当前内容，区分大小写，不理解语义；不联网、不自动播放或提取。")
                    OutlinedTextField(u.keyword,{if(canUse())c.keyword(u.session,it)},label={Text("关键词（最多64字）")},modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("unified-query"))
                    Box{TextButton({if(canUse())menu=true},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-scope")){Text("范围："+unifiedScopeLabel(u.scope))}
                        DropdownMenu(menu,{menu=false}){UnifiedKeywordScope.entries.forEach{s->DropdownMenuItem(text={Text(unifiedScopeLabel(s))},onClick={menu=false;if(canUse())c.scope(u.session,s)},modifier=Modifier.testTag("unified-scope:"+s.name))}}}
                    FilledTonalButton({if(canUse())c.search(u.session)},enabled=!u.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-search")){Text("查找当前内容")}
                    TextButton({if(canUse())c.keyword(u.session,"")},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-clear")){Text("清空查询和结果")}
                    TextButton({if(canUse())c.close(u.session)},modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("unified-close")){Text("返回回忆时间线")}
                    when{u.busy->Text("正在认证所选内容与原件。",Modifier.testTag("unified-status"))
                        u.error!=null->Text(u.error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("unified-error"))
                        u.snapshot==null->Text("请输入中文词句，再主动查找。",Modifier.testTag("unified-status"))
                        u.snapshot.groups.isEmpty()->Text("当前所选内容没有找到逐字匹配。可修改关键词或范围。",Modifier.testTag("unified-empty"))
                        else->Text("第 ${u.page+1} 页 · 共 ${u.snapshot.groups.size} 个原件组",Modifier.testTag("unified-count"))}
                }
                items(u.visibleGroups,key={it.key}){g->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("unified-group:"+g.key)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("原件版本 ${g.binding.source.revision} · "+java.time.Instant.ofEpochMilli(g.binding.captured).atZone(java.time.ZoneId.of(g.binding.zone)).toLocalDate())
                        g.items.forEach{e->key(e.key){
                            Text(when(e){is UnifiedKeywordEvidence.Original->"原文命中";is UnifiedKeywordEvidence.Audio->"人工转写 · 非自动识别";is UnifiedKeywordEvidence.Image->if(e.value.engine.synthetic)"合成端口测试结果 · 非真实 OCR"else if(e.value.current.humanCorrection)"图片文字 · 人工纠正"else"图片提取文字";is UnifiedKeywordEvidence.Preference->"已采纳普通偏好 · 人工确认"})
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
