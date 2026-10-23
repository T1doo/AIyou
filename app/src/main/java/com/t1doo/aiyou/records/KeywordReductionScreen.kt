package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.aiyouFocusOutline
import com.t1doo.aiyou.model.RecordDateBasis

@Composable internal fun KeywordReductionAction(value:OriginalSearchState,enabled:Boolean,onOpen:(OriginalSearchState)->Boolean){
    if(value.stage!=OriginalSearchStage.READY||value.hits.isNotEmpty()||value.input.mode!=OriginalSearchMode.KEYWORDS||
        value.owner==null||value.proof==null||KeywordReductionDraft.from(value.input.query)==null)return
    var waiting by remember(value){mutableStateOf(false)}
    TextButton(onClick={waiting=!onOpen(value)},enabled=enabled,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("keyword-reduction-open")){
        Text("删减关键词再查找")
    }
    if(waiting)Text("结果尚未确认或已变化，请等待本机读取后再试。",color=MaterialTheme.colorScheme.error,
        modifier=Modifier.testTag("keyword-reduction-waiting").semantics{liveRegion=LiveRegionMode.Polite})
}

/** Production body; words originate only in the user's already submitted keyword query. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun KeywordReductionContent(u:QuestionKeywordUi,onToggle:(QuestionKeywordUi,Int)->Unit,
    onConfirm:(QuestionKeywordUi)->Unit,onCancel:(QuestionKeywordUi)->Unit,scroll:ScrollState=rememberScrollState()){
    val draft=u.reduction?:return
    Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp),shape=MaterialTheme.shapes.large){
        Column(Modifier.verticalScroll(scroll).padding(16.dp).testTag("keyword-reduction-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("删减关键词再查找",style=MaterialTheme.typography.titleLarge)
            Text("这次没有匹配原文。你可以移除词项，再明确确认查找；不会自动放宽日期或补充同义词。")
            Text("原查询",style=MaterialTheme.typography.titleMedium)
            Text(u.binding.input.query,Modifier.testTag("keyword-reduction-original"))
            Text("点选移除或恢复 · 默认全部保留",style=MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                draft.tokens.forEachIndexed{index,word->val kept=draft.keptMask and (1 shl index)!=0
                    FilterChip(selected=kept,onClick={onToggle(u,index)},label={Text(word)},shape=RoundedCornerShape(12.dp),
                        modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("keyword-reduction-term-$index").semantics{
                            contentDescription="第${index+1}词：$word";stateDescription=if(kept)"保留"else"已移除"
                        })
                }
            }
            Text("将查找 · 全部包含",style=MaterialTheme.typography.titleMedium)
            Text(draft.remaining.joinToString(" ").ifEmpty{"尚未保留关键词"},Modifier.testTag("keyword-reduction-remaining"))
            Text("至少移除一个词项，并保留一个词。删除重复词不一定扩大结果范围。")
            HorizontalDivider()
            val input=u.binding.input
            Text("保持原条件",style=MaterialTheme.typography.titleMedium)
            Text((if(input.dateBasis==RecordDateBasis.CAPTURED)"捕获日期（每条原件保存的时区）"else"人工事件日期（未补记的不匹配）")+
                "：${input.from.ifEmpty{"不限起始"}} 至 ${input.through.ifEmpty{"不限结束"}}，两端包含。",Modifier.testTag("keyword-reduction-dates"))
            Text("标签：${input.tag?:"不限"}；人物：${input.person?:"不限"}；"+
                (if(input.onlyFavorites)"仅收藏；"else"不限收藏；")+(if(input.onlyImages)"仅当前版本关联图片。"else"不限图片。"),Modifier.testTag("keyword-reduction-filters"))
            Text("删词可能找到更多相关或无关记录。不会生成答案，否定和引述仍以原文为准。")
            u.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("keyword-reduction-error").semantics{liveRegion=LiveRegionMode.Polite})}
            Button(onClick={onConfirm(u)},enabled=draft.canConfirm,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("keyword-reduction-confirm"),shape=RoundedCornerShape(12.dp)){
                Text("确认剩余关键词并查找")
            }
            TextButton(onClick={onCancel(u)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("keyword-reduction-cancel")){
                Text("取消，保留原查找条件")
            }
        }
    }
}
