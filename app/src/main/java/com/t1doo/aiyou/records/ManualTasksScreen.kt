package com.t1doo.aiyou.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import java.time.Clock
import kotlinx.coroutines.delay
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.ui.NotebookPageHeader

/** Optional read-only observer of the real presentation callbacks; contains no stored title. */
class ManualTaskCallbacks(val begin: () -> Unit, val create: (Long, String) -> Unit,
    val change: (String, Long, ManualTaskStatus) -> Unit, val close: (Long) -> Unit,
    val creationDate: (Long, ManualTaskDateInput) -> Unit = { _, _ -> },
    val beginDate: (String, Long, ManualTaskDateOperation) -> Unit = { _, _, _ -> },
    val editDate: (Long, ManualTaskDateInput) -> Unit = { _, _ -> },
    val saveDate: (Long, ManualTaskDateInput) -> Unit = { _, _ -> },
    val closeDate: (Long) -> Unit = {})
fun interface ManualTasksObserver {
    fun callbacks(actions: ManualTaskCallbacks)
    fun input(state: TextFieldState) {}
}
val LocalManualTasksObserver = staticCompositionLocalOf<ManualTasksObserver> { ManualTasksObserver {} }

@Composable
fun ManualTasksScreen(local: LocalRecordUiState, onBegin: () -> Unit, onEdit: (Long, String) -> Unit,
    onCreate: (Long, String) -> Unit, onChange: (String, Long, ManualTaskStatus) -> Unit,
    onRetry: () -> Unit, onRefresh: () -> Unit, onClose: (Long) -> Unit, onRecordsRetry: () -> Unit,
    modifier: Modifier = Modifier, companion: (@Composable () -> Unit)? = null,
    onCreationDate: (Long, ManualTaskDateInput) -> Unit = { _, _ -> },
    onBeginDate: (String, Long, ManualTaskDateOperation) -> Unit = { _, _, _ -> },
    onEditDate: (Long, ManualTaskDateInput) -> Unit = { _, _ -> },
    onSaveDate: (Long, ManualTaskDateInput) -> Unit = { _, _ -> },
    onCloseDate: (Long) -> Unit = {}, onSource: (String, Long) -> Unit = { _, _ -> }, clock: Clock = Clock.systemUTC(),
    onProposalOpen:(String,Long)->Unit={_,_->},onProposalRefresh:()->Unit={},
    reminders: TaskReminderController? = null, ownsReminderRoute: () -> Boolean = { true },
    titles: ManualTaskController? = null, ownsTitleRoute: () -> Boolean = { true }) {
    val tasks = local.tasks
    val storageReady = local.ready && !local.opening && local.error == null
    val available = storageReady && tasks.available && !tasks.loading
    val canWrite = available && !tasks.busy && tasks.pending == null && !local.reminders.busy && local.reminders.pending == null
    val canChoose = canWrite && tasks.editor == null && tasks.dateEditor == null && tasks.titleEditor == null && local.reminders.editor == null
    val countsReady = available && !tasks.busy && tasks.pending == null && tasks.error == null
    val proposals = local.taskProposals
    val proposalsReady = storageReady && proposals.available && !proposals.loading && proposals.error == null &&
        proposals.editor?.let { !it.busy && it.pending == null } != false
    val now by produceState(clock.instant(), clock) {
        // Display classification only, never a scheduler or a persisted inferred deadline.
        while (true) { delay(30_000); value = clock.instant() }
    }
    val observer = LocalManualTasksObserver.current
    SideEffect { observer.callbacks(ManualTaskCallbacks(onBegin, onCreate, onChange, onClose,
        onCreationDate, onBeginDate, onEditDate, onSaveDate, onCloseDate)) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val margin = if (maxWidth <= AIyouDimensions.narrowBreakpoint) AIyouDimensions.pageNarrow else AIyouDimensions.pageWide
        val stacked = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize().testTag("manual-task-list"),
                contentPadding = PaddingValues(horizontal = margin, vertical = AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                item("header") { NotebookPageHeader("待办", "由你明确创建的正式任务", stacked, companion) }
                item("create") {
                    Surface(color = AIyouColors.primaryContainer, contentColor = AIyouColors.textPrimary,
                        shape = RoundedCornerShape(AIyouDimensions.cardRadius)) {
                        Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceLg),
                            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            Text(if (countsReady) "正式待办 ${tasks.items.size}" else "正式待办 · 待核对",
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("manual-task-total"))
                            if (countsReady) Text("未完成 ${tasks.items.count { it.status == ManualTaskStatus.OPEN }} · " +
                                "已完成 ${tasks.items.count { it.status == ManualTaskStatus.COMPLETED }} · " +
                                "已取消 ${tasks.items.count { it.status == ManualTaskStatus.CANCELLED }}",
                                style = MaterialTheme.typography.bodySmall)
                            if (proposals.supported) Text(if (proposalsReady)
                                "另有 ${proposals.items.count { it.proposal.state == TaskProposalState.DRAFT }} 份待确认草稿，不计入正式待办"
                                else "待确认草稿另计 · 待核对", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("manual-task-draft-summary"))
                            Button(onClick = onBegin, enabled = canChoose,
                                modifier = Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                                    .testTag("manual-task-begin")) { Text("创建待办") }
                            Text("创建不会自动设置提醒", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("manual-task-reminder-boundary"))
                        }
                    }
                }
                item("state") {
                    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        when {
                            local.error != null -> { Text(local.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRecordsRetry) { Text("重试本机读取") } }
                            local.opening || tasks.loading -> Text("正在读取本机待办…")
                            tasks.busy -> Text("正在保存待办，尚未确认…")
                        }
                        local.taskSourceNotice?.let { Text(it,modifier=Modifier.testTag("manual-task-source-notice")) }
                        tasks.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        tasks.notice?.takeIf { available }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        local.reminders.notice?.takeIf { available }?.let { Text(it) }
                        local.reminders.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (local.reminders.supported && local.reminders.error != null) TextButton(enabled = available && !local.reminders.busy,
                            onClick = { if (ownsReminderRoute()) reminders?.retry() }, modifier = Modifier.testTag("reminder-retry")) { Text("核对提醒状态") }
                        if (tasks.error != null && storageReady) TextButton(onClick = onRetry,
                            enabled = !tasks.busy && !tasks.loading, modifier = Modifier.testTag("manual-task-retry")) {
                            Text(if (tasks.conflict) "重新读取待办" else if (tasks.pending != null) "重试确认" else "重试读取")
                        }
                        if (available && !tasks.busy && tasks.pending == null) TextButton(onClick = onRefresh,
                            modifier = Modifier.testTag("manual-task-refresh")) { Text("重新读取待办") }
                    }
                }
                item("reminder-policy") {
                    Text("日期与时区由你填写，提醒需逐条确认；安排状态以各条待办为准，通知送达未验证。",
                        style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary,
                        modifier = Modifier.testTag("manual-task-reminder-policy"))
                }
                if (available) {
                    if (tasks.items.isEmpty()) item("empty") { StatusNotice("还没有正式待办", "点击“创建待办”并确认保存后，才会加入清单。", state = ComponentState.EMPTY) }
                    for ((bucket, title) in listOf(ManualTaskGroup.PAST to "过去 · 未完成", ManualTaskGroup.TODAY to "今天",
                        ManualTaskGroup.UPCOMING to "接下来", ManualTaskGroup.UNDATED to "未设时间 · 待办",
                        ManualTaskGroup.COMPLETED to "已完成", ManualTaskGroup.CANCELLED to "已取消")) {
                        val group = tasks.items.filter { ManualTaskDates.group(it, now) == bucket }
                            .sortedWith(compareBy<ManualTaskSnapshot> { it.schedule?.date }.thenBy { it.schedule?.time }.thenBy { it.id })
                        if (group.isEmpty()) continue
                        item("heading-" + bucket) {
                            Surface(color = AIyouColors.surfaceMuted, shape = MaterialTheme.shapes.small) {
                                Text(title + " " + group.size, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd)
                                        .testTag("manual-task-heading-" + bucket))
                            }
                        }
                        items(group, key = { "task-" + it.id }) { task ->
                            Surface(color = AIyouColors.surface, shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(AIyouDimensions.borderWidth, AIyouColors.outlineDecorative)) {
                                Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd).testTag("manual-task-row-" + task.id),
                                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                    Text(task.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(manualTaskDateLabel(task), style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("manual-task-date-" + task.id))
                                    task.reminder?.let { Text(taskReminderLabel(it), Modifier.testTag("reminder-status-" + task.id)) }
                                    task.sourceReceipt?.let { receipt ->
                                        Text("由原文摘录创建 · 人工确认 · 原文版本 " + receipt.binding.source.revision)
                                        TextButton(enabled=canChoose,onClick={onSource(task.id,task.version)},
                                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("manual-task-source-"+task.id)) { Text("查看来源原文") }
                                    }
                                    if (task.status == ManualTaskStatus.OPEN) FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                        if (titles != null) TextButton(enabled = canChoose && task.version < Long.MAX_VALUE,
                                            onClick = { if (ownsTitleRoute()) titles.beginTitle(task.id, task.version) },
                                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-title-open-" + task.id)) { Text("修改标题") }
                                        if (local.reminders.supported && reminders != null) {
                                            TextButton(enabled = canChoose && task.schedule?.instant() != null,
                                                onClick = { if (ownsReminderRoute()) reminders.begin(task.id, task.version) },
                                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("reminder-open-" + task.id)) { Text("确认提醒计划") }
                                            if (task.reminder?.phase?.let { it != TaskReminderPhase.REVOKED } == true) TextButton(enabled = canChoose,
                                                onClick = { if (ownsReminderRoute()) reminders.begin(task.id, task.version, cancelling = true) },
                                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("reminder-cancel-" + task.id)) { Text("撤销提醒计划") }
                                        }
                                        TextButton(onClick = { onBeginDate(task.id, task.version, ManualTaskDateOperation.SET) }, enabled = canChoose,
                                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-date-open-" + task.id)) {
                                            Text(if (task.schedule == null) "设置日期时间" else "修改日期时间") }
                                        if (task.schedule != null) {
                                            TextButton(onClick = { onBeginDate(task.id, task.version, ManualTaskDateOperation.POSTPONE) }, enabled = canChoose,
                                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-postpone-" + task.id)) { Text("延后") }
                                            TextButton(onClick = { onBeginDate(task.id, task.version, ManualTaskDateOperation.CLEAR) }, enabled = canChoose,
                                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-clear-" + task.id)) { Text("清除日期时间") }
                                        }
                                        TextButton(onClick = { onChange(task.id, task.version, ManualTaskStatus.COMPLETED) }, enabled = canChoose,
                                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-complete-" + task.id)) { Text("标记完成") }
                                        TextButton(onClick = { onChange(task.id, task.version, ManualTaskStatus.CANCELLED) }, enabled = canChoose,
                                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-cancel-" + task.id)) { Text("取消待办") }
                                    }
                                }
                            }
                        }
                    }
                }
                if(local.taskProposals.supported) {
                    item("proposal-heading") {Text("待确认草稿"+(if(proposalsReady) " "+proposals.items.count {it.proposal.state==TaskProposalState.DRAFT} else " · 待核对"),
                        style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("task-proposal-heading"))}
                    item("proposal-state") {
                        Column {
                            Text("人工草稿与正式待办分开计数；没有语义 AI 建议。")
                            if(proposals.loading) Text("正在读取加密草稿…")
                            proposals.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                            TextButton(enabled=storageReady&&!proposals.loading,onClick=onProposalRefresh,modifier=Modifier.testTag("task-proposal-refresh")) {Text("重新读取待确认草稿")}
                        }
                    }
                    if(storageReady&&proposals.available&&!proposals.loading) {
                        val drafts=proposals.items.filter {it.proposal.state==TaskProposalState.DRAFT}
                        if(drafts.isEmpty()) item("proposal-empty") {Text("没有待确认草稿；可从原文摘录主动发起。")}
                        items(drafts,key={"proposal-"+it.proposal.id}) {item ->
                            val p=item.proposal
                            Surface(color=AIyouColors.surfaceMuted,shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd).testTag("task-proposal-row-"+p.id),
                                verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                Text(p.titleInput.ifEmpty {"尚未填写标题"})
                                Text("人工待确认 · 原文版本 "+p.binding.source.revision+" · "+p.dateInput.date.ifEmpty {"未设日期"})
                                if(!item.sourceAvailable||!local.canUseTaskSource(p.binding)) Text("来源已失效或暂不可核对，不能确认。")
                                TextButton(enabled=proposals.editor==null,onClick={onProposalOpen(p.id,p.version)},
                                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("task-proposal-open-"+p.id)) {Text("查看、修改或拒绝")}
                            }}
                        }
                        item("proposal-rejected") {Text("已拒绝草稿 "+proposals.items.count {it.proposal.state==TaskProposalState.REJECTED}+" · 不计入正式待办")}
                    }
                }
                if(!local.taskProposals.supported) item("proposals") { StatusNotice("没有待确认建议", if(local.supportsSourceTasks) "可从原文摘录手工创建待办；语义 AI 建议尚未开放，原文和草稿不会自动变成待办。" else "AI 候选确认尚未开放；原文与草稿不会自动变成正式待办。", state = ComponentState.EMPTY) }
            }
        }
    }
    tasks.editor?.let { editor ->
        ManualTaskDialog(editor, tasks, storageReady, onEdit, onCreate, onRetry, onClose, onCreationDate)
    }
    tasks.dateEditor?.let { editor -> ManualTaskDateDialog(editor, tasks, storageReady, onEditDate, onSaveDate, onCloseDate, onRetry) }
    if (reminders != null) TaskReminderDialog(local, reminders, ownsReminderRoute)
    if (titles != null) ManualTaskTitleDialog(local, titles, ownsTitleRoute)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTaskDialog(editor: ManualTaskEditor, tasks: ManualTasksUiState, storageReady: Boolean,
    onEdit: (Long, String) -> Unit, onCreate: (Long, String) -> Unit, onRetry: () -> Unit, onClose: (Long) -> Unit,
    onCreationDate: (Long, ManualTaskDateInput) -> Unit) {
    // No Saver or undo Bundle: unfinished private titles live only in this session.
    val input = remember(editor.session) { TextFieldState(editor.input) }
    val dateFields = remember(editor.session) { TaskDateFields(editor.dateInput) }
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
                Text("确认后加密保存在本机。最多 240 个字符；创建不会自动设置提醒，不推断时间。", style = MaterialTheme.typography.bodySmall)
                CompositionLocalProvider(LocalSaveableStateRegistry provides null) { OutlinedTextField(state = input, enabled = storageReady && !tasks.busy && tasks.pending == null,
                    inputTransformation = InputTransformation.maxLength(ManualTasks.MAX_RAW_LENGTH),
                    label = { Text("待办标题") }, lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("manual-task-input")) }
                TaskDateFieldsView(dateFields, storageReady && !tasks.busy && tasks.pending == null,
                    onChanged = { onCreationDate(editor.session, it) })
                if (tasks.busy) Text("正在保存，尚未确认…")
                if (!storageReady) Text("本机存储暂不可读取。输入已保留，恢复读取后再操作。")
                tasks.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onCreationDate(editor.session, dateFields.current()); onCreate(editor.session, input.text.toString()) }, enabled = canCreate,
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

internal fun manualTaskDateLabel(task: ManualTaskSnapshot): String {
    val reminderNote = if (task.reminder == null) " · 未设置提醒计划" else ""
    val due = task.schedule ?: return "手工创建 · 未设时间" + reminderNote
    return "手工创建 · " + due.date + (due.time?.let { " " + it + " · 偏移 " +
        java.time.ZoneOffset.ofTotalSeconds(checkNotNull(due.offsetSeconds)).id } ?: " · 仅日期") +
        " · " + due.timeZone + reminderNote
}

internal class TaskDateFields(input: ManualTaskDateInput) {
    val date = TextFieldState(input.date)
    val time = TextFieldState(input.time)
    val zone = TextFieldState(input.timeZone)
    private var occurrence by mutableStateOf(input.occurrence)
    private var chosenFor = Triple(input.date, input.time, input.timeZone)
    private fun tuple() = Triple(date.text.toString(), time.text.toString(), zone.text.toString())
    fun current(): ManualTaskDateInput {
        val value = tuple()
        return ManualTaskDateInput(value.first, value.second, value.third, if (value == chosenFor) occurrence else null)
    }
    fun choose(value: ManualTaskOccurrence) { chosenFor = tuple(); occurrence = value }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDateFieldsView(fields: TaskDateFields, enabled: Boolean, onChanged: (ManualTaskDateInput) -> Unit) {
    val changed by rememberUpdatedState(onChanged)
    LaunchedEffect(fields) { snapshotFlow { fields.current() }.collect { changed(it) } }
    // Foundation input, selection and Undo histories for all private fields stay memory-only.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        OutlinedTextField(state = fields.date, enabled = enabled, label = { Text("日期（YYYY-MM-DD，可留空）") },
            lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = InputTransformation.maxLength(10),
            modifier = Modifier.fillMaxWidth().testTag("manual-task-date-input"))
        OutlinedTextField(state = fields.time, enabled = enabled, label = { Text("时刻（HH:mm，可留空）") },
            lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = InputTransformation.maxLength(5),
            modifier = Modifier.fillMaxWidth().testTag("manual-task-time-input"))
        OutlinedTextField(state = fields.zone, enabled = enabled, label = { Text("计划时区（IANA）") },
            lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = InputTransformation.maxLength(128),
            modifier = Modifier.fillMaxWidth().testTag("manual-task-zone-input"))
    }
    Text("时刻留空表示仅日期，不会补成某个时刻；时间必须有日期。仅填写日期时间不会安排提醒。", style = MaterialTheme.typography.bodySmall)
    val current = fields.current()
    val offsets = current.overlapOffsets()
    if (offsets.size == 2) {
        Text("该时刻出现两次，请明确选择：", style = MaterialTheme.typography.bodySmall)
        for ((index, occurrence) in listOf(ManualTaskOccurrence.FIRST, ManualTaskOccurrence.SECOND).withIndex()) {
            TextButton(onClick = { fields.choose(occurrence); onChanged(fields.current()) }, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-offset-" + occurrence)) {
                Text((if (index == 0) "第一次 " else "第二次 ") + offsets[index] +
                    if (current.occurrence == occurrence) " · 已选择" else "")
            }
        }
    }
}

@Composable
private fun ManualTaskDateDialog(editor: ManualTaskDateEditor, tasks: ManualTasksUiState, storageReady: Boolean,
    onEdit: (Long, ManualTaskDateInput) -> Unit, onSave: (Long, ManualTaskDateInput) -> Unit,
    onClose: (Long) -> Unit, onRetry: () -> Unit) {
    val fields = remember(editor.session) { TaskDateFields(editor.input) }
    val target = tasks.items.singleOrNull { it.id == editor.taskId && it.version == editor.version && it.status == ManualTaskStatus.OPEN }
    val canSave = storageReady && tasks.available && !tasks.loading && !tasks.busy && tasks.pending == null && target != null && !tasks.conflict
    val title = when (editor.operation) {
        ManualTaskDateOperation.SET -> "设置或修改日期时间"
        ManualTaskDateOperation.POSTPONE -> "延后待办"
        ManualTaskDateOperation.CLEAR -> "清除日期时间"
    }
    Dialog(onDismissRequest = { onClose(editor.session) }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.contentMaxWidth)
                .verticalScroll(rememberScrollState()).padding(AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (tasks.available && target != null) {
                    Text(target.title, style = MaterialTheme.typography.bodyLarge)
                    Text(manualTaskDateLabel(target), style = MaterialTheme.typography.bodySmall)
                } else Text("当前待办暂不可确认，请重新读取。编辑输入已保留，不会自动套到其他版本。")
                if (target?.reminder != null) Text("变更或清除日期时间会撤销旧提醒计划；新时间需要再次单独确认提醒。")
                if (editor.operation == ManualTaskDateOperation.CLEAR) Text("确认后移除计划日期和时刻，保留待办及当前状态。")
                else {
                    if (editor.operation == ManualTaskDateOperation.POSTPONE)
                        Text("请选择比原计划更晚的日期或时刻；更换时区或日期精度请用修改操作。")
                    TaskDateFieldsView(fields, storageReady && !tasks.busy && tasks.pending == null,
                        onChanged = { onEdit(editor.session, it) })
                }
                if (tasks.busy) Text("正在保存，尚未确认…")
                tasks.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onSave(editor.session, fields.current()) }, enabled = canSave,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-date-save")) {
                    Text(when (editor.operation) { ManualTaskDateOperation.SET -> "确认日期时间"
                        ManualTaskDateOperation.POSTPONE -> "确认延后"; ManualTaskDateOperation.CLEAR -> "确认清除" }) }
                if (tasks.error != null) TextButton(onClick = onRetry, enabled = storageReady && !tasks.busy && !tasks.loading,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-date-retry")) {
                    Text(if (tasks.conflict) "重新读取待办" else if (tasks.pending != null) "重试确认" else "重新读取待办") }
                TextButton(onClick = { onClose(editor.session) }, enabled = !tasks.busy,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("manual-task-date-close")) {
                    Text(if (tasks.pending != null) "关闭编辑（仍待确认）" else "关闭编辑") }
            }
        }
    }
}
