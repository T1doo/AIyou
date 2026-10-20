package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.model.SourceMemorySnapshot

internal class MemoryInboxActions(val refresh: () -> Unit, val next: () -> Unit, val previous: () -> Unit,
    val open: Map<String, () -> Unit>)
internal fun interface MemoryInboxObserver { fun onActions(actions: MemoryInboxActions) }
internal val LocalMemoryInboxObserver = staticCompositionLocalOf<MemoryInboxObserver?> { null }

@Composable
internal fun SourceMemoryInboxDialog(ui: SourceMemoryInboxUiState, controller: SourceMemoryInboxController,
    canUse: () -> Boolean, onOpen: (SourceMemorySnapshot) -> Boolean) {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    DisposableEffect(controller, ui.session) { onDispose { controller.close(ui.session) } }
    val actions = MemoryInboxActions(
        refresh = { if (canUse()) controller.refresh(ui.session, ui.request) },
        next = { if (canUse()) controller.next(ui.session, ui.request) },
        previous = { if (canUse()) controller.previous(ui.session, ui.request) },
        open = ui.snapshot?.pending.orEmpty().associate { item -> item.id to {
            if (canUse()) controller.source(ui.session, ui.request, item) { if (canUse()) onOpen(item) else false }
        } })
    LocalMemoryInboxObserver.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    Dialog(onDismissRequest = { if (canUse()) controller.close(ui.session) },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow)
                .widthIn(max = AIyouDimensions.contentMaxWidth).heightIn(max = height), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow)
                    .testTag("memory-inbox-dialog"), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("待审事件与情绪", style = MaterialTheme.typography.titleLarge)
                    Text("汇集你从原文手工保存的线索。前往记录核对后，仍需逐项确认采纳。", style = MaterialTheme.typography.bodySmall)
                    if (ui.busy) Text("正在核对本机候选与来源。")
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-inbox-error")) }
                    ui.snapshot?.let { snapshot ->
                        Text("本页待审 " + snapshot.pending.size + " 条", modifier = Modifier.testTag("memory-inbox-count"))
                        if (snapshot.pending.isEmpty()) Text(if (snapshot.next != null || ui.canGoBack)
                            "本页没有待审线索，可查看更早候选或刷新。" else "当前没有待审事件或当次情绪线索。")
                        snapshot.pending.forEach { item -> key(item.id) {
                            val version = checkNotNull(item.current)
                            HorizontalDivider()
                            Text(version.content.kind.label() + " · " + version.content.assertion.label())
                            Text(version.content.reference.quote, modifier = Modifier.testTag("memory-inbox-quote-" + item.id))
                            Text("原文版本 " + item.source.revision + " · 内容版本 " + version.revision)
                            Text("记录于 " + java.time.Instant.ofEpochMilli(checkNotNull(item.capturedAtMillis))
                                .atZone(java.time.ZoneId.of(checkNotNull(item.captureTimeZone)))
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " " + item.captureTimeZone)
                            TextButton(enabled = !ui.busy, onClick = actions.open.getValue(item.id),
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-inbox-source-" + item.id)) {
                                Text("前往原文审阅")
                            }
                        } }
                        if (snapshot.next != null) TextButton(enabled = !ui.busy, onClick = actions.next,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-inbox-next")) { Text("查看更早候选") }
                    }
                    if (ui.canGoBack) TextButton(enabled = !ui.busy, onClick = actions.previous,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-inbox-previous")) { Text("返回较新候选") }
                    TextButton(enabled = !ui.busy, onClick = actions.refresh,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-inbox-refresh")) { Text("刷新待审线索") }
                    TextButton(onClick = { if (canUse()) controller.close(ui.session) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-inbox-close")) { Text("关闭") }
                }
            }
        }
    }
}
