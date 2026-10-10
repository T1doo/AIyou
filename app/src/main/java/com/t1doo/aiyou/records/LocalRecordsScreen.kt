package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.CaptureComposer
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.MemoryCard
import com.t1doo.aiyou.designsystem.PrivacyChip
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.SearchSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LocalRecordsScreen(
    destination: Destination,
    local: LocalRecordUiState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(margin),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
            item("summary") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                    Text(destination.label, style = MaterialTheme.typography.headlineMedium)
                    Text(if (destination == Destination.MEMORIES) "${local.entries.size} 条本机记录" else "随手记下一点生活",
                        Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    PrivacyChip(ProcessingPolicy.LOCAL_ONLY)
                }
            }
            if (local.opening) item("loading") { StatusNotice("正在读取本机记录", "记录和草稿来自加密数据库。", state = ComponentState.LOADING) }
            local.error?.let { message -> item("error") {
                StatusNotice("本机存储需要处理", message, state = ComponentState.ERROR)
                OutlinedButton(onClick = onRetry) { Text("重试") }
            } }
            local.saveNotice?.let { message -> item("saved") { StatusNotice(message, "可以在回忆中查看原文；尚未执行 AI 整理。") } }
            if (destination == Destination.NOW) item("composer") {
                CaptureComposer(
                    text = local.text, onTextChange = onDraftChange,
                    onSubmit = if (local.ready && !local.saving) onSave else null,
                    askAvailable = false,
                    state = when { local.opening || local.saving -> ComponentState.LOADING
                        !local.ready || local.savePending -> ComponentState.DISABLED
                        local.error != null -> ComponentState.ERROR
                        else -> ComponentState.NORMAL },
                    availability = "${local.draftNotice}。文字仅在本机保存；提问、图片和录音尚未开放。",
                )
            }
            if (destination == Destination.MEMORIES) item("search") { SearchSection(demo = false) }
            if (destination == Destination.NOW) item("recent") { Text("最近记录", style = MaterialTheme.typography.titleLarge) }
            if (local.ready && local.entries.isEmpty()) item("empty") {
                StatusNotice(if (destination == Destination.NOW) "从一句话开始" else "还没有回忆", "文字保存在本机，无需配置模型。", state = ComponentState.EMPTY)
            }
            items(if (destination == Destination.NOW) local.entries.take(5) else local.entries, key = { it.id }) { entry ->
                val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(entry.capturedAtMillis).atZone(ZoneId.of(entry.captureTimeZone)))
                MemoryCard(entry.originalText, "$date · 原文版本 ${entry.currentRevision}", policy = entry.policy)
            }
        }
    }
}
