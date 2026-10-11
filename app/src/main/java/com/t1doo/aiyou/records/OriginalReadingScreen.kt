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
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.PrivacyChip
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.model.StoredEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bounded presentation excerpt only. Never passed to a repository or clipboard. */
internal fun originalPreview(text: String): String {
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
                entry == null -> StatusNotice("这条记录已不可用", "它可能已被移除。返回列表查看当前记录。", state = ComponentState.EMPTY,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
                else -> {
                    Text(metadataText ?: originalMetadata(entry), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    PrivacyChip(entry.policy)
                    Text("长按选择文字，或复制全文。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                    local.originalEditNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AIyouColors.success) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        TextButton(onClick = {
                            if (canCopy(entry.id, entry.currentRevision)) copyDenied = !onCopy(entry.id, entry.currentRevision)
                        },
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("复制全文") }
                        onEdit?.let { edit ->
                            TextButton(onClick = { edit(entry.id, entry.currentRevision) },
                                enabled = !local.saving && !local.savePending,
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
                    }
                    if (copyDenied) Text("无法复制，原文已保留。", color = AIyouColors.error,
                        style = MaterialTheme.typography.bodySmall)
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
                        CompositionLocalProvider(LocalClipboard provides clipboard) {
                            SelectionContainer(state = selection,
                                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).testTag("original-scroll")) {
                                // One fully composed text supports selecting/copying the whole original.
                                Text(AnnotatedString(entry.originalText), style = MaterialTheme.typography.bodyLarge,
                                    color = AIyouColors.textPrimary,
                                    modifier = Modifier.fillMaxWidth().testTag("original-text")
                                        .semantics { contentDescription = "记录完整原文" })
                            }
                        }
                    }
                }
            }
        }
    }
}
