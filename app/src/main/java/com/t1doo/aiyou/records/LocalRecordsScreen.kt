package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.CaptureComposer
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.MemoryCard
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.designsystem.SourceCitationCard
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
    onRequestCaptureDiscard: () -> Unit = {},
    onConfirmCaptureDiscard: (Long) -> Unit = {},
    onCancelCaptureDiscard: (Long) -> Unit = {},
    searchState: OriginalSearchState? = null,
    searchQuery: TextFieldState? = null,
    searchFrom: TextFieldState? = null,
    searchThrough: TextFieldState? = null,
    searchMode: OriginalSearchMode = OriginalSearchMode.KEYWORDS,
    searchDateBasis: com.t1doo.aiyou.model.RecordDateBasis = com.t1doo.aiyou.model.RecordDateBasis.CAPTURED,
    onSearchDateBasis: (com.t1doo.aiyou.model.RecordDateBasis) -> Unit = {},
    onRefreshEventDates: () -> Unit = {},
    searchTag: String? = null,
    onSearchTag: (String?) -> Unit = {},
    searchPerson: String? = null, onSearchPerson: (String?) -> Unit = {}, onRefreshPeople: () -> Unit = {},
    onSearchMode: () -> Unit = {},
    onClearSearch: () -> Unit = {},
    onOpenSearchResult: (OriginalSearchHit) -> Unit = {},
    searchReturnAnchor: OriginalSearchReturnAnchor? = null,
    ownsSearchReturn: () -> Boolean = { false },
    onSearchReturnHandled: () -> Unit = {},
    onBeginExport: (() -> Unit)? = null,
    onOpenRecycleBin: (() -> Unit)? = null,
    onBeginRecordPackage: (() -> Unit)? = null,
    onOpenFactRecap: (() -> Unit)? = null,
    onOpenUnified:(()->Unit)?=null,
    onOpenTranscripts:(()->Unit)?=null,
    onOpenPreferences: (() -> Unit)? = null,
    onOpenMemoryInbox: (() -> Unit)? = null,
    onOpenAcceptedMemories: (() -> Unit)? = null,
    onBeforeSearchInput: (TextFieldState) -> Boolean = { true },
    onHistoryInputCommitted: () -> Unit = {},
    onRebuildLocalSearch: () -> Unit = {},
    onlyFavorites: Boolean = false,
    onSearchFavorites: (Boolean) -> Unit = {},
    onRefreshFavorites: () -> Unit = {},
    onlyImages: Boolean = false,
    onSearchImages: (Boolean) -> Unit = {},
    imageGrid: CurrentImageGridController? = null,
    imageGridMode: Boolean = false,
    onImageGridMode: (Boolean) -> Unit = {},
    ownsImageGrid: () -> Boolean = { false },
    evidenceMode: Boolean = false,
    evidenceState: LocalEvidenceState = LocalEvidenceState(LocalEvidenceStage.WAITING),
    onEvidenceMode: (Boolean) -> Unit = {},
    onOpenEvidence: (LocalEvidenceCard) -> Unit = {},
    onToggleEvidence: (LocalEvidenceCard) -> Unit = {},
    onConfirmEvidence: () -> Unit = {},
    evidenceAnswerState: LocalEvidenceAnswerState = LocalEvidenceAnswerState(LocalEvidenceAnswerStage.UNAVAILABLE),
    evidenceAnswerAvailable: Boolean = false,
    onEvidenceAnswer: () -> Unit = {},
    onCancelEvidenceAnswer: () -> Unit = {},
    onOpenAnswerSource: (com.t1doo.aiyou.model.OriginalTextReference) -> Unit = {},
    evidenceQuestion: (@Composable () -> Unit)? = null,
    onOpenImageTexts:(()->Unit)?=null,
    imageTextBusy:Boolean=false,
    onQuestionKeywords:(()->Unit)?=null,
    onReduceSearchKeywords:((OriginalSearchState)->Boolean)?=null,
) {
    if (destination == Destination.NOW) local.captureDiscard?.let { discard ->
        AlertDialog(
            onDismissRequest = { if (!discard.busy) onCancelCaptureDiscard(discard.requestId) },
            title = { Text(if (discard.recoveryFailed || discard.recovering) "保留这份输入为草稿" else "清空这份未保存的草稿？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (discard.recoveryFailed || discard.recovering) "本机保存确认成功后再返回编辑。"
                    else "清空后无法恢复这份草稿。已保存的记录不会删除。")
                if (discard.busy) Text(if (discard.recovering) "正在保留本机草稿…" else "正在确认本机清空结果…")
                discard.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            } },
            confirmButton = { TextButton(enabled = !discard.busy && !discard.conflict,
                onClick = { onConfirmCaptureDiscard(discard.requestId) }) {
                Text(if (discard.recoveryFailed) "重试保留" else if (discard.error == null) "确认清空" else "重试确认")
            } },
            dismissButton = { TextButton(enabled = !discard.busy,
                onClick = { onCancelCaptureDiscard(discard.requestId) }) {
                Text(if (discard.recoveryFailed) "返回编辑（未保存）" else if (discard.recoveryRequired) "保留输入并返回" else if (discard.error == null) "保留草稿" else "返回编辑")
            } },
        )
    }
    val requestedSearch = searchState?.input?.let { it.onlyImages || it.onlyFavorites || it.dateBasis == com.t1doo.aiyou.model.RecordDateBasis.EVENT || it.tag != null || it.person != null || it.from.isNotEmpty() || it.through.isNotEmpty() ||
        (if (it.mode == OriginalSearchMode.EXACT) it.query.isNotEmpty() else it.query.isNotBlank()) } == true
    // Keep the durable index in memory; a restored record immediately reveals its existing edit draft again.
    val visiblePendingEdits = if (local.supportsRecycleBin) local.pendingEdits.filter { draft -> local.entries.any { it.id == draft.entryId } } else local.pendingEdits
    val filtered = destination == Destination.MEMORIES && requestedSearch && searchState?.stage != OriginalSearchStage.BROWSE
    val searchHits = remember(searchState) { searchState?.hits?.associateBy { it.entry.id }.orEmpty() }
    val displayedEntries = if (destination == Destination.NOW) local.entries.take(5)
        else if (evidenceMode) evidenceState.cards.map { it.hit.entry }
        else if (filtered) searchState?.hits?.map { it.entry }.orEmpty() else local.entries
    val gridEnabled = destination == Destination.MEMORIES && !evidenceMode && onlyImages && imageGridMode && imageGrid != null &&
        searchState?.stage == OriginalSearchStage.READY
    val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
    val gridRows = remember(displayedEntries, columns, gridEnabled) { if (gridEnabled) displayedEntries.chunked(columns) else emptyList() }
    val gridEmission = imageGrid?.state?.collectAsState()?.value
    val gridTiles = gridEmission?.let { searchState?.let { imageGrid?.visible(it)?.tiles } }.orEmpty()
    DisposableEffect(imageGrid, searchState, gridEnabled) {
        onDispose { searchState?.let { imageGrid?.clear(it) } }
    }
    LaunchedEffect(imageGrid, searchState, gridEnabled, listState, gridRows) {
        val controller = imageGrid ?: return@LaunchedEffect
        val owner = searchState ?: return@LaunchedEffect
        if (!gridEnabled) { controller.clear(owner); return@LaunchedEffect }
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.filter { it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset }
                .map { it.key }.toSet()
        }.collect { keys ->
            controller.sync(owner, gridRows.filter { it.first().id in keys }.flatten().map { it.id }, ownsImageGrid)
        }
    }
    var attachedViewport by remember { mutableStateOf(IntSize.Zero) }
    val currentLocal by rememberUpdatedState(local)
    val currentEntries by rememberUpdatedState(displayedEntries)
    val returnObserver = LocalOriginalReadingObserver.current
    val contentKeys = buildList {
        add("summary")
        if (destination == Destination.NOW) add("composer")
        if (local.opening) add("loading")
        if (local.error != null) add("error")
        if (local.error == null && local.saveNotice != null) add("saved")
        if (local.editDraftError != null) add("edit-draft-error")
        addAll(visiblePendingEdits.map { "edit-draft-${it.id}" })
        if (destination == Destination.MEMORIES) add("search")
        if (filtered) add("search-status")
        if (destination == Destination.NOW) add("recent")
        if (local.ready && local.entries.isEmpty()) add("empty")
        if (evidenceMode) add("local-evidence-status")
        addAll(if (gridEnabled) gridRows.map { it.first().id } else displayedEntries.map { it.id })
    }
    LaunchedEffect(searchReturnAnchor, searchState, evidenceState, attachedViewport, contentKeys, returnViewportReady, expectedReturnHeightPx) {
        val anchor = searchReturnAnchor ?: return@LaunchedEffect
        fun observe(stage: String) = returnObserver?.onListReturnStage("search-anchor-$stage",
            searchState?.generation, attachedViewport.height, expectedReturnHeightPx, returnViewportReady, listState)
        observe("evaluate")
        if (destination != Destination.MEMORIES || searchState?.stage != OriginalSearchStage.READY ||
            evidenceMode && evidenceState.stage != LocalEvidenceStage.READY ||
            !ownsSearchReturn() || !returnViewportReady || attachedViewport.height == 0 ||
            (expectedReturnHeightPx != null && attachedViewport.height != expectedReturnHeightPx)) { observe("waiting"); return@LaunchedEffect }
        snapshotFlow { listState.layoutInfo }.first { it.viewportSize == attachedViewport && it.totalItemsCount == contentKeys.size }
        if (!ownsSearchReturn()) return@LaunchedEffect
        val index = contentKeys.indexOf(anchor.key).takeIf { it >= 0 } ?: anchor.index.coerceIn(0, contentKeys.lastIndex)
        observe("layout")
        listState.scrollToItem(index, anchor.offset)
        observe("scrolled")
        if (ownsSearchReturn()) onSearchReturnHandled()
    }
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
    BoxWithConstraints(modifier.fillMaxSize().background(AIyouColors.surface)) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        val stackSummary = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
                .onSizeChanged { attachedViewport = it }, state = listState,
                contentPadding = PaddingValues(horizontal = margin, vertical = AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                item("summary") {
                    if (destination == Destination.MEMORIES && ((onBeginExport != null && local.supportsOriginalExport) ||
                        (onOpenRecycleBin != null && local.supportsRecycleBin) ||
                        (onBeginRecordPackage != null && local.supportsRecordPackages) ||
                        (onOpenFactRecap != null && local.supportsFactRecaps) ||
                        (onOpenUnified != null && local.unifiedKeywordOwner!=null) ||
                        (onOpenTranscripts != null && local.supportsAudioTranscripts) ||
                        (onOpenImageTexts != null && local.supportsImageTexts) ||
                        (onOpenPreferences != null && local.supportsSourcePreferences) ||
                        ((onOpenMemoryInbox != null || onOpenAcceptedMemories != null) && local.supportsMemoryInbox))) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
                            NotebookPageHeader(destination.label, "${local.entries.size} 条本机记录", stackSummary, companion)
                            if(onOpenUnified!=null && local.unifiedKeywordOwner!=null)FilledTonalButton(onOpenUnified,enabled=!imageTextBusy&&local.ready&&!local.opening&&local.error==null,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=AIyouDimensions.minTouchTarget).testTag("unified-open")){Text("统一关键词找回")}
                            val hasReviewTools = (onOpenImageTexts != null && local.supportsImageTexts) ||
                                (onOpenTranscripts != null && local.supportsAudioTranscripts) ||
                                (onOpenPreferences != null && local.supportsSourcePreferences) ||
                                ((onOpenMemoryInbox != null || onOpenAcceptedMemories != null) && local.supportsMemoryInbox) ||
                                (onOpenFactRecap != null && local.supportsFactRecaps) || imageTextBusy
                            val hasManageTools = (onBeginRecordPackage != null && local.supportsRecordPackages) ||
                                (onBeginExport != null && local.supportsOriginalExport) ||
                                (onOpenRecycleBin != null && local.supportsRecycleBin)
                            if (hasReviewTools) {
                            Text("找回与审阅", style=MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            if(onOpenImageTexts!=null && local.supportsImageTexts)TextButton(onOpenImageTexts,enabled=!imageTextBusy&&local.ready&&!local.opening&&local.error==null,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("image-texts-open")){Text("找回图片文字与原图")}
                            if(imageTextBusy)Text("上一图片提取仍在结束，文字入口等待资源释放。")
                            if(onOpenTranscripts!=null && local.supportsAudioTranscripts)TextButton(onOpenTranscripts,enabled=local.ready&&!local.opening&&local.error==null,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("transcripts-open")){Text("找回人工转写与原音")}
                            if (onOpenPreferences != null && local.supportsSourcePreferences) TextButton(onOpenPreferences,
                                enabled = local.ready && !local.opening && local.error == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("preferences-open")) { Text("普通偏好自述与人工审阅") }
                            if (onOpenMemoryInbox != null && local.supportsMemoryInbox) TextButton(onOpenMemoryInbox,
                                enabled = local.ready && !local.opening && local.error == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-inbox-open")) { Text("待审事件与情绪") }
                            if (onOpenAcceptedMemories != null && local.supportsMemoryInbox) TextButton(onOpenAcceptedMemories,
                                enabled = local.ready && !local.opening && local.error == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("memory-accepted-open")) { Text("找回已采纳事件与情绪") }
                            if(onOpenFactRecap!=null && local.supportsFactRecaps) TextButton(onOpenFactRecap,
                                enabled=local.ready && !local.opening && local.error==null,
                                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("recap-open")){Text("生成本机日／周回顾")}
                            }
                            }
                            if (hasManageTools) {
                            HorizontalDivider(color=AIyouColors.outlineDecorative)
                            Text("资料管理", style=MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            if (onBeginRecordPackage != null && local.supportsRecordPackages) TextButton(
                                onClick = onBeginRecordPackage,
                                enabled = local.ready && !local.opening && local.error == null && !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("package-open")) { Text("加密原文包与恢复") }
                            if (onBeginExport != null && local.supportsOriginalExport) TextButton(onClick = onBeginExport, enabled = local.ready && !local.opening && local.error == null &&
                                !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("export-open")) { Text("导出原文、待办与草稿") }
                            if (onOpenRecycleBin != null && local.supportsRecycleBin) TextButton(onClick = onOpenRecycleBin,
                                enabled = local.ready && !local.opening && local.error == null && !local.saving && !local.savePending && local.originalExport == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("recycle-open")) { Text("回收站") }
                            }
                            }
                            local.recycleNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    } else NotebookPageHeader(destination.label,
                        if (destination == Destination.MEMORIES) "${local.entries.size} 条本机记录" else "随手记下一点生活",
                        stackSummary, companion)
                }
                if (destination == Destination.NOW) item("composer") {
                    CaptureComposer(
                        text = local.text, onTextChange = onDraftChange,
                        onSubmit = if (local.ready && !local.saving && local.captureDiscard == null) onSave else null,
                        askAvailable = false,
                        state = when { local.opening || local.saving -> ComponentState.LOADING
                            !local.ready || local.savePending || local.captureDiscard?.busy == true -> ComponentState.DISABLED
                            else -> ComponentState.NORMAL },
                        availability = "${local.draftNotice} · 仅本机保存\n提问、图片和录音尚未开放。",
                        showUnavailableControls = false,
                        maxTextLength = EncryptedEntryRepository.MAX_TEXT_LENGTH,
                        inputError = local.inputNotice,
                    )
                    if (local.text.isNotEmpty()) TextButton(onClick = onRequestCaptureDiscard,
                        enabled = local.ready && !local.opening && !local.saving && !local.savePending && local.captureDiscard == null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("清空草稿") }
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
                items(visiblePendingEdits, key = { "edit-draft-${it.id}" }) { draft ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(if (local.entries.any { it.id == draft.entryId }) "有未保存的原文修改 · 基于版本 ${draft.baseRevision}"
                            else "有未保存的修改 · 源记录已不可用", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onResumeEdit(draft.entryId, draft.id) },
                            enabled = local.ready && !local.opening && local.error == null && local.editDraftIndexAvailable && !local.saving && !local.savePending &&
                                (!local.supportsRecycleBin || local.entries.any { it.id == draft.entryId }),
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("resume-edit-${draft.entryId}")) {
                            Text("恢复编辑草稿")
                        }
                    }
                }
                if (destination == Destination.MEMORIES) item("search") {
                    if (searchState != null && searchQuery != null && searchFrom != null && searchThrough != null)
                        LocalOriginalSearchSection(searchQuery, searchFrom, searchThrough, searchMode,
                            local.ready && !local.opening && local.error == null, onSearchMode, onClearSearch,
                            searchTag, local.manualTags, local.manualTagsAvailable, local.manualTagsError, onSearchTag,
                        person = searchPerson, peopleSupported = local.supportsManualPeople,
                        peopleAvailable = local.manualPeopleAvailable, peopleError = local.manualPeopleError,
                        onPerson = onSearchPerson, onRefreshPeople = onRefreshPeople,
                        onBeforeSearchInput = onBeforeSearchInput,
                        onHistoryInputCommitted = onHistoryInputCommitted,
                            indexSupported = local.localSearch != null, indexBusy = local.localIndexBusy,
                            indexError = local.localIndexError, onRebuild = onRebuildLocalSearch,
                            favoritesSupported = local.supportsFavorites, favoritesAvailable = local.favoritesAvailable,
                            favoritesLoading = local.favoritesLoading, favoritesError = local.favoritesError,
                            onlyFavorites = onlyFavorites, onFavorites = onSearchFavorites, onRefreshFavorites = onRefreshFavorites,
                            onlyImages = onlyImages, onImages = onSearchImages,
                            dateBasis = searchDateBasis, onDateBasis = onSearchDateBasis,
                            eventDatesAvailable = local.eventDatesAvailable, eventDatesError = local.eventDatesError,
                            onRefreshEventDates = onRefreshEventDates,onQuestionKeywords=onQuestionKeywords)
                    else SearchSection(demo = false)
                    if (local.localSearch != null) TextButton(onClick = { onEvidenceMode(!evidenceMode) },
                        enabled = local.ready && !local.opening && local.error == null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-evidence-mode")) {
                        Text(if (evidenceMode) "本机出处候选 · 返回原文列表" else "为当前问题选取本机出处候选")
                    }
                    if (onlyImages && imageGrid != null) TextButton(onClick = { onImageGridMode(!imageGridMode) },
                        enabled = local.ready && !local.opening && local.error == null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-grid")) {
                        Text(if (imageGridMode) "图片网格 · 切换列表" else "切换图片网格")
                    }
                }
                if (filtered) item("search-status") {
                    when (searchState?.stage) {
                        OriginalSearchStage.RUNNING -> StatusNotice("正在查找本机原文", "查找在后台进行。", state = ComponentState.LOADING)
                        OriginalSearchStage.READY -> StatusNotice(if (searchState.hits.isEmpty()) {
                            if (onlyFavorites && !onlyImages && searchState.input.dateBasis == com.t1doo.aiyou.model.RecordDateBasis.CAPTURED && searchState.input.query.isEmpty() && searchState.input.tag == null && searchState.input.from.isEmpty() && searchState.input.through.isEmpty()) "还没有收藏记录" else "没有匹配的原文"
                        } else "找到 ${searchState.hits.size} 条原文",
                            if (searchState.hits.isEmpty()) { if (onlyImages) "可以关闭图片筛选，或调整其他查找条件。" else if (onlyFavorites) "可以切换全部记录，或在原文页收藏记录。" else "试试更短的关键词或调整日期。" } else
                                searchState.message ?: "摘录来自当前版本，可以打开全文。",
                            state = if (searchState.hits.isEmpty()) ComponentState.EMPTY else ComponentState.NORMAL)
                        OriginalSearchStage.INVALID -> StatusNotice("请检查查找条件", searchState.message.orEmpty(), state = ComponentState.ERROR)
                        OriginalSearchStage.FAILED -> StatusNotice("本机查找需要处理", searchState.message.orEmpty(), state = ComponentState.ERROR)
                        else -> StatusNotice("等待本机原文", "原文可读取后再显示查找结果。", state = ComponentState.LOADING)
                    }
                    if(searchState!=null)onReduceSearchKeywords?.let{open->
                        KeywordReductionAction(searchState,local.ready&&!local.opening&&local.error==null,open)
                    }
                }
                if (destination == Destination.NOW) item("recent") { Text("最近记录", style = MaterialTheme.typography.titleLarge) }
                if (local.ready && local.entries.isEmpty()) item("empty") {
                    StatusNotice(if (destination == Destination.NOW) "从一句话开始" else "还没有回忆", "文字保存在本机，无需配置模型。", state = ComponentState.EMPTY)
                }
                if (evidenceMode) {
                    item("local-evidence-status") {
                        Column {
                            LocalEvidenceNotice(evidenceState, onConfirmEvidence)
                            LocalEvidenceAnswerSection(evidenceState, evidenceAnswerState, evidenceAnswerAvailable,
                                onEvidenceAnswer, onCancelEvidenceAnswer, onOpenAnswerSource)
                            evidenceQuestion?.invoke()
                        }
                    }
                    items(evidenceState.cards, key = { it.reference.source.sourceId }) { card ->
                        Column {
                            val selected = evidenceState.selection.cards.any { it === card }
                            Row(Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                                .testTag("local-evidence-select-${card.reference.source.sourceId}")
                                .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggleEvidence(card) }),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected, onCheckedChange = null)
                                Text(if (selected) "已选为本次出处" else "未选入本次出处")
                            }
                            SourceCitationCard(originalMetadata(card.hit.entry), card.reference.quote,
                                "原文版本 ${card.reference.source.revision} · Unicode 码点 [${card.reference.start}, ${card.reference.end})",
                                modifier = Modifier.testTag("local-evidence-${card.reference.source.sourceId}"),
                                onOpen = { onOpenEvidence(card) })
                        }
                    }
                } else if (gridEnabled) items(gridRows, key = { it.first().id }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        row.forEach { entry ->
                            val hit = searchHits.getValue(entry.id)
                            CurrentImageGridCard(hit, gridTiles[entry.id], Modifier.weight(1f)
                                .semantics { selected = entry.id == selectedEntryId },
                                onOpen = { onOpenSearchResult(hit) })
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                } else items(displayedEntries, key = { it.id }) { entry ->
                    val hit = if (filtered) searchHits[entry.id] else null
                    val preview = remember(entry, hit) { hit?.let { originalSearchPreview(entry, it) } }
                    Column {
                    hit?.let { OriginalSearchTagMatch(it) }
                    MemoryCard(preview?.text ?: originalPreview(entry.originalText), originalMetadata(entry), policy = entry.policy,
                        modifier = Modifier.testTag("entry-${entry.id}").semantics { selected = entry.id == selectedEntryId }
                            .then(if (entry.id == selectedEntryId) Modifier.border(AIyouDimensions.iconStroke,
                                AIyouColors.primary, MaterialTheme.shapes.medium) else Modifier),
                        onOpen = { if (hit != null) onOpenSearchResult(hit) else onOpenEntry(entry.id) },
                        highlight = preview?.takeIf { it.highlightEnd > it.highlightStart }?.let {
                            androidx.compose.ui.text.TextRange(it.highlightStart, it.highlightEnd)
                        })
                    }
                }
            }
        }
    }
}
