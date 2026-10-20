package com.t1doo.aiyou.records

import android.app.job.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Work items carry only RAM lookup keys. A stale process item does not open preferences, keys or DB. */
class ReminderJobService:JobService() {
    private class Run(val parameters:JobParameters){val active=AtomicBoolean(true);val tickets=java.util.concurrent.ConcurrentHashMap.newKeySet<ReminderProcessOwner.Ticket>()}
    private val guard=Any()
    private var running:Run?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val runtime=AndroidReminderRuntime.get(this)
        val run=Run(params)
        synchronized(guard){running?.let{it.active.set(false);runtime.owners.stop(it.tickets)};running=run}
        runtime.scope.launch {
            runtime.jobExecution.withLock {
                try {
                    while(run.active.get()){
                        // One yielded turn: at most 16 dequeues, one SQL page, and 16 platform attempts.
                        var work:JobWorkItem?=null;var ticket:ReminderProcessOwner.Ticket?=null;var dequeued=0
                        for(index in 0 until 16){
                            if(!run.active.get())break
                            val item=params.dequeueWork()?:break
                            dequeued++
                            val intent=item.intent
                            val nonce=intent.getStringExtra(AndroidReminderRuntime.NONCE)
                            val id=intent.getStringExtra(AndroidReminderRuntime.TICKET)
                            val found=if(nonce!=null&&id!=null)runtime.owners.find(nonce,id)else null
                            if(found!=null){work=item;ticket=found;break}
                            if(run.active.get())params.completeWork(item)
                        }
                        val current=ticket
                        if(current==null){
                            if(dequeued==16&&run.active.get()){yield();continue}
                            if(run.active.get())withContext(Dispatchers.Main){if(run.active.get())jobFinished(params,false)}
                            break
                        }
                        run.tickets.add(current)
                        if(!run.active.get())current.retire()
                        try{
                            var more:Boolean
                            do {
                                more=runtime.owners.execute(current,16)
                                yield()
                            }while(more&&run.active.get()&&current.active)
                        }finally{
                            runtime.owners.forget(current);run.tickets.remove(current)
                        }
                        if(run.active.get())params.completeWork(requireNotNull(work))
                        yield()
                    }
                }catch(_:Exception){
                    if(run.active.get())withContext(Dispatchers.Main){if(run.active.get())jobFinished(params,false)}
                }finally{
                    run.active.set(false);runtime.owners.stop(run.tickets)
                    try{runtime.owners.closeCold()}catch(_:Exception){/* Unconfirmed close keeps its sealed owner. */}
                    synchronized(guard){if(running===run)running=null}
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        val runtime=AndroidReminderRuntime.get(this)
        val run=synchronized(guard){running?.takeIf{it.parameters===params}}
        run?.let{it.active.set(false);runtime.owners.stop(it.tickets)}
        return false
    }
    override fun onDestroy(){
        val runtime=AndroidReminderRuntime.get(this)
        synchronized(guard){running?.let{it.active.set(false);runtime.owners.stop(it.tickets)}}
        super.onDestroy()
    }
}
