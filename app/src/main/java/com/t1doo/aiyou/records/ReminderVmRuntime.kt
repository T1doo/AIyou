package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.ReminderRuntimeOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** A borrowed foreground port. Native callbacks join the actual VM actor, never wait holding the gate. */
internal class ReminderVmRuntime(val bridge:ReminderOperationBridge,val owner:ReminderRuntimeOwner,
    private val canRun:()->Boolean,private val runTurn:()->Boolean,
    private val retiring:()->Unit={},private val closed:(Boolean)->Unit={},private val restored:()->Unit={},private val wake:()->Unit={}) {
    private val guard=Any()
    private var enqueue:((suspend ()->Unit)->Boolean)?=null
    private var retired=false
    private val turnQueued=AtomicBoolean()
    private val wakePending=AtomicBoolean()
    private class Pending(val result:CompletableDeferred<Any?>,var started:Boolean=false)
    private val pending=LinkedHashSet<Pending>()
    fun attach(submit:(suspend ()->Unit)->Boolean)=synchronized(guard){check(enqueue==null);enqueue=submit}
    fun requestTurn() {
        wakePending.set(true)
        enqueueTurn()
    }
    private fun enqueueTurn() {
        if(!turnQueued.compareAndSet(false,true))return
        val submit=synchronized(guard){if(retired)null else enqueue}
        if(submit==null || !submit {
            turnQueued.set(false)
            val wakeNow=wakePending.getAndSet(false)
            val more=bridge.runtime { if(canRun()){if(wakeNow)wake();runTurn()}else false }
            if(more||wakePending.get())enqueueTurn()
        })turnQueued.set(false)
    }
    suspend fun <T> dispatch(action:()->T):Deferred<T> {
        bridge.assertOutsideOperation()
        val result=CompletableDeferred<Any?>();val ticket=Pending(result)
        val submit=synchronized(guard){
            val submit=enqueue
            if(retired||submit==null||pending.size>=64)null else {
                pending.add(ticket)
                submit
            }
        }
        val accepted=submit?.invoke {
                    val admitted=synchronized(guard){if(retired||ticket !in pending)false else {ticket.started=true;true}}
                    if(admitted)try { result.complete(bridge.runtime { check(canRun()){ "Reminder foreground owner retired" };action() }) }
                    catch(f:Throwable){result.completeExceptionally(f)}
                    finally{synchronized(guard){pending.remove(ticket)}}
                }==true
        if(!accepted)synchronized(guard){pending.remove(ticket)}
        if(!accepted)result.completeExceptionally(IllegalStateException("Reminder foreground owner unavailable"))
        @Suppress("UNCHECKED_CAST") return result as Deferred<T>
    }
    suspend fun <T> userAction(action:suspend ()->T):T=bridge.operation(owner,action)
    fun afterRestore(){restored();requestTurn()}
    fun retire() {
        owner.seal()
        synchronized(guard){
            if(retired)return
            retired=true
            pending.filterNot{it.started}.forEach{it.result.completeExceptionally(IllegalStateException("Reminder work retired"))}
            pending.removeAll{!it.started}
        }
        retiring()
    }
    suspend fun closeStore(action:()->Unit) {
        retire()
        try{bridge.closeOwner(owner,action);closed(true)}catch(f:Throwable){closed(false);throw f}
    }
}
