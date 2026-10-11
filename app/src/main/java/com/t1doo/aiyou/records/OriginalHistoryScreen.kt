package com.t1doo.aiyou.records

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.StatusNotice

@Composable
fun OriginalHistoryScreen(
    history: OriginalHistoryUiState,
    local: LocalRecordUiState,
    listState: LazyListState,
    onSelect: (Long, Int) -> Unit,
    onBack: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onStorageRetry: () -> Unit,
    onCopy: (String, Int) -> Boolean,
    canCopy: (String, Int) -> Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    onConfirmReplacement: (Long) -> Unit = {},
    onCancelReplacement: (Long) -> Unit = {},
    onAttachments: ((String, Int) -> Unit)? = null,
    originalScrollState: ScrollState? = null,
) {
    val current = local.entries.firstOrNull { it.id == history.entryId }
    val storageUnavailable = !local.ready || local.opening || local.error != null
    val changed = !history.loading && history.error == null && !history.unavailable && current?.currentRevision != history.currentRevision
    val selected = history.revisions.firstOrNull { it.revision == history.selectedRevision }
    if (!storageUnavailable && !history.loading && history.error == null && !history.unavailable && !changed && selected != null && current != null) {
        val source = current.copy(currentRevision = selected.revision, originalText = selected.originalText, capturedAtMillis = selected.createdAtMillis)
        OriginalReadingScreen(history.entryId, local.copy(entries = listOf(source), originalEditNotice = null),
            onRetry = { onRetry(history.sessionId) }, onCopy = onCopy, canCopy = canCopy, modifier = modifier,
            metadataText = originalMetadata(source).substringBefore(" · ") + " · " +
                if (selected.revision == history.currentRevision) "当前版本 ${selected.revision} · 只读"
                else "历史版本 ${selected.revision} · 当前版本 ${history.currentRevision} · 只读",
            onHistory = { _, _ -> onBack(history.sessionId) }, historyActionLabel = "返回版本列表",
            onEdit = { _, _ -> onEdit() }, editActionLabel = "用此版本编辑", onAttachments = onAttachments, originalScrollState = originalScrollState)
        history.replacement?.let { pending ->
            AlertDialog(onDismissRequest = { onCancelReplacement(pending.id) },
                title = { Text("替换未保存的修改？") },
                text = { Text("用版本 ${selected.revision} 开始编辑，将替换当前未保存的修改。此刻草稿不变，原文版本仍保留。") },
                confirmButton = { TextButton(onClick = { onConfirmReplacement(pending.id) },
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("history-replace-confirm")) { Text("替换并编辑") } },
                dismissButton = { TextButton(onClick = { onCancelReplacement(pending.id) },
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("history-replace-cancel")) { Text("保留修改") } })
        }
        return
    }
    Box(modifier.fillMaxSize().testTag("history-viewport"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize().testTag("history-content")
            .padding(AIyouDimensions.pageNarrow), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Text("已保存的原文版本 · 仅本机", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
            Text(if (local.originalEdit != null) "正在编辑的修改仍保留，返回后可继续编辑。" else "历史原文只读，当前原文与旧版本分别保留。",
                style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
            when {
                storageUnavailable || history.loading || history.error != null || history.unavailable || changed -> {
                    Column(Modifier.fillMaxWidth().weight(1f).testTag("history-status").verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        StatusNotice(when {
                            local.opening || history.loading -> "正在读取历史版本"
                            history.unavailable -> "这个原文版本已不可用"
                            else -> "历史版本暂不可读取"
                        }, when {
                            storageUnavailable -> local.error ?: "请等待本机存储读取完成。"
                            history.loading -> "请稍候，正在编辑的修改仍保留。"
                            history.unavailable -> "记录或所选版本已不可用，请返回查看当前记录。"
                            changed -> "当前原文已有变化，请重新读取版本列表。"
                            else -> checkNotNull(history.error)
                        }, state = if (local.opening || history.loading) ComponentState.LOADING else ComponentState.ERROR)
                        TextButton(onClick = { if (storageUnavailable) onStorageRetry() else onRetry(history.sessionId) },
                            enabled = !local.opening && !history.loading,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("history-retry")) { Text("重试读取历史") }
                    }
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).testTag("history-list"),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    items(history.revisions, key = { it.revision }) { revision ->
                        val label = if (revision.revision == history.currentRevision) "当前版本 ${revision.revision}" else "历史版本 ${revision.revision}"
                        Column(Modifier.fillMaxWidth().testTag("history-version-${revision.revision}")) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(originalPreview(revision.originalText), maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge, color = AIyouColors.textPrimary)
                            TextButton(onClick = { onSelect(history.sessionId, revision.revision) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("阅读版本 ${revision.revision}") }
                        }
                    }
                }
            }
        }
    }
}
