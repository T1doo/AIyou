package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class MixedCaptureUiState(val draft: CaptureDraft?=null,val text: String="",val busy: Boolean=false,
    val pendingEdits: Int=0,val selection: AttachmentSelection?=null,val pictures: Map<String,AttachmentPicture> = emptyMap(),
    val error: String?=null,val notice: String?=null,val confirmDiscard: Boolean=false,val uncertainSave: Boolean=false,
    val retainJpegMetadata: Boolean=true)

/** Production Now composer, using the existing serial records IO actor. No private Bundle/Saver. */
class MixedCaptureController internal constructor(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue: (suspend (EntryRepository)->Unit)->Boolean,
    private val decode: (ByteArray)->AttachmentPicture,private val clock: Clock) {
    private var binding: CaptureDraft?=null // IO actor only
    private var pendingSave: Triple<CaptureDraft,Long,String>?=null
    private var prepared: Pair<AttachmentIntent,ByteArray>?=null
    private val ids=AtomicLong()
    @Volatile private var disposed=false
    private fun ui()=current().mixedCapture
    private fun available()=!disposed && current().ready && !current().opening && current().error==null
    private fun update(change:(MixedCaptureUiState)->MixedCaptureUiState)=publish { local ->
        if(disposed) local else local.copy(mixedCapture=change(local.mixedCapture ?: MixedCaptureUiState()))
    }
    suspend fun initialize(store: EntryRepository) {
        if(store.captureRecords==null || disposed) return
        try {
            val read=store.captureRecords!!.current();val old=ui()?.draft
            if(old!=null && old.id!=read.id) throw CaptureConflict()
            binding=read
            update { it.copy(draft=read,text=if(it.draft==null) read.text else it.text,error=null) }
            thumbnails(store)
        }
        catch(e:CancellationException){throw e} catch(_:Exception){update { it.copy(error="图文草稿尚未读取，已保留本机数据，请重试。") }}
    }
    fun refresh() {
        if(!available() || ui()?.busy==true) return
        if(pendingSave!=null) { save();return }
        update { it.copy(busy=true,selection=null) }
        enqueue { store -> try { store.captureRecords!!.recover();initialize(store) } finally { update { it.copy(busy=false) } } }
    }
    fun edit(text: String) {
        val u=ui() ?: return
        if(!available() || u.busy || u.selection!=null || u.uncertainSave || text.length>100000 || u.draft==null) return
        update { it.copy(text=text,pendingEdits=it.pendingEdits+1,error=null,notice=null) }
        if(!enqueue { store ->
            try {
                val a=checkNotNull(binding);binding=store.captureRecords!!.edit(a,text)
                update { it.copy(draft=binding,error=null) }
            } catch(e:CancellationException){throw e} catch(_:Exception){reconcile(store);update { it.copy(error="文字草稿保存未确认，输入已保留。请重试保存。") }}
            finally { update { it.copy(pendingEdits=(it.pendingEdits-1).coerceAtLeast(0)) } }
        }) update { it.copy(pendingEdits=(it.pendingEdits-1).coerceAtLeast(0),error="本机队列暂不可用，输入已保留。") }
    }
    fun metadata(retain: Boolean) { if(available() && ui()?.busy==false && ui()?.selection==null) update { it.copy(retainJpegMetadata=retain) } }
    fun select(retryId: String?=null) {
        val u=ui() ?: return;val d=u.draft ?: return
        if(!available() || u.busy || u.selection!=null || u.uncertainSave || u.pendingEdits>0 || d.discarding) return
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
    fun cancelSelection() { if(ui()?.selection?.phase==AttachmentImportPhase.SAVING) return;update { it.copy(selection=null,error=null) };enqueue { clearPrepared() } }
    fun selected(request: Long,source: SelectedImageSource?) {
        val u=ui() ?: return;val s=u.selection ?: return;val id=u.draft?.id ?: return
        if(!available() || s.requestId!=request || s.phase!=AttachmentImportPhase.PICKING) return
        if(source==null) { cancelSelection();return }
        update { it.copy(selection=s.copy(phase=AttachmentImportPhase.PREPARING)) }
        enqueue { store ->
            var bytes: ByteArray?=null
            try {
                if(!owns(id,request)) return@enqueue
                val mime=checkNotNull(source.mediaType());require(mime in LocalAttachmentController.IMAGE_TYPES)
                bytes=source.open().use { LocalAttachmentController.bounded(it) { owns(id,request) } }
                require(LocalAttachmentController.matchesHeader(bytes,mime))
                if(mime=="image/jpeg") { val cleaned=JpegPayloadValidator.prepare(bytes,s.retainJpegMetadata);if(cleaned!==bytes){bytes.fill(0);bytes=cleaned} }
                decode(bytes).releaseUnpublished()
                if(!owns(id,request)) return@enqueue
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
        enqueue { store ->try { commitImage(store,id,s.requestId) } catch(e:CancellationException){throw e} catch(_:Exception){reconcile(store);update { it.copy(selection=it.selection?.copy(phase=AttachmentImportPhase.FAILED),error="图片仍未确认保存，输入已保留，请重试。") }} }
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
    fun requestDiscard() { if(available() && ui()?.busy==false && ui()?.selection==null && ui()?.uncertainSave==false) update { it.copy(confirmDiscard=true) } }
    fun cancelDiscard() { update { it.copy(confirmDiscard=false) } }
    fun confirmDiscard() { if(ui()?.confirmDiscard==true) mutate(resetText=true) { store,a ->store.captureRecords!!.discard(a.id) } }
    private fun mutate(resetText:Boolean=false,operation: suspend (EntryRepository,CaptureDraft)->CaptureDraft) {
        val u=ui() ?: return
        if(!available() || u.busy || u.selection!=null || u.uncertainSave || u.pendingEdits>0) return
        update { it.copy(busy=true) }
        enqueue { store ->
            try { val result=operation(store,checkNotNull(binding));binding=result;clearPrepared()
                update { it.copy(draft=result,text=if(resetText) result.text else it.text,confirmDiscard=false,error=null,notice=null) };thumbnails(store)
            } catch(e:CancellationException){throw e} catch(_:Exception){update { it.copy(error="草稿清理尚未完成，输入和清理身份已保留。请重试原操作。") }}
            finally { update { it.copy(busy=false) } }
        }
    }
    fun save() {
        val u=ui() ?: return
        if(!available() || u.busy || u.selection!=null || u.pendingEdits>0 || u.draft==null) return
        if(pendingSave==null && (u.draft.discarding || u.draft.images.any { it.state!=CaptureImageState.READY })) {
            update { it.copy(error="图片尚未完整保存，请重试读取、重新选择同一图片或明确移除后再保存。") };return
        }
        if(pendingSave==null && u.text.isBlank() && u.draft.images.isEmpty()) { update { it.copy(error="请写下文字或添加至少一张图片。") };return }
        update { it.copy(busy=true,error=null) }
        enqueue { store ->
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
                val epoch=store.localSearch?.epoch()
                val fresh=repo.current();binding=fresh;pendingSave=null;clearPrepared()
                publish { local ->if(disposed) local else local.copy(entries=entries,manualTags=labels,manualTagsAvailable=store.manualTags!=null,localSearchEpoch=epoch,
                    mixedCapture=MixedCaptureUiState(draft=fresh,text=fresh.text,notice=if(result.deleted) "已核对先前保存；记录已删除，没有恢复。" else "已保存在本机 · 文字和全部图片已持久认证。")).withFavoriteSource(favorites) }
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
    private fun owns(id: String,request: Long)=available() && ui()?.draft?.id==id && ui()?.selection?.requestId==request
    private fun clearPrepared() { prepared?.second?.fill(0);prepared=null }
    fun dispose() { disposed=true;publish { it.copy(mixedCapture=null) } }
    fun disposeOnIo() { disposed=true;clearPrepared();binding=null;pendingSave=null }
}
