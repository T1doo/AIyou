package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Nonblocking synthetic/controlled input only. No microphone adapter is installed. -1 means EOF. */
interface ControlledPcmInput : AutoCloseable { fun read(samples:ShortArray):Int }
enum class CaptureAudioPhase { IDLE, PREPARING, STARTING, RECORDING, FINALIZING, UNKNOWN, READY, FAILED, RETIRED }
data class CaptureAudioUi(val phase:CaptureAudioPhase=CaptureAudioPhase.IDLE,val samples:Int=0,val error:String?=null)

/** IO owns samples/device; the records actor alone owns reservation and durable settlement. */
@OptIn(DelicateCoroutinesApi::class)
class ControlledCaptureAudio internal constructor(
    private val current:()->MixedCaptureUiState?,private val ownerReady:()->Boolean,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,
    private val binding:()->CaptureDraft?,private val install:(CaptureDraft)->Unit,
    private val publish:(CaptureAudioUi)->Unit,private val nanoTime:()->Long=System::nanoTime,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any()
    private var gate:CaptureDraftGate?=null
    private var request:Request?=null
    @Volatile private var retired=false
    @Volatile private var state=CaptureAudioUi()
    val busy:Boolean get()=state.phase in setOf(CaptureAudioPhase.PREPARING,CaptureAudioPhase.STARTING,
        CaptureAudioPhase.RECORDING,CaptureAudioPhase.FINALIZING,CaptureAudioPhase.UNKNOWN)
    private class Request(val reservation:CaptureDraftGate.Reservation,val visible:String,val routeCurrent:()->Boolean) {
        @Volatile var stop=false
        @Volatile var cancelled=false
        @Volatile var deviceFailed=false
        @Volatile var interrupted=false
        @Volatile var reserved=false
        var openAdmitted=false // monitor orders this admission against stop/cancel/retire.
        var job:Job?=null
        var wave:ByteArray?=null
        var quarantinedInput:ControlledPcmInput?=null
    }
    internal fun attach(repository:CaptureRepository) {
        val actual=repository.mutationGate ?: return
        synchronized(monitor) { check(gate==null || gate===actual);gate=actual }
    }
    private fun update(r:Request,value:CaptureAudioUi) = synchronized(monitor) {
        if(request===r) { state=value;if(!retired)publish(value) }
    }
    fun start(input:()->ControlledPcmInput,routeCurrent:()->Boolean={true}):Boolean {
        val g=gate ?: return false
        return g.uiMutation {
            val ui=current() ?: return@uiMutation false
            val draft=ui.draft ?: return@uiMutation false
            if(retired || !ownerReady() || !routeCurrent() || busy || ui.busy || ui.selection!=null || ui.uncertainSave ||
                ui.discardPending || ui.confirmDiscard || ui.policyChange!=null || ui.error!=null || draft.discarding ||
                draft.audio!=null || draft.images.any { it.state!=CaptureImageState.READY }) return@uiMutation false
            val reservation=g.freeze(draft.id) ?: return@uiMutation false
            val r=Request(reservation,ui.text,routeCurrent)
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
                        update(r,CaptureAudioUi(CaptureAudioPhase.STARTING))
                        r.job=scope.launch(start=CoroutineStart.ATOMIC) { capture(r,input) }
                    }
                } catch(_:Exception) {
                    // A failed pre-start read cannot create an attachment or discard the typed input.
                    if(r.reserved) update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="录音开始状态尚未确认；当前草稿仍冻结。"))
                    else {
                        g.abandonBeforeReserve(reservation)
                        finishUi(r,CaptureAudioPhase.FAILED,"文字或附件草稿尚未确认；请先核对保存，再开始录音。")
                    }
                }
            }) {
                g.abandonBeforeReserve(reservation)
                finishUi(r,CaptureAudioPhase.FAILED,"本机队列暂不可用，输入已保留。")
                false
            } else true
        } ?: false
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
                try {
                    if(!r.cancelled && count>0) r.wave=LocalPcmAudio.encode(samples,count)
                } finally { samples.fill(0);scratch.fill(0) }
                update(r,CaptureAudioUi(if(r.deviceFailed) CaptureAudioPhase.UNKNOWN else CaptureAudioPhase.FINALIZING,count,
                    if(r.deviceFailed) "录音设备释放尚未确认，不能开始下一段。" else null))
                if(!r.deviceFailed) enqueue { store -> settle(store,r) }
            }
        }
    }
    fun stop() { synchronized(monitor) { request?.stop=true } }
    fun cancel() { synchronized(monitor) {
        if(state.phase in setOf(CaptureAudioPhase.PREPARING,CaptureAudioPhase.STARTING,CaptureAudioPhase.RECORDING))
            request?.let { it.cancelled=true;it.stop=true }
    } }
    fun retry() {
        val r=synchronized(monitor) { request?.takeIf { state.phase==CaptureAudioPhase.UNKNOWN && !it.deviceFailed } } ?: return
        enqueue { store -> settle(store,r) }
    }
    fun reconcile() {
        val r=synchronized(monitor) { request?.takeIf { state.phase==CaptureAudioPhase.UNKNOWN && !it.deviceFailed } } ?: return
        enqueue { store ->
            try {
                val repo=checkNotNull(store.captureRecords);val actual=repo.reconcileAudio(r.reservation)
                if(actual.sameSnapshot(r.reservation.base) && r.wave!=null && !r.cancelled)
                    update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="此段尚未写入草稿；可重试保存，或明确放弃未写入片段。"))
                else finish(store,r,actual)
            } catch(_:Exception) { update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="原音保存结果仍待核对，请保留这次操作。")) }
        }
    }
    /** Explicit discard is permitted only after a strict read proves no audio intent was committed. */
    fun discardUnwritten() {
        val r=synchronized(monitor) { request?.takeIf { state.phase==CaptureAudioPhase.UNKNOWN && !it.deviceFailed } } ?: return
        enqueue { store ->
            try {
                val actual=checkNotNull(store.captureRecords?.peek())
                require(actual.sameSnapshot(r.reservation.base))
                r.cancelled=true;r.wave?.fill(0);r.wave=null
                finish(store,r,actual)
            } catch(_:Exception) { update(r,CaptureAudioUi(CaptureAudioPhase.UNKNOWN,error="无法确认未写入；请先核对同一次保存。")) }
        }
    }
    private suspend fun settle(store:EntryRepository,r:Request) {
        if(synchronized(monitor) { request!==r })return
        if(r.deviceFailed)throw IllegalStateException("Audio resource closure unconfirmed")
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
        },if(r.interrupted) "录音可能中断；仅保留已确认原音。" else null)
    }
    private fun finishUi(r:Request,phase:CaptureAudioPhase,error:String?=null)=synchronized(monitor) {
        if(request===r) { state=CaptureAudioUi(phase,error=error);if(!retired)publish(state);request=null }
    }
    fun retire() {
        synchronized(monitor) { retired=true;request?.stop=true }
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
