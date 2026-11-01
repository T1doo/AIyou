package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.model.*

@Composable internal fun EvidenceQuestionInput(state:LocalEvidenceState,onChange:(String)->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("本次要问的问题",style=MaterialTheme.typography.labelLarge)
        OutlinedTextField(value=state.question,onValueChange=onChange,enabled=state.stage==LocalEvidenceStage.READY,
            modifier=Modifier.fillMaxWidth().testTag("question-draft").semantics{contentDescription="本次要问的问题"},
            minLines=2,maxLines=6,isError=state.questionError!=null,
            supportingText={Text(state.questionError?:"检索关键词只用来找出处；这里可输入完整问题。修改后需重新确认出处，不会自动发送或记下来。")})
    }
}

/** Local metadata only. Choosing a connection grants no record or question sending permission. */
@Composable internal fun QuestionConnectionsSection(ui:QuestionConnectionsState,stage:QuestionConsentStage,
    unavailable:String?,onReload:()->Unit,onSelect:(ModelConnectionConfiguration)->Unit) {
    val busy=stage in setOf(QuestionConsentStage.PREPARING,QuestionConsentStage.AUTHORIZING,
        QuestionConsentStage.RUNNING,QuestionConsentStage.WITHDRAWING)
    Column(Modifier.fillMaxWidth().testTag("question-connections")) {
        Text("问一问 · 选择连接",style=MaterialTheme.typography.titleMedium)
        Text("问题和回答不会自动记下来。先选原文出处与连接，再核对本次发送范围。",style=MaterialTheme.typography.bodySmall)
        unavailable?.let{Text(it,Modifier.testTag("question-network-unavailable"))}
        if(ui.busy)Text("正在读取本机连接与能力档案…")
        ui.error?.let{Text(it,Modifier.testTag("question-connections-error"))}
        if(!ui.busy&&ui.error==null&&ui.configurations.isEmpty())Text("还没有连接配置；可在设置中添加并检查模型，仍可继续本机查找。")
        for(c in ui.configurations) {
            val supported=ui.profiles.singleOrNull{it.connection==c}?.observation?.state==CapabilityState.SUPPORTED
            val ready=c.secretRef!=null&&supported&&ModelConfigurationPurpose.CONVERSATION in c.details.purposes
            TextButton(onClick={onSelect(c)},enabled=ready&&!ui.busy&&!busy&&ui.error==null,
                modifier=Modifier.fillMaxWidth().heightIn(min=AIyouDimensions.minTouchTarget).testTag("question-select-${c.id}")) {
                Text((if(ui.selected?.configuration==c)"已选：" else "选择：")+c.details.name+
                    if(ready)" · ${c.details.modelId}" else " · 对话能力或密钥尚未就绪")
            }
        }
        TextButton(onClick=onReload,enabled=!ui.busy&&!busy,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("question-connections-reload")) {Text("重新读取连接")}
    }
}
