package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID

/** Called only on the process worker. Every metadata/key/SQL operation still enters the same gate. */
internal class ReminderNativeEngine(val resources:ReminderRuntimeResources,val owner:ReminderRuntimeOwner,
    driver:ReminderRuntimeDriver,private val clock:()->Long,private val bootAdmission:ReminderBootAdmission=ReminderBootAdmission(),private val platform:()->ReminderPlatformFingerprint) {
    private val bounded=BudgetDriver(driver)
    val coordinator=ReminderRuntimeCoordinator(owner,{resources.tasks},bounded,clock)
    fun kick(reasons:Int,force:Boolean=false) = coordinator.mutate {
        var meta=resources.rebuild.read();val snapshot=platform()
        val unknownBoot= snapshot.bootCount==null && reasons and (ReminderRebuildRequest.ADMISSION or ReminderRebuildRequest.BOOT)!=0 && !bootAdmission.rebuilt
        if(force || unknownBoot || reasons and ReminderRebuildRequest.CLOCK!=0 || meta.request?.platform?.digest!=snapshot.digest){
            val request=ReminderRebuildRequest.create(meta.version,reasons,snapshot)
            meta=settle({resources.rebuild.request(request)}){resources.rebuild.read().takeIf{it.request==request}}
            if(unknownBoot)bootAdmission.rebuilt=true
        }
        val writeId="wake:"+UUID.randomUUID();val version=meta.version
        settle({resources.rebuild.requestDrain(version,writeId)}){resources.rebuild.read().takeIf{it.lastWriteId==writeId}}
    }
    /** One finite SQL page and no more than commandLimit command-port attempts. */
    fun turn(commandLimit:Int=16,proceed:()->Boolean={true},budget:ReminderPortBudget=ReminderPortBudget()):Boolean =
        bounded.withBudget(budget){turnWithin(commandLimit,proceed,budget)}
    private fun turnWithin(commandLimit:Int,proceed:()->Boolean,budget:ReminderPortBudget):Boolean {
        require(commandLimit in 1..16)
        if(!proceed())return false
        val meta=coordinator.mutate{resources.rebuild.read()}
        if(!meta.planDone){
            coordinator.mutate {
                settle({resources.rebuild.planPage(meta.version,clock(),16)}){
                    resources.rebuild.read().takeIf{it.version>meta.version && it.request==meta.request && (it.planCursor!=meta.planCursor || it.planDone)}
                }
            }
        }else{
            if(budget.remaining==0)return true
            val page=coordinator.mutate{resources.rebuild.commandPage(clock(),16,minOf(commandLimit,budget.remaining))}?:return false
            page.unownedRuntime.forEach{if(proceed())coordinator.reconcile(it)}
            coordinator.drainPage(page,proceed)
            if(!proceed())return false
            coordinator.mutate {
                // Checkpoint replay is independently authenticated by the metadata cell.
                settle({resources.rebuild.checkpoint(page)}){resources.rebuild.read().takeIf{it.version>page.metaVersion && it.drain?.next==page.next}}
            }
        }
        return coordinator.mutate{resources.rebuild.read()}.let{!it.planDone || it.drain?.done==false}
    }
    fun decode(envelope:ReminderEnvelope,purpose:ReminderEnvelopePurpose):TaskReminderToken=coordinator.mutate{resources.envelopes.open(envelope,purpose)}
    fun handle(envelope:ReminderEnvelope,observedAt:Long,register:(ReminderRuntimeIngress)->Unit,budget:ReminderPortBudget=ReminderPortBudget()):ReminderRuntimeReply =
        bounded.withBudget(budget){handleWithin(envelope,observedAt,register)}
    private fun handleWithin(envelope:ReminderEnvelope,observedAt:Long,register:(ReminderRuntimeIngress)->Unit):ReminderRuntimeReply {
        val token=decode(envelope,ReminderEnvelopePurpose.ALARM)
        val ingress=owner.capture(token,observedAt)?:return ReminderRuntimeReply(ReminderRuntimeOutcome.NO_ACCESS,null)
        register(ingress)
        val reply=coordinator.handle(ingress)
        if(reply.outcome==ReminderRuntimeOutcome.ALREADY_OBSERVED)coordinator.reconcile(token.taskId)
        return reply
    }
    private fun <T> settle(call:()->T,read:()->T?):T {
        repeat(2){try{return call()}catch(f:ReminderMetaStorageFailure){read()?.let{return it};if(it==1)throw f}}
        error("Bounded metadata operation exhausted")
    }
}

/** One shared turn budget covers post, schedule and cancel together; capability reads spend no command slot. */
internal class ReminderPortBudget(private val limit:Int=16) {
    init{require(limit in 1..16)}
    var spent:Int=0;private set
    val remaining:Int get()=limit-spent
    fun start(){check(remaining>0){"Reminder turn port budget exhausted"};spent++}
}
private class BudgetDriver(private val delegate:ReminderRuntimeDriver):ReminderRuntimeDriver {
    private var current:ReminderPortBudget?=null
    fun <T> withBudget(budget:ReminderPortBudget,action:()->T):T {
        check(current==null);current=budget
        try{return action()}finally{current=null}
    }
    override fun availability()=delegate.availability()
    override fun schedule(command:TaskReminderCommand):TaskReminderPhase{requireNotNull(current).start();return delegate.schedule(command)}
    override fun cancel(command:TaskReminderCommand):TaskReminderPhase{requireNotNull(current).start();return delegate.cancel(command)}
    override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{requireNotNull(current).start();return delegate.post(token,specification)}
}

/** Worker-confined, process-only fallback when the platform cannot expose a boot count. */
internal class ReminderBootAdmission {var rebuilt=false}
