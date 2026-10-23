package com.t1doo.aiyou

import com.t1doo.aiyou.records.currentTranscriptSource
import com.t1doo.aiyou.records.canUseRecapTranscript
import com.t1doo.aiyou.records.currentImageTextSource
import com.t1doo.aiyou.records.canRetainImageTexts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
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
import com.t1doo.aiyou.records.hasManualPeopleReadingSource
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
    private val reminderClicks=com.t1doo.aiyou.records.ReminderClickNavigation()
    private var reminderClickSignal by mutableLongStateOf(0)
    private var reminderRoute by mutableStateOf<Pair<Long,Long>?>(null)
    private var reminderNavigation by mutableLongStateOf(0)
    private var reminderMessage by mutableStateOf<String?>(null)
    private fun takeReminderClick(intent:android.content.Intent?) {
        val envelope=com.t1doo.aiyou.records.ReminderIntents.read(intent,true)
        reminderClicks.replace(envelope,accessOwner?.controller?.state?.value?:com.t1doo.aiyou.ui.AppAccessState(),
            com.t1doo.aiyou.records.AndroidReminderRuntime.get(this).owners.navigationGeneration())
        reminderClickSignal++
        com.t1doo.aiyou.records.ReminderIntents.consume(intent)
    }
    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);takeReminderClick(intent)}
    private var packagePicker: com.t1doo.aiyou.records.ActivityRecordPackagePicker? = null
    private var packageRecords: LocalRecordsViewModel? = null
    private var accessOwner: com.t1doo.aiyou.ui.AppAccessViewModel? = null
    internal fun admittedRecords(): LocalRecordsViewModel? = accessOwner?.admittedRecords()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeReminderClick(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val access = ViewModelProvider(this)[com.t1doo.aiyou.ui.AppAccessViewModel::class.java]
        accessOwner = access
        val preferences = ViewModelProvider(this)[UiPreferencesViewModel::class.java]
        setContent {
            val accessState by access.controller.state.collectAsState()
            LaunchedEffect(reminderClickSignal,accessState.phase,accessState.session,reminderRoute) {
                val route=reminderRoute
                val request=reminderClicks.currentForRead(accessState,route?.takeIf{it.first==accessState.session}?.second)?:return@LaunchedEffect
                val accepted=access.reminderClick(accessState.session,request.envelope,request.restoreGeneration)
                if(reminderClicks.finish(request,accessState.session,access.controller.state.value,reminderRoute?.takeIf{it.first==accessState.session}?.second)){
                    reminderMessage=if(accepted)null else "提醒已变化，请在待办中查看当前状态。"
                    reminderNavigation++
                }
            }
            reminderMessage?.let{message->AlertDialog(onDismissRequest={reminderMessage=null},title={Text("待办提醒")},text={Text(message)},confirmButton={TextButton(onClick={reminderMessage=null}){Text("知道了")}})}
            SideEffect {
                // This Activity's access gate owns FLAG_SECURE, including after configuration recreation.
                if (accessState.preference?.enabled != false || !accessState.canReadRecords)
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            AIyouTheme {
                com.t1doo.aiyou.ui.AppAccessHost(access.controller) { id ->
                    UnlockedApplication(access, id, preferences, savedInstanceState)
                }
            }
        }
    }

    private var initialPickerStateConsumed = false

    @Composable
    private fun UnlockedApplication(access: com.t1doo.aiyou.ui.AppAccessViewModel, id: Long,
        preferences: UiPreferencesViewModel, savedInstanceState: Bundle?) {
        val session = remember(access, id) { access.session(id) }
        val records = session.records
        val storageUsage = session.storageUsage
        var picker by remember(records) { mutableStateOf<com.t1doo.aiyou.records.ActivityRecordPackagePicker?>(null) }
        DisposableEffect(records) {
            val restore = if (!initialPickerStateConsumed) savedInstanceState else null
            initialPickerStateConsumed = true
            val active = com.t1doo.aiyou.records.ActivityRecordPackagePicker(this@MainActivity, restore)
            records.recordPackages.attachPickerOwner(active)
            packageRecords = records; packagePicker = active; picker = active
            records.audioForeground(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            onDispose {
                records.audioForeground(false)
                records.recordPackages.detachPickerOwner(active)
                active.destroy(); picker = null
                if (packagePicker === active) { packagePicker = null; packageRecords = null }
            }
        }
        val local by records.state.collectAsState()
        val display by preferences.state.collectAsState()
        val accessIdle by records.appAccessIdle.collectAsState()
        AIyouApp(local = local, reminderNavigation = reminderNavigation,
                onNavigationGenerationChanged={generation->
                    if(generation==null){if(reminderRoute?.first==id)reminderRoute=null}
                    else if(access.controller.state.value.let{it.canReadRecords&&it.session==id})reminderRoute=id to generation
                }, display = display, onDisplayChange = preferences::change, onRetryDisplay = preferences::retry, onDraftChange = records::changeText, onSave = records::save, onRetry = records::retry,
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
                audioPlayback=records.audioPlayback,onAudioRouteChanged=records::audioRouteChanged,
                imageGrid = records.imageGrid, storageUsage = storageUsage.controller,
                onOpenAiSession = { id, revision -> records.openAiSession(id, revision) },
                onStartAiSession = records::startAiSession, onStopAiSession = records::stopAiSession,
                onCloseAiSession = records::closeAiSession,
                onOpenTags = records.tags::open, onTagEdit = records.tags::edit, onTagAdd = records.tags::add,
                favorites = records.favorites, eventDates = records.eventDates,
                candidates = records.candidates,
                sourceTags = records.sourceTags, people = records.people, modelConnections = records.modelConnections,
                sourceMemories = records.sourceMemories, memoryInbox = records.memoryInbox, sourcePreferences = records.sourcePreferences, audioTranscripts=records.audioTranscripts, imageTexts=records.imageTexts, unifiedKeywords=records.unifiedKeywords,
                sourceTasks = records.sourceTasks,
                taskProposals = records.taskProposals,
                reminders = records.reminders,
                manualTasks = records.tasks,
                localProcessing = records.localProcessing,
                onTagRemove = records.tags::remove, onTagRetry = records.tags::retry, onTagClose = records.tags::close,
                onTaskBegin = records.tasks::begin, onTaskEdit = records.tasks::edit, onTaskCreate = records.tasks::create,
                onTaskChange = records.tasks::change, onTaskRetry = records.tasks::retry,
                onTaskRefresh = records.tasks::refresh, onTaskClose = records.tasks::close,
                onTaskCreationDate = records.tasks::editCreationDate, onTaskBeginDate = records.tasks::beginDate,
                onTaskEditDate = records.tasks::editDate, onTaskSaveDate = records.tasks::saveDate, onTaskCloseDate = records.tasks::closeDate,
                exports = records.exports, recycleBin = records.recycleBin, recordPackages = records.recordPackages, factRecaps = records.factRecaps,
                recordPackagePicker = picker, currentRecords = { records.state.value },
                appAccessSettings = { com.t1doo.aiyou.ui.AppAccessSettings(access.controller, id,
                    accessIdle && access.retirementReady(id)) })
    }
    override fun onResume() { super.onResume(); packageRecords?.audioForeground(true);packagePicker?.resumed() }
    override fun onPause() { packageRecords?.unifiedKeywords?.retire(); packageRecords?.audioForeground(false);packageRecords?.state?.value?.sourcePreferences?.let { packageRecords?.sourcePreferences?.close(it.session) };packageRecords?.state?.value?.memoryInbox?.let { packageRecords?.memoryInbox?.close(it.session) }; packageRecords?.factRecaps?.cancel(); packagePicker?.paused(); packageRecords?.state?.value?.audioTranscripts?.let{packageRecords?.audioTranscripts?.close(it.session)};packageRecords?.state?.value?.imageTexts?.let{packageRecords?.imageTexts?.close(it.session)}; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { packagePicker?.save(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        reminderClicks.clear()
        packagePicker?.let { packageRecords?.recordPackages?.detachPickerOwner(it); it.destroy() }
        packagePicker = null; packageRecords = null
        accessOwner = null
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
private class NavigationOwner(private val changed:(Long)->Unit={}) {
    private val current = mutableStateOf(0L)
    val gate = com.t1doo.aiyou.records.SearchNavigationGate { current.value=it;changed(it) }
    val generation:Long get() { current.value;return gate.generation }
    var active: Boolean
        get() = gate.active
        set(value) {gate.active=value}
    fun advance() = gate.advance()
    fun tryAdvance(expected:Long)=gate.tryAdvance(expected)
    fun publish(expected:Long,action:()->Unit)=gate.publish(expected,action)
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
    audioPlayback:com.t1doo.aiyou.records.OriginalAudioController?=null,
    onAudioRouteChanged:()->Unit={},
    factRecaps: com.t1doo.aiyou.records.FactRecapController? = null,
    audioTranscripts: com.t1doo.aiyou.records.AudioTranscriptController?=null,
    sourcePreferences: com.t1doo.aiyou.records.SourcePreferenceController? = null,
    memoryInbox: com.t1doo.aiyou.records.SourceMemoryInboxController? = null,
    display: UiPreferencesState = UiPreferencesState(),
    onDisplayChange: (UiPreferences) -> Unit = {},
    onRetryDisplay: () -> Unit = {},
    previewSettings: Boolean = false,
    storageUsage: com.t1doo.aiyou.ui.StorageUsageController? = null,
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
    eventDates: com.t1doo.aiyou.records.RecordEventDateController? = null,
    candidates: com.t1doo.aiyou.records.LocalCandidateController? = null,
    sourceTags: com.t1doo.aiyou.records.SourceTagController? = null,
    people: com.t1doo.aiyou.records.ManualPeopleController? = null,
    modelConnections: com.t1doo.aiyou.records.ModelConnectionsController? = null,
    sourceMemories: com.t1doo.aiyou.records.SourceMemoryController? = null,
    sourceTasks: com.t1doo.aiyou.records.SourceTaskController? = null,
    taskProposals: com.t1doo.aiyou.records.TaskProposalController? = null,
    reminders: com.t1doo.aiyou.records.TaskReminderController? = null,
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
    manualTasks: com.t1doo.aiyou.records.ManualTaskController? = null,
    imageGrid: com.t1doo.aiyou.records.CurrentImageGridController? = null,
    evidenceAnswerFixture: com.t1doo.aiyou.records.SyntheticEvidenceAnswerProvider? = null,
    selectedQuestionService: com.t1doo.aiyou.domain.SelectedQuestionService? = null,
    selectedQuestionConsentFixture: com.t1doo.aiyou.records.SelectedQuestionConsentFixture? = null,
    appAccessSettings: (@Composable () -> Unit)? = null,
    reminderNavigation:Long=0,
    onNavigationGenerationChanged:(Long?)->Unit={},
    unifiedKeywords:com.t1doo.aiyou.records.UnifiedKeywordController?=null,
    imageTexts:com.t1doo.aiyou.records.ImageTextController?=null,
) {
    val currentUnifiedController by rememberUpdatedState(unifiedKeywords)
    val currentUnifiedState by rememberUpdatedState(local?.unifiedKeyword)
    val currentNavigationCallback by rememberUpdatedState(onNavigationGenerationChanged)
    val currentAudioRouteCallback by rememberUpdatedState(onAudioRouteChanged)
    val currentAttachments by rememberUpdatedState(attachments)
    val currentImageTextController by rememberUpdatedState(imageTexts)
    val currentImageTextSnapshot by rememberUpdatedState(local?.imageTexts)
    val currentImageTextRecords by rememberUpdatedState(currentRecords)
    val imageTextBusy=imageTexts?.inFlight?.collectAsState()?.value==true
    val questionControllerRef=remember { java.util.concurrent.atomic.AtomicReference<com.t1doo.aiyou.records.QuestionKeywordController?>(null) }
    val navigation = remember { NavigationOwner{questionControllerRef.get()?.retire();currentUnifiedState?.let{currentUnifiedController?.close(it.session)};(currentImageTextRecords?.invoke()?.imageTexts?:currentImageTextSnapshot)?.let{currentImageTextController?.close(it.session)};currentAudioRouteCallback();currentAttachments?.state?.value?.takeIf{panel->panel.focusedIntent!=null}?.let{panel->currentAttachments?.retireFocusedSource(panel.sessionId)};currentNavigationCallback(it)} }
    DisposableEffect(unifiedKeywords){onDispose{unifiedKeywords?.retire()}}
    DisposableEffect(audioPlayback) { onDispose { audioPlayback?.stop() } }
    DisposableEffect(navigation) { onDispose { navigation.active = false;currentNavigationCallback(null) } }
    SideEffect {currentNavigationCallback(navigation.generation)}
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
    var taskSourceRoute by remember { mutableStateOf(false) }
    // This bit only makes a restored private source route fail closed; no source proof enters Bundle.
    var inboxSourceRoute by rememberSaveable { mutableStateOf(false) }
    var unifiedSourceRoute by remember { mutableStateOf(false) }
    DisposableEffect(sourcePreferences) { onDispose { currentRecords?.invoke()?.sourcePreferences?.let { sourcePreferences?.close(it.session) } } }
    DisposableEffect(memoryInbox) { onDispose { currentRecords?.invoke()?.memoryInbox?.let { memoryInbox?.close(it.session) } } }
    DisposableEffect(factRecaps) { onDispose { factRecaps?.close() } }
    val currentLocal by rememberUpdatedState(local)
    val currentDisplay by rememberUpdatedState(display)
    val currentRecordsProvider by rememberUpdatedState(currentRecords)
    // Construct directly: rememberTextFieldState has a Saver and would put the query in Bundle.
    var searchQuery by remember { mutableStateOf(TextFieldState()) }
    var searchFrom by remember { mutableStateOf(TextFieldState()) }
    var searchThrough by remember { mutableStateOf(TextFieldState()) }
    var searchDateBasis by remember { mutableStateOf(com.t1doo.aiyou.model.RecordDateBasis.CAPTURED) }
    var searchMode by remember { mutableStateOf(OriginalSearchMode.KEYWORDS) }
    var searchTag by remember { mutableStateOf<String?>(null) }
    var searchPerson by remember { mutableStateOf<String?>(null) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var onlyImages by remember { mutableStateOf(false) }
    var imageGridMode by remember { mutableStateOf(false) }
    var evidenceMode by remember { mutableStateOf(false) }
    var searchModeGeneration by remember { mutableLongStateOf(0L) }
    fun searchSource() = if (currentRecordsProvider != null) currentRecordsProvider?.invoke() else currentLocal
    fun searchInput(tagsSource:LocalRecordUiState?=searchSource()): OriginalSearchInput {
        val query = searchQuery.text
        val from = searchFrom.text
        val through = searchThrough.text
        return OriginalSearchInput(query.toString(), from.toString(), through.toString(), searchMode,
            OriginalSearchInputOwnership(query, from, through, searchModeGeneration), searchTag,
            if ((searchTag != null || searchMode == OriginalSearchMode.KEYWORDS && query.isNotBlank()) &&
                tagsSource?.manualTagsAvailable == true) tagsSource.manualTags else null,
            onlyFavorites,
            if (onlyFavorites && tagsSource?.favoritesAvailable == true) tagsSource.favorites else null,
            searchDateBasis, if (searchDateBasis == com.t1doo.aiyou.model.RecordDateBasis.EVENT && tagsSource?.eventDatesAvailable == true)
                tagsSource.eventDates else null,onlyImages = onlyImages, person = searchPerson,
            peopleSnapshot = if ((searchPerson != null || searchMode == OriginalSearchMode.KEYWORDS && query.isNotBlank()) && tagsSource?.manualPeopleAvailable == true) tagsSource.manualPeople else null)
    }
    val search = remember {
        LocalOriginalSearchController(
            currentSource = { searchSource() },
            currentInput = { searchInput() }, dispatcher = kotlinx.coroutines.Dispatchers.IO,
            arbitrateNavigation = navigation.gate::arbitrate,
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
    val evidence = remember { com.t1doo.aiyou.records.LocalEvidenceController(
        currentSearch = { search.visible(searchSource(), searchInput()) }, onInvalidated = onRefreshLocalSearch) }
    DisposableEffect(evidence) { onDispose { evidence.close() } }
    val evidenceEmission by evidence.state.collectAsState()
    val evidenceState = evidenceEmission.let { evidence.visible(searchState) }
    var searchReturnAnchor by remember { mutableStateOf<OriginalSearchReturnAnchor?>(null) }
    var searchReturnInput by remember { mutableStateOf<OriginalSearchInput?>(null) }
    var searchReturnSource by remember { mutableStateOf<List<com.t1doo.aiyou.model.StoredEntry>?>(null) }
    var readerFromSearch by remember { mutableStateOf(false) }
    var searchLocation by remember { mutableStateOf<com.t1doo.aiyou.records.OriginalReadingLocation?>(null) }
    fun searchRouteAvailable(source:LocalRecordUiState?=searchSource()):Boolean {
        return selectedName==Destination.MEMORIES.name&&detailEntryId==null&&!showSettings&&!demo&&!recapRoute&&
            source?.ready==true&&!source.opening&&source.error==null&&
            source.originalEdit==null&&source.originalHistory==null&&source.recordPackage==null&&source.recycleBin?.browsing!=true
    }
    fun questionBinding():com.t1doo.aiyou.records.QuestionSearchBinding? {
        val source=searchSource()?:return null
        val allowed=searchRouteAvailable(source)&&source.localSearch!=null&&!source.saving&&!source.savePending&&
            source.audioTranscripts==null&&source.imageTexts==null&&source.sourcePreferences==null&&source.sourceMemories==null&&source.memoryInbox==null&&
            source.localProcessing==null&&source.ai==null&&source.sourceTags==null&&source.candidates==null&&source.sourceTask==null&&
            source.manualTagEditor==null&&source.manualPeopleEditor==null&&source.eventDateEditor==null
        return if(allowed&&search.acceptsInput())com.t1doo.aiyou.records.QuestionSearchBinding(navigation.generation,source,searchInput(source))else null
    }
    val questionHelper=remember(search,navigation) { com.t1doo.aiyou.records.QuestionKeywordController(navigation.gate,
        currentBinding=::questionBinding,
        retireSearch={search.invalidateInput();evidence.clear();searchReturnAnchor=null;searchReturnInput=null;searchReturnSource=null;searchLocation=null},
        commit={request->search.tryChangeInput {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                searchMode=OriginalSearchMode.KEYWORDS
                searchQuery.edit{replace(0,length,request.query)}
                searchFrom.edit{replace(0,length,request.from)}
                searchThrough.edit{replace(0,length,request.through)}
                searchDateBasis=request.basis;searchModeGeneration++
            }
        }}) }
    DisposableEffect(questionHelper){questionControllerRef.set(questionHelper);onDispose{questionControllerRef.compareAndSet(questionHelper,null);questionHelper.dispose()}}
    val questionLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(questionHelper,questionLifecycle){
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->
            when(event){
                androidx.lifecycle.Lifecycle.Event.ON_RESUME->questionHelper.foreground(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,androidx.lifecycle.Lifecycle.Event.ON_STOP,
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY->questionHelper.foreground(false)
                else->Unit
            }
        }
        questionLifecycle.addObserver(observer)
        questionHelper.foreground(questionLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        onDispose{questionLifecycle.removeObserver(observer);questionHelper.foreground(false)}
    }
    val questionUi by questionHelper.state.collectAsState()
    SideEffect{questionHelper.sync()}
    questionUi?.let{com.t1doo.aiyou.records.QuestionKeywordDialog(it,questionHelper)}
    fun changeSearchInput(change:()->Unit)=navigation.gate.inputAction(generation,search,
        {searchRouteAvailable()&&questionHelper.state.value==null}){search.changeInput(change)}
    fun clearSearch() {
        unifiedSourceRoute=false;unifiedKeywords?.retire()
        taskSourceRoute=false
        search.clear()
        evidence.clear()
        searchQuery.edit { replace(0, length, "") }
        searchFrom.edit { replace(0, length, "") }
        searchThrough.edit { replace(0, length, "") }
        searchQuery = TextFieldState()
        searchFrom = TextFieldState()
        searchThrough = TextFieldState()
        searchMode = OriginalSearchMode.KEYWORDS
        searchTag = null
        searchPerson = null
        searchDateBasis = com.t1doo.aiyou.model.RecordDateBasis.CAPTURED
        onlyFavorites = false
        onlyImages = false
        imageGridMode = false
        evidenceMode = false
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
    var audioSourceAnchor by remember { mutableStateOf<com.t1doo.aiyou.model.AudioTranscriptSource?>(null) }
    var imageSourceAnchor by remember { mutableStateOf<com.t1doo.aiyou.model.ImageTextSource?>(null) }
    val lostImageSource=imageSourceAnchor?.let{local?.currentImageTextSource(it)!=true}==true
    val lostAudioSource=audioSourceAnchor?.let{local?.currentTranscriptSource(it)!=true}==true
    val lostInboxSource = inboxSourceRoute && detailEntryId != null && (searchLocation == null && audioSourceAnchor==null && imageSourceAnchor==null || lostAudioSource || lostImageSource)
    LaunchedEffect(inboxSourceRoute,detailEntryId){if(!inboxSourceRoute||detailEntryId!=audioSourceAnchor?.intent?.entryId)audioSourceAnchor=null}
    LaunchedEffect(inboxSourceRoute,detailEntryId){if(!inboxSourceRoute||detailEntryId!=imageSourceAnchor?.intent?.entryId)imageSourceAnchor=null}
    LaunchedEffect(lostInboxSource) {
        if (lostInboxSource) {
            navigation.advance()
            (currentRecordsProvider?.invoke() ?: currentLocal)?.audioTranscripts?.let { audioTranscripts?.close(it.session) }
            attachments?.state?.value?.takeIf{it.focusedIntent!=null}?.let{attachments?.retireFocusedSource(it.sessionId)}
            audioSourceAnchor=null;imageSourceAnchor=null
            (currentRecordsProvider?.invoke() ?: currentLocal)?.imageTexts?.let{imageTexts?.close(it.session)}
            (currentRecordsProvider?.invoke() ?: currentLocal)?.sourcePreferences?.let { sourcePreferences?.close(it.session) }
            (currentRecordsProvider?.invoke() ?: currentLocal)?.sourceMemories?.let { sourceMemories?.close(it.session) }
            detailEntryId = null; inboxSourceRoute = false
            unifiedSourceRoute=false;unifiedKeywords?.retire()
        }
    }
    val reading = detailEntryId != null && !showSettings && !demo && local != null && selected != Destination.TASKS && !browsingRecycle && !lostInboxSource
    val currentAnswerFixture by rememberUpdatedState(evidenceAnswerFixture)
    val evidenceAnswers = remember(evidence, evidenceAnswerFixture) {
        com.t1doo.aiyou.records.LocalEvidenceAnswerController(evidence,
            currentEvidence = { evidence.visible(search.visible(searchSource(), searchInput())) },
            ownsSession = { currentAnswerFixture === evidenceAnswerFixture && evidenceMode &&
                selectedName == Destination.MEMORIES.name && detailEntryId == null && !recapRoute &&
                !showSettings && !demo && searchSource()?.ready == true && searchSource()?.opening == false &&
                searchSource()?.originalEdit == null && searchSource()?.originalHistory == null &&
                searchSource()?.recycleBin?.browsing != true && searchSource()?.recordPackage == null },
            fixture = evidenceAnswerFixture, localSummary = evidenceAnswerFixture == null)
    }
    DisposableEffect(evidenceAnswers) { onDispose { evidenceAnswers.close() } }
    val evidenceAnswerEmission by evidenceAnswers.state.collectAsState()
    val evidenceAnswerState = evidenceAnswerEmission.let { evidenceAnswers.visible() }
    val currentQuestionService by rememberUpdatedState(selectedQuestionService)
    val evidenceQuestions = remember(evidence, selectedQuestionService) {
        com.t1doo.aiyou.records.LocalEvidenceAnswerController(evidence,
            currentEvidence = { evidence.visible(search.visible(searchSource(), searchInput())) },
            ownsSession = { currentQuestionService === selectedQuestionService && evidenceMode &&
                selectedName == Destination.MEMORIES.name && detailEntryId == null && !recapRoute &&
                !showSettings && !demo && searchSource()?.ready == true && searchSource()?.opening == false &&
                searchSource()?.originalEdit == null && searchSource()?.originalHistory == null &&
                searchSource()?.recycleBin?.browsing != true && searchSource()?.recordPackage == null },
            questionService = selectedQuestionService)
    }
    DisposableEffect(evidenceQuestions) { onDispose { evidenceQuestions.close() } }
    val evidenceQuestionEmission by evidenceQuestions.state.collectAsState()
    val evidenceQuestionState = evidenceQuestionEmission.let { evidenceQuestions.visible() }
    val currentConsentFixture by rememberUpdatedState(selectedQuestionConsentFixture)
    val consentOwner = searchSource()?.selectedQuestionAuthorizationOwner
    val questionConsent = remember(evidence, consentOwner, selectedQuestionConsentFixture) {
        com.t1doo.aiyou.records.SelectedQuestionConsentController(evidence,
            currentEvidence = { evidence.visible(search.visible(searchSource(), searchInput())) },
            ownsSession = { evidenceMode && selectedName == Destination.MEMORIES.name && detailEntryId == null &&
                !recapRoute && !showSettings && !demo && searchSource()?.ready == true && searchSource()?.opening == false &&
                searchSource()?.originalEdit == null && searchSource()?.originalHistory == null &&
                searchSource()?.recycleBin?.browsing != true && searchSource()?.recordPackage == null },
            owner = consentOwner, currentOwner = { searchSource()?.selectedQuestionAuthorizationOwner },
            fixture = selectedQuestionConsentFixture, currentFixture = { currentConsentFixture })
    }
    DisposableEffect(questionConsent) { onDispose { questionConsent.close() } }
    val questionConsentEmission by questionConsent.state.collectAsState()
    val questionConsentState = questionConsentEmission.let { questionConsent.visible() }
    // Read the mode while composing: reading delegated State only inside a stable
    // SideEffect lambda does not schedule that effect when just the mode changes.
    val searchEnabled = questionUi == null && selected == Destination.MEMORIES && !recapRoute && !reading && !browsingRecycle &&
        !browsingPackage && !showSettings && !demo && local != null && local.originalEdit == null && local.originalHistory == null
    val evidenceEnabled = evidenceMode && searchEnabled
    SideEffect {
        if(!showSettings || showLicenses || demo) modelConnections?.leave()
        search.sync(searchEnabled)
        evidence.sync(evidenceEnabled, searchState)
        evidenceAnswers.sync()
        evidenceQuestions.sync()
        questionConsent.sync()
        if (!reading && searchReturnAnchor != null &&
            (renderedSearchInput.copy(tagSnapshot = null, peopleSnapshot = null) != searchReturnInput?.copy(tagSnapshot = null, peopleSnapshot = null) || searchSource()?.entries !== searchReturnSource)) {
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
    val observedAttachmentPanel = attachments?.state?.collectAsState()?.value
    val attachmentPanel = observedAttachmentPanel?.takeIf{panel->reading&&panel.entryId==detailEntryId&&
        (panel.focusAudio==null || audioSourceAnchor?.let{anchor->anchor.intent==panel.focusAudio&&local?.currentTranscriptSource(anchor)==true}==true)&&
        (panel.focusImage==null || imageSourceAnchor?.let{anchor->anchor.intent==panel.focusImage&&local?.currentImageTextSource(anchor)==true}==true)}
    LaunchedEffect(observedAttachmentPanel?.sessionId,reading,detailEntryId,audioSourceAnchor,imageSourceAnchor,local){
        val panel=observedAttachmentPanel
        if(panel?.focusedIntent!=null && (attachmentPanel?.sessionId!=panel.sessionId || local?.let{panel.hasRecordAccess(it)}!=true))attachments?.retireFocusedSource(panel.sessionId)
    }
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
            currentLocal?.eventDateEditor?.let { eventDates?.close(it.session) }
            currentLocal?.manualPeopleEditor?.takeIf { it.entryId == detailEntryId }?.let { people?.close(it.session) }
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
                if(unifiedSourceRoute){unifiedSourceRoute=false;tailReturnRequest=null;unifiedKeywords?.resume()}
                inboxSourceRoute = false
                searchLocation = null
                if(recapRoute) { tailReturnRequest=null;factRecaps?.validate() }
                if (readerFromSearch && origin == Destination.MEMORIES) {
                    search.sync(true)
                    searchReturnSource = search.state.value.entries
                }
            }
        }
    }
    val unifiedUi=local?.unifiedKeyword
    val unifiedRoute=!demo&&!showSettings&&!recapRoute&&!reading&&selected==Destination.MEMORIES&&unifiedUi!=null
    LaunchedEffect(unifiedRoute,unifiedUi?.session){if(!unifiedRoute)unifiedUi?.let{unifiedKeywords?.close(it.session)}}
    if(unifiedRoute&&unifiedUi!=null&&unifiedKeywords!=null){
        fun ownsUnified()=ownsNavigation()&&!showSettings&&!demo&&!recapRoute&&detailEntryId==null&&
            (currentRecordsProvider?.invoke()?:currentLocal)?.unifiedKeyword?.session==unifiedUi.session
        com.t1doo.aiyou.records.UnifiedKeywordDialog(unifiedUi,unifiedKeywords,::ownsUnified){item,confirm->
            navigation.publish(generation){
                val latest=currentRecordsProvider?.invoke()?:currentLocal
                val b=item.binding
                val entry=latest?.entries?.singleOrNull{it.id==b.source.sourceId&&it.currentRevision.toLong()==b.source.revision&&com.t1doo.aiyou.model.OriginalExports.hash(it.originalText)==b.hash&&it.policy==b.policy}
                if(ownsNavigation()&&!showSettings&&!demo&&entry!=null&&latest?.let{!it.supportsLocalProcessing||it.localProcessingAvailable&&it.localProcessingVersions[b.source.sourceId]?.generation==b.generation}==true){
                    confirm {
                        navigation.advance();search.pause();readerFromSearch=false;tailReturnRequest=null
                        audioSourceAnchor=null;imageSourceAnchor=null
                        searchLocation=com.t1doo.aiyou.records.unifiedOriginalLocation(item,unifiedUi.keyword)
                        when(item){
                            is com.t1doo.aiyou.domain.UnifiedKeywordEvidence.Audio->{audioSourceAnchor=item.value.anchor;attachments?.openAudio(item.value.anchor.intent)}
                            is com.t1doo.aiyou.domain.UnifiedKeywordEvidence.Image->{imageSourceAnchor=item.value.anchor;attachments?.openImage(item.value.anchor.intent)}
                            else->Unit
                        }
                        unifiedSourceRoute=true;inboxSourceRoute=true;detailEntryId=entry.id
                    }
                }
            }
        }
    }
    val transcriptUi=local?.audioTranscripts
    val transcriptRoute=!demo&&!showSettings&&transcriptUi!=null&&local?.let{it.ready&&!it.opening&&it.error==null}==true&&
        (!recapRoute || local?.canUseRecapTranscript(transcriptUi.selected,attachmentPanel,audioSourceAnchor)==true)&&
        (if(transcriptUi.selected==null)!reading&&selected==Destination.MEMORIES else reading&&detailEntryId==transcriptUi.selected.entryId&&attachmentPanel?.entryId==transcriptUi.selected.entryId&&attachmentPanel?.revision==transcriptUi.selected.revision)
    LaunchedEffect(transcriptRoute,transcriptUi?.session){if(!transcriptRoute)transcriptUi?.let{audioTranscripts?.close(it.session)}}
    DisposableEffect(audioTranscripts){onDispose{currentRecords?.invoke()?.audioTranscripts?.let{audioTranscripts?.close(it.session)}}}
    if(transcriptUi!=null&&audioTranscripts!=null&&transcriptRoute){
        fun ownsTranscript():Boolean {
            val source=currentRecordsProvider?.invoke() ?: currentLocal
            return ownsNavigation()&&!showSettings&&!demo&&source?.audioTranscripts?.session==transcriptUi.session&&
                (!recapRoute || source?.canUseRecapTranscript(transcriptUi.selected,attachments?.state?.value,audioSourceAnchor)==true)
        }
        com.t1doo.aiyou.records.AudioTranscriptDialog(transcriptUi,audioTranscripts,::ownsTranscript){anchor->
            val intent=anchor.intent
            navigation.publish(generation){val entry=currentLocal?.entries?.singleOrNull{it.id==intent.entryId&&it.currentRevision==intent.revision}
                if(ownsNavigation()&&!showSettings&&!demo&&entry!=null&&currentLocal?.currentTranscriptSource(anchor)==true){navigation.advance();search.pause();readerFromSearch=false;tailReturnRequest=null;searchLocation=null;audioSourceAnchor=anchor;imageSourceAnchor=null;inboxSourceRoute=true;detailEntryId=intent.entryId;attachments?.openAudio(intent)}
            }
        }
    }
    val imageTextUi=local?.imageTexts
    val imageTextRoute=!demo&&!showSettings&&!recapRoute&&!browsingPackage&&!browsingRecycle&&imageTextUi!=null&&local?.canRetainImageTexts(imageTextUi)==true&&
        (if(imageTextUi.selected==null)!reading&&selected==Destination.MEMORIES else reading&&detailEntryId==imageTextUi.selected.entryId&&attachmentPanel?.entryId==imageTextUi.selected.entryId&&attachmentPanel?.revision==imageTextUi.selected.revision&&attachmentPanel?.pictureId==imageTextUi.selected.id&&attachmentPanel?.picture!=null)
    LaunchedEffect(imageTextRoute,imageTextUi?.session){if(!imageTextRoute)imageTextUi?.let{imageTexts?.close(it.session)}}
    DisposableEffect(imageTexts){onDispose{currentRecords?.invoke()?.imageTexts?.let{imageTexts?.close(it.session)}}}
    if(imageTextUi!=null&&imageTexts!=null&&imageTextRoute){
        fun ownsImageText()=ownsNavigation()&&!showSettings&&!demo&&!recapRoute&&currentLocal?.imageTexts?.session==imageTextUi.session
        com.t1doo.aiyou.records.ImageTextDialog(imageTextUi,imageTexts,::ownsImageText){anchor->
            val intent=anchor.intent
            navigation.publish(generation){
                if(ownsNavigation()&&!showSettings&&!demo&&currentLocal?.currentImageTextSource(anchor)==true){
                    navigation.advance();search.pause();readerFromSearch=false;tailReturnRequest=null;searchLocation=null
                    audioSourceAnchor=null;imageSourceAnchor=anchor;inboxSourceRoute=true;detailEntryId=intent.entryId;attachments?.openImage(intent)
                }
            }
        }
    }
    val preferenceUi = local?.sourcePreferences
    val preferenceRoute = !demo && !showSettings && !recapRoute && local?.let { it.ready && !it.opening && it.error == null } == true &&
        preferenceUi?.let { ui -> if (ui.source == null) !reading && selected == Destination.MEMORIES
            else reading && detailEntryId == ui.source.sourceId && local?.entries?.any { it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision } == true } == true
    LaunchedEffect(preferenceRoute, preferenceUi?.session) { if (!preferenceRoute) preferenceUi?.let { sourcePreferences?.close(it.session) } }
    if (preferenceUi != null && sourcePreferences != null && preferenceRoute) {
        fun ownsPreference() = ownsNavigation() && !showSettings && !demo && !recapRoute &&
            currentLocal?.sourcePreferences?.session == preferenceUi.session &&
            (if (preferenceUi.source == null) detailEntryId == null && selectedName == Destination.MEMORIES.name
             else detailEntryId == preferenceUi.source.sourceId)
        com.t1doo.aiyou.records.SourcePreferenceDialog(preferenceUi, sourcePreferences, ::ownsPreference) { location ->
            // Controller has already retired the panel after authenticating its source under the owner lease.
            navigation.publish(generation) {
                val entry = currentLocal?.entries?.singleOrNull { it.id == location.entryId && it.currentRevision == location.revision }
                if (ownsNavigation() && !showSettings && !demo && entry != null && entry.policy != com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE &&
                    com.t1doo.aiyou.model.OriginalExports.hash(entry.originalText) == location.contentHash) {
                    navigation.advance(); search.pause(); readerFromSearch = false; tailReturnRequest = null
                    searchLocation = location; inboxSourceRoute = true; detailEntryId = location.entryId
                }
            }
        }
    }
    val inboxUi = local?.memoryInbox
    val inboxAvailable = !demo && !showSettings && !reading && selected == Destination.MEMORIES && !recapRoute &&
        local?.let { it.ready && !it.opening && it.error == null && (!it.supportsLocalProcessing || it.localProcessingAvailable) } == true
    LaunchedEffect(inboxAvailable, inboxUi?.session) {
        if (!inboxAvailable) inboxUi?.let { memoryInbox?.close(it.session) }
    }
    if (inboxUi != null && memoryInbox != null && inboxAvailable) {
        fun ownsInbox() = ownsNavigation() && selectedName == Destination.MEMORIES.name && detailEntryId == null &&
            !showSettings && !demo && !recapRoute && currentLocal?.memoryInbox?.session == inboxUi.session
        com.t1doo.aiyou.records.SourceMemoryInboxDialog(inboxUi, memoryInbox, ::ownsInbox) { item ->
            val routeGeneration = generation
            var opened = false
            navigation.publish(routeGeneration) {
                val entry = currentLocal?.entries?.singleOrNull { it.id == item.source.sourceId && it.currentRevision.toLong() == item.source.revision }
                if (ownsInbox() && currentLocal?.saving == false && currentLocal?.savePending == false && entry != null && entry.policy != com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE &&
                    com.t1doo.aiyou.model.OriginalExports.hash(entry.originalText) == item.sourceHash && sourceMemories != null) {
                    memoryInbox.close(inboxUi.session); navigation.advance(); search.pause(); readerFromSearch = false; tailReturnRequest = null
                    val ref = checkNotNull(item.current).content.reference
                    searchLocation = com.t1doo.aiyou.records.OriginalReadingLocation(item.source.sourceId, item.source.revision.toInt(), item.sourceHash, ref.start, ref.end)
                    inboxSourceRoute = true; detailEntryId = item.source.sourceId
                    sourceMemories.open(item.source.sourceId, item.source.revision.toInt()); opened = true
                }
            }
            opened
        }
    }
    val selectDestination: (Destination) -> Unit = { destination ->
        if (ownsNavigation() && currentLocal?.recycleBin?.busy != true && currentLocal?.originalEdit == null && currentLocal?.originalHistory == null && attachments?.state?.value == null &&
            navigation.tryAdvance(generation)) {
            currentLocal?.recycleBin?.let { recycleBin?.close(it.session) }
            currentLocal?.recordPackage?.let { recordPackages?.close(it.session) }
            currentLocal?.eventDateEditor?.let { eventDates?.close(it.session) }
            factRecaps?.close();recapRoute=false
            clearSearch()
            tailReturnRequest = null
            searchLocation = null
            selectedName = destination.name; detailEntryId = null; showSettings = false; showLicenses = false
        }
    }
    LaunchedEffect(reminderNavigation) {
        if(reminderNavigation>0)selectDestination(Destination.TASKS)
    }
    val openEntry: (String) -> Unit = { id ->
        if (ownsNavigation() && selectedName == origin.name && !showSettings && !demo && detailEntryId == null &&
            navigation.tryAdvance(generation)) {
            taskSourceRoute=false;inboxSourceRoute=false
            readerFromSearch = origin == Destination.MEMORIES && searchState.stage == OriginalSearchStage.READY
            search.pause()
            tailReturnRequest = null
            searchLocation = null
            if (origin == Destination.NOW) selectedNowId = id else selectedMemoriesId = id
            detailEntryId = id
        }
    }
    fun openSearchResultAt(hit: com.t1doo.aiyou.records.OriginalSearchHit,
        reference: com.t1doo.aiyou.model.OriginalTextReference? = null) {
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
                        locationToken = if (reference != null || hit.originalMatchesQuery) com.t1doo.aiyou.records.OriginalReadingLocation(
                            hit.entry.id, hit.entry.currentRevision, com.t1doo.aiyou.model.OriginalExports.hash(hit.entry.originalText),
                            reference?.start ?: hit.matchStartCodePoint, reference?.end ?: hit.matchEndCodePoint) else null
                        searchLocation = locationToken
                        locationGeneration = navigation.generation
                    }
                } }
            }
        }
    }
    val openSearchResult: (com.t1doo.aiyou.records.OriginalSearchHit) -> Unit = { openSearchResultAt(it) }
    LocalOriginalSearchObserver.current?.let { observer ->
        val observedEvidenceMode = evidenceEnabled
        val observedEvidenceStage = evidenceState.stage
        SideEffect {
            observer.onResults(searchState, openSearchResult)
            observer.onDiagnostics(search.visibilityDiagnostics(local, renderedSearchInput) +
                if (observedEvidenceMode) "; " + evidence.visibilityDiagnostics(searchState) +
                    ", evidenceMode=true, renderedEvidence=$observedEvidenceStage, reading=$reading" else "")
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
    local?.manualPeopleEditor?.takeIf { reading && it.entryId == detailEntryId && it.owner === local.manualPeopleOwner }?.let { editor ->
        people?.let { controller ->
            com.t1doo.aiyou.records.ManualPeopleDialog(editor,
                onEdit = { session, version, text -> if (ownsNavigation()) controller.edit(session, version, text) },
                onAdd = { session, version, text -> if (ownsNavigation()) controller.add(session, version, text) },
                onRemove = { session, version, name, input -> if (ownsNavigation()) controller.remove(session, version, name, input) },
                onRetry = { if (ownsNavigation()) controller.retry(it) }, onClose = { if (ownsNavigation()) controller.close(it) },
                sourceAvailable = local.ready && !local.opening && local.error == null && local.manualPeopleOwner === editor.owner &&
                    local.hasManualPeopleReadingSource(editor),
                onAliasEdit = { session, version, alias, name -> if (ownsNavigation()) controller.editAlias(session, version, alias, name) },
                onAliasSave = { session, version, alias, name -> if (ownsNavigation()) controller.saveAlias(session, version, alias, name) },
                onAliasRemove = { session, version, alias -> if (ownsNavigation()) controller.removeAlias(session, version, alias) })
        }
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
    SideEffect {
        if (!ownsNavigation() || selectedName != Destination.TASKS.name || showSettings || demo)
            local?.reminders?.editor?.let { reminders?.close(it.session) }
        if (!ownsNavigation() || selectedName != Destination.TASKS.name || showSettings || demo)
            local?.tasks?.titleEditor?.let { manualTasks?.closeTitle(it.session) }
    }
    if (exports != null && exportUi != null && ownsExportRoute()) {
        com.t1doo.aiyou.records.OriginalExportPicker(exports, exportUi,
            canLaunch = local?.ready == true && local.error == null && !local.opening, ::ownsExportRoute)
        com.t1doo.aiyou.records.OriginalExportDialog(checkNotNull(local), exportUi, exports, ::ownsExportRoute)
    }

    Scaffold(
        modifier = Modifier.imePadding().onSizeChanged { scaffoldHeightPx = it.height },
        topBar = {
          Column {
            NotebookAppBar(
                if (browsingPackage) { if(packageUi?.fullMode==true) "完整备份与恢复" else "加密原文包" } else if (browsingRecycle) "回收站" else if (viewingPicture) "查看图片" else if (attachmentPanel != null) "记录附件" else if (history != null) "原文版本" else if (editing != null) "编辑原文" else if (reading) "记录原文" else if (showLicenses) "开源许可" else if (showSettings) "设置" else if(recapRoute) "本机回顾" else "爱忆 AIyou",
                if (browsingPackage) "返回列表" else if (viewingPicture) {if(attachmentPanel?.focusImage!=null) "返回原文" else "返回附件列表"} else if (attachmentPanel != null) { if (attachmentPanel.readOnly) "返回历史原文" else "返回原文" }
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
            audioPlayback?.let { com.t1doo.aiyou.records.OriginalAudioStatus(it) }
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
                modifier = modifier, hasRecordPackages=local?.supportsRecordPackages==true, hasFullBackup=local?.supportsFullBackup==true, storageUsage = storageUsage,
                appAccessSection = appAccessSettings,
                modelConnectionSection = if(modelConnections==null || demo) null else ({
                    com.t1doo.aiyou.ui.ModelConnectionSettings(modelConnections,local?.modelConnectionOwner,
                        local?.ready==true && !local.opening && local.error==null, local?.modelSecretOwner, local?.modelCapabilityOwner)
                }))
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
                onMetadataChoice = { attachments.setJpegMetadataChoice(panel.sessionId, it) }, showPictureClose = false,audio=audioPlayback,transcripts=audioTranscripts,imageTexts=imageTexts,
                canUse={ownsNavigation()&&reading&&detailEntryId==panel.entryId&&attachments.state.value?.let{live->live.sessionId==panel.sessionId&&live.entryId==detailEntryId&&live.revision==panel.revision&&!live.unavailable&&currentLocal?.let{live.hasRecordAccess(it)}==true&&
                    (live.focusAudio==null||audioSourceAnchor?.let{it.intent==live.focusAudio&&currentLocal?.currentTranscriptSource(it)==true}==true)&&
                    (live.focusImage==null||imageSourceAnchor?.let{it.intent==live.focusImage&&currentLocal?.currentImageTextSource(it)==true}==true)}==true})
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
                onOpenPeople = people?.let { controller -> { source -> if (canCopy(source.version.sourceId, source.version.revision.toInt())) controller.open(source) } },
                location = searchLocation,
                requiredSource = if(recapRoute||taskSourceRoute||inboxSourceRoute) searchLocation else null,
                requiredSourceTitle = if(unifiedSourceRoute) "统一找回来源版本已变化" else if(inboxSourceRoute) "候选来源版本已变化" else if(taskSourceRoute) "待办来源版本已变化" else "回顾来源版本已变化",
                requiredSourceMessage = if(unifiedSourceRoute) "请返回统一关键词找回重新查找，未将新版本冒充旧出处。" else if(inboxSourceRoute) "请返回回忆刷新待审线索，再核对原文。" else if(taskSourceRoute) "请返回待办重新核对来源，没有将新版本冒充旧摘录。" else "请返回回顾并重新生成，没有将新版本冒充旧摘录。",
                eventDateController = eventDates, canUseEventDate = canCopy,
                candidateController = candidates,
                sourceTagController = sourceTags,
                sourcePreferenceController = sourcePreferences,
                sourceMemoryController = sourceMemories,
                localProcessingController = localProcessing,
                sourceTaskController = sourceTasks,
                canUseSourceTask = { ownsNavigation() && detailEntryId == readerId && selectedName == origin.name && !showSettings && !demo },
                canUseCandidates = canCopy,
                onCandidateLocation = { location ->
                    if (canCopy(location.entryId, location.revision)) {
                        val live = currentRecords?.invoke() ?: local
                        val entry = live.entries.singleOrNull { it.id == location.entryId && it.currentRevision == location.revision }
                        if (entry != null && com.t1doo.aiyou.model.OriginalExports.hash(entry.originalText) == location.contentHash)
                            searchLocation = location
                    }
                },
                onSetFavorite = favorites?.let { controller -> { source, version, desired ->
                    if (canCopy(source.version.sourceId, source.version.revision.toInt())) controller.set(source, version, desired)
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
            },modifier,onRetryRecords={if(ownsNavigation() && recapRoute && !reading) onRetry()},
                onAudioSource=if(attachments==null || audioTranscripts==null) null else {card,audio ->
                    if(ownsNavigation() && detailEntryId==null && recapRoute && !showSettings && !demo) {
                        val routeGeneration=generation
                        factRecaps.audioSource(card,audio) { anchor ->
                            navigation.publish(routeGeneration) {
                                if(ownsNavigation() && recapRoute && selectedName==Destination.MEMORIES.name && detailEntryId==null && !showSettings && !demo &&
                                    currentLocal?.factRecap?.snapshot?.cards?.any { it===card }==true && currentLocal?.currentTranscriptSource(anchor)==true) {
                                    navigation.advance();search.pause();readerFromSearch=false;tailReturnRequest=null;searchLocation=null
                                    imageSourceAnchor=null;audioSourceAnchor=anchor;inboxSourceRoute=true;detailEntryId=anchor.intent.entryId
                                    attachments.openAudio(anchor.intent)
                                }
                            }
                        }
                    }
                })
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
                onCloseDate = { session -> if (ownsTasks()) onTaskCloseDate(session) },
                onProposalOpen = {id,version -> if(ownsTasks()) taskProposals?.openExisting(id,version)},
                onProposalRefresh = {if(ownsTasks()) taskProposals?.refresh()},
                reminders = reminders, ownsReminderRoute = ::ownsTasks,
                titles = manualTasks, ownsTitleRoute = ::ownsTasks,
                onSource = { id,version -> if(ownsTasks()) {
                    val token=generation
                    sourceTasks?.locate(id,version) { binding -> navigation.publish(token) {
                        if(ownsTasks() && detailEntryId==null) {
                            navigation.advance();clearSearch();recapRoute=false;selectedName=Destination.MEMORIES.name
                            searchLocation=com.t1doo.aiyou.records.OriginalReadingLocation(binding.source.sourceId,binding.source.revision.toInt(),binding.sourceHash,binding.start,binding.end)
                            taskSourceRoute=true;detailEntryId=binding.source.sourceId
                        }
                    } }
                } }, clock = taskClock)
        } else if (!demo && local != null && selected==Destination.NOW && local.supportsMixedCapture && mixedCapture!=null) {
            com.t1doo.aiyou.records.MixedCapturePicker(mixedCapture,local.mixedCapture?.selection)
            com.t1doo.aiyou.records.MixedCaptureScreen(local,mixedCapture,modifier,
                listState=nowList,onOpenEntry=openEntry,selectedEntryId=selectedNowId,
                defaultPolicyUnavailable=!display.ready && display.error!=null,audio=audioPlayback,companion=companion,motionAllowed=motionAllowed,
                canImportAudio={ownsNavigation() && selectedName==Destination.NOW.name && !showSettings && !demo && !reading})
        } else if (!demo && local != null && selected != Destination.TASKS) {
            val request = tailReturnRequest?.takeIf { it.destination == selected && it.generation == generation }
            val topPaddingPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
            val bottomPaddingPx = with(density) { contentPadding.calculateBottomPadding().roundToPx() }
            LocalRecordsScreen(selected, local, onDraftChange, onSave, onRetry, modifier, companion = companion,
                imageTextBusy=imageTextBusy,
                onOpenUnified=unifiedKeywords?.let{controller->{if(ownsNavigation()&&selected==Destination.MEMORIES&&!reading&&!showSettings&&!demo){navigation.advance();clearSearch();controller.open()}}},
                onOpenImageTexts=imageTexts?.let{controller->{if(ownsNavigation()&&selected==Destination.MEMORIES&&!reading&&!showSettings&&!demo){navigation.advance();clearSearch();controller.open()}}},
                onOpenTranscripts=audioTranscripts?.let{controller->{if(ownsNavigation()&&selected==Destination.MEMORIES&&!reading&&!showSettings&&!demo){navigation.advance();clearSearch();controller.open()}}},
                onOpenPreferences = sourcePreferences?.let { controller -> { if (ownsNavigation() && selected == Destination.MEMORIES &&
                    !reading && !showSettings && !demo) { navigation.advance(); clearSearch(); controller.open() } } },
                onOpenMemoryInbox = memoryInbox?.let { controller -> { if (ownsNavigation() && selected == Destination.MEMORIES &&
                    !reading && !showSettings && !demo) { navigation.advance(); clearSearch(); controller.open() } } },
                onOpenAcceptedMemories = memoryInbox?.let { controller -> { if (ownsNavigation() && selected == Destination.MEMORIES &&
                    !reading && !showSettings && !demo) { navigation.advance(); clearSearch(); controller.open(com.t1doo.aiyou.domain.SourceMemoryInboxSection.ACCEPTED) } } },
                onOpenFactRecap = factRecaps?.let { controller -> { if(ownsNavigation() && selected==Destination.MEMORIES && !reading && !showSettings && !demo) {
                    navigation.advance();clearSearch();controller.open(currentDisplay.preferences.defaultRecapPeriod);recapRoute=true
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
                onBeforeSearchInput = { field ->
                    navigation.gate.inputDecision(generation,search,{searchRouteAvailable()&&questionHelper.state.value==null}){
                        search.beforeInputCommit{field===searchQuery||field===searchFrom||field===searchThrough}
                    }
                },
                onHistoryInputCommitted = {navigation.gate.inputAction(generation,search,{searchRouteAvailable()&&questionHelper.state.value==null}){search.inputCommitted()}},
                onQuestionKeywords={navigation.gate.inputAction(generation,search,{searchRouteAvailable()}){questionHelper.open()}},
                onReduceSearchKeywords={value->navigation.gate.inputDecision(generation,search,
                    {searchRouteAvailable()&&questionHelper.state.value==null}){questionHelper.openReduction(value,search)}},
                onRebuildLocalSearch = { navigation.gate.inputAction(generation,search,
                    {searchRouteAvailable()&&questionHelper.state.value==null&&currentLocal?.localSearch!=null&&currentLocal?.localIndexBusy==false}){onRebuildLocalSearch()} },
                searchPerson = searchPerson, onSearchPerson = { person -> changeSearchInput {searchPerson=person;searchModeGeneration++} }, onRefreshPeople = {navigation.gate.inputAction(generation,search,{searchRouteAvailable()&&questionHelper.state.value==null}){onRefreshLocalSearch()}},
                searchTag = searchTag, onSearchTag = { tag -> changeSearchInput {searchTag=tag;searchModeGeneration++} },
                onlyFavorites = onlyFavorites, onSearchFavorites = { value -> changeSearchInput {onlyFavorites=value;searchModeGeneration++} },
                onlyImages = onlyImages, onSearchImages = { value -> changeSearchInput {onlyImages=value;imageGridMode=false;searchModeGeneration++} },
                imageGrid = imageGrid, imageGridMode = imageGridMode,
                evidenceMode = evidenceMode, evidenceState = evidenceState,
                evidenceAnswerState = evidenceAnswerState, evidenceAnswerAvailable = evidenceAnswers.available,
                onEvidenceAnswer = { evidenceAnswers.ask(evidenceState) }, onCancelEvidenceAnswer = evidenceAnswers::cancel,
                onOpenAnswerSource = { reference ->
                    evidenceAnswers.sourceFor(evidenceAnswerState, reference)?.let { card ->
                        if (evidence.canOpen(evidenceState, card)) openSearchResultAt(card.hit, card.reference)
                    }
                },
                evidenceQuestion = {
                    com.t1doo.aiyou.records.LocalEvidenceQuestionSection(evidenceState, evidenceQuestionState,
                        evidenceQuestions.available, onReview = { evidenceQuestions.review(evidenceState) },
                        onConfirm = { evidenceQuestions.ask(evidenceState, evidenceQuestionState.questionReview) },
                        onCancel = evidenceQuestions::cancel, onOpen = { reference ->
                            evidenceQuestions.sourceFor(evidenceQuestionState, reference)?.let { card ->
                                if (evidence.canOpen(evidenceState, card)) openSearchResultAt(card.hit, card.reference)
                            }
                        })
                    com.t1doo.aiyou.records.SelectedQuestionConsentSection(evidenceState, questionConsentState,
                        selectedQuestionConsentFixture, questionConsent.available,
                        onReview = { questionConsent.review(evidenceState) }, onConfirm = questionConsent::confirm,
                        onStop = { questionConsent.stop(questionConsentState) },
                        onWithdraw = { questionConsent.withdraw(questionConsentState) }, onOpen = { reference ->
                            questionConsent.openSource(questionConsentState, reference) { card ->
                                if (evidence.canOpen(evidenceState, card)) openSearchResultAt(card.hit, card.reference)
                            }
                        })
                },
                onEvidenceMode = { value -> if (ownsNavigation() && detailEntryId == null &&
                    selectedName == Destination.MEMORIES.name && !showSettings && !demo) {
                    evidence.clear(); evidenceMode = value; if (value) imageGridMode = false
                } },
                onOpenEvidence = { card -> if (evidence.canOpen(evidenceState, card)) openSearchResultAt(card.hit, card.reference) },
                onToggleEvidence = { card -> if (ownsNavigation() && detailEntryId == null &&
                    selectedName == Destination.MEMORIES.name && !showSettings && !demo && evidenceMode)
                    evidence.toggle(evidenceState, card) },
                onConfirmEvidence = { if (ownsNavigation() && detailEntryId == null &&
                    selectedName == Destination.MEMORIES.name && !showSettings && !demo && evidenceMode)
                    evidence.confirm(evidenceState) },
                onImageGridMode = { value -> if (ownsNavigation() && detailEntryId == null &&
                    selectedName == Destination.MEMORIES.name && !showSettings && !demo && onlyImages) {
                    imageGridMode = value
                    if (value) { evidence.clear(); evidenceMode = false }
                } },
                ownsImageGrid = { ownsNavigation() && detailEntryId == null &&
                    selectedName == Destination.MEMORIES.name && !showSettings && !demo && imageGridMode && onlyImages &&
                    search.visible(searchSource(), searchInput()) === searchState },
                onRefreshFavorites = {navigation.gate.inputAction(generation,search,{searchRouteAvailable()&&questionHelper.state.value==null}){favorites?.refresh()}},
                searchMode = searchMode,
                searchDateBasis = searchDateBasis,
                onRefreshEventDates = {navigation.gate.inputAction(generation,search,{searchRouteAvailable()&&questionHelper.state.value==null}){onRefreshLocalSearch()}},
                onSearchDateBasis = { value -> changeSearchInput {searchDateBasis=value;searchModeGeneration++} },
                onSearchMode = {changeSearchInput{searchModeGeneration++;searchMode=if(searchMode==OriginalSearchMode.KEYWORDS)OriginalSearchMode.EXACT else OriginalSearchMode.KEYWORDS}},
                onClearSearch = {navigation.gate.inputAction(generation,search,{searchRouteAvailable()&&questionHelper.state.value==null}){clearSearch()}}, onOpenSearchResult = openSearchResult,
                searchReturnAnchor = searchReturnAnchor,
                ownsSearchReturn = { ownsNavigation() && detailEntryId == null && selectedName == Destination.MEMORIES.name &&
                    !showSettings && !demo && searchReturnInput?.copy(tagSnapshot = null, peopleSnapshot = null) == searchInput().copy(tagSnapshot = null, peopleSnapshot = null) && currentLocal?.entries === searchReturnSource &&
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
    if(!demo&&!showSettings&&local?.taskProposals?.editor!=null&&taskProposals!=null) {
        key(generation,local.taskProposals.editor.session) {
            com.t1doo.aiyou.records.TaskProposalDialog(local,taskProposals) {
                ownsNavigation()&&!demo&&!showSettings&&(selectedName==Destination.TASKS.name||detailEntryId==local.taskProposals.editor.binding.source.sourceId)
            }
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
