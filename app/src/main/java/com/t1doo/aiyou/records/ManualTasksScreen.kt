package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.ui.NotebookPageHeader

/** Optional read-only observer of the real presentation callbacks; contains no stored title. */
class ManualTaskCallbacks(val begin: () -> Unit, val create: (Long, String) -> Unit,
    val change: (String, Long, ManualTaskStatus) -> Unit, val close: (Long) -> Unit)
fun interface ManualTasksObserver {
    fun callbacks(actions: ManualTaskCallbacks)
    fun input(state: TextFieldState) {}
}
val LocalManualTasksObserver = staticCompositionLocalOf<ManualTasksObserver> { ManualTasksObserver {} }

@Composable
fun ManualTasksScreen(local: LocalRecordUiState, onBegin: () -> Unit, onEdit: (Long, String) -> Unit,
    onCreate: (Long, String) -> Unit, onChange: (String, Long, ManualTaskStatus) -> Unit,
    onRetry: () -> Unit, onRefresh: () -> Unit, onClose: (Long) -> Unit, onRecordsRetry: () -> Unit,
    modifier: Modifier = Modifier, companion: (@Composable () -> Unit)? = null) {
    val tasks = local.tasks
    val storageReady = local.ready && !local.opening && local.error == null
    val available = storageReady && tasks.available && !tasks.loading
    val canWrite = available && !tasks.busy && tasks.pending == null
    val observer = LocalManualTasksObserver.current
    SideEffect { observer.callbacks(ManualTaskCallbacks(onBegin, onCreate, onChange, onClose)) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        val stacked = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize().testTag("manual-task-list"),
                contentPadding = PaddingValues(horizontal = margin, vertical = AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                item("header") { NotebookPageHeader("待办", "由你明确创建的正式任务", stacked, companion) }
                item("boundary") { StatusNotice("没有系统提醒", "此处仅保存手工待办，不推断日期或时间，不发送通知。") }
                item("create") {
                    OutlinedButton(onClick = onBegin, enabled = canWrite && tasks.editor == null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-begin")) { Text("创建待办") }
                }
                item("state") {
                    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        when {
                            local.error != null -> { Text(local.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRecordsRetry) { Text("重试本机读取") } }
                            local.opening || tasks.loading -> Text("正在读取本机待办…")
                            tasks.busy -> Text("正在保存待办，尚未确认…")
                        }
                        tasks.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        tasks.notice?.takeIf { available }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (tasks.error != null && storageReady) TextButton(onClick = onRetry,
                            enabled = !tasks.busy && !tasks.loading, modifier = Modifier.testTag("manual-task-retry")) {
                            Text(if (tasks.conflict) "重新读取待办" else if (tasks.pending != null) "重试确认" else "重试读取")
                        }
                        if (available && !tasks.busy && tasks.pending == null) TextButton(onClick = onRefresh,
                            modifier = Modifier.testTag("manual-task-refresh")) { Text("重新读取待办") }
                    }
                }
                if (available) {
                    if (tasks.items.isEmpty()) item("empty") { StatusNotice("还没有正式待办", "点击“创建待办”并确认保存后，才会加入清单。", state = ComponentState.EMPTY) }
                    for ((status, title) in listOf(ManualTaskStatus.OPEN to "未设时间 · 待办",
                        ManualTaskStatus.COMPLETED to "已完成", ManualTaskStatus.CANCELLED to "已取消")) {
                        val group = tasks.items.filter { it.status == status }
                        item("heading-$status") { Text(title + " " + group.size, style = MaterialTheme.typography.titleLarge) }
                        items(group, key = { "task-" + it.id }) { task ->
                            Surface(color = AIyouColors.surface, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd),
                                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                    Text(task.title, style = MaterialTheme.typography.bodyLarge)
                                    Text("手工创建 · 未设时间 · 没有系统提醒", style = MaterialTheme.typography.bodySmall)
                                    if (status == ManualTaskStatus.OPEN) FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                        TextButton(onClick = { onChange(task.id, task.version, ManualTaskStatus.COMPLETED) }, enabled = canWrite,
                                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-complete-" + task.id)) { Text("标记完成") }
                                        TextButton(onClick = { onChange(task.id, task.version, ManualTaskStatus.CANCELLED) }, enabled = canWrite,
                                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-cancel-" + task.id)) { Text("取消待办") }
                                    }
                                }
                            }
                        }
                    }
                }
                item("proposals") { StatusNotice("没有待确认建议", "AI 候选确认尚未开放；原文与草稿不会自动变成正式待办。", state = ComponentState.EMPTY) }
            }
        }
    }
    tasks.editor?.let { editor ->
        ManualTaskDialog(editor, tasks, storageReady, onEdit, onCreate, onRetry, onClose)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTaskDialog(editor: ManualTaskEditor, tasks: ManualTasksUiState, storageReady: Boolean,
    onEdit: (Long, String) -> Unit, onCreate: (Long, String) -> Unit, onRetry: () -> Unit, onClose: (Long) -> Unit) {
    // No Saver or undo Bundle: unfinished private titles live only in this session.
    val input = remember(editor.session) { TextFieldState(editor.input) }
    val observer = LocalManualTasksObserver.current
    SideEffect { observer.input(input) }
    val edit by rememberUpdatedState(onEdit)
    LaunchedEffect(input) { snapshotFlow { input.text.toString() }.collect { edit(editor.session, it) } }
    val canCreate = storageReady && tasks.available && !tasks.loading && !tasks.busy && tasks.pending == null
    Dialog(onDismissRequest = { onClose(editor.session) }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.contentMaxWidth)
                .verticalScroll(rememberScrollState()).padding(AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("创建手工待办", style = MaterialTheme.typography.titleLarge)
                Text("确认后加密保存在本机。最多 240 个字符；没有系统提醒，不推断时间。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state = input, enabled = storageReady && !tasks.busy && tasks.pending == null,
                    inputTransformation = InputTransformation.maxLength(ManualTasks.MAX_RAW_LENGTH),
                    label = { Text("待办标题") }, lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("manual-task-input"))
                if (tasks.busy) Text("正在保存，尚未确认…")
                if (!storageReady) Text("本机存储暂不可读取。输入已保留，恢复读取后再操作。")
                tasks.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onCreate(editor.session, input.text.toString()) }, enabled = canCreate,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-create")) { Text("确认创建") }
                if (tasks.error != null) TextButton(onClick = onRetry, enabled = storageReady && !tasks.busy && !tasks.loading,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-dialog-retry")) {
                    Text(if (tasks.conflict) "重新读取待办" else if (tasks.pending != null) "重试确认" else "重试读取")
                }
                TextButton(onClick = { onClose(editor.session) }, enabled = !tasks.busy,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-close")) { Text("关闭") }
            }
        }
    }
}
