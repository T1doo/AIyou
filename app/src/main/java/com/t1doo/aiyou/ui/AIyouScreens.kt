package com.t1doo.aiyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.CaptureComposer
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.MascotBadge
import com.t1doo.aiyou.designsystem.MemoryCard
import com.t1doo.aiyou.designsystem.PrivacyChip
import com.t1doo.aiyou.designsystem.SourceCitationCard
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.designsystem.TaskProposalCard
import com.t1doo.aiyou.model.ProcessingPolicy

enum class Destination(val label: String, val symbol: String) {
    NOW("此刻", "○"), MEMORIES("回忆", "◇"), TASKS("待办", "□"),
}

@Composable
fun MainScreen(
    destination: Destination,
    demo: Boolean,
    modifier: Modifier = Modifier,
    state: ComponentState = ComponentState.NORMAL,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(AIyouColors.background)) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        val stackSummary = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(margin),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap),
            ) {
                item("summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                        if (stackSummary) {
                            Text(if (destination == Destination.NOW) "今天" else destination.label, style = MaterialTheme.typography.headlineMedium)
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
                                Text(if (destination == Destination.NOW) "今天" else destination.label, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                                PrivacyChip(ProcessingPolicy.LOCAL_ONLY)
                            }
                        }
                        Text(when (destination) {
                            Destination.NOW -> if (demo) "10月10日 · 周六 · 示例" else "随手记下一点生活"
                            Destination.MEMORIES -> if (demo) "${ScreenSamples.memories.size} 条虚构记录" else "你的本机原始记录"
                            Destination.TASKS -> "建议与正式任务分别管理"
                        }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                        if (stackSummary) PrivacyChip(ProcessingPolicy.LOCAL_ONLY)
                        if (demo) Text("虚构示例 · 不保存记录、不安排提醒", Modifier.padding(top = AIyouDimensions.spaceSm),
                            style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    }
                }
                if (state == ComponentState.ERROR || state == ComponentState.LOADING || state == ComponentState.OFFLINE) {
                    item("state") {
                        StatusNotice(
                            if (state == ComponentState.ERROR) "示例操作失败" else state.description,
                            when (state) {
                                ComponentState.ERROR -> "保存失败时不能显示成功；保留输入并让用户重试。此处为演示。"
                                ComponentState.OFFLINE -> "仅本机处理，无需连接模型。真实保存功能尚未开放。"
                                else -> "正在展示加载状态；未执行保存或模型调用。"
                            }, state = state,
                        )
                    }
                }
                when (destination) {
                    Destination.NOW -> {
                        item("composer") { CaptureSection(state, demo) }
                        item("today") {
                            Text("今日记录", style = MaterialTheme.typography.titleLarge)
                        }
                        if (demo && state != ComponentState.EMPTY) {
                            items(ScreenSamples.memories.take(1), key = { it.id }) { sample ->
                                MemoryCard(
                                    if (state == ComponentState.LONG_TEXT) ScreenSamples.longText else sample.text,
                                    "示例 · ${sample.date}", annotation = sample.annotation, state = state,
                                )
                            }
                        } else {
                            item("empty") { StatusNotice("从一句话开始", "随口说给爱忆，重要的事，慢慢记好。", state = ComponentState.EMPTY) }
                        }
                    }
                    Destination.MEMORIES -> {
                        item("search") { SearchSection(demo) }
                        if (demo && state != ComponentState.EMPTY) {
                            items(ScreenSamples.memories, key = { it.id }) { sample ->
                                MemoryCard(
                                    if (state == ComponentState.LONG_TEXT) ScreenSamples.longText else sample.text,
                                    "示例 · ${sample.date}", annotation = sample.annotation, state = state,
                                )
                            }
                            item("citation") {
                                Text("AI 帮我找 · 来源示例", style = MaterialTheme.typography.titleLarge)
                                SourceCitationCard("2026-10-10", ScreenSamples.memories.first().text, "示例来源 · 原文版本 1", state = state)
                            }
                        } else {
                            item("empty") { StatusNotice("还没有回忆", "之后可按日期、标签或原文找回记录。搜索功能尚未开放。", state = ComponentState.EMPTY) }
                        }
                    }
                    Destination.TASKS -> {
                        item("pending-title") { Text("待确认", style = MaterialTheme.typography.titleLarge) }
                        if (demo && state != ComponentState.EMPTY) {
                            item("proposal") {
                                TaskProposalCard(
                                    if (state == ComponentState.LONG_TEXT) ScreenSamples.longText else "把旅行照片整理成小册子",
                                    "未定时间 · 需用户确认", "虚构记录 · 版本 1", state = state,
                                )
                            }
                        } else {
                            item("pending-empty") { StatusNotice("没有待确认建议", "建议经过确认后才可成为正式任务。", state = ComponentState.EMPTY) }
                        }
                        item("today-tasks") { Text("今天 · 正式任务 0", style = MaterialTheme.typography.titleLarge) }
                        item("today-empty") { Text("尚未创建正式任务或安排提醒。", style = MaterialTheme.typography.bodyLarge) }
                        item("next") { Text("接下来 · 0", style = MaterialTheme.typography.titleLarge) }
                        item("done") { Text("已完成 · 0", style = MaterialTheme.typography.titleLarge) }
                        item("reminders") { StatusNotice("提醒功能尚未开放", "以后会分别显示任务确认、系统调度和通知权限状态。") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureSection(state: ComponentState, demo: Boolean) {
    // Deliberately memory-only: D02 owns encrypted persisted drafts. No success feedback.
    var text by remember { mutableStateOf("") }
    CaptureComposer(text = text, onTextChange = { text = it }, askAvailable = false, state = state,
        availability = if (demo) "界面示例，不会保存。图片、录音与提问未开放。" else "保存尚未开放。图片、录音与提问未开放。")
}

@Composable
private fun SearchSection(demo: Boolean) {
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Surface(Modifier.fillMaxWidth(), color = AIyouColors.surfaceMuted, shape = MaterialTheme.shapes.medium) {
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().padding(AIyouDimensions.spaceLg)
                .semantics { contentDescription = "搜索回忆，能力尚未开放" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AIyouColors.textPrimary), singleLine = true,
                decorationBox = { field -> Box {
                    if (query.isEmpty()) Text("搜索原文、日期或关键词", style = MaterialTheme.typography.bodyLarge, color = AIyouColors.textSecondary)
                    field()
                } })
        }
        Surface(Modifier.fillMaxWidth(), color = AIyouColors.surfaceMuted, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(AIyouDimensions.spaceMd), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                Text("筛选", style = MaterialTheme.typography.labelLarge, color = AIyouColors.textSecondary)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                    listOf("日期", "人物", "标签", "图片", "收藏").forEach { label ->
                        Surface(color = AIyouColors.surface, shape = MaterialTheme.shapes.small) {
                            TextButton(onClick = {}, enabled = false) { Text(label, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("搜索与筛选尚未开放；输入不会改变${if (demo) "示例" else "记录"}。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
            }
        }
    }
}
