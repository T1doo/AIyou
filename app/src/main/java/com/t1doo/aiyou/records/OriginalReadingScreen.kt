package com.t1doo.aiyou.records

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.PrivacyChip
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.OriginalExports
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Memory-only source binding/offsets. No original/query text or Saver. */
data class OriginalReadingLocation(val entryId: String, val revision: Int, val contentHash: String,
    val startCodePoint: Int, val endCodePoint: Int) {
    override fun toString() = "OriginalReadingLocation(redacted)"
}

/** Bounded presentation excerpt only. Never passed to a repository or clipboard. */
internal fun originalPreview(text: String): String {
    if (text.isBlank()) return "图片记录 · 无文字正文"
    var end = 0
    var points = 0
    while (end < text.length && points < 240) {
        end += Character.charCount(Character.codePointAt(text, end))
        points++
    }
    return text.substring(0, end) + if (end < text.length) "…" else ""
}

internal fun originalMetadata(entry: StoredEntry): String {
    val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
        Instant.ofEpochMilli(entry.capturedAtMillis).atZone(ZoneId.of(entry.captureTimeZone)))
    return "$date · 原文版本 ${entry.currentRevision}"
}

/** Copy occurs only on an explicit user action after the app validates the current ID/revision. */
internal fun copyOriginal(context: Context, text: String): Boolean {
    val clip = ClipData.newPlainText("爱忆原文", text)
    markSensitive(clip)
    return try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    } catch (_: SecurityException) {
        false
    }
}

private fun markSensitive(clip: ClipData) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
}

/** The native selection menu follows the same current-record gate as explicit full copy. */
private class ReadingClipboard(
    private val platform: Clipboard,
    private val isCurrent: () -> Boolean,
    private val onDenied: () -> Unit,
    private val onCopied: () -> Unit,
) : Clipboard by platform {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (!isCurrent() || clipEntry == null) return
        markSensitive(clipEntry.clipData)
        try {
            platform.setClipEntry(clipEntry)
            if (isCurrent()) onCopied()
        } catch (_: SecurityException) {
            if (isCurrent()) onDenied()
        }
    }
}

@Composable
fun OriginalReadingScreen(
    entryId: String,
    local: LocalRecordUiState,
    onRetry: () -> Unit,
    onCopy: (String, Int) -> Boolean,
    canCopy: (String, Int) -> Boolean,
    modifier: Modifier = Modifier,
    onEdit: ((String, Int) -> Unit)? = null,
    onHistory: ((String, Int) -> Unit)? = null,
    metadataText: String? = null,
    historyActionLabel: String = "历史版本",
    editActionLabel: String = "编辑原文",
    onAttachments: ((String, Int) -> Unit)? = null,
    originalScrollState: ScrollState? = null,
    onOpenAi: ((String, Int) -> Unit)? = null,
    onStartAi: (Long) -> Unit = {},
    onStopAi: (Long) -> Unit = {},
    onCloseAi: (Long) -> Unit = {},
    onOpenTags: ((String, Int) -> Unit)? = null,
    onMoveToRecycleBin: ((String, Int) -> Unit)? = null,
    location: OriginalReadingLocation? = null,
    requiredSource: OriginalReadingLocation? = null,
    requiredSourceTitle: String = "回顾来源版本已变化",
    requiredSourceMessage: String = "请返回回顾并重新生成，没有将新版本冒充旧摘录。",
    onSetFavorite: ((String, Int, String, Long, Boolean) -> Unit)? = null,
    onRetryFavorite: (String) -> Unit = {},
    onRefreshFavorites: () -> Unit = {},
    candidateController: LocalCandidateController? = null,
    sourceTagController: SourceTagController? = null,
    sourceMemoryController: SourceMemoryController? = null,
    localProcessingController: LocalProcessingController? = null,
    sourceTaskController: SourceTaskController? = null,
    canUseSourceTask: () -> Boolean = { false },
    canUseCandidates: (String, Int) -> Boolean = { _, _ -> false },
    onCandidateLocation: (OriginalReadingLocation) -> Unit = {},
) {
    // Resolve the current local source on every update; navigation never carries plaintext.
    val entry = local.entries.firstOrNull { it.id == entryId }
    var copyDenied by remember(entry?.id, entry?.currentRevision) { mutableStateOf(false) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
            .padding(AIyouDimensions.pageNarrow), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            when {
                local.opening -> StatusNotice("正在读取原文", "请稍候。", state = ComponentState.LOADING,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
                !local.ready || local.error != null -> StatusNotice("原文暂不可读取", local.error ?: "请返回列表，等待本机记录读取完成。",
                    state = ComponentState.ERROR, actionLabel = if (local.error != null) "重试" else null, onAction = onRetry,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
                entry == null -> StatusNotice("这条记录已不可用", local.recycleNotice ?: "它可能已被移除。返回列表查看当前记录。", state = ComponentState.EMPTY,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
                requiredSource!=null && (entry.id!=requiredSource.entryId || entry.currentRevision!=requiredSource.revision ||
                    OriginalExports.hash(entry.originalText)!=requiredSource.contentHash) -> StatusNotice(requiredSourceTitle,
                    requiredSourceMessage,state=ComponentState.ERROR,
                    modifier=Modifier.verticalScroll(rememberScrollState()))
                else -> {
                    Text(metadataText ?: originalMetadata(entry), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    PrivacyChip(entry.policy)
                    local.packageOrigins[entry.id]?.takeIf { local.packageOriginsAvailable }?.let { origin ->
                        Text("加密包恢复副本 · 原文版本 " + origin.sourceRevision + " · 包创建于 " +
                            java.time.Instant.ofEpochMilli(origin.packageCreatedAtMillis).atZone(java.time.ZoneId.of(entry.captureTimeZone))
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日")),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("package-provenance"))
                    }
                    if (!local.packageOriginsAvailable) Text(local.packageOriginsError ?: "恢复来源暂不可核对。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("长按选择文字，或复制全文。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    local.originalEditNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AIyouColors.success) }
                    if (local.supportsFavorites) {
                        val favorite = local.favorites[entry.id]
                        val writing = local.favoriteWrites[entry.id]
                        val sourceHash = remember(entry.originalText) { OriginalExports.hash(entry.originalText) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            TextButton(onClick = {
                                favorite?.let { onSetFavorite?.invoke(entry.id, entry.currentRevision, sourceHash, it.version, !it.isFavorite) }
                            }, enabled = onSetFavorite != null && local.favoritesAvailable && favorite != null &&
                                !local.favoritesLoading && writing == null && !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-favorite-set")) {
                                Text(if (favorite?.isFavorite == true && local.favoritesAvailable) "取消收藏" else "收藏")
                            }
                            if (writing != null && !writing.busy) TextButton(onClick = { onRetryFavorite(entry.id) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-favorite-retry")) {
                                Text(if (writing.conflict) "重新读取收藏" else "重试确认收藏")
                            }
                            else if ((!local.favoritesAvailable || favorite == null) && !local.favoritesLoading) TextButton(onClick = onRefreshFavorites,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-favorite-refresh")) { Text("重新读取收藏") }
                        }
                        if (writing?.busy == true || local.favoritesLoading) Text("正在确认本机收藏…", style = MaterialTheme.typography.bodySmall)
                        val error = writing?.error ?: local.favoritesError
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("original-favorite-error")) }
                        local.favoriteNotice?.takeIf { local.favoritesAvailable && local.favoriteNoticeReceipt?.let { receipt ->
                            receipt.entryId == entry.id && favorite?.let { it.version == receipt.version && it.isFavorite == receipt.isFavorite } == true
                        } == true }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("original-favorite-notice")) }
                    }
                    // Keep detail actions reachable without consuming the full original viewport.
                    FlowRow(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                        .testTag("original-actions"), horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        TextButton(onClick = {
                            if (canCopy(entry.id, entry.currentRevision)) copyDenied = !onCopy(entry.id, entry.currentRevision)
                        },
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("复制全文") }
                        onEdit?.let { edit ->
                            TextButton(onClick = { edit(entry.id, entry.currentRevision) },
                                enabled = entry.originalText.isNotBlank() && !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-reading-edit")) { Text(editActionLabel) }
                        }
                        onHistory?.let { history ->
                            TextButton(onClick = { history(entry.id, entry.currentRevision) },
                                enabled = !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text(historyActionLabel) }
                        }
                        onAttachments?.let { attachments ->
                            TextButton(onClick = { attachments(entry.id, entry.currentRevision) },
                                enabled = !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("查看附件") }
                        }
                        onOpenTags?.let { openTags ->
                            TextButton(onClick = { openTags(entry.id, entry.currentRevision) },
                                enabled = !local.saving && !local.savePending,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-manual-tags")) { Text("手工标签") }
                        }
                        onMoveToRecycleBin?.let { move ->
                            TextButton(onClick = { move(entry.id, entry.currentRevision) },
                                enabled = local.supportsRecycleBin && !local.saving && !local.savePending && local.recycleBin == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-recycle-move")) { Text("移入回收站") }
                        }
                        sourceTaskController?.let { controller ->
                            TextButton(enabled=local.supportsSourceTasks&&!local.saving&&!local.savePending&&entry.originalText.isNotBlank(),
                                onClick={if(canUseSourceTask()) controller.openOriginal(entry.id,entry.currentRevision)},
                                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("original-source-task")) { Text("从原文摘录创建待办") }
                        }
                        localProcessingController?.let { controller ->
                            TextButton(enabled = local.supportsLocalProcessing && !local.saving && !local.savePending,
                                onClick = { if (canUseCandidates(entry.id, entry.currentRevision)) controller.open(entry.id, entry.currentRevision) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-local-processing")) { Text("处理范围") }
                        }
                        onOpenAi?.let { openAi ->
                            TextButton(onClick = { openAi(entry.id, entry.currentRevision) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                                    .testTag("original-reading-ai")) { Text("AI 整理") }
                        }
                        candidateController?.let { controller ->
                            TextButton(enabled = local.supportsCandidates && !local.saving && !local.savePending &&
                                entry.originalText.isNotBlank() && entry.policy != com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE,
                                onClick = { if (canUseCandidates(entry.id, entry.currentRevision)) controller.open(entry.id, entry.currentRevision) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-local-candidates")) { Text("本机摘录候选") }
                        }
                        sourceTagController?.let { controller ->
                            TextButton(enabled = local.supportsSourceTags && !local.saving && !local.savePending &&
                                entry.originalText.isNotBlank() && entry.policy != com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE,
                                onClick = { if (canUseCandidates(entry.id, entry.currentRevision)) controller.open(entry.id, entry.currentRevision) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-source-tags")) { Text("从原文添加标签") }
                        }
                        sourceMemoryController?.let { controller ->
                            TextButton(enabled = local.supportsSourceMemories && !local.saving && !local.savePending && entry.originalText.isNotBlank() && entry.policy != com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE,
                                onClick = { if (canUseCandidates(entry.id, entry.currentRevision)) controller.open(entry.id, entry.currentRevision) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-source-memories")) { Text("事件与当次情绪") }
                        }
                    }
                    local.localProcessing?.takeIf { it.source.sourceId == entry.id && it.source.revision == entry.currentRevision.toLong() }?.let { ui ->
                        localProcessingController?.let { controller ->
                            LocalProcessingDialog(ui, entry, controller) { canUseCandidates(entry.id, entry.currentRevision) }
                        }
                    }
                    local.candidates?.takeIf { it.source.sourceId == entry.id && it.source.revision == entry.currentRevision.toLong() }?.let { ui ->
                        candidateController?.let { controller ->
                            LocalCandidateDialog(ui, controller, { canUseCandidates(entry.id, entry.currentRevision) }, onCandidateLocation,
                                onTask = if(sourceTaskController==null||!local.supportsSourceTasks) null else { binding ->
                                    if(canUseSourceTask()) { controller.close(ui.session);sourceTaskController.open(binding) }
                                })
                        }
                    }
                    local.sourceTags?.takeIf { it.source.sourceId == entry.id && it.source.revision == entry.currentRevision.toLong() }?.let { ui ->
                        sourceTagController?.let { controller ->
                            SourceTagDialog(ui, controller, { canUseCandidates(entry.id, entry.currentRevision) }, onCandidateLocation,
                                { onOpenTags?.invoke(entry.id, entry.currentRevision) })
                        }
                    }
                    local.sourceMemories?.takeIf { it.source.sourceId == entry.id && it.source.revision == entry.currentRevision.toLong() }?.let { ui ->
                        sourceMemoryController?.let { controller ->
                            SourceMemoryDialog(ui, controller, { canUseCandidates(entry.id, entry.currentRevision) }, onCandidateLocation)
                        }
                    }
                    local.ai?.takeIf { it.source.sourceId == entry.id &&
                        it.source.revision == entry.currentRevision.toLong() }?.let { selected ->
                        RecordAiSection(selected, onStartAi, onStopAi, onCloseAi)
                    }
                    if (copyDenied) Text("无法复制，原文已保留。", color = AIyouColors.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (entry.originalText.isBlank()) Text("图片记录 · 无文字正文；请打开附件查看原图。")
                    key(entry.id, entry.currentRevision) {
                        val id = entry.id
                        val revision = entry.currentRevision
                        val platform = LocalClipboard.current
                        val currentGate by rememberUpdatedState(canCopy)
                        val clipboard = remember(platform, id, revision) {
                            ReadingClipboard(platform, isCurrent = { currentGate(id, revision) },
                                onDenied = { copyDenied = true }, onCopied = { copyDenied = false })
                        }
                        // SelectionState's Saver includes selected plaintext. Keep it memory-only.
                        val selection = remember(entry.originalText) { SelectionState() }
                        LocalOriginalReadingObserver.current?.let { observer ->
                            SideEffect { observer.onClipboard(clipboard); observer.onSelection(selection) }
                        }
                        val scroll = originalScrollState ?: rememberScrollState()
                        val currentLocation = location?.takeIf { it.entryId == entry.id &&
                            it.revision == revision && it.contentHash == OriginalExports.hash(entry.originalText) &&
                            it.startCodePoint >= 0 && it.endCodePoint > it.startCodePoint &&
                            it.endCodePoint <= entry.originalText.codePointCount(0, entry.originalText.length) }
                        val start = currentLocation?.let { entry.originalText.offsetByCodePoints(0, it.startCodePoint) }
                        val end = currentLocation?.let { entry.originalText.offsetByCodePoints(0, it.endCodePoint) }
                        val annotated = remember(entry.originalText, currentLocation) {
                            AnnotatedString.Builder(entry.originalText).apply {
                                if (start != null && end != null) addStyle(SpanStyle(
                                    background = AIyouColors.surfaceMuted, fontWeight = FontWeight.Bold), start, end)
                            }.toAnnotatedString()
                        }
                        var layout by remember(entry.originalText, currentLocation) { mutableStateOf<TextLayoutResult?>(null) }
                        LaunchedEffect(currentLocation, layout, scroll) {
                            val measured = layout
                            if (start != null && measured != null && currentGate(entry.id, revision)) {
                                val line = measured.getLineForOffset(start)
                                scroll.scrollTo(measured.getLineTop(line).roundToInt().coerceAtLeast(0))
                            }
                        }
                        CompositionLocalProvider(LocalClipboard provides clipboard) {
                            SelectionContainer(state = selection,
                                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).testTag("original-scroll")) {
                                // One fully composed text supports selecting/copying the whole original.
                                Text(annotated, style = MaterialTheme.typography.bodyLarge,
                                    color = AIyouColors.textPrimary,
                                    onTextLayout = { layout = it },
                                    modifier = Modifier.fillMaxWidth().testTag("original-text")
                                        .semantics { contentDescription = "记录完整原文" })
                            }
                        }
                    }
                }
            }
        }
    }
    local.sourceTask?.takeIf { it.binding.source.sourceId==entryId }?.let { ui ->
        sourceTaskController?.let { SourceTaskDialog(ui,it,local.canUseTaskSource(ui.binding),canUseSourceTask) }
    }

}
