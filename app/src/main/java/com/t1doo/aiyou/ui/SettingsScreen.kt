package com.t1doo.aiyou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.StatusNotice

@Composable
fun SettingsScreen(
    display: UiPreferencesState,
    onDisplayChange: (UiPreferences) -> Unit,
    onRetryDisplay: () -> Unit,
    hasAttachments: Boolean,
    demo: Boolean,
    demoState: ComponentState,
    onLicenses: () -> Unit,
    onDemo: () -> Unit,
    onDemoState: (ComponentState) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
                .verticalScroll(rememberScrollState()).testTag("settings-scroll").padding(margin),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                Text("按你的节奏使用爱忆", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                SettingsGroup("陪伴与显示") {
                    Text("一只安静的水豚，陪你记下一点生活。", style = MaterialTheme.typography.bodyMedium, color = AIyouColors.textSecondary)
                    PreferenceSwitch("显示陪伴伙伴", "可随时关闭，不影响记录。", display.preferences.showCompanion,
                        display.ready && !display.busy, "companion-switch") {
                        onDisplayChange(display.preferences.copy(showCompanion = it))
                    }
                    PreferenceSwitch("减少动效", "使用静态反馈，减少装饰动画。", display.preferences.reduceMotion,
                        display.ready && !display.busy, "motion-switch") {
                        onDisplayChange(display.preferences.copy(reduceMotion = it))
                    }
                    if (display.busy) Text(if (display.ready) "正在保存设置…" else "正在读取设置…", style = MaterialTheme.typography.bodySmall)
                    display.error?.let { StatusNotice("显示设置需要处理", it, state = ComponentState.ERROR,
                        actionLabel = "重试设置", onAction = onRetryDisplay) }
                }
                SettingsGroup("本机与隐私") {
                    Text("仅本机处理", style = MaterialTheme.typography.titleMedium)
                    Text(if (hasAttachments) "文字、草稿与已保存记录的图片附件保存在本机。" else "文字与草稿保存在本机。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("记录保存不需要模型或网络。录音、备份与系统提醒尚未开放。",
                        style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                }
                SettingsGroup("模型连接") {
                    Text("尚未开放", style = MaterialTheme.typography.titleMedium)
                    Text("之后可连接你选择的模型。发送任何记录前，会先说明范围并征求授权。",
                        style = MaterialTheme.typography.bodyMedium, color = AIyouColors.textSecondary)
                }
                SettingsGroup("关于") {
                    Text("爱忆 AIyou · 本机记录伙伴", style = MaterialTheme.typography.bodyMedium)
                    Text("陪伴形象为原创中性水豚占位，尚未使用正式 IP 素材。", style = MaterialTheme.typography.bodySmall,
                        color = AIyouColors.textSecondary)
                    TextButton(onClick = onLicenses, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("开源许可与声明") }
                }
                SettingsGroup("界面示例") {
                    Text("虚构内容仅供查看，不保存记录或安排提醒。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    OutlinedButton(onClick = onDemo) { Text(if (demo) "退出虚构示例" else "查看虚构示例") }
                    if (demo) FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        ComponentState.entries.forEach { state ->
                            OutlinedButton(onClick = { onDemoState(state) }) { Text(state.description + if (demoState == state) " · 已选" else "") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = AIyouColors.surface) {
            Column(Modifier.padding(AIyouDimensions.spaceLg), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) { content() }
        }
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget).testTag(tag)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = AIyouDimensions.spaceSm), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
