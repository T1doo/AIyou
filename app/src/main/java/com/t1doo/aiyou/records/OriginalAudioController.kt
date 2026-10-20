package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OriginalAudioPhase { IDLE, LOADING, FOCUS, PLAYING, CLOSING, CLOSING_UNKNOWN }
data class OriginalAudioUi(val phase:OriginalAudioPhase=OriginalAudioPhase.IDLE,val attachmentId:String?=null,
    val durationMillis:Long=0,val playedMillis:Long=0,val error:String?=null) {
    val busy get()=phase!=OriginalAudioPhase.IDLE
    override fun toString()="OriginalAudioUi(phase=$phase, redacted)"
}

/** A single foreground resource owner. SQL actor authenticates; independent worker owns native IO. */
@OptIn(DelicateCoroutinesApi::class)
class OriginalAudioController internal constructor(
    private val current:()->LocalRecordUiState,
    private val panel:()->AttachmentUiState?,
    private val gate:()->CaptureDraftGate?,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,
    private val output:((()->Unit)->OriginalAudioOutput)?,
    private val mayStart:()->Boolean,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any()
    private val mutable=MutableStateFlow(OriginalAudioUi())
    val state=mutable.asStateFlow()
    @Volatile private var resumed=false
    private var route=0L
    private var foregroundEpoch=0L
    private var retired=false
    private var request:Request?=null
    private class Request(val gate:CaptureDraftGate,val pin:CaptureDraftGate.PlaybackPin,
        val route:Long,val foreground:Long,val id:String?,val text:String?,val draftId:String?,val panel:AttachmentUiState?) {
        var stopped=false
        var wave:ByteArray?=null
        var native:OriginalAudioOutput?=null
        var job:Job?=null
        var unknown=false
        var readDone=false
    }
    val busy get()=state.value.busy
    val isResumed get()=resumed
    fun foreground(value:Boolean)=synchronized(monitor) {
        if(resumed!=value) { resumed=value;foregroundEpoch++;if(!value)request?.stopped=true }
    }
    fun routeChanged()=synchronized(monitor) { route++;request?.stopped=true }
    fun playDraft():Boolean = start(null)
    fun playAttachment(session:Long,id:String):Boolean {
        val p=panel() ?: return false
        if(p.sessionId!=session || !p.hasRecordAccess(current()) || p.busy || p.selection!=null || p.error!=null || p.unavailable ||
            p.items.none { it.intent.id==id && it.intent.mediaType=="audio/wav" && it.state==AttachmentState.READY })return false
        return start(p,id)
    }
    private fun start(p:AttachmentUiState?,id:String?=null):Boolean {
        val g=gate() ?: return false
        return g.sourceUi {
            synchronized(monitor) {
                if(retired || !resumed || request!=null || output==null || !mayStart())return@synchronized false
                val local=current();val ui=local.mixedCapture
                if(!local.ready || local.opening || local.error!=null || local.saving || local.savePending ||
                    local.captureDiscard!=null || ui?.let { it.busy || it.selection!=null || it.uncertainSave ||
                        it.discardPending || it.confirmDiscard || it.policyChange!=null || it.error!=null ||
                        it.audio.phase in setOf(CaptureAudioPhase.PREPARING,CaptureAudioPhase.STARTING,CaptureAudioPhase.RECORDING,
                            CaptureAudioPhase.PICKING,CaptureAudioPhase.IMPORTING,CaptureAudioPhase.FINALIZING,CaptureAudioPhase.UNKNOWN) }==true)return@synchronized false
                if(p==null && ui?.draft?.audio?.state!=CaptureAudioState.READY)return@synchronized false
                val pin=g.preparePlayback() ?: return@synchronized false
                val r=Request(g,pin,route,foregroundEpoch,id,ui?.text,ui?.draft?.id,p)
                request=r;mutable.value=OriginalAudioUi(OriginalAudioPhase.LOADING,id)
                if(!enqueue { store -> read(store,r) }) { finish(r,"本机队列暂不可用，原音保留。");false } else true
            }
        } ?: false
    }
    private fun live(r:Request)=synchronized(monitor) {
        request===r && !retired && !r.stopped && resumed && r.route==route && r.foreground==foregroundEpoch &&
            (r.panel==null || panel()?.let { it.sessionId==r.panel.sessionId && it.revision==r.panel.revision &&
                it.historyOwner==r.panel.historyOwner && it.hasRecordAccess(current()) && !it.unavailable && it.error==null }==true)
    }
    private suspend fun read(store:EntryRepository,r:Request) {
        var bytes:ByteArray?=null
        try {
            check(live(r));r.gate.activatePlayback(r.pin)
            val p=r.panel
            bytes=if(p==null) {
                val ui=checkNotNull(current().mixedCapture);val actual=checkNotNull(store.captureRecords?.peek())
                require(ui.pendingEdits==0 && ui.text==r.text && ui.text==actual.text && actual.id==r.draftId &&
                    checkNotNull(ui.draft).sameSnapshot(actual))
                checkNotNull(store.captureRecords).audio(actual).also {
                    try { require(checkNotNull(checkNotNull(store.captureRecords).peek()).sameSnapshot(actual)) }
                    catch(e:Exception) { it.fill(0);throw e }
                }
            } else {
                val expected=checkNotNull(p.items.singleOrNull { it.intent.id==r.id })
                require(expected.state==AttachmentState.READY && expected.intent.mediaType=="audio/wav")
                validatePanel(store,p)
                val media=checkNotNull(store.attachments)
                require(media.list(p.entryId,p.revision).singleOrNull { it.intent.id==r.id }==expected)
                media.read(checkNotNull(r.id)).also {
                    try {
                        validatePanel(store,p)
                        require(media.list(p.entryId,p.revision).singleOrNull { row -> row.intent.id==r.id }==expected)
                    } catch(e:Exception) { it.fill(0);throw e }
                }
            }
            val info=LocalPcmAudio.inspect(bytes)
            require(live(r))
            synchronized(monitor) {
                check(live(r));r.wave=bytes;bytes=null;r.readDone=true
                mutable.value=OriginalAudioUi(OriginalAudioPhase.FOCUS,r.id,info.durationMillis)
                r.job=scope.launch(start=CoroutineStart.ATOMIC) { run(r,info.samples) }
            }
        } catch(_:Exception) { bytes?.fill(0);r.readDone=true;finish(r,"原音暂不可播放或来源已变化；文件保留。") }
    }
    private suspend fun validatePanel(store:EntryRepository,p:AttachmentUiState) {
        val local=current()
        require(panel()?.let { it.sessionId==p.sessionId && it.entryId==p.entryId && it.revision==p.revision &&
            it.historyOwner==p.historyOwner && it.hasRecordAccess(local) && !it.unavailable && it.error==null && !it.busy }==true)
        val expected=checkNotNull(local.localProcessingVersions[p.entryId])
        val entry=store.entries().single { it.id==p.entryId }
        require(entry.currentRevision==(p.historyOwner?.currentRevision ?: p.revision) && expected.source==SourceVersion(p.entryId,entry.currentRevision.toLong()))
        val policy=checkNotNull(store.localProcessing).read(expected.source)
        require(policy.generation==expected.generation)
        require(store.revisions(p.entryId).single { it.revision==p.revision }.entryId==p.entryId)
        store.recycleBin?.read(p.entryId)?.let { require(!it.deleted && it.generation==expected.generation) }
    }
    private inline fun step(r:Request,action:()->Unit):Boolean {
        val admitted=synchronized(monitor) { live(r) }
        if(admitted)action()
        return admitted
    }
    private suspend fun run(r:Request,total:Int) {
        val scratch=ShortArray(1024)
        var error:String?=null
        try {
            val sink=checkNotNull(output).invoke { stopRequest(r) }
            synchronized(monitor) { r.native=sink }
            var focus=false
            if(!step(r) { focus=sink.focus() } || !focus) { error="未取得音频焦点；原音保留。";return }
            if(!step(r) { sink.open() } || !step(r) { sink.play() })return
            synchronized(monitor) { if(request===r)mutable.value=mutable.value.copy(phase=OriginalAudioPhase.PLAYING) }
            var written=0
            while(live(r)) {
                if(written<total) {
                    val n=minOf(scratch.size,total-written)
                    val wave=checkNotNull(r.wave)
                    repeat(n) { i -> val at=44+(written+i)*2;scratch[i]=((wave[at].toInt() and 255) or (wave[at+1].toInt() shl 8)).toShort() }
                    var accepted=0
                    if(!step(r) { accepted=sink.write(scratch,n) })break
                    require(accepted in 0..n);written+=accepted;scratch.fill(0)
                }
                val played=sink.playedFrames();require(played in 0..written.toLong())
                synchronized(monitor) { if(request===r)mutable.value=mutable.value.copy(playedMillis=played*1000/LocalPcmAudio.SAMPLE_RATE) }
                if(played==total.toLong())break
                delay(10)
            }
        } catch(_:Exception) { error="播放中断；原音保留。" }
        finally { withContext(NonCancellable) { scratch.fill(0);closeNative(r,error) } }
    }
    private fun stopRequest(r:Request)=synchronized(monitor) { if(request===r)r.stopped=true }
    fun stop():Unit=synchronized(monitor) {
        val r=request ?: return
        r.stopped=true
        if(r.unknown) {
            val previous=r.job
            r.unknown=false;r.job=scope.launch(start=CoroutineStart.ATOMIC) { previous?.join();closeNative(r,null) }
        }
    }
    private fun closeNative(r:Request,error:String?) {
        synchronized(monitor) { if(request===r)mutable.value=mutable.value.copy(phase=OriginalAudioPhase.CLOSING) }
        try { r.native?.close();r.native=null;finish(r,error) }
        catch(_:Exception) { synchronized(monitor) {
            r.unknown=true
            if(request===r)mutable.value=mutable.value.copy(phase=OriginalAudioPhase.CLOSING_UNKNOWN,error="播放资源关闭尚未确认；请停止核对，暂不能写入或开始另一段。")
        } }
    }
    private fun finish(r:Request,error:String?) {
        val release=synchronized(monitor) {
            if(request!==r)false else {
                check(r.native==null)
                r.wave?.fill(0);r.wave=null
                request=null;mutable.value=OriginalAudioUi(error=error);true
            }
        }
        // No monitor -> gate inversion against UI's gate -> monitor admission.
        if(release)r.gate.finishPlayback(r.pin)
    }
    fun retire()=synchronized(monitor) { retired=true;resumed=false;request?.stopped=true }
    suspend fun closeAndJoin() {
        retire()
        val r=synchronized(monitor) { request }
        r?.job?.join()
        check(synchronized(monitor) { request==null }) { "Audio closure unconfirmed" }
        scope.cancel()
    }
}
