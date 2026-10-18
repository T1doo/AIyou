package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class CapturePolicyChange(val requestId: Long, val expected: CaptureDraft, val target: ProcessingPolicy,
    val priorError: String? = null) {
    override fun toString() = "CapturePolicyChange(redacted)"
}

data class MixedCaptureUiState(val draft: CaptureDraft?=null,val text: String="",val busy: Boolean=false,
    val pendingEdits: Int=0,val selection: AttachmentSelection?=null,val pictures: Map<String,AttachmentPicture> = emptyMap(),
    val error: String?=null,val notice: String?=null,val confirmDiscard: Boolean=false,val uncertainSave: Boolean=false,
    val retainJpegMetadata: Boolean=true,val discardPending: Boolean=false,
    val supportsProcessingChoice: Boolean=false,val policyChange: CapturePolicyChange?=null) {
    override fun toString()="MixedCaptureUiState(busy=$busy, redacted)"
}

internal data class CaptureDay(val date: LocalDate, val zone: ZoneId)

/** Production Now composer, using the existing serial records IO actor. No private Bundle/Saver. */
class MixedCaptureController internal constructor(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue: (suspend (EntryRepository)->Unit)->Boolean,
    private val decode: (ByteArray)->AttachmentPicture,private val clock: Clock) {
    private var binding: CaptureDraft?=null // IO actor only
    private var pendingSave: Triple<CaptureDraft,Long,String>?=null
    private var pendingDiscard: CaptureDraft?=null // Exact original clear identity; IO actor only.
    private var prepared: Pair<AttachmentIntent,ByteArray>?=null
    private val ids=AtomicLong()
    @Volatile private var disposed=false
    private fun ui()=current().mixedCapture
    private fun available()=!disposed && current().ready && !current().opening && current().error==null
    internal fun today(zone: ZoneId = ZoneId.systemDefault()) = CaptureDay(LocalDate.now(clock.withZone(zone)), zone)
    /** Same current-source order as Memories; capture date, not a manually assigned event date. */
    internal fun recentEntries(day: CaptureDay = today()): List<StoredEntry> {
        if (!available()) return emptyList()
        val start = day.date.atStartOfDay(day.zone).toInstant().toEpochMilli()
        val end = day.date.plusDays(1).atStartOfDay(day.zone).toInstant().toEpochMilli()
        return current().entries.asSequence().filter { it.capturedAtMillis >= start && it.capturedAtMillis < end }.take(5).toList()
    }
    private fun update(change:(MixedCaptureUiState)->MixedCaptureUiState)=publish { local ->
        if(disposed) local else local.copy(mixedCapture=change(local.mixedCapture ?: MixedCaptureUiState()))
    }
    suspend fun initialize(store: EntryRepository) {
        // Routine record reloads must not turn a lost range ACK into an optimistic UI acknowledgement.
        if(store.captureRecords==null || disposed || ui()?.policyChange!=null) return
        try {
            val read=store.captureRecords!!.current();val old=ui()?.draft
            if(old!=null && old.id!=read.id) throw CaptureConflict()
            binding=read
            update {
                val text=if(it.draft==null) read.text else it.text
                it.copy(draft=read,text=text,error=if(text!=read.text && it.pendingEdits==0)
                    it.error ?: "文字草稿尚未确认保存，输入已保留，请重试保存。" else null,
                    supportsProcessingChoice=store.captureRecords!!.supportsProcessingChoice)
            }
            thumbnails(store)
        }
        catch(e:CancellationException){throw e} catch(_:Exception){update { it.copy(error="图文草稿尚未读取，已保留本机数据，请重试。") }}
    }
    fun refresh() {
        if(!available() || ui()?.busy==true) return
        ui()?.policyChange?.let { readCurrentPolicy(it.requestId);return }
        if(ui()?.discardPending==true) { confirmDiscard();return }
        if(pendingSave!=null) { save();return }
        update { it.copy(busy=true,selection=null) }
        submit { store -> try { store.captureRecords!!.recover();initialize(store) } finally { update { it.copy(busy=false) } } }
    }
    fun edit(text: String) {
        val u=ui() ?: return
        if(!available() || u.busy || u.selection!=null || u.uncertainSave || u.discardPending || u.policyChange!=null || text.length>100000 || u.draft==null) return
        update { it.copy(text=text,pendingEdits=it.pendingEdits+1,error=null,notice=null) }
        if(!enqueue { store ->
            try {
                val a=checkNotNull(binding);binding=store.captureRecords!!.edit(a,text)
                update { it.copy(draft=binding,error=null) }
            } catch(e:CancellationException){throw e} catch(_:Exception){reconcile(store);update { it.copy(error="文字草稿保存未确认，输入已保留。请重试保存。") }}
            finally { update { it.copy(pendingEdits=(it.pendingEdits-1).coerceAtLeast(0)) } }
        }) update { it.copy(pendingEdits=(it.pendingEdits-1).coerceAtLeast(0),error="本机队列暂不可用，输入已保留。") }
    }
    fun changePolicy(draftId: String, version: Long, target: ProcessingPolicy) {
        val u=ui() ?: return;val draft=u.draft ?: return
        if(!available() || !u.supportsProcessingChoice || u.busy || u.pendingEdits>0 || u.selection!=null ||
            u.uncertainSave || u.discardPending || u.confirmDiscard || u.policyChange!=null || draft.discarding ||
            draft.id!=draftId || draft.version!=version || target==draft.policy ||
            target !in setOf(ProcessingPolicy.LOCAL_ONLY,ProcessingPolicy.AI_NONE)) return
        val change=CapturePolicyChange(ids.incrementAndGet(),draft,target,u.error)
        update { it.copy(policyChange=change,busy=true,notice=null) }
        submit { store -> commitPolicy(store,change) }
    }
    fun retryPolicy(requestId: Long) {
        val u=ui() ?: return;val change=u.policyChange ?: return
        if(!available() || u.busy || change.requestId!=requestId || u.draft?.id!=change.expected.id) return
        update { it.copy(busy=true,error=null) }
        submit { store -> commitPolicy(store,change) }
    }
    private fun ownsPolicy(change: CapturePolicyChange) = available() && ui()?.let {
        it.policyChange?.requestId==change.requestId && it.draft?.id==change.expected.id &&
            it.draft.version==change.expected.version && !it.uncertainSave && !it.discardPending
    }==true
    private suspend fun commitPolicy(store: EntryRepository,change: CapturePolicyChange) {
        try {
            if(!ownsPolicy(change)) return
            val result=checkNotNull(store.captureRecords).changePolicy(change.expected,change.target)
            require(result.id==change.expected.id && result.version==Math.addExact(change.expected.version,1) &&
                result.policy==change.target && result.text==change.expected.text && result.images==change.expected.images && !result.discarding)
            if(!ownsPolicy(change)) return
            binding=result
            update { it.copy(draft=result,policyChange=null,busy=false,error=change.priorError,
                notice="这份草稿的范围已保存在本机；已有记录保持不变。") }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){if(ownsPolicy(change)) update { it.copy(error="范围保存尚未确认。请重试确认同一次选择，或重新读取当前范围；核对前不能保存记录。") }}
        finally { update { if(it.policyChange?.requestId==change.requestId) it.copy(busy=false) else it } }
    }
    fun readCurrentPolicy(requestId: Long) {
        val u=ui() ?: return;val change=u.policyChange ?: return
        if(!available() || u.busy || change.requestId!=requestId) return
        update { it.copy(busy=true,error=null) }
        submit { store ->
            try {
                if(!ownsPolicy(change)) return@submit
                val actual=checkNotNull(store.captureRecords).current()
                require(actual.id==change.expected.id && !actual.discarding)
                if(!ownsPolicy(change)) return@submit
                binding=actual
                update { it.copy(draft=actual,policyChange=null,busy=false,error=change.priorError,
                    notice="已重新读取这份草稿的当前范围；此前未确认的选择没有重发。请核对后继续。") }
                thumbnails(store)
            } catch(e:CancellationException){throw e}
            catch(_:Exception){if(ownsPolicy(change)) update { it.copy(error="当前范围仍无法完整读取；输入与这次选择已保留，请稍后重试。") }}
            finally { update { if(it.policyChange?.requestId==requestId) it.copy(busy=false) else it } }
        }
    }
    fun metadata(retain: Boolean) { if(available() && ui()?.busy==false && ui()?.selection==null && ui()?.discardPending==false && ui()?.policyChange==null) update { it.copy(retainJpegMetadata=retain) } }
    fun select(retryId: String?=null) {
        val u=ui() ?: return;val d=u.draft ?: return
        if(!available() || u.busy || u.selection!=null || u.uncertainSave || u.discardPending || u.policyChange!=null || u.pendingEdits>0 || d.discarding) return
        val item=retryId?.let { id -> d.images.singleOrNull { it.intent.id==id && it.state!=CaptureImageState.READY } ?: return }
        if(item==null && d.images.size>=20) { update { it.copy(error="一次最多 20 张原图。") };return }
        clearPrepared()
        update { it.copy(selection=AttachmentSelection(ids.incrementAndGet(),item?.intent?.id ?: UUID.randomUUID().toString(),item?.intent,retainJpegMetadata=u.retainJpegMetadata),error=null,notice=null) }
    }
    fun claimPicker(request: Long): Boolean {
        val s=ui()?.selection ?: return false
        if(!available() || s.requestId!=request || s.launched || s.phase!=AttachmentImportPhase.PICKING) return false
        update { it.copy(selection=s.copy(launched=true)) };return true
    }
    fun pickerFailed(request: Long) { if(ui()?.selection?.requestId==request) update { it.copy(selection=null,error="未能选择图片，草稿已保留。") } }
    fun cancelSelection() { val selection=ui()?.selection ?: return;if(selection.phase==AttachmentImportPhase.SAVING) return;update { it.copy(selection=null,error=null) };enqueue { clearPrepared() } }
    fun selected(request: Long,source: SelectedImageSource?) {
        val u=ui() ?: return;val s=u.selection ?: return;val id=u.draft?.id ?: return
        if(!available() || s.requestId!=request || s.phase!=AttachmentImportPhase.PICKING) return
        if(source==null) { cancelSelection();return }
        update { it.copy(selection=s.copy(phase=AttachmentImportPhase.PREPARING)) }
        submit { store ->
            var bytes: ByteArray?=null
            try {
                if(!owns(id,request)) return@submit
                val mime=checkNotNull(source.mediaType());require(mime in LocalAttachmentController.IMAGE_TYPES)
                bytes=source.open().use { LocalAttachmentController.bounded(it) { owns(id,request) } }
                require(LocalAttachmentController.matchesHeader(bytes,mime))
                if(mime=="image/jpeg") { val cleaned=JpegPayloadValidator.prepare(bytes,s.retainJpegMetadata);if(cleaned!==bytes){bytes.fill(0);bytes=cleaned} }
                decode(bytes).releaseUnpublished()
                if(!owns(id,request)) return@submit
                val intent=AttachmentIntent(s.attachmentId,id,1,mime,LocalAttachmentController.hash(bytes),bytes.size.toLong())
                require(s.expected==null || s.expected==intent)
                clearPrepared();prepared=intent to bytes;bytes=null;commitImage(store,id,request)
            } catch(e:CancellationException){if(owns(id,request)) throw e}
            catch(_:Exception){reconcile(store);if(owns(id,request)) update { it.copy(selection=it.selection?.copy(phase=AttachmentImportPhase.FAILED),error="图片保存未确认，文字和已选图片保留。可重试确认或重新选择同一图片。") }}
            finally { bytes?.fill(0) }
        }
    }
    fun retryImage() {
        val u=ui() ?: return;val s=u.selection ?: return;val id=u.draft?.id ?: return
        if(!available() || s.phase!=AttachmentImportPhase.FAILED) return
        if(prepared==null) { cancelSelection();return }
        submit { store ->try { commitImage(store,id,s.requestId) } catch(e:CancellationException){throw e} catch(_:Exception){reconcile(store);update { it.copy(selection=it.selection?.copy(phase=AttachmentImportPhase.FAILED),error="图片仍未确认保存，输入已保留，请重试。") }} }
    }
    private suspend fun commitImage(store: EntryRepository,id: String,request: Long) {
        if(!owns(id,request)) return
        val p=checkNotNull(prepared);update { it.copy(selection=it.selection?.copy(phase=AttachmentImportPhase.SAVING)) }
        val result=store.captureRecords!!.add(checkNotNull(binding),p.first,p.second)
        binding=result
        if(owns(id,request)) update { it.copy(draft=result,selection=null,error=null,notice="图片已加密暂存在草稿；尚未保存为记录。") }
        clearPrepared();thumbnails(store)
    }
    fun remove(id: String) { mutate { store,a ->store.captureRecords!!.remove(a.id,id) } }
    fun requestDiscard() { if(available() && ui()?.busy==false && ui()?.selection==null && ui()?.uncertainSave==false && ui()?.discardPending==false && ui()?.policyChange==null) update { it.copy(confirmDiscard=true) } }
    fun cancelDiscard() { update { it.copy(confirmDiscard=false) } }
    fun confirmDiscard() { if(ui()?.confirmDiscard==true || ui()?.discardPending==true) mutate(resetText=true) { store,a ->store.captureRecords!!.discard(a.id) } }
    private fun mutate(resetText:Boolean=false,operation: suspend (EntryRepository,CaptureDraft)->CaptureDraft) {
        val u=ui() ?: return
        if(!available() || u.busy || u.selection!=null || u.uncertainSave || u.policyChange!=null || u.pendingEdits>0 || (u.discardPending && !resetText)) return
        update { it.copy(busy=true,discardPending=it.discardPending || resetText) }
        val accepted=submit(if(resetText && u.discardPending)
            "本机队列暂不可用，清空结果仍待核对。请稍后核对同一次清空，再继续记录。" else null) { store ->
            try {
                val expected=if(resetText) pendingDiscard ?: checkNotNull(binding).also { pendingDiscard=it } else checkNotNull(binding)
                val result=operation(store,expected);binding=result;clearPrepared()
                update { it.copy(draft=result,text=if(resetText) result.text else it.text,confirmDiscard=false,
                    discardPending=if(resetText) false else it.discardPending,error=null,notice=null) }
                if(resetText) pendingDiscard=null
                thumbnails(store)
            } catch(e:CancellationException){throw e} catch(_:Exception){update { it.copy(error=if(resetText)
                "清空结果尚未确认。清空前内容仅供核对，请核对同一次清空后继续记录。"
                else "草稿清理尚未完成，输入和清理身份已保留。请重试原操作。") }}
            finally { update { it.copy(busy=false) } }
        }
        if(!accepted) update { it.copy(discardPending=u.discardPending) }
    }
    fun save() {
        val u=ui() ?: return
        if(!available() || u.busy || u.selection!=null || u.pendingEdits>0 || u.draft==null || u.discardPending || u.policyChange!=null) return
        if(pendingSave==null && (u.draft.discarding || u.draft.images.any { it.state!=CaptureImageState.READY })) {
            update { it.copy(error="图片尚未完整保存，请重试读取、重新选择同一图片或明确移除后再保存。") };return
        }
        if(pendingSave==null) try { OriginalExports.hash(u.text) } catch(_:Exception) {
            update { it.copy(error="文字输入尚未完成，草稿已保留。请完成输入后再保存。") };return
        }
        if(pendingSave==null && u.text.isBlank() && u.draft.images.isEmpty()) { update { it.copy(error="请写下文字或添加至少一张图片。") };return }
        update { it.copy(busy=true,error=null) }
        submit { store ->
            try {
                val repo=checkNotNull(store.captureRecords)
                if(pendingSave==null) {
                    var d=checkNotNull(binding)
                    if(d.text!=u.text) { d=repo.edit(d,u.text);binding=d }
                    pendingSave=Triple(d,clock.millis(),ZoneId.systemDefault().id)
                }
                val p=checkNotNull(pendingSave);val result=repo.save(p.first,p.second,p.third)
                val entries=store.entries();val labels=store.manualTags?.snapshots().orEmpty()
                val favorites=readFavoriteSource(store)
                val eventDates=readEventDateSource(store)
                val epoch=store.localSearch?.epoch()
                val fresh=repo.current();binding=fresh;pendingSave=null;clearPrepared()
                publish { local ->if(disposed) local else local.copy(entries=entries,manualTags=labels,manualTagsAvailable=store.manualTags!=null,localSearchEpoch=epoch,
                    mixedCapture=MixedCaptureUiState(draft=fresh,text=fresh.text,supportsProcessingChoice=repo.supportsProcessingChoice,
                        notice=if(result.deleted) "已核对先前保存；记录已删除，没有恢复。" else "已保存在本机 · 文字和全部图片已持久认证。")).withFavoriteSource(favorites).withEventDateSource(eventDates) }
                thumbnails(store)
            } catch(e:CancellationException){throw e} catch(_:Exception){update { it.copy(error="保存结果尚未确认，文字及全部图片保留。请核对同一次保存，不会另建记录。",uncertainSave=pendingSave!=null) }}
            finally { update { it.copy(busy=false) } }
        }
    }
    private suspend fun thumbnails(store: EntryRepository) {
        val d=binding ?: return;val pictures=mutableMapOf<String,AttachmentPicture>()
        for(item in d.images.filter { it.state==CaptureImageState.READY }) {
            try { val bytes=store.captureRecords!!.image(d,item.intent.id)
                try { pictures[item.intent.id]=decode(bytes) } finally { bytes.fill(0) }
            } catch(e:CancellationException){throw e} catch(_:Exception){update { it.copy(error="有图片无法读取，未静默跳过。请重试读取或移除该图片。") }}
        }
        if(disposed) pictures.values.forEach { it.releaseUnpublished() }
        else update { it.copy(pictures=pictures) }
    }
    private suspend fun reconcile(store: EntryRepository) {
        try { val a=store.captureRecords!!.current();if(a.id==binding?.id) { binding=a;update { it.copy(draft=a) } } }
        catch(e:CancellationException){throw e} catch(_:Exception) { /* Preserve acknowledged binding and current input. */ }
    }
    private fun submit(rejectionMessage:String?=null,action:suspend(EntryRepository)->Unit):Boolean {
        if(enqueue(action)) return true
        update { it.copy(busy=false,error=rejectionMessage ?: "本机操作暂不可用，文字和已选图片已保留。请重试。") };return false
    }
    private fun owns(id: String,request: Long)=available() && ui()?.draft?.id==id && ui()?.selection?.requestId==request
    private fun clearPrepared() { prepared?.second?.fill(0);prepared=null }
    fun dispose() { disposed=true;publish { it.copy(mixedCapture=null) } }
    fun disposeOnIo() { disposed=true;clearPrepared();binding=null;pendingSave=null;pendingDiscard=null }
}
