package com.t1doo.aiyou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesState
import com.t1doo.aiyou.ui.UiPreferencesViewModel
import com.t1doo.aiyou.ui.SettingsScreen
import com.t1doo.aiyou.ui.CompanionEntry
import com.t1doo.aiyou.ui.CompanionDialog
import com.t1doo.aiyou.ui.companionMotionAllowed
import com.t1doo.aiyou.designsystem.MascotState
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
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.MainScreen
import androidx.lifecycle.ViewModelProvider
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.records.LocalRecordsScreen
import com.t1doo.aiyou.records.LocalOriginalSearchController
import com.t1doo.aiyou.records.OriginalSearchInput
import com.t1doo.aiyou.records.OriginalSearchInputOwnership
import com.t1doo.aiyou.records.OriginalSearchMode
import com.t1doo.aiyou.records.OriginalSearchStage
import com.t1doo.aiyou.records.OriginalSearchReturnAnchor
import com.t1doo.aiyou.records.LocalOriginalSearchObserver
import com.t1doo.aiyou.records.LocalRecordsViewModel
import com.t1doo.aiyou.records.OpenSourceLicenses
import com.t1doo.aiyou.records.OriginalReadingScreen
import com.t1doo.aiyou.records.LocalAttachmentController
import com.t1doo.aiyou.records.AttachmentHistoryOwner
import com.t1doo.aiyou.records.hasRecordAccess
import com.t1doo.aiyou.records.LocalAttachmentScreen
import com.t1doo.aiyou.records.AttachmentUiState
import com.t1doo.aiyou.records.PlatformAttachmentPicture
import com.t1doo.aiyou.records.AttachmentPicker
import com.t1doo.aiyou.records.OriginalEditScreen
import com.t1doo.aiyou.records.OriginalHistoryScreen
import com.t1doo.aiyou.records.LocalOriginalReadingObserver
import com.t1doo.aiyou.records.OriginalReadingCallbacks
import com.t1doo.aiyou.records.copyOriginal
import com.t1doo.aiyou.ui.NotebookAppBar
import com.t1doo.aiyou.ui.NotebookNavigation

class MainActivity : ComponentActivity() {
    private var packagePicker: com.t1doo.aiyou.records.ActivityRecordPackagePicker? = null
    private var packageRecords: LocalRecordsViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val records = ViewModelProvider(this)[LocalRecordsViewModel::class.java]
        packageRecords = records
        packagePicker = com.t1doo.aiyou.records.ActivityRecordPackagePicker(this, savedInstanceState).also {
            records.recordPackages.attachPickerOwner(it)
        }
        val preferences = ViewModelProvider(this)[UiPreferencesViewModel::class.java]
        setContent {
            val local by records.state.collectAsState()
            val display by preferences.state.collectAsState()
            AIyouTheme { AIyouApp(local = local, display = display, onDisplayChange = preferences::change, onRetryDisplay = preferences::retry, onDraftChange = records::changeText, onSave = records::save, onRetry = records::retry,
                onRefreshLocalSearch = records::refreshLocalSearch, onRebuildLocalSearch = records::rebuildLocalSearch,
                onBeginOriginalEdit = records::beginOriginalEdit, onOriginalEditChange = records::changeOriginalEdit,
                onOriginalEditSave = records::saveOriginalEdit, onOriginalEditCancel = records::cancelOriginalEdit,
                onOriginalEditKeep = records::keepOriginalEdit,
                onResumeOriginalEdit = records::resumeOriginalEdit,
                onRequestCaptureDiscard = records::requestCaptureDiscard,
                onConfirmCaptureDiscard = records::confirmCaptureDiscard,
                onCancelCaptureDiscard = records::cancelCaptureDiscard,
                onBeginOriginalHistory = records::beginOriginalHistory, onSelectOriginalRevision = records::selectOriginalRevision,
                onBackOriginalHistory = records::backOriginalHistory, onRetryOriginalHistory = records::retryOriginalHistory,
                onEditOriginalHistory = records::editOriginalHistory,
                onConfirmHistoryReplacement = records::confirmHistoryReplacement,
                onCancelHistoryReplacement = records::cancelHistoryReplacement, attachments = records.attachments, mixedCapture = records.mixedCapture,
                onOpenAiSession = { id, revision -> records.openAiSession(id, revision) },
                onStartAiSession = records::startAiSession, onStopAiSession = records::stopAiSession,
                onCloseAiSession = records::closeAiSession,
                onOpenTags = records.tags::open, onTagEdit = records.tags::edit, onTagAdd = records.tags::add,
                favorites = records.favorites,
                candidates = records.candidates,
                sourceTags = records.sourceTags,
                sourceMemories = records.sourceMemories,
                localProcessing = records.localProcessing,
                onTagRemove = records.tags::remove, onTagRetry = records.tags::retry, onTagClose = records.tags::close,
                onTaskBegin = records.tasks::begin, onTaskEdit = records.tasks::edit, onTaskCreate = records.tasks::create,
                onTaskChange = records.tasks::change, onTaskRetry = records.tasks::retry,
                onTaskRefresh = records.tasks::refresh, onTaskClose = records.tasks::close,
                onTaskCreationDate = records.tasks::editCreationDate, onTaskBeginDate = records.tasks::beginDate,
                onTaskEditDate = records.tasks::editDate, onTaskSaveDate = records.tasks::saveDate, onTaskCloseDate = records.tasks::closeDate,
                exports = records.exports, recycleBin = records.recycleBin, recordPackages = records.recordPackages, factRecaps = records.factRecaps,
                recordPackagePicker = packagePicker, currentRecords = { records.state.value }) }
        }
    }
    override fun onResume() { super.onResume(); packagePicker?.resumed() }
    override fun onPause() { packageRecords?.factRecaps?.cancel(); packagePicker?.paused(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { packagePicker?.save(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        packagePicker?.let { packageRecords?.recordPackages?.detachPickerOwner(it); it.destroy() }
        packagePicker = null; packageRecords = null
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode in 1..65535) {
            packagePicker?.result(requestCode, resultCode, data)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }
}

/** A callback belongs to one live navigation session, including repeated opens of the same ID. */
private class NavigationOwner {
    private val lock = Any()
    private val current = mutableStateOf(0L)
    private var alive = true
    val generation get() = synchronized(lock) { current.value }
    var active: Boolean
        get() = synchronized(lock) { alive }
        set(value) = synchronized(lock) { alive = value }
    fun advance() = synchronized(lock) { current.value = Math.addExact(current.value, 1) }
    fun tryAdvance(expected: Long): Boolean = synchronized(lock) {
        if (!alive || current.value != expected) false
        else { current.value = Math.addExact(current.value, 1); true }
    }
    fun publish(expected: Long, action: () -> Unit) = synchronized(lock) {
        if (alive && current.value == expected) action()
    }
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
    onRequestCaptureDiscard: () -> Unit = {},
    onConfirmCaptureDiscard: (Long) -> Unit = {},
    onCancelCaptureDiscard: (Long) -> Unit = {},
    previewEntryId: String? = null,
    onBeginOriginalEdit: (String, Int) -> Unit = { _, _ -> },
    onOriginalEditChange: (Long, String) -> Unit = { _, _ -> },
    onOriginalEditSave: (Long) -> Unit = {},
    onOriginalEditCancel: (Long) -> Unit = {},
    onOriginalEditKeep: (Long) -> Unit = {},
    onResumeOriginalEdit: (String, String) -> Unit = { _, _ -> },
    onBeginOriginalHistory: (String, Int) -> Unit = { _, _ -> },
    onSelectOriginalRevision: (Long, Int) -> Unit = { _, _ -> },
    onBackOriginalHistory: (Long) -> Unit = {},
    onRetryOriginalHistory: (Long) -> Unit = {},
    onEditOriginalHistory: (Long, Long, Int, Int) -> Unit = { _, _, _, _ -> },
    onConfirmHistoryReplacement: (Long, Long, Long) -> Unit = { _, _, _ -> },
    onCancelHistoryReplacement: (Long, Long, Long) -> Unit = { _, _, _ -> },
    attachments: LocalAttachmentController? = null,
    mixedCapture: com.t1doo.aiyou.records.MixedCaptureController? = null,
    factRecaps: com.t1doo.aiyou.records.FactRecapController? = null,
    display: UiPreferencesState = UiPreferencesState(),
    onDisplayChange: (UiPreferences) -> Unit = {},
    onRetryDisplay: () -> Unit = {},
    previewSettings: Boolean = false,
    onOpenAiSession: (String, Int) -> Unit = { _, _ -> },
    onStartAiSession: (Long) -> Unit = {},
    onStopAiSession: (Long) -> Unit = {},
    onCloseAiSession: (Long) -> Unit = {},
    currentRecords: (() -> LocalRecordUiState?)? = null,
    onOpenTags: (String, Int) -> Unit = { _, _ -> },
    onTagEdit: (Long, Long, String) -> Unit = { _, _, _ -> },
    onTagAdd: (Long, Long, String) -> Unit = { _, _, _ -> },
    onTagRemove: (Long, Long, String, String) -> Unit = { _, _, _, _ -> },
    onTagRetry: (Long) -> Unit = {},
    onTagClose: (Long) -> Unit = {},
    favorites: com.t1doo.aiyou.records.RecordFavoriteController? = null,
    candidates: com.t1doo.aiyou.records.LocalCandidateController? = null,
    sourceTags: com.t1doo.aiyou.records.SourceTagController? = null,
    sourceMemories: com.t1doo.aiyou.records.SourceMemoryController? = null,
    localProcessing: com.t1doo.aiyou.records.LocalProcessingController? = null,
    onTaskBegin: () -> Unit = {},
    onTaskEdit: (Long, String) -> Unit = { _, _ -> },
    onTaskCreate: (Long, String) -> Unit = { _, _ -> },
    onTaskChange: (String, Long, com.t1doo.aiyou.model.ManualTaskStatus) -> Unit = { _, _, _ -> },
    onTaskRetry: () -> Unit = {},
    onTaskRefresh: () -> Unit = {},
    onTaskClose: (Long) -> Unit = {},
    onTaskCreationDate: (Long, com.t1doo.aiyou.model.ManualTaskDateInput) -> Unit = { _, _ -> },
    onTaskBeginDate: (String, Long, com.t1doo.aiyou.model.ManualTaskDateOperation) -> Unit = { _, _, _ -> },
    onTaskEditDate: (Long, com.t1doo.aiyou.model.ManualTaskDateInput) -> Unit = { _, _ -> },
    onTaskSaveDate: (Long, com.t1doo.aiyou.model.ManualTaskDateInput) -> Unit = { _, _ -> },
    onTaskCloseDate: (Long) -> Unit = {},
    taskClock: java.time.Clock = java.time.Clock.systemUTC(),
    exports: com.t1doo.aiyou.records.OriginalExportController? = null,
    recycleBin: com.t1doo.aiyou.records.RecycleBinController? = null,
    recordPackages: com.t1doo.aiyou.records.RecordPackageController? = null,
    recordPackagePicker: com.t1doo.aiyou.records.RecordPackagePicker? = null,
    onRefreshLocalSearch: () -> Unit = {},
    onRebuildLocalSearch: () -> Unit = {},
) {
    val navigation = remember { NavigationOwner() }
    DisposableEffect(navigation) { onDispose { navigation.active = false } }
    val generation = navigation.generation
    fun ownsNavigation() = navigation.active && navigation.generation == generation
    var selectedName by rememberSaveable { mutableStateOf(previewDestination.name) }
    var showSettings by rememberSaveable { mutableStateOf(previewSettings) }
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
    val recapList = remember { LazyListState() }
    var recapRoute by remember { mutableStateOf(false) }
    DisposableEffect(factRecaps) { onDispose { factRecaps?.close() } }
    val currentLocal by rememberUpdatedState(local)
    val currentRecordsProvider by rememberUpdatedState(currentRecords)
    // Construct directly: rememberTextFieldState has a Saver and would put the query in Bundle.
    var searchQuery by remember { mutableStateOf(TextFieldState()) }
    var searchFrom by remember { mutableStateOf(TextFieldState()) }
    var searchThrough by remember { mutableStateOf(TextFieldState()) }
    var searchMode by remember { mutableStateOf(OriginalSearchMode.KEYWORDS) }
    var searchTag by remember { mutableStateOf<String?>(null) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var searchModeGeneration by remember { mutableLongStateOf(0L) }
    fun searchSource() = if (currentRecordsProvider != null) currentRecordsProvider?.invoke() else currentLocal
    fun searchInput(): OriginalSearchInput {
        val query = searchQuery.text
        val from = searchFrom.text
        val through = searchThrough.text
        val tagsSource = searchSource()
        return OriginalSearchInput(query.toString(), from.toString(), through.toString(), searchMode,
            OriginalSearchInputOwnership(query, from, through, searchModeGeneration), searchTag,
            if (searchTag != null && tagsSource?.manualTagsAvailable == true) tagsSource.manualTags else null,
            onlyFavorites,
            if (onlyFavorites && tagsSource?.favoritesAvailable == true) tagsSource.favorites else null)
    }
    val search = remember {
        LocalOriginalSearchController(
            currentSource = { searchSource() },
            currentInput = { searchInput() }, dispatcher = kotlinx.coroutines.Dispatchers.IO,
            onInvalidated = onRefreshLocalSearch,
            matcher = { entries, input ->
                val owner = searchSource()?.localSearch
                if (owner == null) com.t1doo.aiyou.records.LocalOriginalSearchMatcher.match(entries, input)
                else com.t1doo.aiyou.records.LocalOriginalSearchMatcher.indexed(entries, input, owner)
            })
    }
    DisposableEffect(search) { onDispose { search.close() } }
    val searchEmission by search.state.collectAsState()
    val renderedSearchInput = searchInput()
    val searchState = searchEmission.let { search.visible(local, renderedSearchInput) }
    var searchReturnAnchor by remember { mutableStateOf<OriginalSearchReturnAnchor?>(null) }
    var searchReturnInput by remember { mutableStateOf<OriginalSearchInput?>(null) }
    var searchReturnSource by remember { mutableStateOf<List<com.t1doo.aiyou.model.StoredEntry>?>(null) }
    var readerFromSearch by remember { mutableStateOf(false) }
    var searchLocation by remember { mutableStateOf<com.t1doo.aiyou.records.OriginalReadingLocation?>(null) }
    fun clearSearch() {
        search.clear()
        searchQuery.edit { replace(0, length, "") }
        searchFrom.edit { replace(0, length, "") }
        searchThrough.edit { replace(0, length, "") }
        searchQuery = TextFieldState()
        searchFrom = TextFieldState()
        searchThrough = TextFieldState()
        searchMode = OriginalSearchMode.KEYWORDS
        searchTag = null
        onlyFavorites = false
        searchModeGeneration++
        searchReturnAnchor = null
        searchReturnInput = null
        searchReturnSource = null
        readerFromSearch = false
        searchLocation = null
    }
    val context = LocalContext.current
    val selected = Destination.valueOf(selectedName)
    val packageUi = local?.recordPackage
    val browsingPackage = packageUi != null && selected == Destination.MEMORIES && detailEntryId == null && !showSettings && !demo
    val recycleUi = local?.recycleBin
    val browsingRecycle = recycleUi?.browsing == true && selected == Destination.MEMORIES && detailEntryId == null && !showSettings && !demo
    val reading = detailEntryId != null && !showSettings && !demo && local != null && selected != Destination.TASKS && !browsingRecycle
    SideEffect {
        search.sync(selected == Destination.MEMORIES && !recapRoute && !reading && !browsingRecycle && !browsingPackage && !showSettings && !demo &&
            local != null && local.originalEdit == null && local.originalHistory == null)
        if (!reading && searchReturnAnchor != null &&
            (renderedSearchInput.copy(tagSnapshot = null) != searchReturnInput?.copy(tagSnapshot = null) || searchSource()?.entries !== searchReturnSource)) {
            searchReturnAnchor = null; searchReturnInput = null; searchReturnSource = null
        }
    }
    var companionPanel by rememberSaveable { mutableStateOf(false) }
    val companionVisible = display.ready && display.preferences.showCompanion && !showSettings && !reading && !browsingRecycle && !demo
    val motionAllowed = companionMotionAllowed(!display.ready || display.preferences.reduceMotion)
    val feedbackId = local?.saveFeedbackId ?: 0L
    var handledFeedback by rememberSaveable { mutableLongStateOf(feedbackId) }
    var companionPulse by remember { mutableLongStateOf(0L) }
    var companionSaved by remember { mutableStateOf(false) }
    LaunchedEffect(feedbackId, companionVisible) {
        companionSaved = false
        if (feedbackId != handledFeedback) {
            handledFeedback = feedbackId
            if (companionVisible && local?.error == null && local?.saveNotice != null) {
                companionSaved = true
                companionPulse++
                delay(800)
                companionSaved = false
            }
        }
        if (!companionVisible) companionPanel = false
    }
    val companion: (@Composable () -> Unit)? = if (companionVisible) ({
        CompanionEntry(if (companionSaved) MascotState.Saved else MascotState.Idle,
            companionPulse, motionAllowed, onOpen = { companionPanel = true })
    }) else null
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val origin = selected
    val readerId = detailEntryId
    val attachmentPanel = attachments?.state?.collectAsState()?.value
    val editing = if (reading) local?.originalEdit?.takeIf { it.entryId == readerId } else null
    val history = if (reading) local?.originalHistory?.takeIf { it.entryId == readerId } else null
    val visibleAiSession = local?.ai?.takeIf { reading && editing == null && history == null &&
        attachmentPanel == null && it.source.sourceId == readerId }?.sessionId
    // Leaving this reader (including composition disposal) retires exactly its AI session.
    DisposableEffect(visibleAiSession) {
        onDispose { visibleAiSession?.let(onCloseAiSession) }
    }
    val historyList = remember(history?.sessionId) { LazyListState() }
    val originalScroll = remember(selected, readerId, local?.entries?.firstOrNull { it.id == readerId }?.currentRevision) { ScrollState(0) }
    val historicalScroll = remember(readerId, history?.sessionId, history?.selectedRevision) { ScrollState(0) }
    fun canShowPicture(panel: AttachmentUiState, source: LocalRecordUiState?) =
        panel.picture is PlatformAttachmentPicture && !panel.unavailable && panel.error == null &&
            source != null && source.ready && !source.opening && source.error == null && panel.hasRecordAccess(source)
    val viewingPicture = attachmentPanel?.let { canShowPicture(it, local) } == true
    fun ownsEdit(sessionId: Long): Boolean = ownsNavigation() && reading && detailEntryId == readerId &&
        selectedName == origin.name && !showSettings && !demo &&
        currentLocal?.originalHistory == null && currentLocal?.originalEdit?.let { it.sessionId == sessionId && it.entryId == readerId } == true
    fun ownsHistory(sessionId: Long) = ownsNavigation() && attachments?.state?.value == null && reading && detailEntryId == readerId &&
        selectedName == origin.name && !showSettings && !demo &&
        currentLocal?.originalHistory?.let { it.sessionId == sessionId && it.entryId == readerId } == true
    val backHistory: (Long) -> Unit = { sessionId ->
        if (ownsHistory(sessionId)) { navigation.advance(); onBackOriginalHistory(sessionId) }
    }
    val selectHistory: (Long, Int) -> Unit = { sessionId, revision ->
        if (ownsHistory(sessionId) && currentLocal?.originalHistory?.let { !it.loading && it.error == null && !it.unavailable &&
                it.revisions.any { r -> r.revision == revision } } == true) {
            navigation.advance(); onSelectOriginalRevision(sessionId, revision)
        }
    }
    val retryHistory: (Long) -> Unit = { sessionId ->
        if (ownsHistory(sessionId) && currentLocal?.originalHistory?.loading == false) {
            navigation.advance(); onRetryOriginalHistory(sessionId)
        }
    }
    val discardEdit: (Long) -> Unit = { sessionId ->
        if (ownsEdit(sessionId) && currentLocal?.originalEdit?.canCancel == true) {
            navigation.advance(); discardEditSession = null; onOriginalEditCancel(sessionId)
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
    val keepEdit: (Long) -> Unit = { sessionId ->
        if (ownsEdit(sessionId) && currentLocal?.originalEdit?.canCancel == true && discardEditSession == null)
            onOriginalEditKeep(sessionId)
    }
    val returnToList: () -> Unit = {
        if (ownsNavigation() && reading) {
            currentLocal?.manualTagEditor?.takeIf { it.entryId == detailEntryId }?.let { onTagClose(it.session) }
            val edit = currentLocal?.originalEdit?.takeIf { it.entryId == readerId }
            val activeHistory = currentLocal?.originalHistory?.takeIf { it.entryId == readerId }
            val activeAttachment = attachments?.state?.value
            if (activeAttachment != null) {
                if (!activeAttachment.saving) {
                    navigation.advance()
                    if (canShowPicture(activeAttachment, currentLocal)) attachments?.closePicture(activeAttachment.sessionId)
                    else attachments?.close(activeAttachment.sessionId)
                }
            } else if (activeHistory != null) backHistory(activeHistory.sessionId)
            else if (edit != null) cancelEdit(edit.sessionId) else {
                navigation.advance()
                val latest = currentLocal
                val last = if (origin == Destination.NOW) latest?.entries?.take(5)?.lastOrNull() else latest?.entries?.lastOrNull()
                tailReturnRequest = if (!readerFromSearch && latest?.ready == true && !latest.opening && latest.error == null && last?.id == readerId)
                    TailReturnRequest(origin, checkNotNull(readerId), navigation.generation) else null
                detailEntryId = null
                searchLocation = null
                if(recapRoute) { tailReturnRequest=null;factRecaps?.validate() }
                if (readerFromSearch && origin == Destination.MEMORIES) {
                    search.sync(true)
                    searchReturnSource = search.state.value.entries
                }
            }
        }
    }
    val selectDestination: (Destination) -> Unit = { destination ->
        if (ownsNavigation() && currentLocal?.recycleBin?.busy != true && currentLocal?.originalEdit == null && currentLocal?.originalHistory == null && attachments?.state?.value == null &&
            navigation.tryAdvance(generation)) {
            currentLocal?.recycleBin?.let { recycleBin?.close(it.session) }
            currentLocal?.recordPackage?.let { recordPackages?.close(it.session) }
            factRecaps?.close();recapRoute=false
            clearSearch()
            tailReturnRequest = null
            searchLocation = null
            selectedName = destination.name; detailEntryId = null; showSettings = false; showLicenses = false
        }
    }
    val openEntry: (String) -> Unit = { id ->
        if (ownsNavigation() && selectedName == origin.name && !showSettings && !demo && detailEntryId == null &&
            navigation.tryAdvance(generation)) {
            readerFromSearch = origin == Destination.MEMORIES && searchState.stage == OriginalSearchStage.READY
            search.pause()
            tailReturnRequest = null
            searchLocation = null
            if (origin == Destination.NOW) selectedNowId = id else selectedMemoriesId = id
            detailEntryId = id
        }
    }
    val openSearchResult: (com.t1doo.aiyou.records.OriginalSearchHit) -> Unit = { hit ->
        if (ownsNavigation() && selected == Destination.MEMORIES && !reading && !showSettings && !demo &&
            search.canOpen(searchState, hit)) {
            var locationToken: com.t1doo.aiyou.records.OriginalReadingLocation? = null
            var locationGeneration: Long? = null
            search.open(searchState, hit, rollback = {
                locationGeneration?.let { token -> navigation.publish(token) {
                    if (detailEntryId == hit.entry.id && searchLocation === locationToken) {
                        navigation.advance()
                        detailEntryId = null; searchLocation = null; readerFromSearch = false
                        searchReturnAnchor = null; searchReturnInput = null; searchReturnSource = null
                        search.invalidateInput()
                    }
                } }
            }) {
                navigation.publish(generation) { if (ownsNavigation() && selectedName == Destination.MEMORIES.name && detailEntryId == null &&
                    !showSettings && !demo && search.canOpen(searchState, hit)) {
                    val index = memoriesList.firstVisibleItemIndex
                    searchReturnAnchor = OriginalSearchReturnAnchor(memoriesList.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String,
                        index, memoriesList.firstVisibleItemScrollOffset)
                    searchReturnInput = searchState.input
                    searchReturnSource = searchState.entries
                    openEntry(hit.entry.id)
                    if (detailEntryId == hit.entry.id) {
                        locationToken = com.t1doo.aiyou.records.OriginalReadingLocation(
                            hit.entry.id, hit.entry.currentRevision, com.t1doo.aiyou.model.OriginalExports.hash(hit.entry.originalText),
                            hit.matchStartCodePoint, hit.matchEndCodePoint)
                        searchLocation = locationToken
                        locationGeneration = navigation.generation
                    }
                } }
            }
        }
    }
    LocalOriginalSearchObserver.current?.let { observer ->
        SideEffect {
            observer.onResults(searchState, openSearchResult)
            observer.onDiagnostics(search.visibilityDiagnostics(local, renderedSearchInput))
            observer.onInputs(searchQuery, searchFrom, searchThrough)
        }
    }
    val canCopy: (String, Int) -> Boolean = { id, revision ->
        val latest = currentLocal
        ownsNavigation() && detailEntryId == id && selectedName == origin.name && !showSettings && !demo &&
            latest != null && latest.ready && !latest.opening && latest.error == null &&
            latest.originalEdit == null && latest.originalHistory == null && latest.recycleBin == null && attachments?.state?.value == null &&
            latest.entries.any { it.id == id && it.currentRevision == revision }
    }
    val beginEdit: (String, Int) -> Unit = { id, revision ->
        if (canCopy(id, revision) && currentLocal?.saving == false && currentLocal?.savePending == false) {
            navigation.advance(); onBeginOriginalEdit(id, revision)
        }
    }
    val beginAttachments: (String, Int) -> Unit = { id, revision ->
        if (canCopy(id, revision) && currentLocal?.saving == false && currentLocal?.savePending == false) {
            navigation.advance(); attachments?.open(id, revision)
        }
    }
    val beginHistory: (String, Int) -> Unit = { id, revision ->
        val latest = currentLocal
        if (ownsNavigation() && reading && detailEntryId == id && selectedName == origin.name && !showSettings && !demo &&
            latest != null && latest.ready && !latest.opening && latest.error == null && !latest.saving && !latest.savePending &&
            latest.originalHistory == null && attachments?.state?.value == null && latest.originalEdit?.canCancel != false &&
            latest.entries.any { it.id == id && it.currentRevision == revision }) {
            navigation.advance(); onBeginOriginalHistory(id, revision)
        }
    }
    val canCopyHistory: (String, Int) -> Boolean = { id, revision ->
        val latest = currentLocal
        val live = latest?.originalHistory
        history != null && ownsHistory(history.sessionId) && latest != null && latest.ready && !latest.opening && latest.error == null &&
            live != null && live.requestId == history.requestId && !live.loading && live.error == null && !live.unavailable &&
            live.entryId == id && live.selectedRevision == revision && live.revisions.singleOrNull { it.revision == revision }?.entryId == id &&
            latest.entries.any { it.id == id && it.currentRevision == live.currentRevision }
    }
    val beginHistoricalAttachments: (String, Int) -> Unit = { id, revision ->
        val latest = currentLocal
        val live = latest?.originalHistory
        if (attachments != null && canCopyHistory(id, revision) && latest?.saving == false && !latest.savePending &&
            latest.originalEdit?.canCancel != false && live != null && live.replacement == null && live.currentRevision != null) {
            navigation.advance()
            attachments?.openHistorical(id, revision, AttachmentHistoryOwner(live.sessionId, live.requestId, live.currentRevision))
        }
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
            navigation.advance()
            onEditOriginalHistory(history.sessionId, history.requestId, revision, currentRevision)
        }
    }
    val confirmHistoryReplacement: (Long) -> Unit = { replacementId ->
        if (history?.selectedRevision != null && canCopyHistory(history.entryId, history.selectedRevision) &&
            currentLocal?.originalHistory?.replacement?.id == replacementId) {
            navigation.advance()
            onConfirmHistoryReplacement(history.sessionId, history.requestId, replacementId)
        }
    }
    val cancelHistoryReplacement: (Long) -> Unit = { replacementId ->
        if (history != null && ownsHistory(history.sessionId) &&
            currentLocal?.originalHistory?.let { it.requestId == history.requestId && it.replacement?.id == replacementId } == true) {
            navigation.advance()
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
            editHistory, confirmHistoryReplacement, cancelHistoryReplacement, beginHistoricalAttachments)
        SideEffect {
            observer.onNavigation(callbacks)
            if (selected != Destination.TASKS) observer.onRecordList(if (selected == Destination.NOW) nowList else memoriesList, imeBottomPx)
        }
    }
    BackHandler(enabled = showSettings) {
        if (ownsNavigation()) { navigation.advance(); if (showLicenses) showLicenses = false else showSettings = false }
    }
    local?.manualTagEditor?.takeIf { reading && it.entryId == detailEntryId }?.let { editor ->
        val tagLocal = checkNotNull(local)
        com.t1doo.aiyou.records.ManualTagDialog(editor,
            onEdit = { session, version, text -> if (ownsNavigation()) onTagEdit(session, version, text) },
            onAdd = { session, version, text -> if (ownsNavigation()) onTagAdd(session, version, text) },
            onRemove = { session, version, name, input -> if (ownsNavigation()) onTagRemove(session, version, name, input) },
            onRetry = { if (ownsNavigation()) onTagRetry(it) }, onClose = { if (ownsNavigation()) onTagClose(it) },
            sourceAvailable = tagLocal.ready && !tagLocal.opening && tagLocal.error == null &&
                tagLocal.entries.any { it.id == editor.entryId && it.currentRevision == editor.revision })
    }
    BackHandler(enabled = reading && recycleUi == null, onBack = returnToList)
    BackHandler(enabled = recapRoute && !reading && !showSettings && selected==Destination.MEMORIES) {
        if(ownsNavigation()) { navigation.advance();factRecaps?.close();recapRoute=false }
    }
    fun ownsRecycleRoute() = ownsNavigation() && !showSettings && !demo &&
        currentLocal?.recycleBin?.session == recycleUi?.session &&
        (if (recycleUi?.browsing == true) selectedName == Destination.MEMORIES.name && detailEntryId == null else reading)
    SideEffect { if (recycleUi != null && !ownsRecycleRoute()) recycleBin?.close(recycleUi.session) }
    if (recycleBin != null && recycleUi != null && ownsRecycleRoute()) {
        com.t1doo.aiyou.records.RecycleConfirmationDialog(checkNotNull(local), recycleUi, recycleBin, ::ownsRecycleRoute)
    }
    BackHandler(enabled = browsingRecycle && recycleUi?.busy == false) {
        if (ownsRecycleRoute()) { recycleBin?.close(checkNotNull(recycleUi).session); navigation.advance() }
    }
    val exportUi = local?.originalExport
    fun ownsExportRoute() = ownsNavigation() && selectedName == Destination.MEMORIES.name &&
        detailEntryId == null && !showSettings && !demo && currentLocal?.recycleBin == null && currentLocal?.recordPackage == null
    fun ownsPackageRoute() = ownsNavigation() && selectedName == Destination.MEMORIES.name &&
        detailEntryId == null && !showSettings && !demo && currentLocal?.recycleBin == null &&
        currentLocal?.recordPackage?.session == packageUi?.session
    SideEffect { if (packageUi != null && !ownsPackageRoute()) recordPackages?.close(packageUi.session) }
    BackHandler(enabled = browsingPackage) {
        if (ownsPackageRoute()) { recordPackages?.close(checkNotNull(packageUi).session); navigation.advance() }
    }
    SideEffect {
        if (exportUi != null && !ownsExportRoute()) exports?.close(exportUi.session)
    }
    if (exports != null && exportUi != null && ownsExportRoute()) {
        com.t1doo.aiyou.records.OriginalExportPicker(exports, exportUi,
            canLaunch = local?.ready == true && local.error == null && !local.opening, ::ownsExportRoute)
        com.t1doo.aiyou.records.OriginalExportDialog(checkNotNull(local), exportUi, exports, ::ownsExportRoute)
    }

    Scaffold(
        modifier = Modifier.imePadding().onSizeChanged { scaffoldHeightPx = it.height },
        topBar = {
            NotebookAppBar(
                if (browsingPackage) "加密原文包" else if (browsingRecycle) "回收站" else if (viewingPicture) "查看图片" else if (attachmentPanel != null) "记录附件" else if (history != null) "原文版本" else if (editing != null) "编辑原文" else if (reading) "记录原文" else if (showLicenses) "开源许可" else if (showSettings) "设置" else if(recapRoute) "本机回顾" else "爱忆 AIyou",
                if (browsingPackage) "返回列表" else if (viewingPicture) "返回附件列表" else if (attachmentPanel != null) { if (attachmentPanel.readOnly) "返回历史原文" else "返回原文" }
                else if (history != null) { if (history.selectedRevision != null) "返回版本列表" else if (editing != null) "返回编辑" else "返回原文" }
                else if (editing != null) "取消编辑" else if(reading && recapRoute) "返回回顾" else if (reading || browsingRecycle) "返回列表" else if (showLicenses) "返回设置" else if (showSettings) "返回" else if(recapRoute) "返回回忆" else "设置",
                actionEnabled = recycleUi?.busy != true && attachmentPanel?.saving != true && (history != null || editing?.canCancel != false),
            ) {
                if (browsingPackage && ownsPackageRoute()) {
                    recordPackages?.close(checkNotNull(packageUi).session); navigation.advance()
                } else if (browsingRecycle && ownsRecycleRoute()) {
                    recycleBin?.close(checkNotNull(recycleUi).session); navigation.advance()
                } else if (reading && recycleUi == null) returnToList() else if(recapRoute && ownsNavigation()) {
                    navigation.advance();factRecaps?.close();recapRoute=false
                } else if (ownsNavigation() && recycleUi == null &&
                    navigation.tryAdvance(generation)) {
                    clearSearch()
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
            SettingsScreen(display, onDisplayChange, onRetryDisplay, attachments != null,
                demo, ComponentState.valueOf(stateName),
                onLicenses = { if (ownsNavigation()) { navigation.advance(); showLicenses = true } },
                onDemo = { if (ownsNavigation()) { navigation.advance(); clearSearch(); demo = !demo } },
                onDemoState = { if (ownsNavigation()) { navigation.advance(); stateName = it.name } },
                modifier = modifier, hasRecordPackages=local?.supportsRecordPackages==true)
        } else if (browsingPackage && local != null && recordPackages != null && packageUi != null) {
            com.t1doo.aiyou.records.RecordPackageScreen(local, packageUi, recordPackages, recordPackagePicker, ::ownsPackageRoute, modifier)
        } else if (browsingRecycle && local != null && recycleBin != null && recycleUi != null) {
            com.t1doo.aiyou.records.RecycleBinScreen(local, recycleUi, recycleBin, ::ownsRecycleRoute, modifier)
        } else if (attachmentPanel != null && attachments != null && reading && local != null) {
            val panel = if (!local.ready || local.opening || local.error != null ||
                !attachmentPanel.hasRecordAccess(local))
                attachmentPanel.copy(picture = null, unavailable = true,
                    error = "当前原文暂不可读取或版本已变化。原文与附件已保留，请返回原文。") else attachmentPanel
            AttachmentPicker(attachments, attachmentPanel.selection, canLaunch = !panel.unavailable)
            LocalAttachmentScreen(panel, onSelect = { attachments.select(panel.sessionId, it) },
                onRefresh = { attachments.refresh(panel.sessionId) }, onCancel = { attachments.cancel(panel.sessionId) },
                onRetrySave = { attachments.retrySave(panel.sessionId) }, onView = { attachments.view(panel.sessionId, it) },
                onClosePicture = { attachments.closePicture(panel.sessionId) }, modifier = modifier,
                onRetryRecords = if (local.ready && local.error != null && !local.opening) retryReading else null,
                onMetadataChoice = { attachments.setJpegMetadataChoice(panel.sessionId, it) }, showPictureClose = false)
        } else if (history != null && local != null) {
            OriginalHistoryScreen(history, local, historyList, selectHistory, backHistory, retryHistory,
                retryReading, copyHistory, canCopyHistory, modifier, editHistory, confirmHistoryReplacement, cancelHistoryReplacement,
                onAttachments = if (attachments != null) beginHistoricalAttachments else null, originalScrollState = historicalScroll)
        } else if (editing != null && local != null) {
            OriginalEditScreen(editing, local, changeEdit, saveEdit, cancelEdit, modifier, onRetry = retryReading, onHistory = beginHistory, onKeep = keepEdit)
        } else if (reading && local != null) {
            OriginalReadingScreen(checkNotNull(readerId), local, onRetry = retryReading,
                onCopy = copy, canCopy = canCopy, modifier = modifier, onEdit = beginEdit, onHistory = beginHistory,
                onAttachments = if (attachments != null) beginAttachments else null, originalScrollState = originalScroll,
                onOpenAi = { id, revision -> if (canCopy(id, revision)) onOpenAiSession(id, revision) },
                onStartAi = onStartAiSession, onStopAi = onStopAiSession, onCloseAi = onCloseAiSession,
                onOpenTags = { id, revision -> if (canCopy(id, revision)) onOpenTags(id, revision) },
                location = searchLocation,
                requiredSource = if(recapRoute) searchLocation else null,
                candidateController = candidates,
                sourceTagController = sourceTags,
                sourceMemoryController = sourceMemories,
                localProcessingController = localProcessing,
                canUseCandidates = canCopy,
                onCandidateLocation = { location ->
                    if (canCopy(location.entryId, location.revision)) {
                        val live = currentRecords?.invoke() ?: local
                        val entry = live.entries.singleOrNull { it.id == location.entryId && it.currentRevision == location.revision }
                        if (entry != null && com.t1doo.aiyou.model.OriginalExports.hash(entry.originalText) == location.contentHash)
                            searchLocation = location
                    }
                },
                onSetFavorite = favorites?.let { controller -> { id, revision, hash, version, desired ->
                    if (canCopy(id, revision)) controller.set(id, revision, hash, version, desired)
                } },
                onRetryFavorite = { id -> if (ownsNavigation() && detailEntryId == id && !showSettings && !demo) favorites?.retry(id) },
                onRefreshFavorites = { if (ownsNavigation() && !showSettings && !demo) favorites?.refresh() },
                onMoveToRecycleBin = recycleBin?.let { controller -> { id, revision ->
                    if (canCopy(id, revision)) { navigation.advance(); controller.requestMove(id, revision) }
                } })
        } else if(!demo && local!=null && selected==Destination.MEMORIES && recapRoute && factRecaps!=null) {
            com.t1doo.aiyou.records.FactRecapScreen(local,factRecaps,recapList,{card,images ->
                if(ownsNavigation() && detailEntryId==null && recapRoute && !showSettings) {
                    val routeGeneration=generation
                    factRecaps.source(card) {
                        navigation.publish(routeGeneration) {
                            if(recapRoute && selectedName==Destination.MEMORIES.name && detailEntryId==null && !showSettings && !demo &&
                                currentLocal?.factRecap?.snapshot?.cards?.any { it===card }==true &&
                                currentLocal?.entries?.any { it.id==card.source.sourceId && it.currentRevision.toLong()==card.source.revision &&
                                    com.t1doo.aiyou.model.OriginalExports.hash(it.originalText)==card.contentHash }==true) {
                                navigation.advance();search.pause();readerFromSearch=false;tailReturnRequest=null
                                searchLocation=com.t1doo.aiyou.records.OriginalReadingLocation(card.source.sourceId,card.source.revision.toInt(),card.contentHash,0,0)
                                detailEntryId=card.source.sourceId
                                if(images) attachments?.open(card.source.sourceId,card.source.revision.toInt())
                            }
                        }
                    }
                }
            },modifier,onRetryRecords={if(ownsNavigation() && recapRoute && !reading) onRetry()})
        } else if (!demo && local != null && selected == Destination.TASKS &&
            (local.tasks.supported || local.opening || local.error != null)) {
            fun ownsTasks() = ownsNavigation() && selectedName == Destination.TASKS.name && !showSettings && !demo
            com.t1doo.aiyou.records.ManualTasksScreen(local,
                onBegin = { if (ownsTasks()) onTaskBegin() },
                onEdit = { session, text -> if (ownsTasks()) onTaskEdit(session, text) },
                onCreate = { session, text -> if (ownsTasks()) onTaskCreate(session, text) },
                onChange = { id, version, status -> if (ownsTasks()) onTaskChange(id, version, status) },
                onRetry = { if (ownsTasks()) onTaskRetry() }, onRefresh = { if (ownsTasks()) onTaskRefresh() },
                onClose = { session -> if (ownsTasks()) onTaskClose(session) },
                onRecordsRetry = { if (ownsTasks()) onRetry() }, modifier = modifier, companion = companion,
                onCreationDate = { session, input -> if (ownsTasks()) onTaskCreationDate(session, input) },
                onBeginDate = { id, version, operation -> if (ownsTasks()) onTaskBeginDate(id, version, operation) },
                onEditDate = { session, input -> if (ownsTasks()) onTaskEditDate(session, input) },
                onSaveDate = { session, input -> if (ownsTasks()) onTaskSaveDate(session, input) },
                onCloseDate = { session -> if (ownsTasks()) onTaskCloseDate(session) }, clock = taskClock)
        } else if (!demo && local != null && selected==Destination.NOW && local.supportsMixedCapture && mixedCapture!=null) {
            com.t1doo.aiyou.records.MixedCapturePicker(mixedCapture,local.mixedCapture?.selection)
            com.t1doo.aiyou.records.MixedCaptureScreen(local,mixedCapture,modifier)
        } else if (!demo && local != null && selected != Destination.TASKS) {
            val request = tailReturnRequest?.takeIf { it.destination == selected && it.generation == generation }
            val topPaddingPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
            val bottomPaddingPx = with(density) { contentPadding.calculateBottomPadding().roundToPx() }
            LocalRecordsScreen(selected, local, onDraftChange, onSave, onRetry, modifier, companion = companion,
                onOpenFactRecap = factRecaps?.let { controller -> { if(ownsNavigation() && selected==Destination.MEMORIES && !reading && !showSettings && !demo) {
                    navigation.advance();clearSearch();controller.open();recapRoute=true
                } } },
                onBeginRecordPackage = recordPackages?.let { controller -> { if (ownsNavigation() &&
                    selectedName == Destination.MEMORIES.name && detailEntryId == null && !showSettings && !demo) {
                    navigation.advance(); clearSearch(); controller.open()
                } } },
                onBeginExport = exports?.let { controller -> { if (ownsExportRoute() && navigation.tryAdvance(generation)) {
                    search.pause(); controller.open()
                } } },
                onOpenRecycleBin = recycleBin?.let { controller -> { if (ownsNavigation() && selectedName == Destination.MEMORIES.name &&
                    detailEntryId == null && !showSettings && !demo) { navigation.advance(); clearSearch(); controller.open() } } },
                searchState = searchState, searchQuery = searchQuery, searchFrom = searchFrom, searchThrough = searchThrough,
                onBeforeSearchInput = { field -> search.beforeInputCommit {
                    val source = searchSource()
                    ownsNavigation() && selectedName == Destination.MEMORIES.name && detailEntryId == null &&
                        !showSettings && !demo && source?.ready == true && !source.opening && source.error == null &&
                        (field === searchQuery || field === searchFrom || field === searchThrough)
                } },
                onHistoryInputCommitted = search::inputCommitted,
                onRebuildLocalSearch = { if (ownsNavigation() && selectedName == Destination.MEMORIES.name && detailEntryId == null &&
                    currentLocal?.ready == true && currentLocal?.opening == false && currentLocal?.error == null &&
                    currentLocal?.localSearch != null && currentLocal?.localIndexBusy == false)
                    onRebuildLocalSearch() },
                searchTag = searchTag, onSearchTag = { tag -> search.changeInput { if (ownsNavigation()) { searchTag = tag; searchModeGeneration++ } } },
                onlyFavorites = onlyFavorites, onSearchFavorites = { value -> search.changeInput {
                    if (ownsNavigation() && detailEntryId == null && selectedName == Destination.MEMORIES.name && !showSettings && !demo) {
                        onlyFavorites = value; searchModeGeneration++
                    }
                } },
                onRefreshFavorites = { if (ownsNavigation() && detailEntryId == null && selectedName == Destination.MEMORIES.name && !showSettings && !demo) favorites?.refresh() },
                searchMode = searchMode, onSearchMode = { search.changeInput { if (ownsNavigation()) {
                    searchModeGeneration++
                    searchMode = if (searchMode == OriginalSearchMode.KEYWORDS) OriginalSearchMode.EXACT else OriginalSearchMode.KEYWORDS
                } } },
                onClearSearch = { if (ownsNavigation()) clearSearch() }, onOpenSearchResult = openSearchResult,
                searchReturnAnchor = searchReturnAnchor,
                ownsSearchReturn = { ownsNavigation() && detailEntryId == null && selectedName == Destination.MEMORIES.name &&
                    !showSettings && !demo && searchReturnInput?.copy(tagSnapshot = null) == searchInput().copy(tagSnapshot = null) && currentLocal?.entries === searchReturnSource &&
                    search.visible(currentLocal, searchInput()) === searchState },
                onSearchReturnHandled = { if (ownsNavigation()) {
                    searchReturnAnchor = null; searchReturnInput = null; searchReturnSource = null
                } },
                onRequestCaptureDiscard = { if (ownsNavigation()) onRequestCaptureDiscard() },
                onConfirmCaptureDiscard = { if (ownsNavigation()) onConfirmCaptureDiscard(it) },
                onCancelCaptureDiscard = { if (ownsNavigation()) onCancelCaptureDiscard(it) },
                listState = if (selected == Destination.NOW) nowList else memoriesList,
                selectedEntryId = if (selected == Destination.NOW) selectedNowId else selectedMemoriesId,
                onOpenEntry = openEntry, tailReturnEntryId = request?.entryId, tailReturnGeneration = request?.generation,
                expectedReturnHeightPx = (scaffoldHeightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(0),
                returnViewportReady = scaffoldHeightPx > 0 &&
                    (imeVisible || (navigationHeightPx > 0 && bottomPaddingPx >= navigationHeightPx)),
                ownsTailReturn = { ownsNavigation() && request != null && tailReturnRequest == request &&
                    detailEntryId == null && selectedName == request.destination.name && !showSettings && !demo },
                onTailReturnHandled = { if (ownsNavigation() && request != null && tailReturnRequest == request) tailReturnRequest = null },
                onResumeEdit = { id, draftId ->
                    val latest = currentLocal
                    if (ownsNavigation() && detailEntryId == null && !showSettings && !demo && latest != null &&
                        latest.ready && !latest.opening && latest.error == null && !latest.saving && !latest.savePending &&
                        latest.originalEdit == null && latest.originalHistory == null && attachments?.state?.value == null &&
                        latest.pendingEdits.any { it.entryId == id && it.id == draftId }) {
                        navigation.advance(); search.pause(); searchReturnAnchor = null; searchReturnInput = null; searchReturnSource = null
                        readerFromSearch = false
                        detailEntryId = id; onResumeOriginalEdit(id, draftId)
                    }
                })
        } else {
            MainScreen(
                destination = selected, demo = demo, modifier = modifier, companion = companion,
                state = if (demo) ComponentState.valueOf(stateName) else ComponentState.NORMAL,
            )
        }
    }
    if (companionVisible && companionPanel) CompanionDialog(onClose = { companionPanel = false }, motionAllowed = motionAllowed)
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
