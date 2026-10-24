package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.AttachmentIntegrityFailure
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.LocalAttachment
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Platform selection is short-lived. No URI, grant, name or plaintext enters saved state. */
interface SelectedImageSource {
    fun mediaType(): String?
    fun open(): InputStream
}

interface AttachmentPicture {
    val width: Int
    val height: Int
    val allocationBytes: Long get() = width.toLong() * height * 4
    fun releaseUnpublished() {}
}
/** Read permission comes from this exact history request, never from an import session. */
data class AttachmentHistoryOwner(val sessionId: Long, val requestId: Long, val currentRevision: Int)
enum class AttachmentImportPhase { PICKING, PREPARING, SAVING, FAILED }
data class AttachmentSelection(val requestId: Long, val attachmentId: String,
    val expected: AttachmentIntent? = null, val phase: AttachmentImportPhase = AttachmentImportPhase.PICKING,
    val launched: Boolean = false, val sourcePrepared: Boolean = false, val retryable: Boolean = true,
    val pickerKey: String = UUID.randomUUID().toString(), val retainJpegMetadata: Boolean = true)
data class AttachmentUiState(val sessionId: Long, val entryId: String, val revision: Int,
    val loading: Boolean = true, val items: List<LocalAttachment> = emptyList(),
    val selection: AttachmentSelection? = null, val readingId: String? = null,
    val picture: AttachmentPicture? = null, val error: String? = null,
    val notice: String? = null, val unavailable: Boolean = false,
    val historyOwner: AttachmentHistoryOwner? = null, val retainJpegMetadata: Boolean = true, val focusAudio:AttachmentIntent?=null, val focusImage:AttachmentIntent?=null,val pictureId:String?=null, val transcriptSource:Boolean=false, val transcriptReference:com.t1doo.aiyou.domain.AudioTranscriptPlaybackReference?=null) {
    init { require(focusAudio==null||focusImage==null)
        require(!transcriptSource||focusAudio!=null)
        require(transcriptReference==null||transcriptSource&&transcriptReference.anchor.intent==focusAudio&&transcriptReference.anchor.intent.entryId==entryId&&transcriptReference.anchor.intent.revision==revision)
    }
    val focusedIntent get()=focusAudio?:focusImage
    val readOnly get() = historyOwner != null
    val saving get() = selection?.phase == AttachmentImportPhase.SAVING
    val busy get() = loading || readingId != null || selection?.phase in
        setOf(AttachmentImportPhase.PICKING, AttachmentImportPhase.PREPARING, AttachmentImportPhase.SAVING)
}

/** Shared production presentation/IO gate; global storage failures remain independently recoverable. */
internal fun AttachmentUiState.hasRecordAccess(local: LocalRecordUiState): Boolean {
    if (local.saving || local.savePending) return false
    val entry = local.entries.singleOrNull { it.id == entryId } ?: return false
    val owner = historyOwner ?: return local.originalEdit == null && local.originalHistory == null &&
        entry.currentRevision == revision
    val history = local.originalHistory ?: return false
    return history.sessionId == owner.sessionId && history.requestId == owner.requestId &&
        history.entryId == entryId && history.selectedRevision == revision &&
        history.currentRevision == owner.currentRevision && entry.currentRevision == owner.currentRevision &&
        !history.loading && history.error == null && !history.unavailable && history.replacement == null &&
        (local.originalEdit == null || local.originalEdit.entryId == entryId && local.originalEdit.canCancel) &&
        history.revisions.singleOrNull { it.revision == revision }?.entryId == entryId
}

/** Every operation runs on the record ViewModel's existing serial IO queue. */
class LocalAttachmentController(
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val decodePng: (ByteArray) -> AttachmentPicture,
    private val canUseRecord: () -> Boolean = { true },
    private val canUsePanel: (AttachmentUiState) -> Boolean = { true },
    private val sourceUi:((()->Unit)->Unit)={it()},
    private val onPanelClose:()->Unit={},
    private val beforeImageMutation:()->Unit={},
) {
    private val mutable = MutableStateFlow<AttachmentUiState?>(null)
    val state = mutable.asStateFlow()
    private val ids = AtomicLong()
    @Volatile private var disposed = false
    // IO queue owned. UI cancellation changes ownership; it never erases an active Cipher source.
    private var prepared: Prepared? = null
    private data class Prepared(val sessionId: Long, val requestId: Long,
        val intent: AttachmentIntent, val bytes: ByteArray)

    fun open(entryId: String, revision: Int) { sourceUi { openSourceOwned(entryId,revision) } }
    private fun openSourceOwned(entryId: String, revision: Int) {
        openPanel(entryId, revision, null)
    }

    fun openHistorical(entryId: String, revision: Int, owner: AttachmentHistoryOwner) { sourceUi { openHistoricalSourceOwned(entryId,revision,owner) } }
    private fun openHistoricalSourceOwned(entryId: String, revision: Int, owner: AttachmentHistoryOwner) {
        if (owner.sessionId <= 0 || owner.requestId <= 0 || owner.currentRevision < revision) return
        openPanel(entryId, revision, owner)
    }

    fun openAudio(intent:AttachmentIntent) {sourceUi {if(intent.mediaType=="audio/wav")openPanel(intent.entryId,intent.revision,null,intent)}}
    fun openTranscript(reference:com.t1doo.aiyou.domain.AudioTranscriptPlaybackReference) {sourceUi {
        val i=reference.anchor.intent
        if(i.mediaType=="audio/wav")openPanel(i.entryId,i.revision,null,i,transcript=reference)
    }}
    private fun openPanel(entryId: String, revision: Int, owner: AttachmentHistoryOwner?,focus:AttachmentIntent?=null,image:AttachmentIntent?=null,transcript:com.t1doo.aiyou.domain.AudioTranscriptPlaybackReference?=null) {
        if (disposed || mutable.value != null || revision <= 0) return
        val panel = AttachmentUiState(ids.incrementAndGet(), entryId, revision, historyOwner = owner,focusAudio=focus,focusImage=image,transcriptSource=transcript!=null,transcriptReference=transcript)
        if (mutable.compareAndSet(null, panel)) refresh(panel.sessionId)
    }

    fun openImage(intent:AttachmentIntent) {sourceUi {if(intent.mediaType in IMAGE_TYPES)openPanel(intent.entryId,intent.revision,null,image=intent)}}

    /** Lost private proof retires the real candidate, including an in-flight read. Playback
     * stop/close retains its existing resource ACK gate; this never fakes a native release. */
    fun retireFocusedAudio(sessionId:Long) {if(mutable.value?.takeIf{it.sessionId==sessionId}?.focusAudio!=null)retireFocusedSource(sessionId)}
    fun retireFocusedImage(sessionId:Long) {if(mutable.value?.takeIf{it.sessionId==sessionId}?.focusImage!=null)retireFocusedSource(sessionId)}
    fun retireFocusedSource(sessionId:Long) {
        while(true) {
            val panel=mutable.value ?: return
            if(panel.sessionId!=sessionId || panel.focusedIntent==null)return
            if(mutable.compareAndSet(panel,null)) {
                onPanelClose()
                enqueue {discard(sessionId)}
                return
            }
        }
    }
    fun close(sessionId: Long) {
        val panel = mutable.value ?: return
        if (panel.sessionId != sessionId || panel.saving) return
        onPanelClose()
        if (mutable.compareAndSet(panel, null)) enqueue { discard(sessionId) }
    }

    fun refresh(sessionId: Long) { sourceUi { refreshSourceOwned(sessionId) } }
    private fun refreshSourceOwned(sessionId: Long) {
        val panel = mutable.value ?: return
        if (panel.sessionId != sessionId || panel.saving || panel.selection != null || panel.readingId != null) return
        update(sessionId) { it.copy(loading = true, error = null, picture = null, pictureId=null) }
        enqueue { repository ->
            if (!live(sessionId)) return@enqueue
            try {
                current(repository, panel)
                val store = access(repository)
                store.recover(beforeImageMutation)
                current(repository, panel)
                val rows = store.list(panel.entryId, panel.revision)
                verifyRows(panel, rows)
                current(repository, panel)
                update(sessionId) { it.copy(loading = false, items = rows.toList(), error = null) }
                panel.focusImage?.let{focus->if(live(sessionId))view(sessionId,focus.id)}
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(sessionId, failure, "附件暂不可读取。原文与文件已保留，可以重试。") }
        }
    }

    fun select(sessionId: Long, failedId: String? = null) { sourceUi { selectSourceOwned(sessionId,failedId) } }
    private fun selectSourceOwned(sessionId: Long, failedId: String? = null) {
        val panel = mutable.value ?: return
        if (panel.readOnly || panel.focusedIntent!=null || panel.sessionId != sessionId || panel.busy || panel.selection != null || panel.error != null || panel.unavailable) return
        val prior = failedId?.let { id -> panel.items.singleOrNull {
            it.intent.id == id && it.state == AttachmentState.FAILED && it.intent.mediaType in IMAGE_TYPES
        }?.intent ?: return }
        update(sessionId) { it.copy(picture = null, pictureId=null, error = null, notice = null,
            selection = AttachmentSelection(ids.incrementAndGet(), prior?.id ?: UUID.randomUUID().toString(), prior,
                retainJpegMetadata = it.retainJpegMetadata)) }
    }

    fun setJpegMetadataChoice(sessionId: Long, retain: Boolean) { sourceUi { setJpegMetadataChoiceSourceOwned(sessionId,retain) } }
    private fun setJpegMetadataChoiceSourceOwned(sessionId: Long, retain: Boolean) {
        val panel = mutable.value ?: return
        if (panel.sessionId != sessionId || panel.readOnly || panel.focusedIntent!=null || panel.busy || panel.selection != null || panel.error != null || panel.unavailable) return
        update(sessionId) { it.copy(retainJpegMetadata = retain) }
    }

    fun claimPicker(requestId: Long): Boolean {
        val panel = mutable.value ?: return false
        if (panel.readOnly || panel.focusedIntent!=null) return false
        val selected = panel.selection ?: return false
        if (selected.requestId != requestId || selected.phase != AttachmentImportPhase.PICKING || selected.launched) return false
        return mutable.compareAndSet(panel, panel.copy(selection = selected.copy(launched = true)))
    }

    fun pickerFailed(requestId: Long) { sourceUi { pickerFailedSourceOwned(requestId) } }
    private fun pickerFailedSourceOwned(requestId: Long) {
        val panel = mutable.value ?: return
        if (panel.readOnly || panel.focusedIntent!=null) return
        if (panel.selection?.requestId == requestId) failed(panel.sessionId, IllegalStateException(),
            "无法打开图片选择器。原文与附件已保留，请重新选择。", requestId)
    }

    fun selected(requestId: Long, source: SelectedImageSource?) {
        var accepted:(()->Unit)?=null
        sourceUi { selectedSourceOwned(requestId,source){accepted=it} }
        accepted?.invoke() // No capture monitor while acquiring query/navigation lifecycle ownership.
    }
    private fun selectedSourceOwned(requestId: Long, source: SelectedImageSource?,afterAdmission:((()->Unit)->Unit)) {
        val panel = mutable.value ?: return
        val selected = panel.selection ?: return
        if (panel.readOnly || panel.focusedIntent!=null) return
        if (selected.requestId != requestId || selected.phase != AttachmentImportPhase.PICKING) return
        if(!canUseRecord()||!canUsePanel(panel)||!live(panel.sessionId))return
        if (source == null) { cancel(panel.sessionId); return }
        if (!mutable.compareAndSet(panel, panel.copy(selection = selected.copy(phase = AttachmentImportPhase.PREPARING)))) return
        afterAdmission {
        beforeImageMutation() // Stable selection CAS accepted, outside the CAS and before queue admission.
        enqueue { repository ->
            if (!owns(panel.sessionId, requestId)) return@enqueue
            var bytes: ByteArray? = null
            try {
                val mediaType = checkNotNull(source.mediaType())
                require(mediaType in IMAGE_TYPES)
                bytes = source.open().use { input -> bounded(input) { owns(panel.sessionId, requestId) } }
                if (!owns(panel.sessionId, requestId)) return@enqueue
                require(matchesHeader(bytes, checkNotNull(mediaType)))
                if (mediaType == "image/jpeg") {
                    val cleaned = JpegPayloadValidator.prepare(bytes, selected.retainJpegMetadata)
                    if (cleaned !== bytes) { bytes.fill(0); bytes = cleaned }
                }
                if (!owns(panel.sessionId, requestId)) return@enqueue
                decodePng(bytes).releaseUnpublished() // Actual bounded platform decode; never cache this validation bitmap.
                val intent = AttachmentIntent(selected.attachmentId, panel.entryId, panel.revision,
                    mediaType, hash(bytes), bytes.size.toLong())
                require(selected.expected == null || selected.expected == intent)
                if (!owns(panel.sessionId, requestId)) return@enqueue
                discard(panel.sessionId)
                prepared = Prepared(panel.sessionId, requestId, intent, bytes)
                bytes = null
                update(panel.sessionId) { it.copy(selection = it.selection?.copy(sourcePrepared = true)) }
                commit(repository, panel.sessionId, requestId)
            } catch (cancelled: CancellationException) {
                if (owns(panel.sessionId, requestId)) throw cancelled
            } catch (failure: Exception) {
                failed(panel.sessionId, failure, "图片未保存。请重新选择可读取的 JPEG 或 PNG 图片；原文与原文件已保留。", requestId)
            } finally { bytes?.fill(0) }
        }
        }
    }

    fun cancel(sessionId: Long) { sourceUi { cancelSourceOwned(sessionId) } }
    private fun cancelSourceOwned(sessionId: Long) {
        val panel = mutable.value ?: return
        if (panel.readOnly || panel.focusedIntent!=null || panel.sessionId != sessionId || panel.saving) return
        update(sessionId) { it.copy(selection = null, error = null, notice = "未继续添加图片，原文与已有附件已保留。") }
        enqueue { discard(sessionId) }
        refresh(sessionId)
    }

    fun retrySave(sessionId: Long) {
        var accepted:(()->Unit)?=null
        sourceUi { retrySaveSourceOwned(sessionId){accepted=it} }
        accepted?.invoke()
    }
    private fun retrySaveSourceOwned(sessionId: Long,afterAdmission:((()->Unit)->Unit)) {
        val panel = mutable.value ?: return
        val selected = panel.selection ?: return
        if (panel.readOnly || panel.focusedIntent!=null || panel.sessionId != sessionId || selected.phase != AttachmentImportPhase.FAILED || !selected.retryable || panel.unavailable) return
        if(!canUseRecord()||!canUsePanel(panel)||!live(sessionId))return
        if(!mutable.compareAndSet(panel,panel.copy(selection=selected.copy(phase=AttachmentImportPhase.PREPARING))))return
        afterAdmission {
        beforeImageMutation()
        enqueue { repository ->
            if (!owns(sessionId, selected.requestId)) return@enqueue
            if (prepared?.requestId == selected.requestId) commit(repository, sessionId, selected.requestId)
            else {
                update(sessionId) { it.copy(selection = null, error = null) }
                select(sessionId, selected.expected?.id)
            }
        }
        }
    }

    private suspend fun commit(repository: EntryRepository, sessionId: Long, requestId: Long) {
        val input = prepared?.takeIf { it.sessionId == sessionId && it.requestId == requestId } ?: return
        val panel = mutable.value ?: return
        val selected = panel.selection ?: return
        if (panel.readOnly || panel.focusedIntent!=null) return
        if (!owns(sessionId, requestId)) return
        try {
            current(repository, panel)
            val store = access(repository)
            // Claim before any durable write: subsequent Cancel/Back cannot promise a rollback.
            if (!mutable.compareAndSet(panel, panel.copy(selection = selected.copy(phase = AttachmentImportPhase.SAVING), error = null))) return
            beforeImageMutation() // Actor revalidated the exact source/request before the real write.
            store.recover(beforeImageMutation)
            val existing = store.list(panel.entryId, panel.revision).singleOrNull { it.intent.id == input.intent.id }
            if (existing != null && existing.intent != input.intent) throw AttachmentIntegrityFailure()
            val result = existing?.takeIf { it.state == AttachmentState.READY }
                ?: store.save(input.intent) { ByteArrayInputStream(input.bytes) }
            check(result.intent == input.intent && result.state == AttachmentState.READY)
            val rows = store.list(panel.entryId, panel.revision)
            verifyRows(panel, rows)
            check(rows.single { it.intent.id == input.intent.id } == result)
            val restored = store.read(input.intent.id)
            try { check(restored.size.toLong() == input.intent.byteSize && hash(restored) == input.intent.sha256) }
            finally { restored.fill(0) }
            current(repository, panel)
            update(sessionId) { it.copy(selection = null, items = rows.toList(), error = null,
                notice = "图片已保存在本机 · 原文版本 ${panel.revision}") }
            discard(sessionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val rows = if (failure is LocalRecordUnavailable || failure is RecordConflict) null
                else try { access(repository).list(panel.entryId, panel.revision).also { verifyRows(panel, it) } } catch (_: Exception) { null }
            val corrupt = rows?.any { it.intent.id == input.intent.id && it.state == AttachmentState.CORRUPT } == true
            update(sessionId) { it.copy(items = rows ?: it.items, selection = it.selection?.copy(retryable = !corrupt)) }
            failed(sessionId, failure, "图片保存状态尚未确认。原文、原文件与本次图片已保留，请重试确认。", requestId)
            if (corrupt) {
                update(sessionId) { it.copy(error = "附件无法通过完整性校验。原文件已保留，不会显示或覆盖。") }
                discard(sessionId)
            }
        }
    }

    fun view(sessionId: Long, id: String) { sourceUi { viewSourceOwned(sessionId,id) } }
    private fun viewSourceOwned(sessionId: Long, id: String) {
        val panel = mutable.value ?: return
        if(panel.focusAudio!=null || panel.focusImage?.let{it.id!=id}==true)return
        if (panel.sessionId != sessionId || panel.busy || panel.selection != null || panel.unavailable) return
        val item = panel.items.singleOrNull { it.intent.id == id && it.state == AttachmentState.READY && it.intent.mediaType in IMAGE_TYPES } ?: return
        update(sessionId) { it.copy(readingId = id, picture = null, pictureId=null, error = null) }
        enqueue { repository ->
            if (!live(sessionId)) return@enqueue
            try {
                current(repository, panel)
                val store = access(repository)
                val rows = store.list(panel.entryId, panel.revision)
                verifyRows(panel, rows)
                if (rows.singleOrNull { it.intent.id == id } != item) throw AttachmentIntegrityFailure()
                current(repository, panel)
                val bytes = store.read(item.intent.id)
                val picture = try {
                    current(repository, panel)
                    if (bytes.size.toLong() != item.intent.byteSize || hash(bytes) != item.intent.sha256) throw AttachmentIntegrityFailure()
                    if (!matchesHeader(bytes, item.intent.mediaType)) throw AttachmentIntegrityFailure()
                    decodePng(bytes)
                } finally { bytes.fill(0) }
                var published = false
                try {
                    current(repository, panel)
                    while (true) {
                        val state = mutable.value
                        if (state == null || disposed || state.sessionId != sessionId || state.readingId != id) break
                        if (mutable.compareAndSet(state, state.copy(readingId = null, picture = picture, pictureId=id, error = null))) {
                            published = true; break
                        }
                    }
                } finally { if (!published) picture.releaseUnpublished() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val rows = if (failure is RecordConflict || failure is LocalRecordUnavailable) null
                    else try { access(repository).list(panel.entryId, panel.revision).also { verifyRows(panel, it) } } catch (_: Exception) { null }
                update(sessionId) { it.copy(items = rows ?: it.items, picture = null, pictureId=null) }
                failed(sessionId, failure, "图片暂不可查看，原文件已保留。请重试读取；损坏的附件不会显示。")
            }
        }
    }

    fun closePicture(sessionId: Long) = update(sessionId) { it.copy(picture = null, pictureId=null) }
    fun markDisposed() { disposed = true; mutable.value = null }
    fun disposeOnIo() { prepared?.bytes?.fill(0); prepared = null }
    private fun discard(sessionId: Long) { if (prepared?.sessionId == sessionId) disposeOnIo() }
    private fun live(sessionId: Long) = !disposed && mutable.value?.sessionId == sessionId
    private fun owns(sessionId: Long, requestId: Long) = live(sessionId) && mutable.value?.selection?.requestId == requestId
    private inline fun update(sessionId: Long, transform: (AttachmentUiState) -> AttachmentUiState) {
        mutable.update { if (it != null && !disposed && it.sessionId == sessionId) transform(it) else it }
    }
    private fun failed(sessionId: Long, failure: Exception, message: String, requestId: Long? = null) {
        if (requestId != null && !owns(sessionId, requestId)) return
        update(sessionId) { it.copy(loading = false, readingId = null, picture = null, pictureId=null,
            unavailable = failure is RecordConflict || it.unavailable,
            error = when (failure) {
                is RecordConflict -> "原文版本已变化或不可用。附件保留在原版本，请返回原文。"
                is LocalRecordUnavailable -> "本机记录暂不可读取，请先恢复本机读取。图片选择与原文已保留。"
                else -> message
            },
            selection = it.selection?.let { selected -> selected.copy(phase = AttachmentImportPhase.FAILED) }) }
    }
    private suspend fun current(repository: EntryRepository, panel: AttachmentUiState) {
        if (!canUseRecord()) throw LocalRecordUnavailable()
        if (!canUsePanel(panel) || !live(panel.sessionId)) throw RecordConflict()
        val entry = repository.entries().singleOrNull { it.id == panel.entryId } ?: throw RecordConflict()
        val owner = panel.historyOwner
        if (entry.currentRevision != (owner?.currentRevision ?: panel.revision)) throw RecordConflict()
        if (owner != null && repository.revisions(panel.entryId).singleOrNull {
                it.revision == panel.revision }?.entryId != panel.entryId) throw RecordConflict()
        if (owner != null && repository.entries().singleOrNull { it.id == panel.entryId }?.currentRevision != owner.currentRevision) throw RecordConflict()
        if (!canUseRecord()) throw LocalRecordUnavailable()
        if (!canUsePanel(panel) || !live(panel.sessionId)) throw RecordConflict()
    }
    private fun verifyRows(panel: AttachmentUiState, rows: List<LocalAttachment>) {
        panel.focusedIntent?.let{expected->if(rows.singleOrNull{it.intent==expected&&it.state==AttachmentState.READY}==null)throw AttachmentIntegrityFailure()}
        if (rows.any { it.intent.entryId != panel.entryId || it.intent.revision != panel.revision } ||
            rows.map { it.intent.id }.distinct().size != rows.size) throw AttachmentIntegrityFailure()
    }
    private class LocalRecordUnavailable : IllegalStateException()
    private fun access(repository: EntryRepository): AttachmentStore = checkNotNull(repository.attachments)

    companion object {
        const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        val IMAGE_TYPES = setOf("image/png", "image/jpeg")
        private val PNG_HEADER = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        internal fun matchesHeader(bytes: ByteArray, mediaType: String) = when (mediaType) {
            "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_HEADER)
            "image/jpeg" -> bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == (-1).toByte()
            else -> false
        }
        internal fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun bounded(input: InputStream, active: () -> Boolean): ByteArray {
            val buffer = ByteArray(8192)
            val output = ByteArray(MAX_SOURCE_BYTES)
            var total = 0
            return try {
                while (true) {
                    if (!active()) throw CancellationException("Selection closed")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0 || total.toLong() + count > MAX_SOURCE_BYTES) throw AttachmentIntegrityFailure()
                    buffer.copyInto(output, total, 0, count)
                    total += count
                }
                output.copyOf(total)
            } finally { buffer.fill(0); output.fill(0) }
        }
    }
}
