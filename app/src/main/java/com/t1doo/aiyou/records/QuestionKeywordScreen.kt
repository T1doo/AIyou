package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.designsystem.aiyouFocusOutline
import com.t1doo.aiyou.model.RecordDateBasis

@Composable internal fun QuestionKeywordDialog(u:QuestionKeywordUi,c:QuestionKeywordController){
    DisposableEffect(c,u.session){onDispose{c.cancel(u)}}
    Dialog(onDismissRequest={c.cancel(u)},properties=DialogProperties(usePlatformDefaultWidth=false)){
        CompositionLocalProvider(LocalSaveableStateRegistry provides null){QuestionKeywordContent(u,c::question,c::terms,c::choose,c::dateChoice,c::from,c::through,c::basis,c::confirm,c::cancel)}
    }
}

/** The actual production body is also rendered by screenshot previews. No storage runs here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun QuestionKeywordContent(u:QuestionKeywordUi,
    onQuestion:(QuestionKeywordUi,String)->Unit,onTerms:(QuestionKeywordUi,String)->Unit,
    onChoose:(QuestionKeywordUi,QuestionKeywordFragments.Fragment)->Unit,onDate:(QuestionKeywordUi,QuestionDateChoice)->Unit,
    onFrom:(QuestionKeywordUi,String)->Unit,onThrough:(QuestionKeywordUi,String)->Unit,
    onBasis:(QuestionKeywordUi,RecordDateBasis)->Unit,onConfirm:(QuestionKeywordUi)->Unit,onCancel:(QuestionKeywordUi)->Unit,
    scroll:ScrollState=rememberScrollState()){
    Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp),shape=MaterialTheme.shapes.large){
        Column(Modifier.verticalScroll(scroll).padding(16.dp).testTag("question-keyword-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("从问题里选词",style=MaterialTheme.typography.titleLarge)
            Text("只在本机提供原字片段，不理解问题含义、不生成答案。先选词并确认日期，才发起原文查找。",style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(u.question,{onQuestion(u,it)},label={Text("完整问题（最多512字）")},minLines=3,
                modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("question-keyword-question"))
            Text("可选片段 · 默认不选",style=MaterialTheme.typography.titleMedium)
            if(u.proposals.values.isEmpty())Text("输入问题后可从原字里选词，也可以直接编辑下方关键词。")
            val selected=u.terms.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                u.proposals.values.forEach{fragment->FilterChip(selected=fragment.text in selected,onClick={onChoose(u,fragment)},
                    label={Text(fragment.text)},shape=RoundedCornerShape(12.dp),modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline().testTag("question-fragment:${fragment.start}:${fragment.end}"))}
            }
            if(u.proposals.truncated)Text("只展示部分片段；完整问题仍在上方，可以直接编辑关键词。",Modifier.testTag("question-keyword-truncated"))
            OutlinedTextField(u.terms,{onTerms(u,it)},label={Text("确认词（空格分隔，全部包含）")},
                supportingText={Text("最多8词，含空格共64字。可以删改；新增文字是你的输入，不会做同义替换。")},
                modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("question-keyword-terms"))
            HorizontalDivider()
            Text("明确日期条件",style=MaterialTheme.typography.titleMedium)
            Text("问题里的时间不会自动变成日期条件；不限日期也需要明确选择。",Modifier.testTag("question-keyword-date-notice"))
            Text("当前依据："+if(u.dateBasis==RecordDateBasis.CAPTURED)"原件捕获日期（每条记录保存的时区）"else"人工事件日期（没有日期的不匹配）")
            OutlinedButton(onClick={onBasis(u,if(u.dateBasis==RecordDateBasis.CAPTURED)RecordDateBasis.EVENT else RecordDateBasis.CAPTURED)},
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("question-keyword-basis"),shape=RoundedCornerShape(12.dp)){
                Text(if(u.dateBasis==RecordDateBasis.CAPTURED)"切换为人工事件日期"else"切换为捕获日期")
            }
            listOf(QuestionDateChoice.CURRENT to "沿用当前条件：${u.binding.input.from.ifEmpty{"不限起始"}} 至 ${u.binding.input.through.ifEmpty{"不限结束"}}",
                QuestionDateChoice.UNLIMITED to "明确不限日期",QuestionDateChoice.RANGE to "手工填写范围（两端包含）").forEach{(choice,label)->
                FilterChip(selected=u.dateChoice==choice,onClick={onDate(u,choice)},label={Text(label)},shape=RoundedCornerShape(12.dp),
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("question-keyword-date:${choice.name}"))
            }
            if(u.dateChoice==QuestionDateChoice.RANGE){
                OutlinedTextField(u.from,{onFrom(u,it)},label={Text("开始日期 YYYY-MM-DD，可留一端空白")},singleLine=true,modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("question-keyword-from"))
                OutlinedTextField(u.through,{onThrough(u,it)},label={Text("结束日期 YYYY-MM-DD，可留一端空白")},singleLine=true,modifier=Modifier.fillMaxWidth().aiyouFocusOutline().testTag("question-keyword-through"))
            }
            Text("同时保留：标签 ${u.binding.input.tag?:"不限"}；人物 ${u.binding.input.person?:"不限"}；"+
                (if(u.binding.input.onlyFavorites)"仅收藏；"else"不限收藏；")+(if(u.binding.input.onlyImages)"仅关联图片。"else"不限图片。"),style=MaterialTheme.typography.bodySmall)
            Text("将使用原文关键词查找，所有词都需满足。否定、引述和冲突原文会保留；查回不等于事情已发生，也不确认唯一偏好。",Modifier.testTag("question-keyword-context"))
            u.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("question-keyword-error").semantics{liveRegion=LiveRegionMode.Polite})}
            Button(onClick={onConfirm(u)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("question-keyword-confirm"),shape=RoundedCornerShape(12.dp)){
                Text("确认关键词与日期并查找")
            }
            TextButton(onClick={onCancel(u)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).aiyouFocusOutline().testTag("question-keyword-cancel")){Text("取消并清除此问题")}
        }
    }
}
