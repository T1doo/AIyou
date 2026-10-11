package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.CaptureComposer
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.MemoryCard
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.LocalProcessingStatus
import com.t1doo.aiyou.ui.SearchSection

@Composable
fun LocalRecordsScreen(
    destination: Destination,
    local: LocalRecordUiState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    selectedEntryId: String? = null,
    onOpenEntry: (String) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize().drawBehind {
        drawRect(Brush.verticalGradient(
            colors = listOf(AIyouColors.primaryContainer, AIyouColors.background),
            endY = (AIyouDimensions.composerRadius * 16).toPx(),
        ))
    }) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        val stackSummary = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize(), state = listState,
                contentPadding = PaddingValues(horizontal = margin, vertical = AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                item("summary") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            Text(destination.label, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                            if (!stackSummary) LocalProcessingStatus()
                        }
                        Text(if (destination == Destination.MEMORIES) "${local.entries.size} 条本机记录" else "随手记下一点生活",
                            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                        if (stackSummary) LocalProcessingStatus()
                    }
                }
                if (destination == Destination.NOW) item("composer") {
                    CaptureComposer(
                        text = local.text, onTextChange = onDraftChange,
                        onSubmit = if (local.ready && !local.saving) onSave else null,
                        askAvailable = false,
                        state = when { local.opening || local.saving -> ComponentState.LOADING
                            !local.ready || local.savePending -> ComponentState.DISABLED
                            else -> ComponentState.NORMAL },
                        availability = "${local.draftNotice} · 仅本机保存\n提问、图片和录音尚未开放。",
                        showUnavailableControls = false,
                        maxTextLength = EncryptedEntryRepository.MAX_TEXT_LENGTH,
                        inputError = local.inputNotice,
                    )
                }
                if (local.opening) item("loading") { StatusNotice("正在读取本机记录", "记录和草稿来自加密数据库。", state = ComponentState.LOADING) }
                local.error?.let { message -> item("error") {
                    StatusNotice("本机存储需要处理", message, state = ComponentState.ERROR,
                        actionLabel = "重试", onAction = onRetry)
                } }
                if (local.error == null) local.saveNotice?.let { message -> item("saved") { StatusNotice(message, "可以在回忆中查看原文；尚未执行 AI 整理。") } }
                if (destination == Destination.MEMORIES) item("search") { SearchSection(demo = false) }
                if (destination == Destination.NOW) item("recent") { Text("最近记录", style = MaterialTheme.typography.titleLarge) }
                if (local.ready && local.entries.isEmpty()) item("empty") {
                    StatusNotice(if (destination == Destination.NOW) "从一句话开始" else "还没有回忆", "文字保存在本机，无需配置模型。", state = ComponentState.EMPTY)
                }
                items(if (destination == Destination.NOW) local.entries.take(5) else local.entries, key = { it.id }) { entry ->
                    val preview = remember(entry.originalText) { originalPreview(entry.originalText) }
                    MemoryCard(preview, originalMetadata(entry), policy = entry.policy,
                        modifier = Modifier.testTag("entry-${entry.id}").semantics { selected = entry.id == selectedEntryId }
                            .then(if (entry.id == selectedEntryId) Modifier.border(AIyouDimensions.iconStroke,
                                AIyouColors.primary, MaterialTheme.shapes.medium) else Modifier),
                        onOpen = { onOpenEntry(entry.id) })
                }
            }
        }
    }
}
