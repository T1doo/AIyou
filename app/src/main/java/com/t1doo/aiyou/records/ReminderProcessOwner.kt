package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/** One process authority. Queue hints contain no durable authority and cannot cross an access epoch. */
internal class ReminderProcessOwner(
    val bridge: ReminderOperationBridge,
    private val unlocked: () -> Boolean,
    private val coldAccess: suspend () -> Boolean,
    private val coldOpen: () -> OwnedReminderRuntime,
    private val driver: (ReminderRuntimeResources) -> ReminderRuntimeDriver,
    private val clock: () -> Long,
    private val platform: () -> ReminderPlatformFingerprint,
) {
    enum class Phase { NO_FRONT, OPENING, FRONT, LOCKED, UNKNOWN }
    class Ticket internal constructor(val nonce:String, val id:String, internal val epoch:Long,
        internal val owner:ReminderRuntimeOwner?, val envelope:ReminderEnvelope?, val observedAt:Long, val reasons:Int) {
        private val stopped=AtomicBoolean()
        internal var started=false
        @Volatile private var ingress:ReminderRuntimeIngress?=null
        val active:Boolean get()=!stopped.get()
        fun retire(){stopped.set(true);ingress?.retire()}
        internal fun register(value:ReminderRuntimeIngress){ingress=value;if(!active)value.retire()}
    }
    private class Holder(val owner:ReminderRuntimeOwner,val cold:Boolean) {
        var needsAdmission=true
        var closureUnconfirmed=false
        var quarantine:Throwable?=null
        var store:OwnedReminderRuntime?=null
        var engine:ReminderNativeEngine?=null
        var foreground:ReminderVmRuntime?=null
    }
    private val guard=Any()
    private val bootAdmission=ReminderBootAdmission()
    val nonce=UUID.randomUUID().toString()
    private var epoch=0L
    private var navigationGeneration=0L
    fun navigationGeneration():Long=synchronized(guard){navigationGeneration}
    private var phase=Phase.NO_FRONT
    private var holder:Holder?=null
    private val tickets=LinkedHashMap<String,Ticket>()

    /** Fast observation only: never blocks on the worker or any records IO. */
    fun frontOpening() = transition(Phase.OPENING)
    fun locked(unknown:Boolean=false)=transition(if(unknown)Phase.UNKNOWN else Phase.LOCKED)
    private fun transition(next:Phase) = synchronized(guard) {
        if(phase==next)return@synchronized
        phase=next;epoch++
        tickets.values.forEach{it.retire()};tickets.clear()
        holder?.owner?.seal()
    }
    fun clockChanged()=synchronized(guard){
        tickets.entries.removeAll{(_,ticket)->(ticket.envelope!=null).also{if(it)ticket.retire()}}
    }
    fun capture(envelope:ReminderEnvelope?=null,reasons:Int=ReminderRebuildRequest.CAPABILITY):Ticket? {
        if(!unlocked())return null
        return synchronized(guard){
            if(phase !in setOf(Phase.NO_FRONT,Phase.FRONT))return@synchronized null
            if(envelope!=null)tickets.values.firstOrNull{it.envelope?.identity==envelope.identity&&valid(it)}?.let{return@synchronized it}
            if(tickets.size>=64)return@synchronized null
            val current=holder
            if(phase==Phase.FRONT && current?.owner?.isAdmitted!=true)return@synchronized null
            Ticket(nonce,UUID.randomUUID().toString(),epoch,current?.owner,envelope,clock(),reasons).also{tickets[it.id]=it}
        }
    }
    fun find(processNonce:String,id:String):Ticket?=synchronized(guard){if(processNonce!=nonce)null else tickets[id]?.takeIf{valid(it)}}
    private fun valid(ticket:Ticket)=ticket.active && ticket.epoch==epoch && tickets[ticket.id]===ticket &&
        phase in setOf(Phase.NO_FRONT,Phase.FRONT) && (ticket.owner==null || holder?.owner===ticket.owner)
    fun forget(ticket:Ticket)=synchronized(guard){ticket.retire();if(tickets[ticket.id]===ticket)tickets.remove(ticket.id);Unit}
    fun stop(tickets:Collection<Ticket>){tickets.forEach{it.retire()}}

    /** Called outside the VM actor/worker. Access is read before a cold key/store is even requested. */
    suspend fun execute(ticket:Ticket,commandLimit:Int=16):Boolean {
        require(commandLimit in 1..16)
        if(!unlocked() || !synchronized(guard){valid(ticket)})return false
        var current=synchronized(guard){holder}
        if(current==null){
            current=bridge.runtime {
                if(!unlocked() || !synchronized(guard){valid(ticket)})return@runtime null
                synchronized(guard){holder}?.let{return@runtime it}
                var preparing:Holder?=null
                try {
                    bridge.gate.admit({runBlocking {coldAccess()} && unlocked() && synchronized(guard){valid(ticket)&&phase==Phase.NO_FRONT}}){owner->
                        val candidate=Holder(owner,true).also{preparing=it}
                        synchronized(guard){holder=candidate}
                        val store=coldOpen();candidate.store=store
                        if(!owner.isAdmitted)throw IllegalStateException("Cold reminder admission retired")
                        candidate.engine=ReminderNativeEngine(store.resources,owner,driver(store.resources),clock,bootAdmission,platform)
                        candidate
                    }.also{if(it==null && synchronized(guard){phase==Phase.NO_FRONT})locked()}
                }catch(f:Throwable){
                    if(synchronized(guard){phase==Phase.NO_FRONT})locked(true)
                    preparing?.let { candidate ->
                        if(f is ReminderRuntimeClosureUnconfirmed){candidate.closureUnconfirmed=true;candidate.quarantine=f}
                        else try {
                            candidate.owner.closeAndAwait{candidate.store?.let{it.close();it.awaitClosed()}}
                            synchronized(guard){if(holder===candidate)holder=null}
                        }catch(close:Throwable){candidate.closureUnconfirmed=true;candidate.quarantine=close;throw close}
                    }
                    throw f
                }
            }
        }
        val admitted=current?:return false
        val action={
            check(unlocked() && synchronized(guard){valid(ticket)&&holder===admitted}){"Reminder ticket retired"}
            val engine=requireNotNull(admitted.engine)
            val budget=ReminderPortBudget()
            if(!ticket.started){
                ticket.started=true
                ticket.envelope?.let{engine.handle(it,ticket.observedAt,ticket::register,budget)}
                if(ticket.active){engine.kick(ticket.reasons or if(admitted.needsAdmission)ReminderRebuildRequest.ADMISSION else 0);admitted.needsAdmission=false}
            }
            if(!ticket.active)false else {
                engine.turn(commandLimit,{ticket.active && unlocked() && admitted.owner.isAdmitted},budget)
            }
        }
        return admitted.foreground?.dispatch(action)?.await() ?: bridge.runtime(action)
    }
    suspend fun closeCold() {
        val old=synchronized(guard){holder?.takeIf{it.cold}?.also { _ ->
            tickets.values.forEach{it.retire()};tickets.clear();epoch++
        }}?:return
        old.owner.seal()
        bridge.runtime {
            if(synchronized(guard){holder===old}){
                if(old.closureUnconfirmed)throw ReminderRuntimeClosureUnconfirmed()
                try{old.owner.closeAndAwait{old.store?.let{it.close();it.awaitClosed()}}}
                catch(f:Throwable){old.closureUnconfirmed=true;old.quarantine=f;throw f}
                synchronized(guard){if(holder===old)holder=null}
            }
        }
    }
    /** Foreground caller owns actual repository lifecycle; runtime closes it through the same gate. */
    suspend fun <T:com.t1doo.aiyou.domain.EntryRepository> prepareFront(open:()->T):Pair<T,ReminderVmRuntime> {
        frontOpening();closeCold()
        var candidate:Holder?=null
        var repository:T?=null
        try {
            return requireNotNull(bridge.runtime {bridge.gate.admit({unlocked() && synchronized(guard){phase==Phase.OPENING&&holder==null}}){owner->
                val prepared=Holder(owner,false).also{candidate=it}
                synchronized(guard){holder=prepared}
                val store=open().also{repository=it};val resources=requireNotNull(store.reminderRuntime)
                val engine=ReminderNativeEngine(resources,owner,driver(resources),clock,bootAdmission,platform).also{prepared.engine=it}
                val runtime=ReminderVmRuntime(bridge,owner,
                    {owner.isAdmitted && synchronized(guard){holder===prepared&&phase==Phase.FRONT}},
                    {engine.turn()},
                    closed={success->if(success)synchronized(guard){if(holder===prepared)holder=null}},
                    restored={synchronized(guard){
                        if(holder===prepared){epoch++;navigationGeneration++;tickets.values.forEach{it.retire()};tickets.clear()}
                    }},
                    wake={engine.kick(ReminderRebuildRequest.ADMISSION)})
                prepared.foreground=runtime
                store to runtime
            }})
        }catch(f:Throwable){
            candidate?.let{prepared->bridge.closeOwner(prepared.owner){(repository as? AutoCloseable)?.close();(repository as? AwaitableRecordStorage)?.awaitClosed()};synchronized(guard){if(holder===prepared)holder=null}}
            throw f
        }
    }
    suspend fun click(runtime:ReminderVmRuntime,envelope:ReminderEnvelope):com.t1doo.aiyou.model.ManualTaskSnapshot? {
        val prepared=synchronized(guard){holder?.takeIf{phase==Phase.FRONT&&it.foreground===runtime&&it.owner.isAdmitted}}?:return null
        return runtime.dispatch {
            val engine=requireNotNull(prepared.engine)
            val token=engine.decode(envelope,ReminderEnvelopePurpose.CLICK)
            engine.coordinator.mutate{it.runtimeView(token.taskId)?.task?.takeIf{task->
                task.status==com.t1doo.aiyou.model.ManualTaskStatus.OPEN && task.reminder?.let{r->r.id==token.reminderId&&r.scheduleVersion==token.scheduleVersion&&r.phase!=TaskReminderPhase.REVOKED}==true
            }}
        }.await().takeIf{synchronized(guard){phase==Phase.FRONT&&holder===prepared&&runtime.owner.isAdmitted}}
    }
    fun frontReady(runtime:ReminderVmRuntime) {
        val fresh=synchronized(guard){check(phase in setOf(Phase.OPENING,Phase.FRONT)&&holder?.foreground===runtime&&runtime.owner.isAdmitted);(phase==Phase.OPENING).also{phase=Phase.FRONT}}
        if(!fresh)return
        runtime.requestTurn()
    }
}
