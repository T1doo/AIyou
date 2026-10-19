package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.model.ProcessingPolicy

/** Memory-only observation of exact application handlers, never detached UI nodes or private text. */
internal val LocalCapturePolicyActionObserver = staticCompositionLocalOf<((String, () -> Unit) -> Unit)?> { null }
private class CapturePolicyPresentation { var active = true }

@Composable
internal fun CaptureProcessingChoice(ui: MixedCaptureUiState, controller: MixedCaptureController) {
    val draft = ui.draft ?: return
    val owner = remember(controller) { CapturePolicyPresentation() }
    DisposableEffect(owner) { onDispose { owner.active = false } }
    val observer = LocalCapturePolicyActionObserver.current
    val enabled = !ui.busy && ui.pendingEdits == 0 && ui.selection == null && !ui.uncertainSave &&
        !ui.discardPending && !ui.confirmDiscard && !draft.discarding && ui.policyChange == null
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Text("这份草稿的处理范围", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text("只影响这份尚未保存的记录；已有记录保持不变。两种范围都禁止发送到远端。",
            style = MaterialTheme.typography.bodySmall)
        for (policy in listOf(ProcessingPolicy.LOCAL_ONLY, ProcessingPolicy.AI_NONE)) {
            val choose = { if (owner.active) controller.changePolicy(draft.id, draft.version, policy) }
            if (observer != null) SideEffect { observer("policy:${policy.name}", choose) }
            Row(Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("mixed-policy-${policy.name}")
                .selectable(draft.policy == policy, enabled = enabled, role = Role.RadioButton, onClick = choose)
                .padding(vertical = AIyouDimensions.spaceSm), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                RadioButton(draft.policy == policy, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(if (policy == ProcessingPolicy.LOCAL_ONLY) "仅本机处理" else "不参与 AI")
                    Text(if (policy == ProcessingPolicy.LOCAL_ONLY) "可在单独确认后使用本机整理；以后新草稿的默认范围可在设置中选择。"
                        else "只作手账保存与人工浏览，不参与本机或远端 AI。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ui.policyChange?.let { change ->
            Text("范围保存尚未确认：${if (change.target == ProcessingPolicy.AI_NONE) "不参与 AI" else "仅本机处理"}。核对前不能保存记录。",
                modifier = Modifier.testTag("mixed-policy-pending"))
            val retry = { if (owner.active) controller.retryPolicy(change.requestId) }
            val read = { if (owner.active) controller.readCurrentPolicy(change.requestId) }
            if (observer != null) SideEffect { observer("retry", retry); observer("read-current", read) }
            OutlinedButton(retry, enabled = !ui.busy, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("mixed-policy-retry")) { Text("重试确认这次范围") }
            OutlinedButton(read, enabled = !ui.busy, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("mixed-policy-read")) { Text("重新读取当前范围") }
        }
    }
}
