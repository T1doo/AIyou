package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.StatusNotice

@Composable
internal fun LocalEvidenceNotice(state: LocalEvidenceState) {
    Column(Modifier.testTag("local-evidence-status")) {
        Text("按当前关键词或精确片段及筛选选取出处，不理解问题语义，也没有调用模型作答。“不参与 AI”的记录与无文字图片不进入候选。",
            style = MaterialTheme.typography.bodySmall)
        if (state.question.isNotEmpty()) Text("本次问题／片段：${state.question}", style = MaterialTheme.typography.bodyMedium)
        when (state.stage) {
            LocalEvidenceStage.READY -> StatusNotice("${state.cards.size} 条本机出处候选",
                if (state.limited) "已达到 5 条／1200 码点预算，还有候选未展示。可收窄条件或返回原文列表。"
                else "摘录来自当前版本，不代表问题已得到回答或事实已获证实。可打开准确原文核对。")
            LocalEvidenceStage.EMPTY -> StatusNotice("没有可引用的证据",
                "当前筛选下没有可用于候选的原文文字。请调整条件；这不能证明某件事从未发生。", state = ComponentState.EMPTY)
            LocalEvidenceStage.INVALID, LocalEvidenceStage.FAILED, LocalEvidenceStage.STALE -> StatusNotice("出处候选需要处理",
                state.message.orEmpty(), state = ComponentState.ERROR)
            else -> StatusNotice("等待本机出处候选", "请输入问题中的关键词或精确片段，等待当前查找完成。", state = ComponentState.LOADING)
        }
    }
}
