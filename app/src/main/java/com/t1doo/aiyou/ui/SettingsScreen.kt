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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.t1doo.aiyou.model.FactRecapPeriod
import com.t1doo.aiyou.model.ProcessingPolicy
import java.util.concurrent.atomic.AtomicBoolean

/** Observe the owned callback itself in tests, before Compose's detachable click/sound wrapper. */
internal val LocalRecapPreferenceActionObserver = staticCompositionLocalOf<((FactRecapPeriod, () -> Unit) -> Unit)?> { null }
internal val LocalCaptureDefaultActionObserver = staticCompositionLocalOf<((ProcessingPolicy, () -> Unit) -> Unit)?> { null }

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
    hasRecordPackages: Boolean = false,
    storageUsage: StorageUsageController? = null,
    appAccessSection: (@Composable () -> Unit)? = null,
    hasFullBackup: Boolean = false,
    modelConnectionSection: (@Composable () -> Unit)? = null,
) {
    val owner = remember { AtomicBoolean(true) }
    DisposableEffect(owner) { onDispose { owner.set(false) } }
    val currentDisplay by rememberUpdatedState(display)
    val currentChange by rememberUpdatedState(onDisplayChange)
    fun change(update: (UiPreferences) -> UiPreferences) {
        val confirmed = currentDisplay
        if (owner.get() && confirmed.ready && !confirmed.busy && !confirmed.saveUnconfirmed) currentChange(update(confirmed.preferences))
    }
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
                        display.ready && !display.busy && !display.saveUnconfirmed, "companion-switch") {
                        change { value -> value.copy(showCompanion = it) }
                    }
                    PreferenceSwitch("减少动效", "使用静态反馈，减少装饰动画。", display.preferences.reduceMotion,
                        display.ready && !display.busy && !display.saveUnconfirmed, "motion-switch") {
                        change { value -> value.copy(reduceMotion = it) }
                    }
                    if (display.busy) Text(if (display.ready) "正在保存设置…" else "正在读取设置…", style = MaterialTheme.typography.bodySmall)
                    display.error?.let { StatusNotice("显示设置需要处理", it, state = ComponentState.ERROR,
                        actionLabel = "重试设置", onAction = onRetryDisplay) }
                }
                SettingsGroup("回顾偏好") {
                    Text("默认回顾范围", style = MaterialTheme.typography.titleMedium)
                    Text("用于下次打开本机回顾时的初始范围，生成前仍可改选。每次都需要主动点击生成。",
                        style = MaterialTheme.typography.bodyMedium, color = AIyouColors.textSecondary)
                    for (period in FactRecapPeriod.entries) {
                        val selected = display.preferences.defaultRecapPeriod == period
                        val select = { change { it.copy(defaultRecapPeriod = period) } }
                        LocalRecapPreferenceActionObserver.current?.invoke(period, select)
                        Row(Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                            .testTag("recap-default-" + period.name.lowercase())
                            .selectable(selected, enabled = display.ready && !display.busy && !display.saveUnconfirmed, role = Role.RadioButton,
                                onClick = select),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            RadioButton(selected, onClick = null, enabled = display.ready && !display.busy && !display.saveUnconfirmed)
                            Text(if (period == FactRecapPeriod.DAY) "单日" else "所在周（周一开始）")
                        }
                    }
                    if (display.busy) Text(if (display.ready) "正在保存回顾偏好…" else "正在读取回顾偏好…")
                    display.error?.let { StatusNotice("回顾偏好尚未确认", it, state = ComponentState.ERROR,
                        actionLabel = "重试回顾设置", onAction = onRetryDisplay) }
                }
                SettingsGroup("本机与隐私") {
                    Text("以后新草稿的默认处理范围", style = MaterialTheme.typography.titleMedium)
                    Text("只用于之后新建的草稿；当前草稿、已有记录和导入内容保持各自范围。每份草稿仍可单独选择，两种范围都禁止外发。",
                        style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.selectableGroup()) {
                        for (policy in listOf(ProcessingPolicy.LOCAL_ONLY, ProcessingPolicy.AI_NONE)) {
                            val select = { change { it.copy(defaultCapturePolicy = policy) } }
                            LocalCaptureDefaultActionObserver.current?.invoke(policy, select)
                            val enabled = display.ready && !display.busy && !display.saveUnconfirmed
                            Row(Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                                .testTag("capture-default-${policy.name}")
                                .selectable(display.preferences.defaultCapturePolicy == policy, enabled = enabled,
                                    role = Role.RadioButton, onClick = select), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                RadioButton(display.preferences.defaultCapturePolicy == policy, onClick = null, enabled = enabled)
                                Text(if (policy == ProcessingPolicy.LOCAL_ONLY) "仅本机处理" else "不参与 AI")
                            }
                        }
                    }
                    if (display.busy) Text("正在核对默认范围…")
                    if (!display.ready && display.error != null)
                        Text("设置暂不可读取；以后新建的草稿暂用不参与 AI。当前草稿范围保持不变，仍可逐稿选择。",
                            modifier = Modifier.testTag("capture-default-read-failed"))
                    display.error?.let { StatusNotice("默认范围尚未确认", it, state = ComponentState.ERROR,
                        actionLabel = "重试核对默认范围", onAction = onRetryDisplay) }
                    Text(if (hasAttachments) "文字、草稿与已保存记录的图片附件保存在本机。" else "文字与草稿保存在本机。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(if(hasFullBackup) "记录保存不需要模型或网络。可在回忆的加密包入口制作选定记录包，或备份完整资料库并恢复到空库；录音和系统提醒尚未开放。"
                        else if(hasRecordPackages) "记录保存不需要模型或网络。可在回忆中制作选定记录与原图的加密包并恢复；整库备份、录音和系统提醒尚未开放。"
                        else "记录保存不需要模型或网络。整库备份、录音与系统提醒尚未开放。",
                        style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                }
                SettingsGroup("模型连接") {
                    if(modelConnectionSection!=null) modelConnectionSection() else {
                    Text("尚未开放", style = MaterialTheme.typography.titleMedium)
                    Text("之后可连接你选择的模型。发送任何记录前，会先说明范围并征求授权。",
                        style = MaterialTheme.typography.bodyMedium, color = AIyouColors.textSecondary)
                    }
                }
                appAccessSection?.let { section -> SettingsGroup("应用锁") { section() } }
                if (storageUsage != null) SettingsGroup("存储") {
                    StorageUsageSection(storageUsage)
                }
                SettingsGroup("关于") {
                    Text("爱忆 AIyou · 本机记录伙伴", style = MaterialTheme.typography.bodyMedium)
                    Text("陪伴插画按噜噜参考形象生成；只用于本机展示，不读取你的记录。", style = MaterialTheme.typography.bodySmall,
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
