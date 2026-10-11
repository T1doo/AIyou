package com.t1doo.aiyou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.ProviderConnectionCard
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.MainScreen
import androidx.lifecycle.ViewModelProvider
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.records.LocalRecordsScreen
import com.t1doo.aiyou.records.LocalRecordsViewModel
import com.t1doo.aiyou.records.OpenSourceLicenses
import com.t1doo.aiyou.records.OriginalReadingScreen
import com.t1doo.aiyou.records.OriginalEditScreen
import com.t1doo.aiyou.records.OriginalHistoryScreen
import com.t1doo.aiyou.records.LocalOriginalReadingObserver
import com.t1doo.aiyou.records.OriginalReadingCallbacks
import com.t1doo.aiyou.records.copyOriginal
import com.t1doo.aiyou.ui.NotebookAppBar
import com.t1doo.aiyou.ui.NotebookNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val records = ViewModelProvider(this)[LocalRecordsViewModel::class.java]
        setContent {
            val local by records.state.collectAsState()
            AIyouTheme { AIyouApp(local = local, onDraftChange = records::changeText, onSave = records::save, onRetry = records::retry,
                onBeginOriginalEdit = records::beginOriginalEdit, onOriginalEditChange = records::changeOriginalEdit,
                onOriginalEditSave = records::saveOriginalEdit, onOriginalEditCancel = records::cancelOriginalEdit,
                onBeginOriginalHistory = records::beginOriginalHistory, onSelectOriginalRevision = records::selectOriginalRevision,
                onBackOriginalHistory = records::backOriginalHistory, onRetryOriginalHistory = records::retryOriginalHistory,
                onEditOriginalHistory = records::editOriginalHistory,
                onConfirmHistoryReplacement = records::confirmHistoryReplacement,
                onCancelHistoryReplacement = records::cancelHistoryReplacement) }
        }
    }
}

/** A callback belongs to one live navigation session, including repeated opens of the same ID. */
private class NavigationOwner {
    var generation by mutableStateOf(0L)
    var active = true
}

/** One real reader return; a later navigation cannot complete this request. */
private data class TailReturnRequest(val destination: Destination, val entryId: String, val generation: Long)

@Composable
fun AIyouApp(
    previewDestination: Destination = Destination.NOW,
    previewDemo: Boolean = false,
    previewState: ComponentState = ComponentState.NORMAL,
    local: LocalRecordUiState? = null,
    onDraftChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
    previewEntryId: String? = null,
    onBeginOriginalEdit: (String, Int) -> Unit = { _, _ -> },
    onOriginalEditChange: (Long, String) -> Unit = { _, _ -> },
    onOriginalEditSave: (Long) -> Unit = {},
    onOriginalEditCancel: (Long) -> Unit = {},
    onBeginOriginalHistory: (String, Int) -> Unit = { _, _ -> },
    onSelectOriginalRevision: (Long, Int) -> Unit = { _, _ -> },
    onBackOriginalHistory: (Long) -> Unit = {},
    onRetryOriginalHistory: (Long) -> Unit = {},
    onEditOriginalHistory: (Long, Long, Int, Int) -> Unit = { _, _, _, _ -> },
    onConfirmHistoryReplacement: (Long, Long, Long) -> Unit = { _, _, _ -> },
    onCancelHistoryReplacement: (Long, Long, Long) -> Unit = { _, _, _ -> },
) {
    val navigation = remember { NavigationOwner() }
    DisposableEffect(navigation) { onDispose { navigation.active = false } }
    val generation = navigation.generation
    fun ownsNavigation() = navigation.active && navigation.generation == generation
    var selectedName by rememberSaveable { mutableStateOf(previewDestination.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var demo by rememberSaveable { mutableStateOf(previewDemo) }
    var stateName by rememberSaveable { mutableStateOf(previewState.name) }
    var detailEntryId by rememberSaveable { mutableStateOf(previewEntryId) }
    var selectedNowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMemoriesId by rememberSaveable { mutableStateOf<String?>(null) }
    var discardEditSession by remember { mutableStateOf<Long?>(null) }
    var tailReturnRequest by remember { mutableStateOf<TailReturnRequest?>(null) }
    var scaffoldHeightPx by remember { mutableIntStateOf(0) }
    var navigationHeightPx by remember { mutableIntStateOf(0) }
    val nowList = rememberLazyListState()
    val memoriesList = rememberLazyListState()
    val currentLocal by rememberUpdatedState(local)
    val context = LocalContext.current
    val selected = Destination.valueOf(selectedName)
    val reading = detailEntryId != null && !showSettings && !demo && local != null && selected != Destination.TASKS
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val origin = selected
    val readerId = detailEntryId
    val editing = if (reading) local?.originalEdit?.takeIf { it.entryId == readerId } else null
    val history = if (reading) local?.originalHistory?.takeIf { it.entryId == readerId } else null
    val historyList = remember(history?.sessionId) { LazyListState() }
    fun ownsEdit(sessionId: Long): Boolean = ownsNavigation() && reading && detailEntryId == readerId &&
        selectedName == origin.name && !showSettings && !demo &&
        currentLocal?.originalHistory == null && currentLocal?.originalEdit?.let { it.sessionId == sessionId && it.entryId == readerId } == true
    fun ownsHistory(sessionId: Long) = ownsNavigation() && reading && detailEntryId == readerId &&
        selectedName == origin.name && !showSettings && !demo &&
        currentLocal?.originalHistory?.let { it.sessionId == sessionId && it.entryId == readerId } == true
    val backHistory: (Long) -> Unit = { sessionId ->
        if (ownsHistory(sessionId)) { navigation.generation++; onBackOriginalHistory(sessionId) }
    }
    val selectHistory: (Long, Int) -> Unit = { sessionId, revision ->
        if (ownsHistory(sessionId) && currentLocal?.originalHistory?.let { !it.loading && it.error == null && !it.unavailable &&
                it.revisions.any { r -> r.revision == revision } } == true) {
            navigation.generation++; onSelectOriginalRevision(sessionId, revision)
        }
    }
    val retryHistory: (Long) -> Unit = { sessionId ->
        if (ownsHistory(sessionId) && currentLocal?.originalHistory?.loading == false) {
            navigation.generation++; onRetryOriginalHistory(sessionId)
        }
    }
    val discardEdit: (Long) -> Unit = { sessionId ->
        if (ownsEdit(sessionId) && currentLocal?.originalEdit?.canCancel == true) {
            navigation.generation++; discardEditSession = null; onOriginalEditCancel(sessionId)
        }
    }
    val cancelEdit: (Long) -> Unit = { sessionId ->
        if (ownsEdit(sessionId) && currentLocal?.originalEdit?.canCancel == true) {
            if (currentLocal?.originalEdit?.dirty == true) discardEditSession = sessionId else discardEdit(sessionId)
        }
    }
    val changeEdit: (Long, String) -> Unit = { sessionId, text ->
        if (ownsEdit(sessionId) && currentLocal?.originalEdit?.canCancel == true) onOriginalEditChange(sessionId, text)
    }
    val saveEdit: (Long) -> Unit = { sessionId ->
        if (ownsEdit(sessionId) && currentLocal?.originalEdit?.saving == false) onOriginalEditSave(sessionId)
    }
    val returnToList: () -> Unit = {
        if (ownsNavigation() && reading) {
            val edit = currentLocal?.originalEdit?.takeIf { it.entryId == readerId }
            val activeHistory = currentLocal?.originalHistory?.takeIf { it.entryId == readerId }
            if (activeHistory != null) backHistory(activeHistory.sessionId)
            else if (edit != null) cancelEdit(edit.sessionId) else {
                navigation.generation++
                val latest = currentLocal
                val last = if (origin == Destination.NOW) latest?.entries?.take(5)?.lastOrNull() else latest?.entries?.lastOrNull()
                tailReturnRequest = if (latest?.ready == true && !latest.opening && latest.error == null && last?.id == readerId)
                    TailReturnRequest(origin, checkNotNull(readerId), navigation.generation) else null
                detailEntryId = null
            }
        }
    }
    val selectDestination: (Destination) -> Unit = { destination ->
        if (ownsNavigation() && currentLocal?.originalEdit == null && currentLocal?.originalHistory == null) {
            navigation.generation++
            tailReturnRequest = null
            selectedName = destination.name; detailEntryId = null; showSettings = false; showLicenses = false
        }
    }
    val openEntry: (String) -> Unit = { id ->
        if (ownsNavigation() && selectedName == origin.name && !showSettings && !demo && detailEntryId == null) {
            navigation.generation++
            tailReturnRequest = null
            if (origin == Destination.NOW) selectedNowId = id else selectedMemoriesId = id
            detailEntryId = id
        }
    }
    val canCopy: (String, Int) -> Boolean = { id, revision ->
        val latest = currentLocal
        ownsNavigation() && detailEntryId == id && selectedName == origin.name && !showSettings && !demo &&
            latest != null && latest.ready && !latest.opening && latest.error == null &&
            latest.originalEdit == null && latest.originalHistory == null &&
            latest.entries.any { it.id == id && it.currentRevision == revision }
    }
    val beginEdit: (String, Int) -> Unit = { id, revision ->
        if (canCopy(id, revision) && currentLocal?.saving == false && currentLocal?.savePending == false) {
            navigation.generation++; onBeginOriginalEdit(id, revision)
        }
    }
    val beginHistory: (String, Int) -> Unit = { id, revision ->
        val latest = currentLocal
        if (ownsNavigation() && reading && detailEntryId == id && selectedName == origin.name && !showSettings && !demo &&
            latest != null && latest.ready && !latest.opening && latest.error == null && !latest.saving && !latest.savePending &&
            latest.originalHistory == null && latest.originalEdit?.canCancel != false &&
            latest.entries.any { it.id == id && it.currentRevision == revision }) {
            navigation.generation++; onBeginOriginalHistory(id, revision)
        }
    }
    val canCopyHistory: (String, Int) -> Boolean = { id, revision ->
        val latest = currentLocal
        val live = latest?.originalHistory
        history != null && ownsHistory(history.sessionId) && latest != null && latest.ready && !latest.opening && latest.error == null &&
            live != null && live.requestId == history.requestId && !live.loading && live.error == null && !live.unavailable &&
            live.entryId == id && live.selectedRevision == revision && live.revisions.any { it.revision == revision } &&
            latest.entries.any { it.id == id && it.currentRevision == live.currentRevision }
    }
    val copyHistory: (String, Int) -> Boolean = { id, revision ->
        if (canCopyHistory(id, revision)) currentLocal?.originalHistory?.revisions?.firstOrNull { it.revision == revision }?.let {
            copyOriginal(context, it.originalText)
        } ?: false else false
    }
    val editHistory: () -> Unit = {
        val revision = history?.selectedRevision
        val currentRevision = history?.currentRevision
        val latest = currentLocal
        if (history != null && revision != null && currentRevision != null && canCopyHistory(history.entryId, revision) &&
            latest?.saving == false && !latest.savePending && latest.originalEdit?.canCancel != false) {
            navigation.generation++
            onEditOriginalHistory(history.sessionId, history.requestId, revision, currentRevision)
        }
    }
    val confirmHistoryReplacement: (Long) -> Unit = { replacementId ->
        if (history?.selectedRevision != null && canCopyHistory(history.entryId, history.selectedRevision) &&
            currentLocal?.originalHistory?.replacement?.id == replacementId) {
            navigation.generation++
            onConfirmHistoryReplacement(history.sessionId, history.requestId, replacementId)
        }
    }
    val cancelHistoryReplacement: (Long) -> Unit = { replacementId ->
        if (history != null && ownsHistory(history.sessionId) &&
            currentLocal?.originalHistory?.let { it.requestId == history.requestId && it.replacement?.id == replacementId } == true) {
            navigation.generation++
            onCancelHistoryReplacement(history.sessionId, history.requestId, replacementId)
        }
    }
    val retryReading: () -> Unit = {
        val latest = currentLocal
        if (readerId != null && ownsNavigation() && detailEntryId == readerId && selectedName == origin.name && !showSettings && !demo &&
            latest != null && !latest.opening && latest.error != null) onRetry()
    }
    val copy: (String, Int) -> Boolean = { id, revision ->
        if (canCopy(id, revision)) currentLocal?.entries?.firstOrNull { it.id == id }?.let {
            copyOriginal(context, it.originalText)
        } ?: false else false
    }
    LocalOriginalReadingObserver.current?.let { observer ->
        val callbacks = OriginalReadingCallbacks(openEntry, selectDestination, returnToList, retryReading, copy,
            beginEdit, changeEdit, saveEdit, cancelEdit, discardEdit, beginHistory, selectHistory, backHistory, retryHistory, copyHistory,
            editHistory, confirmHistoryReplacement, cancelHistoryReplacement)
        SideEffect {
            observer.onNavigation(callbacks)
            if (selected != Destination.TASKS) observer.onRecordList(if (selected == Destination.NOW) nowList else memoriesList, imeBottomPx)
        }
    }
    BackHandler(enabled = showSettings) {
        if (ownsNavigation()) { navigation.generation++; if (showLicenses) showLicenses = false else showSettings = false }
    }
    BackHandler(enabled = reading, onBack = returnToList)

    Scaffold(
        modifier = Modifier.imePadding().onSizeChanged { scaffoldHeightPx = it.height },
        topBar = {
            NotebookAppBar(
                if (history != null) "原文版本" else if (editing != null) "编辑原文" else if (reading) "记录原文" else if (showLicenses) "开源许可" else if (showSettings) "设置" else "爱忆 AIyou",
                if (history != null) { if (history.selectedRevision != null) "返回版本列表" else if (editing != null) "返回编辑" else "返回原文" }
                else if (editing != null) "取消编辑" else if (reading) "返回列表" else if (showLicenses) "返回设置" else if (showSettings) "返回" else "设置",
                actionEnabled = history != null || editing?.canCancel != false,
            ) {
                if (reading) returnToList() else if (ownsNavigation()) {
                    navigation.generation++
                    tailReturnRequest = null
                    if (showLicenses) showLicenses = false else showSettings = !showSettings
                }
            }
        },
        bottomBar = {
            if (!imeVisible && !reading) {
                DisposableEffect(Unit) { onDispose { navigationHeightPx = 0 } }
                Box(Modifier.onSizeChanged { navigationHeightPx = it.height }) {
                    NotebookNavigation(if (showSettings) null else selected, onSelect = selectDestination)
                }
            }
        },
    ) { contentPadding ->
        val modifier = Modifier.padding(contentPadding).consumeWindowInsets(contentPadding)
        if (showLicenses) {
            OpenSourceLicenses(modifier)
        } else if (showSettings) {
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceLg),
            ) {
                StatusNotice("仅本机处理", "可保存文字与恢复本机草稿；AI 连接、附件与系统提醒尚未开放。")
                ProviderConnectionCard("我的模型 · 未配置", "视觉、转写和语义检索能力未检测")
                TextButton(onClick = { if (ownsNavigation()) { navigation.generation++; showLicenses = true } }) { Text("开源许可与声明") }
                Text("界面示例", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { if (ownsNavigation()) { navigation.generation++; demo = !demo } }) { Text(if (demo) "退出虚构示例" else "查看虚构示例") }
                if (demo) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        ComponentState.entries.forEach { state ->
                            OutlinedButton(onClick = { if (ownsNavigation()) { navigation.generation++; stateName = state.name } }) {
                                Text(state.description + if (stateName == state.name) " · 已选" else "")
                            }
                        }
                    }
                }
            }
        } else if (history != null && local != null) {
            OriginalHistoryScreen(history, local, historyList, selectHistory, backHistory, retryHistory,
                retryReading, copyHistory, canCopyHistory, modifier, editHistory, confirmHistoryReplacement, cancelHistoryReplacement)
        } else if (editing != null && local != null) {
            OriginalEditScreen(editing, local, changeEdit, saveEdit, cancelEdit, modifier, onRetry = retryReading, onHistory = beginHistory)
        } else if (reading && local != null) {
            OriginalReadingScreen(checkNotNull(readerId), local, onRetry = retryReading,
                onCopy = copy, canCopy = canCopy, modifier = modifier, onEdit = beginEdit, onHistory = beginHistory)
        } else if (!demo && local != null && selected != Destination.TASKS) {
            val request = tailReturnRequest?.takeIf { it.destination == selected && it.generation == generation }
            val topPaddingPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
            val bottomPaddingPx = with(density) { contentPadding.calculateBottomPadding().roundToPx() }
            LocalRecordsScreen(selected, local, onDraftChange, onSave, onRetry, modifier,
                listState = if (selected == Destination.NOW) nowList else memoriesList,
                selectedEntryId = if (selected == Destination.NOW) selectedNowId else selectedMemoriesId,
                onOpenEntry = openEntry, tailReturnEntryId = request?.entryId, tailReturnGeneration = request?.generation,
                expectedReturnHeightPx = (scaffoldHeightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(0),
                returnViewportReady = scaffoldHeightPx > 0 &&
                    (imeVisible || (navigationHeightPx > 0 && bottomPaddingPx >= navigationHeightPx)),
                ownsTailReturn = { ownsNavigation() && request != null && tailReturnRequest == request &&
                    detailEntryId == null && selectedName == request.destination.name && !showSettings && !demo },
                onTailReturnHandled = { if (ownsNavigation() && request != null && tailReturnRequest == request) tailReturnRequest = null })
        } else {
            MainScreen(
                destination = selected, demo = demo, modifier = modifier,
                state = if (demo) ComponentState.valueOf(stateName) else ComponentState.NORMAL,
            )
        }
    }
    discardEditSession?.let { sessionId ->
        if (editing?.sessionId == sessionId && editing.canCancel) {
            AlertDialog(onDismissRequest = { if (ownsEdit(sessionId)) discardEditSession = null },
                title = { Text("放弃这次修改？") },
                text = { Text("本次未保存的修改将被丢弃，原文与此刻的草稿保持不变。") },
                confirmButton = { TextButton(onClick = { discardEdit(sessionId) }) { Text("放弃修改") } },
                dismissButton = { TextButton(onClick = { if (ownsEdit(sessionId)) discardEditSession = null }) { Text("继续编辑") } })
        }
    }
}
