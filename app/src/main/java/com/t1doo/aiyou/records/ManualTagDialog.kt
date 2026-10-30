package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import com.t1doo.aiyou.designsystem.AIyouDimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTagDialog(editor: ManualTagEditor, onEdit: (Long, Long, String) -> Unit,
    onAdd: (Long, Long, String) -> Unit, onRemove: (Long, Long, String, String) -> Unit,
    onRetry: (Long) -> Unit, onClose: (Long) -> Unit, sourceAvailable: Boolean = true) {
    // No Saver: tag input and its undo history never enter Android saved state.
    val version = editor.snapshot?.version ?: 0
    val input = remember(editor.session, version) { TextFieldState(editor.input) }
    val currentEdit by rememberUpdatedState(onEdit)
    LaunchedEffect(input, version) { snapshotFlow { input.text.toString() }.collect { currentEdit(editor.session, version, it) } }
    Dialog(onDismissRequest = { onClose(editor.session) }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.contentMaxWidth)
                .verticalScroll(rememberScrollState()).padding(AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("手工标签", style = MaterialTheme.typography.titleLarge)
                Text("由你新增或移除，独立于正文版本。外侧空白会去除，每个最多 40 个字符、共 12 个。",
                    style = MaterialTheme.typography.bodySmall)
                if (editor.busy) Text("正在核对本机标签…")
                if (!sourceAvailable) Text("原文暂不可读取。标签与输入已保留，请恢复原文读取后再操作。")
                if (sourceAvailable && editor.snapshot?.names?.isEmpty() == true) Text("还没有手工标签")
                editor.snapshot?.names?.takeIf { sourceAvailable }?.forEachIndexed { index, name ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { onRemove(editor.session, version, name, input.text.toString()) },
                            enabled = sourceAvailable && !editor.busy && editor.pending == null && !editor.conflict,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-tag-remove-$index")) { Text("移除") }
                    }
                }
                OutlinedTextField(state = input, enabled = sourceAvailable && !editor.busy && editor.snapshot != null &&
                    editor.pending == null,
                    inputTransformation = InputTransformation.maxLength(160),
                    label = { Text("新增手工标签") }, lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("manual-tag-input"))
                if (editor.pending != null && !editor.conflict) Text("保存结果待确认，请先重试确认，再修改标签。")
                TextButton(onClick = { onAdd(editor.session, version, input.text.toString()) },
                    enabled = sourceAvailable && !editor.busy && editor.snapshot != null && editor.pending == null && !editor.conflict,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-tag-add")) { Text("保存新增标签") }
                editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                editor.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (editor.error != null) TextButton(onClick = { onRetry(editor.session) }, enabled = sourceAvailable && !editor.busy,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-tag-retry")) {
                    Text(if (editor.conflict) "重新读取标签" else "重试确认")
                }
                TextButton(onClick = { onClose(editor.session) },
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-tag-close")) { Text("关闭") }
            }
        }
    }
}
