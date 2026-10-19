package com.t1doo.aiyou.records

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.AwaitableRecordStorage
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftRef
import com.t1doo.aiyou.model.OriginalEditDraftIndex
import com.t1doo.aiyou.model.OriginalEditDraftState
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.model.SourceVersion
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CaptureDiscardUiState(val requestId: Long, val busy: Boolean = false, val error: String? = null,
    val conflict: Boolean = false, val recoveryRequired: Boolean = false,
    val recovering: Boolean = false, val recoveryFailed: Boolean = false)

data class LocalRecordUiState(
    val taskProposals: TaskProposalsUiState = TaskProposalsUiState(),
    val supportsSourceTasks: Boolean = false,
    val sourceTask: SourceTaskUiState? = null,
    val taskSourceNotice: String? = null,
    val supportsLocalProcessing: Boolean = false,
    val localProcessingVersions: Map<String, com.t1doo.aiyou.domain.LocalProcessingVersion> = emptyMap(),
    val localProcessingAvailable: Boolean = true,
    val localProcessing: LocalProcessingUiState? = null,
    val recordAuthorizationOwner: com.t1doo.aiyou.domain.RecordAuthorizationRepository? = null,
    val supportsFactRecaps: Boolean = false,
    val factRecap: FactRecapUiState? = null,
    val supportsMixedCapture: Boolean = false,
    val mixedCapture: MixedCaptureUiState? = null,
    val ready: Boolean = false,
    val opening: Boolean = true,
    val saving: Boolean = false,
    val savePending: Boolean = false,
    val text: String = "",
    val entries: List<StoredEntry> = emptyList(),
    val draftNotice: String = "正在读取本机草稿",
    val saveNotice: String? = null,
    val saveFeedbackId: Long = 0L,
    val error: String? = null,
    val inputNotice: String? = null,
    val captureDiscard: CaptureDiscardUiState? = null,
    val originalEdit: OriginalEditUiState? = null,
    val originalEditNotice: String? = null,
    val originalHistory: OriginalHistoryUiState? = null,
    val supportsEditDrafts: Boolean = false,
    val pendingEdits: List<OriginalEditDraftRef> = emptyList(),
    val editDraftError: String? = null,
    val editDraftIssueCount: Int = 0,
    val editDraftIndexAvailable: Boolean = true,
    val ai: RecordAiUiState? = null,
    val supportsCandidates: Boolean = false,
    val supportsSourceTags: Boolean = false,
    val sourceTags: SourceTagUiState? = null,
    val supportsSourceMemories: Boolean = false,
    val sourceMemories: SourceMemoryUiState? = null,
    val candidates: LocalCandidateUiState? = null,
    val supportsManualPeople: Boolean = false,
    val manualPeopleOwner: com.t1doo.aiyou.domain.ManualPeopleRepository? = null,
    val manualPeople: Map<String, com.t1doo.aiyou.model.ManualPeopleSnapshot> = emptyMap(),
    val manualPeopleAvailable: Boolean = false,
    val manualPeopleError: String? = null,
    val manualPeopleEditor: ManualPeopleEditor? = null,
    val manualTags: Map<String, com.t1doo.aiyou.model.ManualTagSnapshot> = emptyMap(),
    val manualTagsAvailable: Boolean = false,
    val manualTagsError: String? = null,
    val manualTagEditor: ManualTagEditor? = null,
    val supportsEventDates: Boolean = false,
    val eventDates: Map<String, com.t1doo.aiyou.model.RecordEventDateSnapshot> = emptyMap(),
    val eventDatesAvailable: Boolean = false,
    val eventDatesError: String? = null,
    val eventDateEditor: RecordEventDateEditor? = null,
    val eventDateWrites: Map<String, RecordEventDateWrite> = emptyMap(),
    val eventDateControllerRetired: Boolean = false,
    val supportsFavorites: Boolean = false,
    val favorites: Map<String, com.t1doo.aiyou.model.RecordFavoriteSnapshot> = emptyMap(),
    val favoritesAvailable: Boolean = false,
    val favoritesLoading: Boolean = false,
    val favoritesError: String? = null,
    val favoriteWrites: Map<String, RecordFavoriteWrite> = emptyMap(),
    val favoriteNotice: String? = null,
    val favoriteNoticeEntryId: String? = null,
    val favoriteNoticeReceipt: com.t1doo.aiyou.model.RecordFavoriteSnapshot? = null,
    val favoriteControllerRetired: Boolean = false,
    val tasks: ManualTasksUiState = ManualTasksUiState(),
    val supportsOriginalExport: Boolean = false,
    val originalExport: OriginalExportUiState? = null,
    val supportsRecycleBin: Boolean = false,
    val supportsPermanentErasure: Boolean = false,
    val recycleControllerRetired: Boolean = false,
    val recycleBin: RecycleBinUiState? = null,
    val recycleNotice: String? = null,
    val supportsRecordPackages: Boolean = false,
    val supportsFullBackup:Boolean=false,
    val recordPackage: RecordPackageUiState? = null,
    val packageOrigins: Map<String, com.t1doo.aiyou.model.RecordPackageOrigin> = emptyMap(),
    val packageOriginsAvailable: Boolean = true,
    val packageOriginsError: String? = null,
    val localSearch: com.t1doo.aiyou.domain.LocalSearchRepository? = null,
    val localSearchEpoch: Long? = null,
    val localIndexBusy: Boolean = false,
    val localIndexError: String? = null,
    val reminders: TaskRemindersUiState = TaskRemindersUiState(),
    val selectedQuestionAuthorizationOwner: com.t1doo.aiyou.domain.SelectedQuestionAuthorizationRepository? = null,
)

/** All draft writes and saves share one queue, preserving edits around a save boundary. */
class LocalRecordsViewModel internal constructor(
    application: Application,
    private val openStore: () -> EntryRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val decodePng: (ByteArray) -> AttachmentPicture = PlatformImageDecoder::decode,
    private val bindAi: ((EntryRepository) -> RecordAiCapability)? = null,
    private val extractLocalCandidates: (com.t1doo.aiyou.model.LocalCandidateJob) -> com.t1doo.aiyou.model.ExcerptDocument = com.t1doo.aiyou.domain.LocalExcerptRules::extract,
    private val decodeCapture: (ByteArray) -> AttachmentPicture = PlatformImageDecoder::thumbnail,
    private val decodeGrid: (ByteArray) -> AttachmentPicture = PlatformImageDecoder::gridThumbnail,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { EncryptedEntryRepository.open(application) })
    private val mutableState = MutableStateFlow(LocalRecordUiState())
    val state = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val appAccessGuard = Any()
    private val appAccessCommands = java.util.concurrent.atomic.AtomicInteger()
    private val mutableAppAccessIdle = MutableStateFlow(false)
    internal val appAccessIdle = mutableAppAccessIdle.asStateFlow()
    private val appAccessClosed = kotlinx.coroutines.CompletableDeferred<Unit>()

    private fun submit(command: Command): kotlinx.coroutines.channels.ChannelResult<Unit> = synchronized(appAccessGuard) {
        appAccessCommands.incrementAndGet()
        mutableAppAccessIdle.value = false
        val result = commands.trySend(command)
        if (result.isFailure) mutableAppAccessIdle.value = appAccessCommands.decrementAndGet() == 0
        result
    }

    internal fun canRetireForAppAccess(): Boolean = synchronized(appAccessGuard) { appAccessMayRetire() }

    /** Freeze admission at the same boundary used by every records IO command. */
    internal fun retireForAppAccess(): Boolean = synchronized(appAccessGuard) {
        if (!appAccessMayRetire()) return@synchronized false
        editsDisposed = true
        commands.close()
        true
    }

    private fun appAccessMayRetire(): Boolean {
        val local = mutableState.value
        if (editsDisposed || appAccessCommands.get() != 0 || !local.ready || local.opening || local.error != null ||
            local.saving || local.savePending || local.captureDiscard != null || local.localIndexBusy ||
            local.originalEdit != null || local.originalHistory != null || attachments.state.value != null ||
            local.ai != null || local.sourceTask != null || local.sourceTags != null || local.sourceMemories != null ||
            local.localProcessing != null || local.factRecap != null || local.candidates != null ||
            local.originalExport != null || local.recordPackage != null || local.recycleBin != null ||
            local.manualTagEditor != null || local.manualPeopleEditor != null || local.eventDateEditor != null ||
            local.favoriteWrites.isNotEmpty() || local.eventDateWrites.isNotEmpty() ||
            local.tasks.busy || local.tasks.pending != null || local.tasks.editor != null ||
            local.tasks.dateEditor != null || local.tasks.titleEditor != null || local.taskProposals.editor != null ||
            local.reminders.busy || local.reminders.pending != null || local.reminders.editor != null) return false
        val mixed = local.mixedCapture
        if (mixed != null) {
            val draft = mixed.draft ?: return false
            return !mixed.busy && mixed.pendingEdits == 0 && mixed.selection == null &&
                !mixed.uncertainSave && !mixed.discardPending && !mixed.confirmDiscard && mixed.policyChange == null &&
                mixed.error == null && !draft.discarding && draft.text == mixed.text
        }
        return persistedDraft?.text == local.text || (local.text.isEmpty() && editGeneration.get() == 0L)
    }

    internal suspend fun awaitAppAccessClosed() = appAccessClosed.await()
    internal val appAccessClosure: kotlinx.coroutines.Deferred<Unit> get() = appAccessClosed
    val imageGrid = CurrentImageGridController(
        enqueue = { action -> submit(Command.Attachment(action)).isSuccess },
        decode = decodeGrid, current = { mutableState.value })
    val sourceTasks = SourceTaskController({ mutableState.value }, { change -> update(change) },
        { action -> submit(Command.ManualTasks(action)).isSuccess },clock, { tasks.refresh() },{taskProposals.open(it)})
    val taskProposals = TaskProposalController({ mutableState.value }, { change -> update(change) },
        { action -> submit(Command.ManualTasks(action)).isSuccess },clock, { tasks.refresh() })
    val localProcessing = LocalProcessingController({ mutableState.value }, { change -> update(change) },
        { action -> submit(Command.LocalProcessing(action)).isSuccess }, ::retireDerived, clock)
    val factRecaps = FactRecapController({ mutableState.value }, { change -> update(change) },
        { action -> submit(Command.FactRecap(action)).isSuccess },clock.withZone(ZoneId.systemDefault()))
    val mixedCapture = MixedCaptureController({ mutableState.value },{ change -> update(change) },
        { action -> submit(Command.MixedCapture(action)).isSuccess },decodeCapture,clock)
    val attachments = LocalAttachmentController(
        enqueue = { action -> submit(Command.Attachment(action)).isSuccess }, decodePng = decodePng,
        canUseRecord = { mutableState.value.let { it.ready && !it.opening && it.error == null &&
            !it.saving && !it.savePending && it.captureDiscard == null && it.recycleBin == null } },
        canUsePanel = { it.hasRecordAccess(mutableState.value) })
    val tags = ManualTagController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.ManualTags(action)).isSuccess })
    val people = ManualPeopleController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.ManualPeople(action)).isSuccess })
    val favorites = RecordFavoriteController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.Favorites(action)).isSuccess })
    val eventDates = RecordEventDateController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.EventDates(action)).isSuccess })
    val tasks = ManualTaskController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.ManualTasks(action)).isSuccess }, clock = clock)
    val reminders = TaskReminderController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.ManualTasks(action)).isSuccess }, clock = clock)
    val candidates = LocalCandidateController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.LocalCandidates(action)).isSuccess },
        scope = viewModelScope, clock = clock, extract = extractLocalCandidates)
    val sourceTags = SourceTagController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.LocalCandidates(action)).isSuccess }, clock = clock)
    val sourceMemories = SourceMemoryController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.LocalCandidates(action)).isSuccess }, clock = clock)
    val exports = OriginalExportController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.OriginalExport(action)).isSuccess }, clock = clock, decodeImage = decodeCapture)
    val recordPackages = RecordPackageController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.RecordPackage(action)).isSuccess },
        cipher = com.t1doo.aiyou.data.JcaRecordPackageCipher(), clock = clock,
        onFullRestored = { store -> mixedCapture.adoptFullRestore(store);tasks.initialize(store);reminders.initialize(store);taskProposals.initialize(store) })
    val recycleBin = RecycleBinController(current = { mutableState.value }, publish = { change -> update(change) },
        enqueue = { action -> submit(Command.RecycleBin(action)).isSuccess },
        canMove = { attachments.state.value == null },
        onCommittedMove = { id ->
            ai.reconcileRecords()
            attachments.state.value?.takeIf { it.entryId == id }?.let { attachments.close(it.sessionId) }
            mutableState.value.originalExport?.takeIf { it.selection.any { source -> source.sourceId == id } }?.let { exports.close(it.session) }
        })
    private var repository: EntryRepository? = null
    private var indexQueued = false // Existing IO actor only.
    private var indexFailed = false
    private val searchRefreshQueued = java.util.concurrent.atomic.AtomicBoolean()
    private var storageOwnership: RecordsStorageOwnership? = null // Local IO queue publishes one immutable owner.
    private val ai = LocalRecordAiController(
        currentPolicy = { source -> mutableState.value.let { local ->
            if (editsDisposed || !local.ready || local.opening || local.error != null || !local.hasLocalProcessingProof(source)) null
            else local.entries.firstOrNull { it.id == source.sourceId && it.currentRevision.toLong() == source.revision }?.policy
        } },
        publish = { selected -> update { local ->
            val current = selected?.let { value -> local.entries.firstOrNull {
                it.id == value.source.sourceId && it.currentRevision.toLong() == value.source.revision
            } }
            when {
                selected == null -> local.copy(ai = null)
                !editsDisposed && local.ready && !local.opening && local.error == null && current != null &&
                    (current.policy == ProcessingPolicy.REMOTE_ALLOWED ||
                        selected.phase == RecordAiPhase.BLOCKED && selected.preview.isEmpty() && selected.annotation == null) ->
                    local.copy(ai = selected)
                local.ai?.sessionId == selected.sessionId -> local.copy(ai = null)
                else -> local
            }
        } },
    )
    private var persistedDraft: TextDraft? = null
    private var captureDraftMayBeConsumed = false // IO queue only; an unacknowledged delete invalidates save reuse.
    private val editGeneration = AtomicLong()
    private var pendingCommit: TextDraft? = null
    private val pendingIntent = AtomicReference<SaveIntent?>(null)
    private val discardIds = AtomicLong()
    private var discardIntent: DiscardIntent? = null // UI thread owns identity; IO queue owns expected snapshot.
    private val originalEditIds = AtomicLong()
    private val originalHistoryIds = AtomicLong()
    private val historyReplacementIds = AtomicLong()
    // IO-queue owned. Once revise returned, a failed readback must never revise again.
    private var committedOriginal: Pair<Long, StoredEntry>? = null
    private data class DraftBinding(val sessionId: Long, var snapshot: OriginalEditDraft,
        var expected: OriginalEditDraft?, var persisted: Boolean, var unacknowledged: OriginalEditDraft? = null)
    private var originalDraft: DraftBinding? = null // IO queue owned, never Bundle/log/plaintext storage.
    @Volatile private var editsDisposed = false
    private val indexRebuildQueued = java.util.concurrent.atomic.AtomicBoolean()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                open()
                ai.reconcileRecords()
                scheduleLocalIndex()
                mutableAppAccessIdle.value = appAccessCommands.get() == 0
                for (command in commands) {
                    val previousEntries = mutableState.value.entries
                    try {
                        if(command is Command.Save || command is Command.Revise ||
                            command is Command.RecordPackage || command is Command.RecycleBin ||
                            command is Command.MixedCapture || command is Command.Retry || command is Command.RefreshSearch) factRecaps.invalidate()
                        when (command) {
                            is Command.LocalProcessing -> command.action(checkNotNull(repository))
                            is Command.FactRecap -> command.action(checkNotNull(repository))
                            is Command.Edit -> {
                                persistedDraft = checkNotNull(repository).updateDraft(command.text)
                                captureDraftMayBeConsumed = false
                                if (command.generation == editGeneration.get()) update {
                                    it.copy(draftNotice = "草稿已保存在本机", error = null, saveNotice = null)
                                }
                            }
                            is Command.Save -> {
                                if (pendingIntent.get() !== command.intent) continue
                                val store = checkNotNull(repository)
                                val draft = pendingCommit ?: persistedDraft?.takeIf { !captureDraftMayBeConsumed && it.text == command.intent.text }
                                    ?: store.updateDraft(command.intent.text)
                                pendingCommit = draft
                                store.saveDraft(draft, clock.millis(), ZoneId.systemDefault().id)
                                val entries = store.entries()
                                val favoriteValues = readFavoriteSource(store)
                                val eventDateValues = readEventDateSource(store)
                                // Publish the saved source ledger before enabling immediate metadata actions.
                                // Its optional read failure must not change the acknowledged record save.
                                refreshProcessingVersions(entries)
                                pendingCommit = null
                                persistedDraft = null
                                captureDraftMayBeConsumed = false
                                pendingIntent.compareAndSet(command.intent, null)
                                // Success follows commit; the list is read from the database.
                                update { it.copy(text = "", saving = false, savePending = false, entries = entries, draftNotice = "没有未保存的草稿", saveNotice = "已保存在本机", saveFeedbackId = it.saveFeedbackId + 1, error = null).withFavoriteSource(favoriteValues).withEventDateSource(eventDateValues) }
                            }
                            is Command.DiscardCapture -> discardCapture(command.intent)
                            is Command.RestoreCapture -> restoreCapture(command.intent)
                            Command.Retry -> { indexFailed = false; open() }
                            is Command.RecoverDraft -> {
                                val store = checkNotNull(repository)
                                persistedDraft = store.updateDraft(command.text)
                                captureDraftMayBeConsumed = false
                                val entries = store.entries()
                                update { it.copy(opening = false, entries = entries, error = null,
                                    draftNotice = if (command.generation == editGeneration.get()) "草稿已保存在本机" else it.draftNotice,
                                    originalHistory = it.originalHistory?.let { history -> history.copy(
                                        requestId = history.requestId + 1, loading = true, revisions = emptyList(), error = null, unavailable = false,
                                        replacement = null) }) }
                                mutableState.value.originalHistory?.let { submit(Command.ReadHistory(it.sessionId, it.requestId)) }
                            }
                            is Command.Revise -> reviseOriginal(command)
                            is Command.OpenEdit -> openOriginalDraft(command)
                            is Command.PersistEdit -> persistOriginalDraft(command)
                            is Command.KeepEdit -> keepOriginalDraft(command)
                            is Command.DiscardEdit -> discardOriginalDraft(command.sessionId)
                            is Command.ReleaseEdit -> {
                                if (originalDraft?.sessionId == command.sessionId) originalDraft = null
                                refreshPendingDrafts()
                            }
                            Command.RefreshEditDrafts -> refreshPendingDrafts()
                            is Command.ReadHistory -> readHistory(command)
                            is Command.MixedCapture -> {
                                val before=mutableState.value.entries
                                command.action(checkNotNull(repository))
                                if(mutableState.value.entries!=before) scheduleLocalIndex()
                            }
                            is Command.Attachment -> {
                                val store=checkNotNull(repository)
                                factRecaps.checkCurrent(store)
                                try { command.action(store) } finally { factRecaps.checkCurrent(store) }
                            }
                            is Command.ManualTags -> {
                                val store=checkNotNull(repository)
                                factRecaps.checkCurrent(store)
                                try { command.action(store) } finally { factRecaps.checkCurrent(store) }
                            }
                            is Command.ManualPeople -> command.action(checkNotNull(repository))
                            is Command.Favorites -> command.action(checkNotNull(repository))
                            is Command.EventDates -> command.action(checkNotNull(repository))
                            is Command.ManualTasks -> command.action(checkNotNull(repository))
                            is Command.LocalCandidates -> command.action(checkNotNull(repository))
                            is Command.OriginalExport -> command.action(checkNotNull(repository))
                            is Command.RecordPackage -> command.action(checkNotNull(repository))
                            is Command.RecycleBin -> command.action(checkNotNull(repository))
                            Command.IndexBatch -> runLocalIndex()
                            Command.RebuildIndex -> try { rebuildLocalIndex() } finally { indexRebuildQueued.set(false) }
                            Command.RefreshSearch -> try { refreshSearchSource() } finally { searchRefreshQueued.set(false) }
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { failed(failure, preserveQueuedSave = command is Command.Edit) }
                    finally {
                        if (previousEntries != mutableState.value.entries || command is Command.LocalProcessing ||
                            command is Command.Retry || command is Command.RefreshSearch) refreshProcessingVersions()
                        ai.reconcileRecords()
                        if (command is Command.Save || command is Command.Revise || command is Command.ManualTags ||
                            command is Command.RecordPackage || command is Command.RecycleBin ||
                            command is Command.Retry || command is Command.RefreshSearch || command is Command.LocalProcessing) scheduleLocalIndex()
                        mutableAppAccessIdle.value = appAccessCommands.decrementAndGet() == 0
                    }
                }
            } finally {
                editsDisposed = true
                sourceTasks.dispose()
                taskProposals.dispose()
                localProcessing.dispose()
                factRecaps.dispose()
                mixedCapture.dispose()
                ai.markDisposed()
                attachments.markDisposed()
                imageGrid.dispose()
                tags.dispose(); people.dispose()
                favorites.dispose()
                eventDates.dispose()
                tasks.dispose()
                reminders.dispose()
                candidates.dispose()
                sourceTags.dispose()
                sourceMemories.dispose()
                exports.dispose()
                recordPackages.dispose()
                recycleBin.dispose()
                try { withContext(NonCancellable + Dispatchers.IO) {
                    try { ai.closeAndJoin() }
                    finally {
                        mixedCapture.disposeOnIo()
                        try { attachments.disposeOnIo() }
                        finally {
                            (repository as? AutoCloseable)?.close()
                            (repository as? AwaitableRecordStorage)?.awaitClosed()
                            repository = null
                        }
                    }
                } } catch (_: Exception) {
                    appAccessClosed.completeExceptionally(IllegalStateException("Records session closure unconfirmed"))
                }
            }
        }.invokeOnCompletion { failure ->
            if (failure == null || failure is CancellationException) appAccessClosed.complete(Unit)
            else appAccessClosed.completeExceptionally(IllegalStateException("Records session closure unconfirmed"))
        }
    }

    /** Local and AI writers validate the selected source in the same state CAS. */
    private inline fun update(transform: (LocalRecordUiState) -> LocalRecordUiState) {
        mutableState.update { previous ->
            val requested = transform(previous)
            val proposed = if (requested.manualPeople.keys.any { id -> requested.entries.none { it.id == id } }) requested.copy(
                manualPeople = java.util.Collections.unmodifiableMap(requested.manualPeople.filterKeys { id -> requested.entries.any { it.id == id } })) else requested
            val panel = proposed.localProcessing
            val scoped = if (panel != null && (editsDisposed || !proposed.ready || proposed.opening || proposed.error != null ||
                panel.authorizationSource?.let { it.owner !== proposed.recordAuthorizationOwner } == true ||
                proposed.entries.none { it.id == panel.source.sourceId && it.currentRevision.toLong() == panel.source.revision &&
                    com.t1doo.aiyou.model.OriginalExports.hash(it.originalText) == panel.sourceHash })) proposed.copy(localProcessing = null) else proposed
            val safe = if (scoped.supportsLocalProcessing && !scoped.localProcessingAvailable) scoped.copy(
                candidates = null, sourceTags = null, sourceMemories = null, ai = null, factRecap = null) else scoped
            val candidate = reconcileFactRecap(previous,safe)
            val transformed = if (candidate.supportsFavorites && candidate.favoritesAvailable &&
                candidate.entries.any { !candidate.favorites.containsKey(it.id) }) candidate.copy(
                    favoritesAvailable = false, favoritesError = "记录列表已变化，请重新读取收藏状态。") else candidate
            val eventSource = if (transformed.supportsEventDates && transformed.eventDatesAvailable &&
                transformed.entries.any { !transformed.eventDates.containsKey(it.id) }) transformed.copy(
                    eventDatesAvailable = false, eventDatesError = "记录列表已变化，请重新读取事件日期。") else transformed
            val eventEditor = eventSource.eventDateEditor
            val dated = if (eventEditor != null && (!eventSource.ready || eventSource.opening || eventSource.error != null ||
                eventSource.metadataReadingSource(eventEditor.entryId, eventEditor.revision)?.let {
                    it.contentHash == eventEditor.sourceHash && it.generation == eventEditor.sourceGeneration } != true))
                eventSource.copy(eventDateEditor = eventEditor.copy(preview = null, conflict = true,
                    error = "原文已变化或不可用，输入已保留。请关闭后重新打开。")) else eventSource
            val personEditor = dated.manualPeopleEditor
            val personScoped = if (personEditor != null && dated.manualPeopleOwner !== personEditor.owner) dated.copy(manualPeopleEditor = null)
                else if (personEditor != null && (!dated.ready || dated.opening || dated.error != null ||
                !dated.hasManualPeopleReadingSource(personEditor)))
                dated.copy(manualPeopleEditor = personEditor.copy(busy = false, conflict = true,
                    error = "原文、账号或来源范围已变化，输入已保留，请刷新原文后重新打开。")) else dated
            val editor = personScoped.manualTagEditor
            val labelled = if (editor != null && (!personScoped.ready || personScoped.opening || personScoped.error != null ||
                personScoped.entries.none { it.id == editor.entryId && it.currentRevision == editor.revision }))
                personScoped.copy(manualTagEditor = editor.copy(busy = false, conflict = true,
                    error = "原文已变化或不可用，输入已保留，请关闭后重新打开。")) else personScoped
            val review = labelled.candidates
            val next = if (review != null && (editsDisposed || !labelled.ready || labelled.opening || labelled.error != null ||
                labelled.entries.none { it.id == review.source.sourceId && it.currentRevision.toLong() == review.source.revision &&
                    it.policy != ProcessingPolicy.AI_NONE && com.t1doo.aiyou.model.OriginalExports.hash(it.originalText) == review.sourceHash }))
                labelled.copy(candidates = null) else labelled
            val sourcePreview = next.sourceTags
            val visible = if (sourcePreview != null && (editsDisposed || !next.ready || next.opening || next.error != null ||
                next.entries.none { it.id == sourcePreview.source.sourceId && it.currentRevision.toLong() == sourcePreview.source.revision &&
                    it.policy != ProcessingPolicy.AI_NONE && com.t1doo.aiyou.model.OriginalExports.hash(it.originalText) == sourcePreview.sourceHash }))
                next.copy(sourceTags = null) else next
            val memories = visible.sourceMemories
            val reviewed = if (memories != null && (editsDisposed || !visible.ready || visible.opening || visible.error != null ||
                visible.entries.none { it.id == memories.source.sourceId && it.currentRevision.toLong() == memories.source.revision &&
                    it.policy != ProcessingPolicy.AI_NONE && com.t1doo.aiyou.model.OriginalExports.hash(it.originalText) == memories.sourceHash }))
                visible.copy(sourceMemories = null) else visible
            val selected = reviewed.ai
            val entry = selected?.let { value -> reviewed.entries.firstOrNull {
                it.id == value.source.sourceId && it.currentRevision.toLong() == value.source.revision
            } }
            if (selected == null || !editsDisposed && reviewed.ready && !reviewed.opening && reviewed.error == null &&
                entry != null && (entry.policy == ProcessingPolicy.REMOTE_ALLOWED ||
                    selected.phase == RecordAiPhase.BLOCKED && selected.preview.isEmpty() && selected.annotation == null)) reviewed
            else reviewed.copy(ai = null)
        }
    }

    /** Opens a memory-only session over one already persisted current original. Never sends. */
    fun openAiSession(entryId: String, expectedRevision: Int): Long {
        mutableState.value.ai?.let { ai.leave(it.sessionId) }
        val local = mutableState.value
        if (editsDisposed || !local.ready || local.opening || local.error != null ||
            local.recordPackage != null || local.originalEdit != null || local.originalHistory != null || local.recycleBin != null || attachments.state.value != null) return 0L
        val entry = local.entries.firstOrNull { it.id == entryId && it.currentRevision == expectedRevision } ?: return 0L
        return ai.open(SourceVersion(entry.id, entry.currentRevision.toLong()), entry.policy)
    }

    fun startAiSession(sessionId: Long) = ai.start(sessionId)
    fun stopAiSession(sessionId: Long) = ai.stop(sessionId)
    fun closeAiSession(sessionId: Long) = ai.leave(sessionId)

    fun changeText(text: String) {
        val current = mutableState.value
        if (editsDisposed || !current.ready || current.opening || current.saving || current.savePending || current.captureDiscard?.busy == true || current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        if (text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH) {
            update { it.copy(
                inputNotice = "文本过长，输入长度上限为 ${EncryptedEntryRepository.MAX_TEXT_LENGTH}。本次输入未替换原草稿，请缩短后重试。",
                saveNotice = null,
            ) }
            return
        }
        val generation = editGeneration.incrementAndGet()
        discardIntent = null
        update { it.copy(text = text, draftNotice = "正在保存草稿", saveNotice = null, inputNotice = null, captureDiscard = null) }
        submit(Command.Edit(text, generation))
    }

    fun save() {
        val current = mutableState.value
        if (editsDisposed || !current.ready || current.captureDiscard != null || current.saving || current.text.isBlank() || current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        val intent = pendingIntent.get() ?: SaveIntent(current.text, editGeneration.get()).also { pendingIntent.set(it) }
        update { it.copy(saving = true, savePending = true, saveNotice = null, error = null, inputNotice = null) }
        submit(Command.Save(intent))
    }

    fun retry() {
        val current = mutableState.value
        if (current.captureDiscard != null) return
        if (current.editDraftError != null && current.ready) {
            submit(Command.RefreshEditDrafts)
            if (current.error == null) return
        }
        if (current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) {
            if (!current.ready || current.opening || current.error == null || current.originalEdit?.saving == true) return
            // Recover the prior capture write without replacing this editor or routing through changeText.
            update { it.copy(opening = true) }
            submit(Command.RecoverDraft(current.text, editGeneration.get()))
        } else if (!current.ready && !current.opening) {
            update { it.copy(opening = true, error = null) }
            submit(Command.Retry)
        } else if (mutableState.value.savePending) save()
        else if (mutableState.value.ready) changeText(mutableState.value.text)
    }

    fun requestCaptureDiscard() {
        val current = mutableState.value
        if (editsDisposed || !current.ready || current.opening || current.saving || current.savePending ||
            current.text.isEmpty() || current.captureDiscard != null || current.originalEdit != null ||
            current.originalHistory != null || attachments.state.value != null) return
        val intent = DiscardIntent(discardIds.incrementAndGet(), current.text, editGeneration.get())
        discardIntent = intent
        update { it.copy(captureDiscard = CaptureDiscardUiState(intent.id)) }
    }

    fun cancelCaptureDiscard(requestId: Long) {
        val active = mutableState.value.captureDiscard ?: return
        if (active.requestId != requestId || active.busy) return
        if (active.recoveryRequired && !active.recoveryFailed) {
            queueCaptureRecovery(requestId)
            return
        }
        discardIntent = null
        update { it.copy(captureDiscard = null,
            draftNotice = if (active.recoveryFailed) "当前输入未确认保存在本机，退出应用可能丢失。" else it.draftNotice) }
    }

    fun confirmCaptureDiscard(requestId: Long) {
        val current = mutableState.value
        val active = current.captureDiscard ?: return
        val intent = discardIntent ?: return
        if (editsDisposed || active.requestId != requestId || active.busy || active.conflict || intent.id != requestId ||
            intent.generation != editGeneration.get() || intent.text != current.text) return
        if (active.recoveryFailed) { queueCaptureRecovery(requestId); return }
        update { it.copy(captureDiscard = active.copy(busy = true, error = null)) }
        submit(Command.DiscardCapture(intent))
    }

    private fun queueCaptureRecovery(requestId: Long) {
        val current = mutableState.value
        val active = current.captureDiscard ?: return
        val intent = discardIntent ?: return
        if (editsDisposed || active.requestId != requestId || active.busy || intent.id != requestId ||
            intent.generation != editGeneration.get() || intent.text != current.text || !active.recoveryRequired) return
        update { it.copy(captureDiscard = active.copy(busy = true, recovering = true, error = null)) }
        submit(Command.RestoreCapture(intent))
    }

    private suspend fun restoreCapture(intent: DiscardIntent) {
        try {
            val store = checkNotNull(repository)
            val restored = store.restoreDiscardedDraft(checkNotNull(intent.expected), intent.replacement)
            if (restored != intent.expected && restored != intent.replacement || store.readDraft() != restored) throw RecordConflict()
            persistedDraft = restored
            captureDraftMayBeConsumed = false
            update { current ->
                if (current.captureDiscard?.requestId != intent.id || editGeneration.get() != intent.generation) current
                else current.copy(captureDiscard = null, draftNotice = "草稿已保存在本机", error = null, saveNotice = null)
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            update { current ->
                if (current.captureDiscard?.requestId != intent.id) current
                else current.copy(draftNotice = "当前输入未确认保存在本机，退出应用可能丢失。",
                    captureDiscard = current.captureDiscard.copy(busy = false, recovering = false, recoveryFailed = true,
                        conflict = failure is RecordConflict,
                        error = if (failure is RecordConflict) "本机已有变化后的草稿，未覆盖它。当前输入尚未确认保存；返回编辑或退出应用前请先保留输入。"
                            else "保留草稿尚未确认，退出应用可能丢失当前输入。请重试保留，或返回编辑后自行保留输入。"))
            }
        }
    }

    private suspend fun discardCapture(intent: DiscardIntent) {
        try {
            val store = checkNotNull(repository)
            // Prior autosaves finish first. Never replace a failed autosave merely to delete it.
            if (!intent.bound) {
                val actual = store.readDraft()
                if (actual != persistedDraft || actual?.text != intent.text) throw RecordConflict()
                intent.expected = actual
                intent.bound = true
            }
            // A failed acknowledgement may follow a committed delete. Never reuse this cache to save.
            captureDraftMayBeConsumed = true
            store.discardDraft(checkNotNull(intent.expected))
            if (store.readDraft() != null) throw RecordConflict()
            persistedDraft = null
            captureDraftMayBeConsumed = false
            update { current ->
                if (current.captureDiscard?.requestId != intent.id || editGeneration.get() != intent.generation) current
                else current.copy(text = "", captureDiscard = null, draftNotice = "没有未保存的草稿",
                    saveNotice = null, inputNotice = null, error = null)
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            update { current ->
                if (current.captureDiscard?.requestId != intent.id) current
                else current.copy(draftNotice = if (intent.bound) "当前输入未确认保存在本机，退出前请保留或保存。" else current.draftNotice,
                    captureDiscard = current.captureDiscard.copy(busy = false, recoveryRequired = intent.bound,
                    conflict = failure is RecordConflict,
                    error = if (failure is RecordConflict) "本机草稿已变化，本次清空已停止。当前输入已保留，请返回编辑。"
                        else "清空尚未确认，当前输入已保留。可以重试确认或返回编辑。"))
            }
        }
    }

    fun beginOriginalEdit(entryId: String, expectedRevision: Int) {
        if (editsDisposed) return
        val current = mutableState.value
        if (current.captureDiscard != null || current.recycleBin != null || current.recordPackage != null) return
        if (!current.ready || current.opening || current.error != null || current.saving || current.savePending || current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        val entry = current.entries.firstOrNull { it.id == entryId && it.currentRevision == expectedRevision } ?: return
        current.ai?.let { ai.leave(it.sessionId) }
        val edit = OriginalEditUiState(originalEditIds.incrementAndGet(), entry.id, entry.currentRevision, entry.originalText,
            loadingDraft = current.supportsEditDrafts)
        update { it.copy(originalEdit = edit, originalEditNotice = null) }
        if (current.supportsEditDrafts) submit(Command.OpenEdit(edit, false))
    }

    fun resumeOriginalEdit(entryId: String, draftId: String) {
        val current = mutableState.value
        if (current.captureDiscard != null || current.recycleBin != null || current.recordPackage != null) return
        if (current.supportsRecycleBin && current.entries.none { it.id == entryId }) return
        if (editsDisposed || !current.ready || current.opening || current.error != null || !current.editDraftIndexAvailable || current.saving || current.savePending ||
            current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        val ref = current.pendingEdits.singleOrNull { it.entryId == entryId && it.id == draftId } ?: return
        val entry = current.entries.firstOrNull { it.id == entryId }
        current.ai?.let { ai.leave(it.sessionId) }
        val edit = OriginalEditUiState(originalEditIds.incrementAndGet(), entryId, entry?.currentRevision ?: ref.baseRevision,
            entry?.originalText.orEmpty(), loadingDraft = true)
        update { it.copy(originalEdit = edit, originalEditNotice = null) }
        submit(Command.OpenEdit(edit, false, draftId))
    }

    fun changeOriginalEdit(sessionId: Long, text: String) {
        if (editsDisposed) return
        var accepted = false
        update { current ->
            val edit = current.originalEdit
            if (edit == null || edit.sessionId != sessionId || !edit.canCancel || current.originalHistory != null) current
            else if (text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH) current.copy(originalEdit = edit.copy(
                inputNotice = "文本过长，本次输入未替换你的修改。长度上限为 ${EncryptedEntryRepository.MAX_TEXT_LENGTH}。"))
            else {
                accepted = true
                current.copy(originalEdit = edit.copy(text = text, inputNotice = null,
                    draftNotice = if (current.supportsEditDrafts) "正在保存本机编辑草稿" else edit.draftNotice,
                    error = if (edit.conflict) edit.error else null))
            }
        }
        if (accepted && mutableState.value.supportsEditDrafts) submit(Command.PersistEdit(sessionId, text))
    }

    fun keepOriginalEdit(sessionId: Long) {
        if (editsDisposed) return
        val current = mutableState.value
        val edit = current.originalEdit ?: return
        if (edit.sessionId != sessionId || !edit.canCancel || !edit.dirty || edit.draftConflict ||
            !current.supportsEditDrafts || !current.ready || current.opening || current.error != null ||
            current.originalHistory != null || attachments.state.value != null) return
        update { if (it.originalEdit != edit) it else it.copy(
            originalEdit = edit.copy(keeping = true, error = null, draftNotice = "正在确认本机编辑草稿")) }
        if (mutableState.value.originalEdit?.let { it.sessionId == sessionId && it.keeping } == true)
            submit(Command.KeepEdit(sessionId, edit.text))
    }

    fun cancelOriginalEdit(sessionId: Long) {
        if (editsDisposed) return
        val active = mutableState.value.originalEdit ?: return
        if (active.sessionId != sessionId || !active.canCancel || mutableState.value.originalHistory != null) return
        if (mutableState.value.supportsEditDrafts && !active.draftConflict) {
            update { if (it.originalEdit?.sessionId != sessionId) it else it.copy(
                originalEdit = it.originalEdit.copy(discarding = true, error = null)) }
            submit(Command.DiscardEdit(sessionId))
            return
        }
        update { current ->
            val edit = current.originalEdit
            if (edit?.sessionId != sessionId || !edit.canCancel || current.originalHistory != null) current
            else current.copy(originalEdit = null, originalEditNotice = null)
        }
        if (mutableState.value.supportsEditDrafts) submit(Command.ReleaseEdit(sessionId))
    }

    fun saveOriginalEdit(sessionId: Long) {
        if (editsDisposed) return
        val current = mutableState.value
        val edit = current.originalEdit ?: return
        if (edit.sessionId != sessionId || edit.saving || edit.keeping || edit.conflict || edit.loadingDraft || edit.discarding || !current.ready || current.opening || current.error != null || current.originalHistory != null) return
        if (!edit.awaitingReadback && (edit.text.isBlank() || !edit.dirty || edit.text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH)) return
        update { it.copy(originalEdit = edit.copy(saving = true, error = null, inputNotice = null)) }
        submit(Command.Revise(sessionId, edit.entryId, edit.baseRevision, edit.text))
    }

    fun beginOriginalHistory(entryId: String, expectedRevision: Int) {
        val current = mutableState.value
        if (current.captureDiscard != null || current.recycleBin != null || current.recordPackage != null) return
        if (!current.ready || current.opening || current.error != null || current.saving || current.savePending || current.originalHistory != null || attachments.state.value != null) return
        if (current.originalEdit?.let { it.entryId != entryId || !it.canCancel } == true) return
        if (current.entries.none { it.id == entryId && it.currentRevision == expectedRevision }) return
        current.ai?.let { ai.leave(it.sessionId) }
        val history = OriginalHistoryUiState(originalHistoryIds.incrementAndGet(), entryId)
        update { it.copy(originalHistory = history) }
        submit(Command.ReadHistory(history.sessionId, history.requestId))
    }

    fun selectOriginalRevision(sessionId: Long, revision: Int?) {
        if (attachments.state.value != null) return
        val current = mutableState.value
        val history = current.originalHistory ?: return
        if (history.sessionId != sessionId || history.loading || history.error != null || history.unavailable ||
            !current.ready || current.opening || current.error != null ||
            revision != null && history.revisions.none { it.revision == revision }) return
        requestHistory(history.copy(selectedRevision = revision))
    }

    fun retryOriginalHistory(sessionId: Long) {
        if (attachments.state.value != null) return
        val current = mutableState.value
        val history = current.originalHistory ?: return
        if (history.sessionId != sessionId || history.loading || !current.ready || current.opening || current.error != null) return
        requestHistory(history)
    }

    fun backOriginalHistory(sessionId: Long) {
        if (attachments.state.value != null) return
        val history = mutableState.value.originalHistory ?: return
        if (history.sessionId != sessionId) return
        if (history.replacement != null) cancelHistoryReplacement(sessionId, history.requestId, history.replacement.id)
        else if (history.selectedRevision != null) requestHistory(history.copy(selectedRevision = null))
        else update { if (it.originalHistory?.sessionId == sessionId) it.copy(originalHistory = null) else it }
    }

    fun editOriginalHistory(sessionId: Long, requestId: Long, revision: Int, expectedCurrentRevision: Int) {
        val beforeEditId = mutableState.value.originalEdit?.sessionId
        update { current ->
            if (!canEditHistory(current, sessionId, requestId, revision, expectedCurrentRevision)) current
            else if (current.originalEdit?.dirty == true) current.copy(originalHistory = current.originalHistory!!.copy(
                replacement = current.originalHistory.replacement ?: OriginalHistoryReplacement(
                    historyReplacementIds.incrementAndGet(), current.originalEdit.sessionId)))
            else historyToEditor(current)
        }
        queueHistoricalDraft(beforeEditId)
    }

    fun confirmHistoryReplacement(sessionId: Long, requestId: Long, replacementId: Long) {
        val beforeEditId = mutableState.value.originalEdit?.sessionId
        update { current ->
            val history = current.originalHistory ?: return@update current
            val pending = history.replacement ?: return@update current
            if (pending.id != replacementId || current.originalEdit?.sessionId != pending.editSessionId ||
                history.selectedRevision == null || history.currentRevision == null ||
                !canEditHistory(current, sessionId, requestId, history.selectedRevision, history.currentRevision)) current
            else historyToEditor(current)
        }
        queueHistoricalDraft(beforeEditId)
    }

    private fun queueHistoricalDraft(beforeEditId: Long?) {
        mutableState.value.originalEdit?.takeIf { it.loadingDraft && it.sessionId != beforeEditId }?.let {
            submit(Command.OpenEdit(it, true))
        }
    }

    fun cancelHistoryReplacement(sessionId: Long, requestId: Long, replacementId: Long) {
        if (attachments.state.value != null) return
        update { current ->
            val history = current.originalHistory
            if (history?.sessionId != sessionId || history.requestId != requestId || history.replacement?.id != replacementId) current
            else current.copy(originalHistory = history.copy(replacement = null))
        }
    }

    private fun canEditHistory(current: LocalRecordUiState, sessionId: Long, requestId: Long,
        revision: Int, expectedCurrentRevision: Int): Boolean {
        val history = current.originalHistory ?: return false
        return attachments.state.value == null && current.ready && !current.opening && current.error == null && !current.saving && !current.savePending &&
            history.sessionId == sessionId && history.requestId == requestId && !history.loading &&
            history.error == null && !history.unavailable && history.selectedRevision == revision &&
            history.currentRevision == expectedCurrentRevision && history.revisions.any { it.revision == revision } &&
            current.entries.any { it.id == history.entryId && it.currentRevision == expectedCurrentRevision } &&
            (current.originalEdit == null || current.originalEdit.entryId == history.entryId && current.originalEdit.canCancel)
    }

    private fun historyToEditor(current: LocalRecordUiState): LocalRecordUiState {
        val history = checkNotNull(current.originalHistory)
        val entry = current.entries.single { it.id == history.entryId }
        val source = history.revisions.single { it.revision == history.selectedRevision }
        val edit = OriginalEditUiState(originalEditIds.incrementAndGet(), entry.id,
            entry.currentRevision, entry.originalText, text = source.originalText, sourceRevision = source.revision,
            loadingDraft = current.supportsEditDrafts)
        return current.copy(originalHistory = null, originalEditNotice = null, originalEdit = edit)
    }

    private fun requestHistory(history: OriginalHistoryUiState) {
        val next = history.copy(requestId = history.requestId + 1, loading = true, revisions = emptyList(), error = null, unavailable = false,
            replacement = null)
        update { if (it.originalHistory?.sessionId == history.sessionId) it.copy(originalHistory = next) else it }
        submit(Command.ReadHistory(next.sessionId, next.requestId))
    }

    private suspend fun readHistory(command: Command.ReadHistory) {
        val history = mutableState.value.originalHistory ?: return
        if (history.sessionId != command.sessionId || history.requestId != command.requestId || !history.loading) return
        fun owns(current: LocalRecordUiState) = current.originalHistory?.let {
            it.sessionId == command.sessionId && it.requestId == command.requestId
        } == true
        var observedEntries: List<StoredEntry>? = null
        try {
            val store = checkNotNull(repository)
            val before = store.entries().also { observedEntries = it }.firstOrNull { it.id == history.entryId }
            val revisions = if (before == null) emptyList() else store.revisions(history.entryId)
            val entries = store.entries().also { observedEntries = it }
            val after = entries.firstOrNull { it.id == history.entryId }
            if (after == null) {
                update { if (!owns(it)) it else it.copy(entries = entries, originalHistory =
                    it.originalHistory!!.copy(loading = false, revisions = emptyList(), currentRevision = null, unavailable = true)) }
                return
            }
            if (before != after) throw RecordConflict()
            check(revisions.isNotEmpty() && revisions.all { it.entryId == after.id && it.revision in 1..after.currentRevision } &&
                revisions.map { it.revision }.toSet().size == revisions.size &&
                revisions.singleOrNull { it.revision == after.currentRevision }?.originalText == after.originalText)
            val ordered = revisions.sortedByDescending { it.revision }
            update { if (!owns(it)) it else it.copy(entries = entries, originalHistory =
                it.originalHistory!!.copy(loading = false, currentRevision = after.currentRevision, revisions = ordered,
                    unavailable = history.selectedRevision != null && ordered.none { r -> r.revision == history.selectedRevision })) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            update { if (!owns(it)) it else it.copy(entries = observedEntries ?: it.entries, originalHistory = it.originalHistory!!.copy(
                loading = false, revisions = emptyList(), error = if (failure is RecordConflict)
                    "读取期间原文版本变化。请重新读取，正在编辑的修改仍保留。"
                else "历史版本读取失败。原文和正在编辑的修改仍保留，可以重试。")) }
        }
    }

    private fun updatePendingDraft(snapshot: OriginalEditDraft) {
        update { current -> current.copy(pendingEdits = current.pendingEdits.filter { it.entryId != snapshot.entryId } +
            if (snapshot.state == OriginalEditDraftState.ACTIVE) listOf(OriginalEditDraftRef(snapshot.id, snapshot.entryId, snapshot.baseRevision)) else emptyList()) }
    }

    private fun indexNotice(index: OriginalEditDraftIndex): String? = if (index.unreadableCount == 0) null else
        "${index.unreadableCount} 份编辑草稿暂无法安全恢复。可能损坏、密钥不匹配或读取期间变化。加密数据已保留；" +
            if (index.drafts.isEmpty()) "当前没有可安全恢复的编辑草稿，请保留数据并重试读取。" else "可先恢复下方已校验的草稿。"

    private suspend fun refreshPendingDrafts() {
        try {
            val pending = repository?.editDrafts?.pending() ?: OriginalEditDraftIndex(emptyList())
            update { it.copy(pendingEdits = pending.drafts, editDraftError = indexNotice(pending),
                editDraftIssueCount = pending.unreadableCount, editDraftIndexAvailable = true) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            update { it.copy(editDraftIndexAvailable = false, editDraftError = "编辑草稿目录暂不可读取，恢复列表尚未确认。已保留加密数据；请重试本机读取，未自动删除或替换。") }
        }
    }

    // AI_NONE excludes AI participation, not the user's manual original and draft operations.
    private fun mayManuallyEditOriginal(policy: ProcessingPolicy) =
        policy == ProcessingPolicy.AI_NONE || policy == ProcessingPolicy.LOCAL_ONLY

    private suspend fun openOriginalDraft(command: Command.OpenEdit) {
        val sessionId = command.edit.sessionId
        if (mutableState.value.originalEdit?.let { it.sessionId == sessionId && it.loadingDraft } != true) return
        val store = checkNotNull(repository)
        val drafts = checkNotNull(store.editDrafts)
        try {
            val previous = drafts.read(command.edit.entryId)
            if (command.expectedDraftId != null && (previous?.id != command.expectedDraftId || previous.state != OriginalEditDraftState.ACTIVE))
                throw OriginalEditDraftConflict()
            // A confirmed historical replacement compares against the last acknowledged old session,
            // not a newly read competing writer's snapshot.
            val oldBinding = originalDraft?.takeIf { it.snapshot.entryId == command.edit.entryId }
            if (command.replace && oldBinding != null) {
                val known = listOf(if (oldBinding.persisted) oldBinding.snapshot else oldBinding.expected, oldBinding.unacknowledged)
                if (previous !in known) throw OriginalEditDraftConflict()
            }
            // Entering history directly is not consent to discard a dormant recovered edit.
            val restore = previous?.state == OriginalEditDraftState.ACTIVE && (!command.replace || oldBinding == null)
            val snapshot = if (restore) checkNotNull(previous) else OriginalEditDraft(UUID.randomUUID().toString(),
                command.edit.entryId, command.edit.baseRevision, command.edit.sourceRevision, 1,
                command.edit.originalText, command.edit.text)
            val before = store.entries()
            val revisions = store.revisions(snapshot.entryId)
            val entries = store.entries()
            val current = entries.firstOrNull { it.id == snapshot.entryId }
            val base = revisions.singleOrNull { it.revision == snapshot.baseRevision }
            val source = revisions.singleOrNull { it.revision == snapshot.sourceRevision }
            val missing = current == null || base == null || source == null
            if (!missing && base.originalText != snapshot.originalText) throw com.t1doo.aiyou.domain.RecordIntegrityFailure()
            val committed = if (restore) store.committedEdit(snapshot) else null
            val conflict = missing || before != entries || !mayManuallyEditOriginal(current.policy) ||
                committed == null && current.currentRevision != snapshot.baseRevision
            if (committed != null && !conflict) committedOriginal = sessionId to committed
            originalDraft = DraftBinding(sessionId, snapshot, previous, restore)
            // Historical source selection itself creates a dirty input snapshot.
            if (command.replace && !restore && snapshot.text != snapshot.originalText && !conflict) persistDraftSnapshot(sessionId, snapshot.text)
            update { local ->
                if (local.originalEdit?.sessionId != sessionId) local else local.copy(entries = entries,
                    originalEdit = command.edit.copy(baseRevision = snapshot.baseRevision, sourceRevision = snapshot.sourceRevision,
                        originalText = snapshot.originalText, text = snapshot.text, loadingDraft = false,
                        awaitingReadback = committed != null && !conflict,
                        conflict = conflict, draftNotice = if (restore && command.replace) "已恢复已有的未保存修改；请先处理它，历史文本尚未替换此草稿。"
                            else if (committed != null) "已核对这份编辑的加密事务回执。新版本已写入，请重试确认草稿收尾。"
                            else if (restore) "已恢复本机编辑草稿，正式原文尚未被这次恢复修改。"
                            else if (snapshot.text != snapshot.originalText) "编辑草稿已加密保存在本机"
                            else "修改会加密保存为独立的本机编辑草稿。",
                        error = if (conflict) "源记录不可用或版本已变化。已保留你的修改，请复制或明确放弃；未覆盖当前原文。" else null))
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            originalDraft = null
            update { local -> if (local.originalEdit?.sessionId != sessionId) local else local.copy(
                originalEdit = local.originalEdit.copy(loadingDraft = false, conflict = true,
                    draftConflict = failure is OriginalEditDraftConflict,
                    error = "无法安全恢复编辑草稿。当前原文及加密数据已保留，未自动覆盖或删除。请关闭后重试。")) }
            refreshPendingDrafts()
        }
    }

    // Both retries and explicit discard must account for publication whose acknowledgement was lost.
    private suspend fun reconcileDraftAttempt(binding: DraftBinding) {
        val store = checkNotNull(repository?.editDrafts)
        binding.unacknowledged?.let { attempted ->
            val current = store.read(attempted.entryId)
            if (current == attempted) {
                // Visibility after rename is insufficient: reconfirm directory durability without re-encrypting.
                store.write(attempted, attempted)
                binding.snapshot = attempted; binding.expected = attempted; binding.persisted = true
            } else if (current != if (binding.persisted) binding.snapshot else binding.expected) throw OriginalEditDraftConflict()
            binding.unacknowledged = null
        }
    }

    private suspend fun persistDraftSnapshot(sessionId: Long, text: String) {
        val binding = originalDraft?.takeIf { it.sessionId == sessionId } ?: throw OriginalEditDraftConflict()
        val records = checkNotNull(repository)
        val entries = records.entries()
        val source = entries.firstOrNull { it.id == binding.snapshot.entryId }
        update { local -> if (local.originalEdit?.sessionId != sessionId) local else local.copy(entries = entries,
            originalEdit = local.originalEdit.copy(conflict = local.originalEdit.conflict || source == null ||
                source.currentRevision != binding.snapshot.baseRevision || !mayManuallyEditOriginal(source.policy))) }
        if (source == null || !mayManuallyEditOriginal(source.policy)) throw RecordConflict()
        val store = checkNotNull(records.editDrafts)
        reconcileDraftAttempt(binding)
        if (binding.snapshot.state != OriginalEditDraftState.ACTIVE) throw OriginalEditDraftConflict()
        if (binding.persisted && binding.snapshot.text == text) return
        val next = binding.snapshot.copy(text = text,
            version = if (binding.persisted) Math.addExact(binding.snapshot.version, 1L) else 1L)
        val expected = if (binding.persisted) binding.snapshot else binding.expected
        binding.unacknowledged = next
        binding.snapshot = store.write(expected, next)
        binding.unacknowledged = null
        binding.expected = binding.snapshot
        binding.persisted = true
        updatePendingDraft(binding.snapshot)
    }

    private suspend fun persistOriginalDraft(command: Command.PersistEdit) {
        if (mutableState.value.originalEdit?.sessionId != command.sessionId) return
        try {
            persistDraftSnapshot(command.sessionId, command.text)
            update { local ->
                val edit = local.originalEdit
                if (edit?.sessionId != command.sessionId || edit.text != command.text) local
                else local.copy(originalEdit = edit.copy(draftNotice = "编辑草稿已加密保存在本机",
                    error = if (edit.conflict) edit.error else null))
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            update { local -> if (local.originalEdit?.sessionId != command.sessionId) local else local.copy(
                originalEdit = local.originalEdit.copy(draftNotice = "编辑草稿保存状态待确认，当前输入仍保留。",
                    conflict = local.originalEdit.conflict || failure is OriginalEditDraftConflict || failure is RecordConflict,
                    draftConflict = failure is OriginalEditDraftConflict,
                    error = if (failure is RecordConflict) "源记录已不可用或不再允许处理。当前输入仍保留，未重新创建记录或写回已删除来源。请复制输入或明确放弃。"
                        else "编辑草稿写入失败。输入与原数据仍保留；重新输入或保存新版本时会重试。若另一会话已修改草稿，请复制输入并明确放弃当前输入后恢复。")) }
        }
    }

    private suspend fun keepOriginalDraft(command: Command.KeepEdit) {
        fun owns(local: LocalRecordUiState) = local.originalEdit?.let {
            it.sessionId == command.sessionId && it.keeping && it.text == command.text
        } == true
        if (!owns(mutableState.value)) return
        try {
            // Queue ordering drains accepted autosaves first; reconcile any lost acknowledgement.
            persistDraftSnapshot(command.sessionId, command.text)
            val binding = originalDraft?.takeIf { it.sessionId == command.sessionId }
                ?: throw OriginalEditDraftConflict()
            val snapshot = binding.snapshot
            check(binding.persisted && snapshot.state == OriginalEditDraftState.ACTIVE && snapshot.text == command.text)
            val drafts = checkNotNull(repository?.editDrafts)
            // Even an already acknowledged autosave must still be ours and durably confirmed now.
            if (drafts.write(snapshot, snapshot) != snapshot || drafts.read(snapshot.entryId) != snapshot)
                throw OriginalEditDraftConflict()
            updatePendingDraft(snapshot)
            originalDraft = null
            update { if (!owns(it)) it else it.copy(originalEdit = null,
                originalEditNotice = "编辑草稿已保留，可稍后恢复。") }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            update { if (!owns(it)) it else it.copy(originalEdit = it.originalEdit!!.copy(
                keeping = false, draftConflict = failure is OriginalEditDraftConflict,
                conflict = it.originalEdit.conflict || failure is OriginalEditDraftConflict || failure is RecordConflict,
                draftNotice = "保留草稿尚未确认，当前输入仍在编辑中。",
                error = if (failure is OriginalEditDraftConflict) "本机草稿已变化，未覆盖另一会话的修改。请复制当前输入或明确放弃后恢复。"
                    else if (failure is RecordConflict) "源记录不可用，未重新创建记录。当前输入仍保留，请复制输入或明确放弃。"
                    else "保留草稿未确认，尚未关闭编辑。退出应用可能丢失当前修改，请重试保留或自行复制输入。")) }
        }
    }

    private suspend fun discardOriginalDraft(sessionId: Long) {
        if (mutableState.value.originalEdit?.let { it.sessionId == sessionId && it.discarding } != true) return
        try {
            originalDraft?.takeIf { it.sessionId == sessionId }?.let { binding ->
                reconcileDraftAttempt(binding)
                if (!binding.persisted) return@let
                if (binding.snapshot.state != OriginalEditDraftState.ACTIVE) {
                    updatePendingDraft(binding.snapshot)
                    return@let
                }
                val next = binding.snapshot.copy(version = Math.addExact(binding.snapshot.version, 1L),
                    originalText = "", text = "", state = OriginalEditDraftState.DISCARDED)
                binding.unacknowledged = next
                binding.snapshot = checkNotNull(repository?.editDrafts).write(binding.snapshot, next)
                binding.unacknowledged = null
                updatePendingDraft(binding.snapshot)
            }
            originalDraft = null
            update { if (it.originalEdit?.sessionId != sessionId) it else it.copy(originalEdit = null, originalEditNotice = null) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            update { local -> if (local.originalEdit?.sessionId != sessionId) local else local.copy(
                originalEdit = local.originalEdit.copy(discarding = false,
                    draftNotice = "丢弃状态待确认，当前输入仍保留。",
                    draftConflict = failure is OriginalEditDraftConflict,
                    error = if (failure is OriginalEditDraftConflict) "本机已有另一会话的草稿。当前输入仍保留；明确放弃当前输入只会关闭此编辑，不会删除另一会话的修改。"
                        else "丢弃未完成。输入与本机草稿仍保留，可以重试；尚未关闭编辑。")) }
        }
    }

    private suspend fun reviseOriginal(command: Command.Revise) {
        val active = mutableState.value.originalEdit
        if (active?.sessionId != command.sessionId || !active.saving || active.text != command.text) return
        val store = checkNotNull(repository)
        try {
            val committed = committedOriginal?.takeIf { it.first == command.sessionId }?.second
                ?: run {
                    if (store.editDrafts != null) persistDraftSnapshot(command.sessionId, command.text)
                    val draft = originalDraft?.takeIf { it.sessionId == command.sessionId && it.persisted }?.snapshot
                    if (draft == null) store.revise(command.entryId, command.expectedRevision, command.text, clock.millis())
                    else store.reviseFromDraft(draft, clock.millis())
                }.also { entry ->
                    committedOriginal = command.sessionId to entry
                    update { current ->
                        if (current.originalEdit?.sessionId != command.sessionId) current
                        else current.copy(originalEdit = current.originalEdit.copy(awaitingReadback = true))
                    }
                }
            var competingDraft = false
            try {
                originalDraft?.takeIf { it.sessionId == command.sessionId }?.let { binding ->
                    reconcileDraftAttempt(binding)
                    if (binding.persisted && binding.snapshot.state == OriginalEditDraftState.ACTIVE) {
                        val next = binding.snapshot.copy(version = Math.addExact(binding.snapshot.version, 1L),
                            originalText = "", text = "", state = OriginalEditDraftState.COMMITTED)
                        binding.unacknowledged = next
                        binding.snapshot = checkNotNull(store.editDrafts).write(binding.snapshot, next)
                        binding.unacknowledged = null
                        updatePendingDraft(binding.snapshot)
                    }
                }
            } catch (_: OriginalEditDraftConflict) {
                // The formal transaction already returned. Preserve the competing draft and
                // confirm that transaction independently; a CAS loss must not repeat revise.
                competingDraft = true
                refreshPendingDrafts()
            }
            val entries = store.entries()
            val latest = entries.firstOrNull { it.id == committed.id }
            check(latest != null && latest.currentRevision >= committed.currentRevision &&
                (latest.currentRevision != committed.currentRevision || latest.originalText == committed.originalText))
            update { current ->
                if (current.originalEdit?.sessionId != command.sessionId) current
                else current.copy(entries = entries, originalEdit = null,
                    originalEditNotice = if (competingDraft) "新版本已保存在本机；本机另一会话的编辑草稿已保留，请单独恢复处理。"
                        else if (latest.currentRevision == committed.currentRevision) "新版本已保存在本机"
                        else "修改已保存在本机，原文又有更新，请查看当前版本。")
            }
            committedOriginal = null
            originalDraft = null
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            val committed = committedOriginal?.first == command.sessionId
            val refreshed = if (failure is RecordConflict && !committed) {
                try { store.entries() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            } else null
            update { current ->
                val edit = current.originalEdit
                if (edit?.sessionId != command.sessionId) current
                else current.copy(entries = refreshed ?: current.entries, originalEdit = edit.copy(saving = false,
                    awaitingReadback = committed, conflict = (failure is RecordConflict || failure is OriginalEditDraftConflict) && !committed,
                    draftConflict = failure is OriginalEditDraftConflict && !committed,
                    error = when {
                        committed -> "新版本已写入，但草稿收尾或读取确认失败。请重试确认，不会再次创建版本。"
                        failure is OriginalEditDraftConflict -> "本机编辑草稿已由另一会话变化。当前输入仍保留，未覆盖本机草稿或原文。可复制输入，明确放弃当前输入后重新恢复。"
                        failure is RecordConflict -> "原文版本已变化或记录已不可用。你的修改已保留，未覆盖当前原文。"
                        else -> "修改保存失败。你的修改与原文已保留，可以重试。"
                    }))
            }
        }
    }

    private suspend fun open() {
        try {
            val store = repository ?: openStore().also { repository = it }
            store.prepare()
            persistedDraft = store.readDraft()
            captureDraftMayBeConsumed = false
            val entries = store.entries()
            val peopleValues = try { store.manualPeople?.snapshots() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val labels = try { store.manualTags?.snapshots() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val favoriteValues = try { store.favorites?.snapshots() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val eventDateValues = readEventDateSource(store)
            val origins = try { store.recordPackages?.origins() ?: emptyMap() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val pending = try { store.editDrafts?.pending() ?: OriginalEditDraftIndex(emptyList()) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val ownership = storageOwnership ?: RecordsStorageOwnership(store, bindAi?.let { factory ->
                { factory(store) }
            }).also { storageOwnership = it }
            ai.bind(ownership)
            val processingVersions = try { store.localProcessing?.versions() ?: emptyMap() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val searchEpoch = try { store.localSearch?.epoch() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            update { it.copy(ready = true, opening = false, text = persistedDraft?.text.orEmpty(),
                taskProposals = it.taskProposals.copy(supported=store.taskProposals!=null,available=false,loading=store.taskProposals!=null),
                supportsSourceTasks = store.sourceTasks != null && store.localProcessing != null && store.manualTasks != null,
                supportsLocalProcessing = store.localProcessing != null,
                recordAuthorizationOwner = store.recordAuthorizations,
                selectedQuestionAuthorizationOwner = store.selectedQuestionAuthorizations,
                localProcessingVersions = processingVersions.orEmpty(), localProcessingAvailable = processingVersions != null,
                supportsFactRecaps = store.factRecaps != null,
                supportsMixedCapture = store.captureRecords != null,
                supportsCandidates = store.localCandidates != null,
                supportsSourceTags = store.sourceTags != null,
                supportsSourceMemories = store.sourceMemories != null,
                supportsManualPeople = store.manualPeople != null, manualPeopleOwner = store.manualPeople, manualPeople = peopleValues.orEmpty(),
                manualPeopleAvailable = peopleValues != null,
                manualPeopleError = if (store.manualPeople != null && peopleValues == null) "手工人物关联暂不可读取，请重试。" else null,
                entries = entries, manualTags = labels.orEmpty(), manualTagsAvailable = labels != null,
                supportsEventDates = store.eventDates != null, eventDates = eventDateValues?.values.orEmpty(),
                eventDatesAvailable = eventDateValues?.values != null,
                eventDatesError = if (store.eventDates != null && eventDateValues?.values == null) "事件日期暂不可读取，请重试。" else null,
                supportsFavorites = store.favorites != null, favorites = favoriteValues.orEmpty(),
                favoritesAvailable = favoriteValues != null, favoritesLoading = false,
                favoritesError = if (store.favorites != null && favoriteValues == null) "收藏状态暂不可读取，请重试。" else null,
                supportsOriginalExport = store.originalExport != null,
                supportsFullBackup = store.fullBackups != null,
                supportsRecordPackages = store.recordPackages != null && store.originalExport != null && store.manualTags != null,
                packageOrigins = origins.orEmpty(), packageOriginsAvailable = origins != null,
                packageOriginsError = if (origins == null) "恢复来源暂不可读取，不能确认来源。" else null,
                localSearch = store.localSearch,
                localSearchEpoch = searchEpoch,
                supportsRecycleBin = store.recycleBin != null,
                supportsPermanentErasure = store.permanentErasure != null,
                manualTagsError = if (store.manualTags != null && labels == null) "手工标签暂不可读取，请重试。" else null,
                draftNotice = if (persistedDraft == null) "没有未保存的草稿" else "已恢复本机草稿", error = null,
                supportsEditDrafts = store.editDrafts != null, pendingEdits = pending?.drafts.orEmpty(),
                editDraftIssueCount = pending?.unreadableCount ?: 0, editDraftIndexAvailable = pending != null,
                editDraftError = if (pending == null) "编辑草稿目录暂不可读取，恢复列表尚未确认。已保留加密数据；请重试本机读取，未自动删除或替换。" else indexNotice(pending)) }
            mixedCapture.initialize(store)
            tasks.initialize(store)
            reminders.initialize(store)
            taskProposals.initialize(store)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { failed(failure) }
        catch (_: LinkageError) { failed(IllegalStateException()) }
    }

    fun refreshLocalSearch() {
        if (!editsDisposed && mutableState.value.ready && searchRefreshQueued.compareAndSet(false, true) &&
            !submit(Command.RefreshSearch).isSuccess) searchRefreshQueued.set(false)
    }
    fun rebuildLocalSearch() {
        if (!editsDisposed && mutableState.value.ready && !mutableState.value.opening &&
            mutableState.value.error == null && mutableState.value.localSearch != null &&
            !mutableState.value.localIndexBusy && indexRebuildQueued.compareAndSet(false, true)) {
            update { it.copy(localIndexBusy = true) }
            if (!submit(Command.RebuildIndex).isSuccess) {
                indexRebuildQueued.set(false)
                update { if (editsDisposed) it else it.copy(localIndexBusy = false) }
            }
        }
    }
    private fun scheduleLocalIndex() {
        if (!editsDisposed && !indexQueued && !indexFailed && repository?.localSearch != null &&
            mutableState.value.ready && mutableState.value.error == null) {
            indexQueued = submit(Command.IndexBatch).isSuccess
        }
    }
    private fun runLocalIndex() {
        indexQueued = false
        val owner = repository?.localSearch ?: return
        if (editsDisposed || !mutableState.value.ready || mutableState.value.error != null) return
        update { if (editsDisposed || it.localSearch !== owner) it else it.copy(localIndexBusy = true) }
        try {
            val progress = owner.batch()
            update { if (!it.ready || editsDisposed || it.localSearch !== owner) it else
                it.copy(localSearchEpoch = progress.epoch, localIndexBusy = progress.remaining, localIndexError = null) }
            if (progress.remaining) scheduleLocalIndex()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            indexFailed = true
            update { if (!it.ready || editsDisposed || it.localSearch !== owner) it else
                it.copy(localIndexBusy = false, localIndexError = "本机查找整理暂停。原文已保留，可以明确重新整理。") }
        }
    }
    private fun rebuildLocalIndex() {
        val owner = repository?.localSearch ?: return
        if (editsDisposed || !mutableState.value.ready || mutableState.value.error != null) return
        try {
            val epoch = owner.rebuild()
            indexFailed = false
            update { if (!it.ready || editsDisposed || it.localSearch !== owner) it else
                it.copy(localSearchEpoch = epoch, localIndexError = null, localIndexBusy = true) }
            scheduleLocalIndex()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            indexFailed = true
            update { if (!it.ready || editsDisposed || it.localSearch !== owner) it else
                it.copy(localIndexBusy = false, localIndexError = "本机查找重新整理失败。原文与现有数据已保留，可以重试。") }
        }
    }
    /** Retires memory-only authority before a policy write or after observing a generation change. */
    private fun retireDerived(id: String) {
        taskProposals.invalidateSource(id)
        sourceTasks.invalidateSource(id)
        mutableState.value.candidates?.takeIf { it.source.sourceId == id }?.let { candidates.close(it.session) }
        mutableState.value.sourceTags?.takeIf { it.source.sourceId == id }?.let { sourceTags.close(it.session) }
        mutableState.value.sourceMemories?.takeIf { it.source.sourceId == id }?.let { sourceMemories.close(it.session) }
        mutableState.value.ai?.takeIf { it.source.sourceId == id }?.let { ai.leave(it.sessionId) }
        factRecaps.close()
        mutableState.value.originalExport?.takeIf { it.selection.any { source -> source.sourceId == id } }?.let { exports.close(it.session) }
        mutableState.value.recordPackage?.takeIf { it.selection.any { source -> source.sourceId == id } }?.let { recordPackages.close(it.session) }
    }
    private suspend fun refreshProcessingVersions(observedEntries: List<StoredEntry>? = null) {
        val store = repository ?: return
        val port = store.localProcessing ?: return
        if (editsDisposed) return
        val before = mutableState.value
        val entries = try { observedEntries ?: store.entries() }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val versions = try { if (entries == null) null else port.versions() }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val changed = if (versions == null || !before.localProcessingAvailable) before.entries.map { it.id }.toSet()
            else (before.localProcessingVersions.keys + versions.keys).filter { before.localProcessingVersions[it] != versions[it] }.toSet()
        changed.forEach(::retireDerived)
        update { local ->
            val panel = local.localProcessing
            val stale = panel?.snapshot?.let { snapshot -> versions?.get(snapshot.source.sourceId) !=
                com.t1doo.aiyou.domain.LocalProcessingVersion(snapshot.source, snapshot.generation) } == true
            local.copy(entries = entries ?: local.entries, localProcessingVersions = versions.orEmpty(), localProcessingAvailable = versions != null,
                recordAuthorizationOwner = store.recordAuthorizations,
                selectedQuestionAuthorizationOwner = store.selectedQuestionAuthorizations,
                localProcessing = if (stale && !panel.busy && panel.pending == null && panel.pendingWithdrawal == null) panel.copy(
                    snapshot = null, authorization = null, confirmation = null, withdrawalConfirmation = null,
                    notice = null, error = "处理范围已变化，请关闭后从当前原文重新打开。") else panel)
        }
    }

    private suspend fun refreshSearchSource() {
        val store = repository ?: return
        if (editsDisposed || !mutableState.value.ready) return
        val entries = store.entries()
        val peopleValues = try { store.manualPeople?.snapshots() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val labels = try { store.manualTags?.snapshots() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val favoriteValues = try { store.favorites?.snapshots() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val eventDateValues = readEventDateSource(store)
        val epoch = try { store.localSearch?.epoch() } catch (_: Exception) { null }
        refreshProcessingVersions(entries)
        update { if (!it.ready || editsDisposed) it else it.copy(entries = entries,
            manualPeople = peopleValues.orEmpty(), manualPeopleAvailable = peopleValues != null,
            manualPeopleError = if (store.manualPeople != null && peopleValues == null) "手工人物关联暂不可读取，请重试。" else null,
            manualTags = labels.orEmpty(), manualTagsAvailable = labels != null, localSearchEpoch = epoch,
            favorites = favoriteValues.orEmpty(), favoritesAvailable = favoriteValues != null,
            favoritesError = if (store.favorites != null && favoriteValues == null) "收藏状态暂不可读取，请重试。" else null,
            manualTagsError = if (store.manualTags != null && labels == null) "手工标签暂不可读取，请重试。" else null).withEventDateSource(eventDateValues) }
    }

    private fun failed(failure: Exception, preserveQueuedSave: Boolean = false) {
        update { it.copy(opening = false, saving = if (preserveQueuedSave) it.saving else false,
            error = if (failure is StorageKeyUnavailable) "无法读取本机加密密钥。请保留原数据并先解锁设备重试。原文包恢复需要可用的本机存储，无法替换设备绑定密钥。"
            else "本机存储操作失败。输入与原数据已保留，可以重试。",
            draftNotice = if (it.ready) "草稿保存状态待确认" else "草稿尚未读取") }
    }

    override fun onCleared() {
        editsDisposed = true
        sourceTasks.dispose()
        taskProposals.dispose()
        localProcessing.dispose()
        factRecaps.dispose()
        mixedCapture.dispose()
        ai.markDisposed()
        attachments.markDisposed()
        imageGrid.dispose()
        tags.dispose(); people.dispose()
        favorites.dispose()
        eventDates.dispose()
        tasks.dispose()
        reminders.dispose()
        candidates.dispose()
        sourceTags.dispose()
        sourceMemories.dispose()
        exports.dispose()
        recordPackages.dispose()
        recycleBin.dispose()
        commands.close()
        super.onCleared()
    }

    private data class SaveIntent(val text: String, val generation: Long)
    private class DiscardIntent(val id: Long, val text: String, val generation: Long,
        var expected: TextDraft? = null, var bound: Boolean = false) {
        val replacement = TextDraft(UUID.randomUUID().toString(), 1, text)
    }

    private sealed interface Command {
        data class LocalProcessing(val action: suspend (EntryRepository) -> Unit) : Command
        data class FactRecap(val action: suspend (EntryRepository) -> Unit) : Command
        data object IndexBatch : Command
        data object RebuildIndex : Command
        data object RefreshSearch : Command
        data class RecordPackage(val action: suspend (EntryRepository) -> Unit) : Command
        data class RecycleBin(val action: suspend (EntryRepository) -> Unit) : Command
        data class OriginalExport(val action: suspend (EntryRepository) -> Unit) : Command
        data class ManualTasks(val action: suspend (EntryRepository) -> Unit) : Command
        data class LocalCandidates(val action: suspend (EntryRepository) -> Unit) : Command
        data class ManualPeople(val action: suspend (EntryRepository) -> Unit) : Command
        data class ManualTags(val action: suspend (EntryRepository) -> Unit) : Command
        data class Favorites(val action: suspend (EntryRepository) -> Unit) : Command
        data class EventDates(val action: suspend (EntryRepository) -> Unit) : Command
        data object RefreshEditDrafts : Command
        data class OpenEdit(val edit: OriginalEditUiState, val replace: Boolean, val expectedDraftId: String? = null) : Command
        data class PersistEdit(val sessionId: Long, val text: String) : Command
        data class KeepEdit(val sessionId: Long, val text: String) : Command
        data class DiscardEdit(val sessionId: Long) : Command
        data class ReleaseEdit(val sessionId: Long) : Command
        data class MixedCapture(val action: suspend (EntryRepository) -> Unit) : Command
        data class Attachment(val action: suspend (EntryRepository) -> Unit) : Command
        data class Edit(val text: String, val generation: Long) : Command
        data class Save(val intent: SaveIntent) : Command
        data class DiscardCapture(val intent: DiscardIntent) : Command
        data class RestoreCapture(val intent: DiscardIntent) : Command
        data object Retry : Command
        data class RecoverDraft(val text: String, val generation: Long) : Command
        data class Revise(val sessionId: Long, val entryId: String, val expectedRevision: Int, val text: String) : Command
        data class ReadHistory(val sessionId: Long, val requestId: Long) : Command
    }
}
