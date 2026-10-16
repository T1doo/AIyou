package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.StatusNotice

@Composable
fun OriginalEditScreen(
    edit: OriginalEditUiState,
    local: LocalRecordUiState,
    onChange: (Long, String) -> Unit,
    onSave: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onHistory: ((String, Int) -> Unit)? = null,
    onKeep: (Long) -> Unit = {},
) {
    val current = local.entries.firstOrNull { it.id == edit.entryId }
    val changed = edit.conflict || current == null || current.currentRevision != edit.baseRevision && !edit.awaitingReadback
    val available = local.ready && !local.opening && local.error == null
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        key(edit.sessionId) {
            Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                val scopeLabel = if (current?.policy == com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE) "不要据此推断 · 人工编辑" else "仅本机"
                Text(if (edit.sourceRevision == edit.baseRevision) "基于原文版本 ${edit.baseRevision} · $scopeLabel"
                    else "来源：历史版本 ${edit.sourceRevision} · 基于原文版本 ${edit.baseRevision} · $scopeLabel", style = MaterialTheme.typography.bodySmall,
                    color = AIyouColors.textSecondary)
                Text("保存创建新版本，旧原文仍保留。", style = MaterialTheme.typography.bodySmall,
                    color = AIyouColors.textSecondary)
                if (!edit.dirty && edit.sourceRevision != edit.baseRevision) Text("文本与当前原文相同，无需保存新版本。",
                    style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                OutlinedTextField(value = edit.text, onValueChange = { onChange(edit.sessionId, it) },
                    readOnly = !edit.canCancel,
                    modifier = Modifier.fillMaxWidth().testTag("original-edit-text"),
                    label = { Text("修改原文") }, textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 3, maxLines = 6, isError = edit.inputNotice != null)
                Text("输入长度 ${edit.text.length} / ${EncryptedEntryRepository.MAX_TEXT_LENGTH}",
                    style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                edit.inputNotice?.let { Text(it, color = AIyouColors.error, style = MaterialTheme.typography.bodySmall) }
                if (!available) {
                    StatusNotice(if (local.opening) "正在恢复本机存储" else "本机记录暂不可用",
                        "你的修改与此刻草稿仍保留，存储恢复后再保存。",
                        state = if (local.opening) ComponentState.LOADING else ComponentState.ERROR)
                    Button(onClick = onRetry, enabled = !local.opening && !edit.saving && local.error != null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-edit-storage-retry")) {
                        Text("重试本机存储")
                    }
                }
                if (changed) StatusNotice("原文已变化", "你的修改仍保留，不能覆盖当前版本。可选择复制修改，取消后打开最新原文。",
                    state = ComponentState.ERROR)
                edit.error?.let { StatusNotice(if (edit.awaitingReadback) "读取确认需要重试" else "修改尚未保存", it, state = ComponentState.ERROR) }
                if (edit.saving) StatusNotice(if (edit.awaitingReadback) "正在确认新版本" else "正在保存修改",
                    "保存完成前请稍候。", state = ComponentState.LOADING)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                    Button(onClick = { onSave(edit.sessionId) }, enabled = !edit.saving && !edit.keeping && !edit.loadingDraft && !edit.discarding && available &&
                        (edit.awaitingReadback || !changed && edit.dirty && edit.text.isNotBlank()),
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-edit-save")) {
                        Text(if (edit.awaitingReadback) "重试读取" else if (edit.error != null) "重试保存" else "保存新版本")
                    }
                    if (local.supportsEditDrafts) TextButton(onClick = { onKeep(edit.sessionId) },
                        enabled = edit.canCancel && edit.dirty && !edit.draftConflict && available,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-edit-keep")) {
                        Text(if (edit.keeping) "正在保留草稿" else "保留草稿并返回原文")
                    }
                    TextButton(onClick = { onCancel(edit.sessionId) }, enabled = edit.canCancel,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-edit-cancel")) { Text("取消编辑") }
                }
                if (edit.keeping) StatusNotice("正在确认编辑草稿", "本机保存确认后返回原文，正式原文保持当前版本。",
                    state = ComponentState.LOADING)
                if (edit.loadingDraft || edit.discarding) StatusNotice(if (edit.loadingDraft) "正在读取编辑草稿" else "正在丢弃修改",
                    "完成前请稍候。", state = ComponentState.LOADING)
                Text(edit.draftNotice, style = MaterialTheme.typography.bodySmall,
                    color = AIyouColors.textSecondary)
                onHistory?.let { history ->
                    TextButton(onClick = { current?.let { history(it.id, it.currentRevision) } },
                        enabled = edit.canCancel && available && current != null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-edit-history")) { Text("查看已保存的历史版本") }
                }
            }
        }
    }
}
