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
fun ManualPeopleDialog(editor: ManualPeopleEditor, onEdit: (Long, Long, String) -> Unit,
    onAdd: (Long, Long, String) -> Unit, onRemove: (Long, Long, String, String) -> Unit,
    onRetry: (Long) -> Unit, onClose: (Long) -> Unit, sourceAvailable: Boolean = true,
    onAliasEdit: (Long, Long, String, String) -> Unit,
    onAliasSave: (Long, Long, String, String) -> Unit,
    onAliasRemove: (Long, Long, String) -> Unit) {
    // No Saver: manual people input and its undo history never enter Android saved state.
    val version = editor.snapshot?.version ?: 0
    val input = remember(editor.session, version) { TextFieldState(editor.input) }
    val aliasInput = remember(editor.session, version) { TextFieldState(editor.aliasInput) }
    val aliasTarget = remember(editor.session, version) { TextFieldState(editor.aliasTarget) }
    var showAliases by remember(editor.session) { mutableStateOf(false) }
    val currentEdit by rememberUpdatedState(onEdit)
    LaunchedEffect(input, version) { snapshotFlow { input.text.toString() }.collect { currentEdit(editor.session, version, it) } }
    val currentAliasEdit by rememberUpdatedState(onAliasEdit)
    LaunchedEffect(aliasInput, aliasTarget, version) { snapshotFlow { aliasInput.text.toString() to aliasTarget.text.toString() }
        .collect { currentAliasEdit(editor.session, version, it.first, it.second) } }
    Dialog(onDismissRequest = { onClose(editor.session) }) {
        CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides null) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.contentMaxWidth)
                .verticalScroll(rememberScrollState()).padding(AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("关联人物", style = MaterialTheme.typography.titleLarge)
                Text("由你明确新增或移除，关联随记录保留。同名筛选不认定为同一身份，不推断关系。每个称呼最多 40 个字符、共 12 个。移除称呼也会撤销本条记录中指向它的别名。",
                    style = MaterialTheme.typography.bodySmall)
                if (editor.busy) Text("正在核对本机人物名称…")
                if (!sourceAvailable) Text("原文暂不可读取。人物名称与输入已保留，请恢复原文读取后再操作。")
                if (sourceAvailable && editor.snapshot?.names?.isEmpty() == true) Text("还没有关联人物")
                editor.snapshot?.names?.takeIf { sourceAvailable }?.forEachIndexed { index, name ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { onRemove(editor.session, version, name, input.text.toString()) },
                            enabled = sourceAvailable && !editor.busy && editor.pending == null && !editor.conflict,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-people-remove-$index")) { Text("移除") }
                    }
                }
                OutlinedTextField(state = input, enabled = sourceAvailable && !editor.busy && editor.snapshot != null,
                    inputTransformation = InputTransformation.maxLength(160),
                    label = { Text("新增关联人物") }, lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("manual-people-input"))
                TextButton(onClick = { onAdd(editor.session, version, input.text.toString()) },
                    enabled = sourceAvailable && !editor.busy && editor.snapshot != null && editor.pending == null && !editor.conflict,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-people-add")) { Text("保存新增人物名称") }
                TextButton(onClick = { showAliases = !showAliases }, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                    .testTag("manual-alias-toggle")) { Text(if (showAliases) "收起本条记录别名" else "管理本条记录别名") }
                if (showAliases) {
                HorizontalDivider()
                Text("本条记录的别名", style = MaterialTheme.typography.titleMedium)
                Text("由你明确确认，只帮助查找这条记录；不合并联系人、不认定同一身份，也不作为原文或 AI 出处。原称呼须已在上方关联。含空格的名称可单独输入完整名称查询。相同别名再次保存会修改它指向的称呼。",
                    style = MaterialTheme.typography.bodySmall)
                editor.snapshot?.aliases?.takeIf { sourceAvailable }?.forEachIndexed { index, relation ->
                    Text(relation.alias + " → " + relation.name)
                    TextButton(onClick = { onAliasRemove(editor.session, version, relation.alias) },
                        enabled = sourceAvailable && !editor.busy && editor.pending == null && !editor.conflict,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-alias-remove-$index")) { Text("撤销此别名") }
                }
                OutlinedTextField(state = aliasInput, enabled = sourceAvailable && !editor.busy && editor.snapshot != null,
                    inputTransformation = InputTransformation.maxLength(160), label = { Text("别名") }, lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("manual-alias-input"))
                OutlinedTextField(state = aliasTarget, enabled = sourceAvailable && !editor.busy && editor.snapshot != null,
                    inputTransformation = InputTransformation.maxLength(160), label = { Text("已关联的原称呼") }, lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("manual-alias-target"))
                TextButton(onClick = { onAliasSave(editor.session, version, aliasInput.text.toString(), aliasTarget.text.toString()) },
                    enabled = sourceAvailable && !editor.busy && editor.snapshot != null && editor.pending == null && !editor.conflict,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-alias-save")) { Text("确认保存本条记录别名") }
                }
                editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                editor.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (editor.error != null) TextButton(onClick = { onRetry(editor.session) }, enabled = sourceAvailable && !editor.busy,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-people-retry")) {
                    Text(if (editor.conflict) "重新读取人物名称" else "重试确认")
                }
                TextButton(onClick = { onClose(editor.session) },
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-people-close")) { Text("关闭") }
            }
        }
        }
    }
}
