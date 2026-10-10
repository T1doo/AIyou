package com.t1doo.aiyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        val margin = if (maxWidth < AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(margin),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceLg),
            ) {
                item("title") { Text(destination.label, style = MaterialTheme.typography.headlineMedium) }
                item("privacy") { PrivacyChip(ProcessingPolicy.LOCAL_ONLY) }
                if (demo) {
                    item("demo") { StatusNotice("虚构示例", "以下记录、AI 内容与任务状态均为界面示例。没有保存记录或安排提醒。") }
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
                        item("today") {
                            Text(if (demo) "今天 · 示例日期 2026-10-10" else "今天", style = MaterialTheme.typography.titleLarge)
                            MascotBadge()
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
                        item("composer") { CaptureSection(state) }
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
private fun CaptureSection(state: ComponentState) {
    // Deliberately memory-only: D02 owns encrypted persisted drafts. No success feedback.
    var text by remember { mutableStateOf("") }
    var askMode by remember { mutableStateOf(false) }
    CaptureComposer(text = text, onTextChange = { text = it }, askMode = askMode, onModeChange = { askMode = it }, state = state)
}

@Composable
private fun SearchSection(demo: Boolean) {
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索回忆") }, placeholder = { Text("上个月让我开心的事") },
        )
        Text("搜索尚未开放；输入不会改变下方${if (demo) "示例" else "空白"}内容。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            listOf("日期", "人物", "标签", "图片", "收藏").forEach { label ->
                OutlinedButton(onClick = {}, enabled = false) { Text(label) }
            }
        }
    }
}
