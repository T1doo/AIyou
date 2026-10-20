package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class SourceMemoryInboxRequest internal constructor()

data class SourceMemoryInboxUiState(val session: Long, val request: SourceMemoryInboxRequest = SourceMemoryInboxRequest(), val busy: Boolean = false,
    val snapshot: SourceMemoryInboxSnapshot? = null, val canGoBack: Boolean = false, val error: String? = null) {
    override fun toString() = "SourceMemoryInboxUiState(redacted)"
}

/** Private read-only pages on the existing IO actor. Every rendered action owns its request. */
class SourceMemoryInboxController internal constructor(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean) {
    private val tokens = AtomicLong()
    private val gate = Any()
    private val cursors = mutableListOf<SourceMemoryInboxCursor?>()
    @Volatile private var disposed = false
    private fun available(local: LocalRecordUiState) = !disposed && local.ready && !local.opening &&
        local.error == null && local.supportsMemoryInbox && (!local.supportsLocalProcessing || local.localProcessingAvailable)
    private fun owns(local: LocalRecordUiState, session: Long, request: SourceMemoryInboxRequest) = available(local) &&
        local.memoryInbox?.let { it.session == session && it.request === request } == true
    private fun owns(session: Long, request: SourceMemoryInboxRequest) = owns(current(), session, request)
    private fun change(session: Long, request: SourceMemoryInboxRequest, action: (SourceMemoryInboxUiState) -> SourceMemoryInboxUiState) = publish {
        if (owns(it, session, request)) it.copy(memoryInbox = action(checkNotNull(it.memoryInbox))) else it
    }
    fun open(): Unit = synchronized(gate) {
        val local = current()
        if (!available(local) || local.saving || local.savePending) return
        val session = tokens.incrementAndGet(); cursors.clear(); cursors.add(null)
        publish { if (available(it)) it.copy(memoryInbox = SourceMemoryInboxUiState(session)) else it }
        current().memoryInbox?.takeIf { it.session == session }?.let { read(it) }
    }
    fun close(session: Long): Unit = synchronized(gate) {
        publish { if (it.memoryInbox?.session == session) it.copy(memoryInbox = null) else it }
        if (current().memoryInbox == null) cursors.clear()
    }
    fun refresh(session: Long, request: SourceMemoryInboxRequest): Unit = synchronized(gate) {
        if (!owns(session, request) || current().memoryInbox?.busy == true) return
        cursors.clear(); cursors.add(null); read(checkNotNull(current().memoryInbox))
    }
    fun next(session: Long, request: SourceMemoryInboxRequest): Unit = synchronized(gate) {
        val ui = current().memoryInbox ?: return
        if (!owns(session, request) || ui.busy) return
        val next = ui.snapshot?.next ?: return
        cursors.add(next); read(ui)
    }
    fun previous(session: Long, request: SourceMemoryInboxRequest): Unit = synchronized(gate) {
        if (!owns(session, request) || current().memoryInbox?.busy == true || cursors.size < 2) return
        cursors.removeAt(cursors.lastIndex); read(checkNotNull(current().memoryInbox))
    }
    private fun read(before: SourceMemoryInboxUiState) {
        val request = SourceMemoryInboxRequest(); val cursor = cursors.lastOrNull()
        change(before.session, before.request) { it.copy(request = request, busy = true, snapshot = null,
            canGoBack = cursors.size > 1, error = null) }
        if (!enqueue { store ->
            if (!owns(before.session, request)) return@enqueue
            try {
                val owner = checkNotNull(store.sourceMemories?.inbox); val snapshot = owner.capture(cursor)
                if (!owns(before.session, request)) return@enqueue
                val accepted = owner.publishCurrent(snapshot) {
                    synchronized(gate) { change(before.session, request) { it.copy(busy = false, snapshot = snapshot) } }
                }
                if (!accepted) fail(before.session, request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(before.session, request) }
        }) fail(before.session, request)
    }
    fun source(session: Long, request: SourceMemoryInboxRequest, item: SourceMemorySnapshot, onOpen: () -> Boolean) {
        val snapshot = synchronized(gate) {
            val ui = current().memoryInbox ?: return
            if (!owns(session, request) || ui.busy) return
            val snapshot = ui.snapshot ?: return
            if (snapshot.pending.none { it === item }) return
            change(session, request) { it.copy(busy = true) }; snapshot
        }
        if (!enqueue { store ->
            if (!owns(session, request)) return@enqueue
            try {
                var opened = false
                val resolved = checkNotNull(store.sourceMemories?.inbox).resolve(snapshot, item) {
                    synchronized(gate) { if (owns(session, request)) opened = onOpen() }
                }
                if (!resolved || !opened) fail(session, request)
                else synchronized(gate) { change(session, request) { it.copy(busy = false) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(session, request) }
        }) fail(session, request)
    }
    private fun fail(session: Long, request: SourceMemoryInboxRequest): Unit = synchronized(gate) {
        change(session, request) { it.copy(busy = false, snapshot = null,
            error = "候选或来源已变化，或本机读取未完成。请刷新重新核对；没有显示不完整的待审结果。") }
    }
    fun invalidate(): Unit = synchronized(gate) {
        val ui = current().memoryInbox ?: return
        change(ui.session, ui.request) { it.copy(request = SourceMemoryInboxRequest(), busy = false, snapshot = null,
            error = "记录或审阅结果已变化，请刷新待审线索。") }
    }
    fun dispose(): Unit = synchronized(gate) { disposed = true; cursors.clear(); publish { it.copy(memoryInbox = null) } }
}

/** Synchronous UI retirement while the IO actor confirms database ownership separately. */
internal fun reconcileMemoryInbox(before: LocalRecordUiState, after: LocalRecordUiState): LocalRecordUiState {
    val ui = after.memoryInbox ?: return after
    if (!after.ready || after.opening || after.error != null || !after.supportsMemoryInbox ||
        after.supportsLocalProcessing && !after.localProcessingAvailable) return after.copy(memoryInbox = null)
    if (ui.snapshot == null || before.memoryInbox?.snapshot === ui.snapshot && before.entries === after.entries && before.localProcessingVersions === after.localProcessingVersions) return after
    val valid = ui.snapshot.pending.all { card -> after.entries.any {
        it.id == card.source.sourceId && it.currentRevision.toLong() == card.source.revision &&
            it.policy != ProcessingPolicy.AI_NONE && OriginalExports.hash(it.originalText) == card.sourceHash &&
            it.capturedAtMillis == card.capturedAtMillis && it.captureTimeZone == card.captureTimeZone
    } && (!after.supportsLocalProcessing || after.localProcessingVersions[card.source.sourceId]?.let {
        it.source == card.source && it.generation == card.generation } == true) }
    return if (valid) after else after.copy(memoryInbox = ui.copy(snapshot = null, busy = false,
        request = SourceMemoryInboxRequest(), error = "来源已变化，请刷新待审线索。"))
}
