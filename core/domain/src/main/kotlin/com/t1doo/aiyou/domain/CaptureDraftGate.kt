package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/** One gate per records owner. No SQL, waiting, device calls or IO while holding its monitor. */
class CaptureDraftGate {
    private var sequence=0L
    private val admitted=mutableSetOf<Mutation>()
    private var reservation: Reservation?=null
    private var retired=false
    private var running=0
    private val destructive=mutableMapOf<String,Int>()
    val frozen: Boolean @Synchronized get()=reservation!=null || retired

    class Mutation internal constructor(internal val gate:CaptureDraftGate,internal val sequence:Long):
        AbstractCoroutineContextElement(Key),AutoCloseable {
        companion object Key:CoroutineContext.Key<Mutation>
        override fun close() { gate.release(this) }
        override fun toString()="CaptureMutation(redacted)"
    }
    class Reservation internal constructor(internal val gate:CaptureDraftGate,val draftId:String,internal val cutoff:Long) {
        internal var snapshot:CaptureDraft?=null
        internal var intent:AttachmentIntent?=null
        internal var activeWrites=0
        val base:CaptureDraft get()=synchronized(gate) { checkNotNull(snapshot) }
        override fun toString()="CaptureReservation(redacted)"
    }
    @Synchronized fun <T> uiMutation(action:()->T):T? = if(reservation!=null || retired) null else action()
    @Synchronized fun admit():Mutation? = if(reservation!=null || retired) null else
        Mutation(this,Math.addExact(sequence,1).also { sequence=it }).also { admitted.add(it) }
    @Synchronized private fun release(mutation:Mutation) { admitted.remove(mutation) }
    @Synchronized private fun validate(mutation:Mutation) {
        check(!retired && mutation.gate===this && mutation in admitted)
        val r=reservation
        check(r==null || (r.snapshot==null && mutation.sequence<=r.cutoff))
    }
    suspend fun <T> mutation(action:suspend ()->T):T {
        val existing=coroutineContext[Mutation]
        val inherited=existing!=null && existing.gate===this
        val accepted=if(inherited) checkNotNull(existing) else admit() ?: throw CaptureConflict()
        synchronized(this) { validate(accepted);running++ }
        return try { context(accepted,action) } finally {
            synchronized(this) { running-- }
            if(!inherited) accepted.close()
        }
    }
    fun <T> syncMutation(action:()->T):T {
        val accepted=admit() ?: throw CaptureConflict()
        synchronized(this) { validate(accepted);running++ }
        return try { action() } finally { synchronized(this) { running-- };accepted.close() }
    }
    /** Standard-library context propagation; domain keeps its model-only dependency. */
    suspend fun <T> context(accepted:Mutation,action:suspend ()->T):T = suspendCoroutine { next ->
        val checked:suspend ()->T={ synchronized(this) { validate(accepted) };action() }
        checked.startCoroutine(object:Continuation<T> {
            override val context=next.context+accepted
            override fun resumeWith(result:Result<T>)=next.resumeWith(result)
        })
    }
    @Synchronized fun freeze(draftId:String):Reservation? {
        if(retired || reservation!=null || destructive.getOrDefault(draftId,0)>0)return null
        return Reservation(this,draftId,sequence).also { reservation=it }
    }
    @Synchronized fun reserve(request:Reservation,actual:CaptureDraft,visibleText:String,pendingEdits:Int) {
        check(reservation===request && !retired && request.snapshot==null && admitted.isEmpty() && running==0)
        require(actual.id==request.draftId && !actual.discarding && actual.audio==null &&
            actual.images.all { it.state==CaptureImageState.READY } && pendingEdits==0 && actual.text==visibleText)
        request.snapshot=actual
    }
    @Synchronized fun bindIntent(request:Reservation,intent:AttachmentIntent) {
        check(reservation===request && request.snapshot!=null)
        require(intent.entryId==request.draftId && intent.revision==1 && intent.mediaType=="audio/wav")
        check(request.intent==null || request.intent==intent);request.intent=intent
    }
    @Synchronized fun requireReserved(request:Reservation):CaptureDraft {
        check(reservation===request && request.gate===this);return request.base
    }
    fun intent(request:Reservation):AttachmentIntent=synchronized(this) {
        requireReserved(request);checkNotNull(request.intent)
    }
    suspend fun <T> audioWrite(request:Reservation,action:suspend ()->T):T {
        synchronized(this) { requireReserved(request);check(admitted.isEmpty() && running==0 && request.activeWrites==0);request.activeWrites++ }
        return try { action() } finally { synchronized(this) { request.activeWrites-- } }
    }
    /** Only called on the serial owner actor after a non-creating durable read. */
    @Synchronized fun finish(request:Reservation,actual:CaptureDraft,install:()->Unit={}) {
        check(reservation===request && request.activeWrites==0 && admitted.isEmpty() && running==0)
        val base=request.snapshot
        if(base!=null && !base.sameSnapshot(actual)) {
            val intent=checkNotNull(request.intent)
            val audio=checkNotNull(actual.audio)
            require(actual.version==Math.addExact(base.version,2) && audio.intent==intent &&
                audio.state in setOf(CaptureAudioState.READY,CaptureAudioState.FAILED))
            require(CaptureDraft(base.id,actual.version,base.text,base.images,base.discarding,base.policy,audio).sameSnapshot(actual))
        } else if(base==null) require(actual.id==request.draftId)
        install()
        reservation=null
    }
    @Synchronized fun abandonBeforeReserve(request:Reservation) {
        if(reservation===request) { check(request.snapshot==null && request.activeWrites==0);reservation=null }
    }
    @Synchronized fun retire() { retired=true }
    suspend fun <T> destruction(draftId:String,action:suspend ()->T):T {
        synchronized(this) {
            if(retired || reservation?.draftId==draftId)throw CaptureConflict()
            destructive[draftId]=destructive.getOrDefault(draftId,0)+1
        }
        return try { action() } finally { synchronized(this) {
            val left=destructive.getValue(draftId)-1
            if(left==0)destructive.remove(draftId) else destructive[draftId]=left
        } }
    }
}
