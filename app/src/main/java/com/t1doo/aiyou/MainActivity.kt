package com.t1doo.aiyou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
            AIyouTheme { AIyouApp(local = local, onDraftChange = records::changeText, onSave = records::save, onRetry = records::retry) }
        }
    }
}

/** A callback belongs to one live navigation session, including repeated opens of the same ID. */
private class NavigationOwner {
    var generation by mutableStateOf(0L)
    var active = true
}

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
    val nowList = rememberLazyListState()
    val memoriesList = rememberLazyListState()
    val currentLocal by rememberUpdatedState(local)
    val context = LocalContext.current
    val selected = Destination.valueOf(selectedName)
    val reading = detailEntryId != null && !showSettings && !demo && local != null && selected != Destination.TASKS
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val origin = selected
    val readerId = detailEntryId
    val returnToList: () -> Unit = {
        if (ownsNavigation() && reading) { navigation.generation++; detailEntryId = null }
    }
    val selectDestination: (Destination) -> Unit = { destination ->
        if (ownsNavigation()) {
            navigation.generation++
            selectedName = destination.name; detailEntryId = null; showSettings = false; showLicenses = false
        }
    }
    val openEntry: (String) -> Unit = { id ->
        if (ownsNavigation() && selectedName == origin.name && !showSettings && !demo && detailEntryId == null) {
            navigation.generation++
            if (origin == Destination.NOW) selectedNowId = id else selectedMemoriesId = id
            detailEntryId = id
        }
    }
    val canCopy: (String, Int) -> Boolean = { id, revision ->
        val latest = currentLocal
        ownsNavigation() && detailEntryId == id && selectedName == origin.name && !showSettings && !demo &&
            latest != null && latest.ready && !latest.opening && latest.error == null &&
            latest.entries.any { it.id == id && it.currentRevision == revision }
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
        val callbacks = OriginalReadingCallbacks(openEntry, selectDestination, returnToList, retryReading, copy)
        SideEffect { observer.onNavigation(callbacks) }
    }
    BackHandler(enabled = showSettings) {
        if (ownsNavigation()) { navigation.generation++; if (showLicenses) showLicenses = false else showSettings = false }
    }
    BackHandler(enabled = reading, onBack = returnToList)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            NotebookAppBar(
                if (reading) "记录原文" else if (showLicenses) "开源许可" else if (showSettings) "设置" else "爱忆 AIyou",
                if (reading) "返回列表" else if (showLicenses) "返回设置" else if (showSettings) "返回" else "设置",
            ) {
                if (reading) returnToList() else if (ownsNavigation()) {
                    navigation.generation++
                    if (showLicenses) showLicenses = false else showSettings = !showSettings
                }
            }
        },
        bottomBar = {
            if (!imeVisible && !reading) {
                NotebookNavigation(if (showSettings) null else selected, onSelect = selectDestination)
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
        } else if (reading && local != null) {
            OriginalReadingScreen(checkNotNull(readerId), local, onRetry = retryReading,
                onCopy = copy, canCopy = canCopy, modifier = modifier)
        } else if (!demo && local != null && selected != Destination.TASKS) {
            LocalRecordsScreen(selected, local, onDraftChange, onSave, onRetry, modifier,
                listState = if (selected == Destination.NOW) nowList else memoriesList,
                selectedEntryId = if (selected == Destination.NOW) selectedNowId else selectedMemoriesId,
                onOpenEntry = openEntry)
        } else {
            MainScreen(
                destination = selected, demo = demo, modifier = modifier,
                state = if (demo) ComponentState.valueOf(stateName) else ComponentState.NORMAL,
            )
        }
    }
}
