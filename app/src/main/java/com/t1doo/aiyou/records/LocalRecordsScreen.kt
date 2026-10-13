package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
import com.t1doo.aiyou.ui.SearchSection
import com.t1doo.aiyou.ui.NotebookPageHeader
import kotlinx.coroutines.flow.first

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
    tailReturnEntryId: String? = null,
    tailReturnGeneration: Long? = null,
    expectedReturnHeightPx: Int? = null,
    returnViewportReady: Boolean = true,
    ownsTailReturn: () -> Boolean = { false },
    onTailReturnHandled: () -> Unit = {},
    onResumeEdit: (String, String) -> Unit = { _, _ -> },
    companion: (@Composable () -> Unit)? = null,
) {
    val displayedEntries = if (destination == Destination.NOW) local.entries.take(5) else local.entries
    var attachedViewport by remember { mutableStateOf(IntSize.Zero) }
    val currentLocal by rememberUpdatedState(local)
    val currentEntries by rememberUpdatedState(displayedEntries)
    val returnObserver = LocalOriginalReadingObserver.current
    LaunchedEffect(destination, tailReturnEntryId, tailReturnGeneration, attachedViewport, displayedEntries,
        expectedReturnHeightPx, returnViewportReady, local.ready, local.opening, local.error, local.saveNotice) {
        val target = tailReturnEntryId ?: return@LaunchedEffect
        fun observe(stage: String) = returnObserver?.onListReturnStage(stage, tailReturnGeneration,
            attachedViewport.height, expectedReturnHeightPx, returnViewportReady, listState)
        // Owner/ack closures belong to this immutable generation, including same-ID reopens.
        fun valid() = ownsTailReturn() && currentLocal.ready && !currentLocal.opening && currentLocal.error == null &&
            currentEntries.lastOrNull()?.id == target
        observe("request")
        if (!valid() || !returnViewportReady || attachedViewport.height == 0 ||
            (expectedReturnHeightPx != null && attachedViewport.height != expectedReturnHeightPx)) return@LaunchedEffect
        // This attachment must have measured its actual viewport before using its item count.
        val measured = snapshotFlow { listState.layoutInfo }.first {
            it.viewportSize == attachedViewport && it.totalItemsCount > 0
        }
        if (!valid()) return@LaunchedEffect
        observe("measured")
        listState.scrollToItem(measured.totalItemsCount - 1)
        if (!valid()) return@LaunchedEffect
        observe("last-item")
        var result = listState.layoutInfo
        var last = result.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (last.key != target) return@LaunchedEffect
        if (listState.canScrollForward) {
            // A card can fit the raw viewport while its content paddings still need scrolling.
            // Use this measured card size, then let the real end-of-content clamp align the tail.
            listState.scrollToItem(last.index, last.size)
            if (!valid()) return@LaunchedEffect
            observe("tail-padding")
            result = listState.layoutInfo
            last = result.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        }
        val fits = last.size <= result.viewportEndOffset - result.viewportStartOffset
        if (result.viewportSize == attachedViewport && last.key == target && !listState.canScrollForward &&
            (!fits || (last.offset >= result.viewportStartOffset && last.offset + last.size <= result.viewportEndOffset))) {
            observe("complete")
            onTailReturnHandled()
        }
        // A changed dataset/viewport can re-evaluate a still-pending request; never busy-retry.
    }
    BoxWithConstraints(modifier.fillMaxSize().drawBehind {
        drawRect(Brush.verticalGradient(
            colors = listOf(AIyouColors.primaryContainer, AIyouColors.background),
            endY = (AIyouDimensions.composerRadius * 16).toPx(),
        ))
    }) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        val stackSummary = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
                .onSizeChanged { attachedViewport = it }, state = listState,
                contentPadding = PaddingValues(horizontal = margin, vertical = AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                item("summary") {
                    NotebookPageHeader(destination.label,
                        if (destination == Destination.MEMORIES) "${local.entries.size} 条本机记录" else "随手记下一点生活",
                        stackSummary, companion)
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
                local.editDraftError?.let { message -> item("edit-draft-error") {
                    StatusNotice(if (local.editDraftIndexAvailable && local.editDraftIssueCount > 0) "${local.editDraftIssueCount} 份编辑草稿无法读取" else "编辑草稿需要处理", message, state = ComponentState.ERROR, actionLabel = "重试读取", onAction = onRetry)
                } }
                items(local.pendingEdits, key = { "edit-draft-${it.id}" }) { draft ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(if (local.entries.any { it.id == draft.entryId }) "有未保存的原文修改 · 基于版本 ${draft.baseRevision}"
                            else "有未保存的修改 · 源记录已不可用", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onResumeEdit(draft.entryId, draft.id) },
                            enabled = local.ready && !local.opening && local.error == null && local.editDraftIndexAvailable && !local.saving && !local.savePending,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("resume-edit-${draft.entryId}")) {
                            Text("恢复编辑草稿")
                        }
                    }
                }
                if (destination == Destination.MEMORIES) item("search") { SearchSection(demo = false) }
                if (destination == Destination.NOW) item("recent") { Text("最近记录", style = MaterialTheme.typography.titleLarge) }
                if (local.ready && local.entries.isEmpty()) item("empty") {
                    StatusNotice(if (destination == Destination.NOW) "从一句话开始" else "还没有回忆", "文字保存在本机，无需配置模型。", state = ComponentState.EMPTY)
                }
                items(displayedEntries, key = { it.id }) { entry ->
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
