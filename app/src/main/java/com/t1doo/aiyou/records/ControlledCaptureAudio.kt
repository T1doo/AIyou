package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Nonblocking synthetic/controlled input only. No microphone adapter is installed. -1 means EOF. */
interface ControlledPcmInput : AutoCloseable { fun read(samples:ShortArray):Int }
interface SelectedWaveSource { fun open():java.io.InputStream }
enum class CaptureAudioPhase { IDLE, PREPARING, STARTING, RECORDING, PICKING, IMPORTING, FINALIZING, UNKNOWN, READY, FAILED, RETIRED }
data class CaptureAudioUi(val phase:CaptureAudioPhase=CaptureAudioPhase.IDLE,val samples:Int=0,val error:String?=null,
    val filePickerKey:String?=null,val fileInput:Boolean=false,val resourceClosureUnknown:Boolean=false)

/** IO owns samples/device; the records actor alone owns reservation and durable settlement. */
@OptIn(DelicateCoroutinesApi::class)
class ControlledCaptureAudio internal constructor(
    private val current:()->MixedCaptureUiState?,private val ownerReady:()->Boolean,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,
    private val binding:()->CaptureDraft?,private val install:(CaptureDraft)->Unit,
    private val publish:(CaptureAudioUi)->Unit,private val nanoTime:()->Long=System::nanoTime,
    private val encodeWave:(ShortArray,Int)->ByteArray=LocalPcmAudio::encode,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any()
    private var gate:CaptureDraftGate?=null
    private var request:Request?=null
    @Volatile private var retired=false
    @Volatile private var state=CaptureAudioUi()
    @Volatile private var fileRoute=0L
    val busy:Boolean get()=state.phase in setOf(CaptureAudioPhase.PREPARING,CaptureAudioPhase.STARTING,
        CaptureAudioPhase.RECORDING,CaptureAudioPhase.PICKING,CaptureAudioPhase.IMPORTING,CaptureAudioPhase.FINALIZING,CaptureAudioPhase.UNKNOWN)
    private class Request(val reservation:CaptureDraftGate.Reservation,val visible:String,val routeCurrent:()->Boolean,
        val file:Boolean,val foreground:()->Boolean) {
        @Volatile var stop=false
        @Volatile var cancelled=false
        @Volatile var deviceFailed=false
        @Volatile var interrupted=false
        @Volatile var reserved=false
        @Volatile var abandonRequested=false // Sticky UI acceptance; actor alone performs durable abandonment.
        var openAdmitted=false // monitor orders this admission against stop/cancel/retire.
        var job:Job?=null
        var wave:ByteArray?=null
        var quarantinedInput:AutoCloseable?=null
        var closingRetry=false
        val pickerKey=UUID.randomUUID().toString()
        var pickerClaimed=false
        var fileResultReceived=false
        var chosen:SelectedWaveSource?=null
        val fileReady=CompletableDeferred<SelectedWaveSource?>()
    }
    internal fun attach(repository:CaptureRepository) {
        val actual=repository.mutationGate ?: return
        synchronized(monitor) { check(gate==null || gate===actual);gate=actual }
    }
    private fun update(r:Request,value:CaptureAudioUi) = synchronized(monitor) {
        if(request===r) { state=value.copy(fileInput=r.file,filePickerKey=if(value.phase==CaptureAudioPhase.PICKING)r.pickerKey else null,
            resourceClosureUnknown=r.deviceFailed);if(!retired)publish(state) }
    }
    fun start(input:()->ControlledPcmInput,routeCurrent:()->Boolean={true}):Boolean {
        return begin(input,routeCurrent,false,{true})
    }
    fun startFile(routeCurrent:()->Boolean,foreground:()->Boolean):Boolean {
        val route=synchronized(monitor) { if(!routeCurrent())return false else fileRoute }
        // The click must own its composition, but the accepted request belongs to the VM route.
        // Configuration disposal cannot invalidate the still-live VM's UUID picker request.
        return begin(null,{fileRoute==route},true,foreground)
    }
    fun fileRouteChanged():Unit=synchronized(monitor) {
        fileRoute++
        if(request?.file==true)cancel()
    }
    private fun begin(input:(()->ControlledPcmInput)?,routeCurrent:()->Boolean,file:Boolean,foreground:()->Boolean):Boolean {
        val g=gate ?: return false
        return g.uiMutation {
            val ui=current() ?: return@uiMutation false
            val draft=ui.draft ?: return@uiMutation false
            if(retired || !ownerReady() || !routeCurrent() || (file && !foreground()) || busy || ui.busy || ui.selection!=null || ui.uncertainSave ||
                ui.discardPending || ui.confirmDiscard || ui.policyChange!=null || ui.error!=null || draft.discarding ||
                draft.audio!=null || draft.images.any { it.state!=CaptureImageState.READY }) return@uiMutation false
            if(file && (draft.images.size>=20 || draft.images.sumOf { it.intent.byteSize }+46>RecordMediaPackages.MAX_IMAGE_TOTAL))return@uiMutation false
            val reservation=g.freeze(draft.id) ?: return@uiMutation false
            val r=Request(reservation,ui.text,routeCurrent,file,foreground)
            synchronized(monitor) { request=r }
            update(r,CaptureAudioUi(CaptureAudioPhase.PREPARING))
            if(!enqueue { store ->
                try {
                    if(retired || r.cancelled || !ownerReady() || !r.routeCurrent()) {
                        g.abandonBeforeReserve(reservation);finishUi(r,CaptureAudioPhase.IDLE);return@enqueue
                    }
                    val u=checkNotNull(current());val actual=checkNotNull(store.captureRecords?.peek())
                    require(u.pendingEdits==0 && u.error==null && !u.busy && u.selection==null && !u.uncertainSave &&
                        !u.discardPending && !u.confirmDiscard && u.policyChange==null && r.visible==u.text)
                    require(checkNotNull(binding()).sameSnapshot(actual) && checkNotNull(u.draft).sameSnapshot(actual))
                    g.reserve(reservation,actual,u.text,u.pendingEdits)
                    r.reserved=true
                    if(retired || r.cancelled || !ownerReady() || !r.routeCurrent()) {
                        g.finish(reservation,actual);finishUi(r,CaptureAudioPhase.IDLE)
                    } else {
                        update(r,CaptureAudioUi(if(file) CaptureAudioPhase.PICKING else CaptureAudioPhase.STARTING))
                        synchronized(monitor) { r.job=scope.launch(start=CoroutineStart.ATOMIC) {
                            if(file) captureFile(r) else capture(r,checkNotNull(input))
                        } }
                    }
                } catch(_:Exception) {
                    // A failed pre-start read cannot create an attachment or discard the typed input.
                    if(r.reserved) update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error=if(file) "原音导入准备尚未确认；当前草稿仍冻结。" else "录音开始状态尚未确认；当前草稿仍冻结。"))
                    else {
                        g.abandonBeforeReserve(reservation)
                        finishUi(r,CaptureAudioPhase.FAILED,if(file) "文字或附件草稿尚未确认；请先核对保存，再导入原音。" else "文字或附件草稿尚未确认；请先核对保存，再开始录音。")
                    }
                }
            }) {
                g.abandonBeforeReserve(reservation)
                finishUi(r,CaptureAudioPhase.FAILED,"本机队列暂不可用，输入已保留。")
                false
            } else true
        } ?: false
    }
    fun claimFile(key:String):Boolean=synchronized(monitor) {
        val r=request ?: return false
        if(retired || r.cancelled || !r.routeCurrent() || !r.foreground() || !r.file || r.pickerKey!=key || r.pickerClaimed || state.phase!=CaptureAudioPhase.PICKING)return false
        r.pickerClaimed=true;true
    }
    fun selectedFile(key:String,source:SelectedWaveSource?):Unit=synchronized(monitor) {
        val r=request ?: return
        if(retired || r.cancelled || !r.file || r.pickerKey!=key || !r.pickerClaimed || r.fileResultReceived || state.phase!=CaptureAudioPhase.PICKING)return
        r.fileResultReceived=true
        if(source==null) { r.cancelled=true;r.fileReady.complete(null) } else { r.chosen=source;resumeFile() }
    }
    fun resumeFile():Unit=synchronized(monitor) {
        val r=request ?: return
        if(!retired && r.file && !r.cancelled && r.routeCurrent() && r.foreground())r.chosen?.let {
            r.chosen=null;r.fileReady.complete(it)
        }
    }
    private suspend fun captureFile(r:Request) {
        var input:java.io.InputStream?=null
        val buffer=ByteArray(LocalPcmAudio.MAX_WAV_BYTES+1)
        var wave:ByteArray?=null
        try {
            val source=r.fileReady.await()
            val admitted=synchronized(monitor) {
                if(source!=null && !retired && request===r && !r.cancelled && r.routeCurrent() && r.foreground()) {
                    r.openAdmitted=true;true
                } else false
            }
            if(admitted) {
                input=checkNotNull(source).open()
                update(r,CaptureAudioUi(CaptureAudioPhase.IMPORTING))
                var size=0;var eof=false
                while(size<buffer.size && !r.cancelled && !retired && r.routeCurrent()) {
                    val n=input.read(buffer,size,buffer.size-size)
                    require(n in -1..(buffer.size-size))
                    if(n<0) { eof=true;break }
                    if(n==0)delay(20)else size+=n
                }
                if(!r.cancelled && !retired && r.routeCurrent()) {
                    require(eof && size<=LocalPcmAudio.MAX_WAV_BYTES)
                    require(r.reservation.base.images.sumOf { it.intent.byteSize }+size<=RecordMediaPackages.MAX_IMAGE_TOTAL)
                    val candidate=buffer.copyOf(size)
                    try { LocalPcmAudio.inspect(candidate);wave=candidate } catch(e:Exception) { candidate.fill(0);throw e }
                }
            }
        } catch(_:Exception) { r.interrupted=true }
        finally { withContext(NonCancellable) {
            try { input?.close() } catch(_:Exception) { r.deviceFailed=true;r.quarantinedInput=input }
            buffer.fill(0)
            synchronized(monitor) {
                r.chosen=null
                if(r.cancelled)wave?.fill(0) else r.wave=wave
                update(r,CaptureAudioUi(if(r.deviceFailed)CaptureAudioPhase.UNKNOWN else CaptureAudioPhase.FINALIZING,
                    error=if(r.deviceFailed) "文件关闭尚未确认，当前草稿仍冻结。" else null))
            }
            if(!r.deviceFailed)enqueue { store -> settle(store,r) }
        } }
    }
    private suspend fun capture(r:Request,open:()->ControlledPcmInput) {
        val samples=ShortArray(LocalPcmAudio.MAX_SAMPLES)
        val scratch=ShortArray(2048)
        var count=0
        var input:ControlledPcmInput?=null
        try {
            val ready=!retired && !r.cancelled && !r.stop && ownerReady() && r.routeCurrent()
            val admitted=synchronized(monitor) {
                if(ready && !retired && request===r && !r.cancelled && !r.stop) { r.openAdmitted=true;true } else false
            }
            if(admitted) {
                val started=nanoTime()
                input=open()
                update(r,CaptureAudioUi(CaptureAudioPhase.RECORDING))
                while(!retired && !r.stop && !r.cancelled && r.routeCurrent() && count<samples.size &&
                    nanoTime()-started<LocalPcmAudio.MAX_SECONDS*1_000_000_000L) {
                    val n=input.read(scratch);require(n in -1..scratch.size)
                    if(n<0)break
                    if(n==0)delay(20) else {
                        val copied=minOf(n,samples.size-count);scratch.copyInto(samples,count,0,copied);count+=copied
                        scratch.fill(0)
                        update(r,CaptureAudioUi(CaptureAudioPhase.RECORDING,count))
                    }
                }
            }
        } catch(_:Exception) { r.interrupted=true }
        finally {
            withContext(NonCancellable) {
                try { input?.close() } catch(_:Exception) { r.deviceFailed=true;r.quarantinedInput=input }
                val encoded=try {
                    if(!r.cancelled && count>0) encodeWave(samples,count) else null
                } finally { samples.fill(0);scratch.fill(0) }
                synchronized(monitor) {
                    // Encoding may take time: cancel wins until this single admission boundary.
                    if(r.cancelled) encoded?.fill(0) else r.wave=encoded
                    update(r,CaptureAudioUi(if(r.deviceFailed) CaptureAudioPhase.UNKNOWN else CaptureAudioPhase.FINALIZING,count,
                        if(r.deviceFailed) "录音设备释放尚未确认，不能开始下一段。" else null))
                }
                if(!r.deviceFailed) enqueue { store -> settle(store,r) }
            }
        }
    }
    fun stop() { synchronized(monitor) { request?.stop=true } }
    fun cancel() { synchronized(monitor) {
        if(state.phase in setOf(CaptureAudioPhase.PREPARING,CaptureAudioPhase.STARTING,CaptureAudioPhase.RECORDING,CaptureAudioPhase.PICKING,CaptureAudioPhase.IMPORTING))
            request?.let { it.cancelled=true;it.stop=true;it.chosen=null;it.fileReady.complete(null) }
    } }
    fun retry() {
        val r=claimSettlement() ?: return
        if(!enqueue { store -> settle(store,r) })update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="本机队列未接纳核对；原音仍保留。"))
    }
    private fun claimSettlement():Request?=synchronized(monitor) {
        request?.takeIf { state.phase==CaptureAudioPhase.UNKNOWN && !it.deviceFailed }?.also {
            update(it,CaptureAudioUi(CaptureAudioPhase.FINALIZING))
        }
    }
    /** One explicit retry of the same quarantined resource; never opens a replacement or saves automatically. */
    fun retryResourceClose():Unit=synchronized(monitor) {
        val r=request?.takeIf { state.phase==CaptureAudioPhase.UNKNOWN && it.deviceFailed && !it.closingRetry } ?: return
        val handle=r.quarantinedInput ?: return
        val previous=r.job;r.closingRetry=true
        r.job=scope.launch(start=CoroutineStart.ATOMIC) {
            previous?.join()
            withContext(NonCancellable) {
                try {
                    handle.close()
                    synchronized(monitor) { r.quarantinedInput=null;r.deviceFailed=false }
                    update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="资源已关闭；原音仍待处理，请核对同次保存或明确放弃。"))
                } catch(_:Exception) { update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="资源关闭仍未确认；不能开始另一段或解冻草稿。")) }
                finally { synchronized(monitor) { r.closingRetry=false } }
            }
        }
    }
    fun reconcile() {
        val r=claimSettlement() ?: return
        if(!enqueue { store ->
            // Read the actor-owned disposition here, after any previously accepted discard command.
            if(r.abandonRequested) { abandon(store,r);return@enqueue }
            try {
                val repo=checkNotNull(store.captureRecords)
                val bound=try { checkNotNull(gate).intent(r.reservation) } catch(_:IllegalStateException) { null }
                val actual=if(bound==null)checkNotNull(repo.peek()).also { require(it.sameSnapshot(r.reservation.base)) }
                    else repo.reconcileAudio(r.reservation)
                if(actual.audio?.state==CaptureAudioState.FAILED && r.wave!=null && !r.abandonRequested)
                    update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="草稿标记失败，原音仍保留；请明确处理这次未完成保存。"))
                else if(r.wave!=null && !r.cancelled && (actual.sameSnapshot(r.reservation.base) || actual.audio?.state==CaptureAudioState.WRITING))
                    update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="此段尚未完整写入草稿；可重试保存，或明确放弃未完成片段。"))
                else finish(store,r,actual)
            } catch(_:Exception) { update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="原音保存结果仍待核对，请保留这次操作。")) }
        })update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="本机队列未接纳核对；原音仍保留。"))
    }
    /** Explicit discard settles only the base or this reservation's incomplete intent; never READY. */
    fun discardUnwritten() {
        val r=synchronized(monitor) { request?.takeIf { state.phase==CaptureAudioPhase.UNKNOWN && !it.deviceFailed }?.also {
            it.abandonRequested=true
            update(it,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="已请求放弃本次未完成原音；结果仍待核对。"))
        } } ?: return
        enqueue { store ->
            abandon(store,r)
        }
    }
    private suspend fun abandon(store:EntryRepository,r:Request) {
        try {
            val repo=checkNotNull(store.captureRecords)
            val bound=try { checkNotNull(gate).intent(r.reservation) } catch(_:IllegalStateException) { null }
            val actual=if(bound==null)checkNotNull(repo.peek()).also { require(it.sameSnapshot(r.reservation.base)) }
                else repo.abandonAudio(r.reservation)
            finish(store,r,actual)
        }
        catch(_:Exception) { update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="放弃结果尚未确认；原音仍保留，请重试或核对同一次放弃。")) }
    }
    private suspend fun settle(store:EntryRepository,r:Request) {
        if(synchronized(monitor) { request!==r })return
        if(r.deviceFailed)throw IllegalStateException("Audio resource closure unconfirmed")
        if(r.abandonRequested) { abandon(store,r);return }
        try {
            val repo=checkNotNull(store.captureRecords);val g=checkNotNull(gate)
            val wave=r.wave
            val actual=if(wave==null) checkNotNull(repo.peek()) else {
                // Keep the first immutable intent on retries. No version rebase or replacement ID.
                val hash=MessageDigest.getInstance("SHA-256").digest(wave).joinToString("") { "%02x".format(it) }
                val bound=try { g.intent(r.reservation) } catch(_:IllegalStateException) {
                    AttachmentIntent(UUID.randomUUID().toString(),r.reservation.draftId,1,"audio/wav",hash,wave.size.toLong()).also {
                        g.bindIntent(r.reservation,it)
                    }
                }
                require(bound.sha256==hash && bound.byteSize==wave.size.toLong())
                repo.addAudio(r.reservation,wave)
            }
            finish(store,r,actual)
        } catch(_:Exception) { update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="原音保存结果尚未确认；请核对同一次保存。")) }
    }
    private suspend fun finish(store:EntryRepository,r:Request,actual:CaptureDraft) {
        require(checkNotNull(store.captureRecords?.peek()).sameSnapshot(actual))
        checkNotNull(gate).finish(r.reservation,actual) { install(actual) }
        r.wave?.fill(0);r.wave=null
        finishUi(r,when(actual.audio?.state) {
            CaptureAudioState.READY -> CaptureAudioPhase.READY
            CaptureAudioState.FAILED -> CaptureAudioPhase.FAILED
            else -> CaptureAudioPhase.IDLE
        },if(r.interrupted) { if(r.file) "文件未能完整读取或格式不支持；未加入原音，文字和图片保留。" else "录音可能中断；仅保留已确认原音。" } else null)
    }
    private fun finishUi(r:Request,phase:CaptureAudioPhase,error:String?=null)=synchronized(monitor) {
        if(request===r) { state=CaptureAudioUi(phase,error=error);if(!retired)publish(state);request=null }
    }
    fun retire() {
        synchronized(monitor) { retired=true;request?.let { it.stop=true;if(it.file) { it.cancelled=true;it.chosen=null;it.fileReady.complete(null) } } }
        gate?.retire()
    }
    /** Called directly by the owner actor finally; never enqueue and await that same actor. */
    suspend fun closeAndFinalize(store:EntryRepository) {
        retire()
        val r=synchronized(monitor) { request }
        try {
            r?.job?.join()
            if(r!=null) {
                if(r.deviceFailed)throw IllegalStateException("Audio resource closure unconfirmed")
                if(!r.reserved) { gate?.abandonBeforeReserve(r.reservation);finishUi(r,CaptureAudioPhase.RETIRED) }
                else { settle(store,r);check(!busy) { "Audio settlement unconfirmed" } }
            }
        } finally { scope.cancel() }
    }
}
