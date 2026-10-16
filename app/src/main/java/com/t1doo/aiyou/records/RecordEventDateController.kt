package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

internal class EventDateSourceReadback(val values: Map<String, RecordEventDateSnapshot>?)
internal fun readEventDateSource(store: EntryRepository): EventDateSourceReadback? {
    val port = store.eventDates ?: return null
    return try { EventDateSourceReadback(port.snapshots()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { EventDateSourceReadback(null) }
}
internal fun LocalRecordUiState.withEventDateSource(readback: EventDateSourceReadback?): LocalRecordUiState =
    if (readback == null) this else copy(eventDates = readback.values.orEmpty(),
        eventDatesAvailable = readback.values != null, eventDatesError =
            if (readback.values == null) "事件日期暂不可读取，原文已保留。请重试读取。" else null)

class RecordEventDatePreview internal constructor(val id: Long, val date: RecordEventDate?) {
    override fun toString() = "RecordEventDatePreview(redacted)"
}
class RecordEventDateEditor internal constructor(val session: Long, val entryId: String, val revision: Int,
    internal val sourceHash: String, val snapshot: RecordEventDateSnapshot? = null,
    val dateInput: String = "", val zoneInput: String = "", val busy: Boolean = true,
    val preview: RecordEventDatePreview? = null, val conflict: Boolean = false,
    val error: String? = null, val notice: String? = null) {
    fun copy(snapshot: RecordEventDateSnapshot? = this.snapshot, dateInput: String = this.dateInput,
        zoneInput: String = this.zoneInput, busy: Boolean = this.busy, preview: RecordEventDatePreview? = this.preview,
        conflict: Boolean = this.conflict, error: String? = this.error, notice: String? = this.notice) =
        RecordEventDateEditor(session, entryId, revision, sourceHash, snapshot, dateInput, zoneInput,
            busy, preview, conflict, error, notice)
    override fun toString() = "RecordEventDateEditor(busy=$busy, redacted)"
}
class RecordEventDateWrite internal constructor(val token: Long, internal val intent: RecordEventDateMutation,
    val busy: Boolean = true, val conflict: Boolean = false, val error: String? = null,
    @Volatile internal var attempted: Boolean = false) {
    fun copy(busy: Boolean = this.busy, conflict: Boolean = this.conflict, error: String? = this.error) =
        RecordEventDateWrite(token, intent, busy, conflict, error, attempted)
    override fun toString() = "RecordEventDateWrite(busy=$busy, redacted)"
}

/** Manual calendar metadata only. The shared records queue owns all borrowed IO. */
class RecordEventDateController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
) {
    private val tokens = AtomicLong()
    @Volatile private var disposed = false
    @Volatile private var editorGate: () -> Boolean = { true }
    private fun usable(local: LocalRecordUiState = current()) = !disposed && !local.eventDateControllerRetired &&
        local.ready && !local.opening && local.error == null && local.supportsEventDates
    private fun source(editor: RecordEventDateEditor, local: LocalRecordUiState = current()) =
        local.entries.any { it.id == editor.entryId && it.currentRevision == editor.revision &&
            OriginalExports.hash(it.originalText) == editor.sourceHash }
    private fun live(editor: RecordEventDateEditor, local: LocalRecordUiState = current()) = usable(local) &&
        local.eventDateEditor?.session == editor.session && editorGate()
    private fun owns(write: RecordEventDateWrite, local: LocalRecordUiState = current()) = usable(local) &&
        local.eventDateWrites[write.intent.entryId]?.token == write.token
    private fun update(editor: RecordEventDateEditor, change: (RecordEventDateEditor) -> RecordEventDateEditor) = publish {
        if (!live(editor, it)) it else it.copy(eventDateEditor = change(checkNotNull(it.eventDateEditor)))
    }

    fun open(id: String, revision: Int, allowed: () -> Boolean = { true }) {
        val local = current()
        if (!allowed() || !usable(local) || local.saving || local.savePending || local.recordPackage != null ||
            local.recycleBin != null || local.captureDiscard != null || local.eventDateEditor != null) return
        val entry = local.entries.firstOrNull { it.id == id && it.currentRevision == revision } ?: return
        val editor = RecordEventDateEditor(tokens.incrementAndGet(), id, revision, OriginalExports.hash(entry.originalText))
        editorGate = allowed
        publish { if (!usable(it) || !allowed() || it.eventDateEditor != null || !source(editor, it)) it else it.copy(eventDateEditor = editor) }
        if (live(editor) && !enqueue { store -> load(editor, store) }) readFailed(editor)
    }
    fun close(session: Long) {
        publish { if (disposed || it.eventDateEditor?.session != session) it else it.copy(eventDateEditor = null) }
    }
    fun edit(session: Long, date: String, zone: String) {
        val editor = current().eventDateEditor?.takeIf { it.session == session && live(it) && source(it) &&
            !it.busy && !it.conflict && current().eventDateWrites[it.entryId] == null } ?: return
        if (date.length > 10 || zone.length > 128) return
        update(editor) { it.copy(dateInput = date, zoneInput = zone, preview = null, error = null, notice = null) }
    }
    /** A blank field never infers a day/zone; clearing is a separate explicit preview. */
    fun preview(session: Long, clear: Boolean = false) {
        val local = current()
        val editor = local.eventDateEditor?.takeIf { it.session == session && live(it) && source(it) &&
            !it.busy && !it.conflict && local.eventDateWrites[it.entryId] == null } ?: return
        val snapshot = editor.snapshot ?: return
        if (!local.eventDatesAvailable || local.eventDates[editor.entryId]?.let {
            it.version == snapshot.version && it.date == snapshot.date } != true) return stale(editor)
        val value = try { if (clear) null else RecordEventDate(editor.dateInput, editor.zoneInput) }
        catch (_: IllegalArgumentException) { update(editor) { it.copy(preview = null,
            error = "请手填有效单日 YYYY-MM-DD 与 IANA 时区（例如 Asia/Shanghai）；未知请留空，不会推测或补时刻。") }; return }
        catch (_: java.time.DateTimeException) { update(editor) { it.copy(preview = null,
            error = "日期无效，请手填真实日历日期；未知请留空。") }; return }
        if (value == snapshot.date) { update(editor) { it.copy(preview = null, error = "这次选择与已保存事件日期相同。") }; return }
        update(editor) { it.copy(preview = RecordEventDatePreview(tokens.incrementAndGet(), value), error = null, notice = null) }
    }
    fun cancelPreview(session: Long, preview: Long) {
        val editor = current().eventDateEditor?.takeIf { it.session == session && it.preview?.id == preview && !it.busy } ?: return
        update(editor) { it.copy(preview = null) }
    }
    fun confirm(session: Long, preview: Long) {
        val local = current()
        val editor = local.eventDateEditor?.takeIf { it.session == session && it.preview?.id == preview &&
            live(it) && source(it) && !it.busy && !it.conflict && local.eventDateWrites[it.entryId] == null } ?: return
        val snapshot = editor.snapshot ?: return
        if (!local.eventDatesAvailable || local.eventDates[editor.entryId]?.let {
            it.version == snapshot.version && it.date == snapshot.date } != true) return stale(editor)
        val write = RecordEventDateWrite(tokens.incrementAndGet(), RecordEventDateMutation(editor.entryId,
            editor.revision, editor.sourceHash, snapshot.version, editor.preview!!.date, UUID.randomUUID().toString()))
        publish { if (!live(editor, it) || !source(editor, it) || it.eventDateWrites[editor.entryId] != null ||
            it.eventDateEditor?.preview?.id != preview) it else it.copy(
                eventDateWrites = it.eventDateWrites + (editor.entryId to write),
                eventDateEditor = it.eventDateEditor?.copy(busy = true, preview = null, error = null, notice = null)) }
        if (owns(write)) submit(editor, write)
    }
    fun retry(session: Long) {
        val editor = current().eventDateEditor?.takeIf { it.session == session && live(it) && !it.busy } ?: return
        val pending = current().eventDateWrites[editor.entryId]
        if (pending != null && !pending.conflict) {
            if (pending.busy) return
            publish { if (!live(editor, it) || !owns(pending, it)) it else it.copy(
                eventDateEditor = it.eventDateEditor?.copy(busy = true, error = null, notice = null),
                eventDateWrites = it.eventDateWrites + (editor.entryId to pending.copy(busy = true, error = null))) }
            if (owns(pending)) submit(editor, pending)
        } else {
            update(editor) { it.copy(busy = true, preview = null, notice = null) }
            if (!enqueue { store -> load(editor, store) }) readFailed(editor)
        }
    }
    private fun stale(editor: RecordEventDateEditor) = update(editor) { it.copy(preview = null, conflict = true,
        error = "原文或事件日期已变化，输入已保留。请重新读取后预览确认。") }
    private suspend fun load(editor: RecordEventDateEditor, store: EntryRepository) {
        if (!live(editor)) return
        try {
            val entries = store.entries()
            val all = checkNotNull(store.eventDates).snapshots()
            val snapshot = all[editor.entryId] ?: throw RecordEventDateUnavailable()
            val epoch = store.localSearch?.epoch()
            publish { local ->
                if (!live(editor, local)) local else {
                    val pending = local.eventDateWrites[editor.entryId]
                    val initialDate = if (pending != null && !pending.conflict) pending.intent.date else snapshot.date
                    val freshEntry = entries.singleOrNull { it.id == editor.entryId }
                    val fresh = freshEntry?.let { it.currentRevision == editor.revision && OriginalExports.hash(it.originalText) == editor.sourceHash } == true
                    local.copy(entries = entries, eventDates = all, eventDatesAvailable = true, eventDatesError = null,
                        localSearchEpoch = epoch, eventDateWrites = if (pending?.conflict == true) local.eventDateWrites - editor.entryId else local.eventDateWrites,
                        eventDateEditor = local.eventDateEditor?.copy(snapshot = snapshot, busy = pending?.busy == true,
                            dateInput = if (editor.snapshot == null) initialDate?.localDate.orEmpty() else local.eventDateEditor.dateInput,
                            zoneInput = if (editor.snapshot == null) initialDate?.timeZone.orEmpty() else local.eventDateEditor.zoneInput,
                            preview = null, conflict = !fresh, notice = null,
                            error = if (!fresh) "原文已变化，输入已保留。请关闭后重新打开。" else pending?.error))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { readFailed(editor) }
    }
    private fun readFailed(editor: RecordEventDateEditor) = publish { local ->
        if (!live(editor, local)) local else local.copy(eventDatesAvailable = false,
            eventDatesError = "事件日期暂不可读取，原文已保留。请重试。",
            eventDateEditor = local.eventDateEditor?.copy(busy = false, preview = null,
                error = "事件日期读取失败，输入与加密数据已保留，请重试读取。"))
    }
    private fun submit(editor: RecordEventDateEditor, write: RecordEventDateWrite) {
        if (!enqueue { store ->
            // A retained callback or navigation before execution cannot start this write.
            if (!owns(write)) return@enqueue
            if (!live(editor)) {
                publish { if (!owns(write, it)) it else it.copy(eventDateWrites =
                    if (write.attempted) it.eventDateWrites + (editor.entryId to write.copy(busy = false,
                        error = "这次保存状态仍待确认，请重新打开并重试同一次选择。"))
                    else it.eventDateWrites - editor.entryId) }
                return@enqueue
            }
            try {
                val dates = checkNotNull(store.eventDates)
                write.attempted = true
                val saved = dates.set(write.intent)
                val entries = store.entries()
                val all = dates.snapshots()
                val fresh = all[editor.entryId] ?: throw RecordEventDateUnavailable()
                if (saved.version != fresh.version || saved.date != fresh.date) throw RecordEventDateConflict()
                val epoch = store.localSearch?.epoch()
                publish { local -> if (!owns(write, local)) local else local.copy(entries = entries, eventDates = all,
                    eventDatesAvailable = true, eventDatesError = null, localSearchEpoch = epoch,
                    eventDateWrites = local.eventDateWrites - editor.entryId,
                    eventDateEditor = if (!live(editor, local)) local.eventDateEditor else local.eventDateEditor?.copy(
                        snapshot = fresh, dateInput = fresh.date?.localDate.orEmpty(), zoneInput = fresh.date?.timeZone.orEmpty(),
                        busy = false, preview = null, conflict = false, error = null,
                        notice = if (fresh.date == null) "事件日期已清除；记录时间与原文版本未改动。" else "事件日期已保存在本机；未补时刻，记录时间与原文版本未改动。")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(editor, write, failure is RecordEventDateConflict || failure is RecordEventDateUnavailable) }
        }) failed(editor, write, false)
    }
    private fun failed(editor: RecordEventDateEditor, write: RecordEventDateWrite, conflict: Boolean) = publish { local ->
        if (!owns(write, local)) local else {
            val message = if (conflict) "原文或事件日期已变化，请重新读取后重新预览确认。"
                else "这次保存状态待确认。请重试确认同一次选择；确认前不能改成新的选择。"
            local.copy(eventDatesAvailable = false, eventDatesError = "事件日期保存状态待确认，原文已保留。",
                eventDateWrites = local.eventDateWrites + (editor.entryId to write.copy(busy = false, conflict = conflict, error = message)),
                eventDateEditor = if (!live(editor, local)) local.eventDateEditor else local.eventDateEditor?.copy(
                    busy = false, preview = null, conflict = conflict, error = message, notice = null))
        }
    }
    fun dispose() { disposed = true; publish { it.copy(eventDateControllerRetired = true, eventDateEditor = null) } }
}
