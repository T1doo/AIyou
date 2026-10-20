package com.t1doo.aiyou.records

import android.app.job.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Work items carry only RAM lookup keys. A stale process item does not open preferences, keys or DB. */
class ReminderJobService:JobService() {
    private class Run(val parameters:JobParameters,val key:String){val active=AtomicBoolean(true);val tickets=java.util.concurrent.ConcurrentHashMap.newKeySet<ReminderProcessOwner.Ticket>()}
    private val guard=Any()
    private var running:Run?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val runtime=AndroidReminderRuntime.get(this)
        val key=runtime.runKey(params)?:return false
        synchronized(guard){running?.takeIf{it.key==key}?.let{return it.active.get()}}
        if(runtime.claimRun(params)!=key)return false
        val run=Run(params,key)
        synchronized(guard){running?.let{it.active.set(false);runtime.endRun(it.key)};running=run}
        runtime.scope.launch {
            runtime.jobExecution.withLock {
                try {
                    var firstDequeue=true
                    while(run.active.get()){
                        // One yielded turn: at most 16 dequeues, one SQL page, and 16 platform attempts.
                        var work:JobWorkItem?=null;var ticket:ReminderProcessOwner.Ticket?=null;var dequeued=0;var waiting=false;var drained=false
                        for(index in 0 until 16){
                            if(!run.active.get())break
                            when(runtime.dequeueState(run.key,firstDequeue)){
                                1->{waiting=true;break}
                                2->{run.active.set(false);break}
                            }
                            firstDequeue=false
                            val item=params.dequeueWork()
                            if(item==null){drained=true;break} // The system stops a completed work queue.
                            dequeued++
                            val intent=item.intent
                            val nonce=intent.getStringExtra(AndroidReminderRuntime.NONCE)
                            val id=intent.getStringExtra(AndroidReminderRuntime.TICKET)
                            val found=if(nonce!=null&&id!=null)runtime.owners.find(nonce,id)?.takeIf{it.submission==run.key}else null
                            if(found!=null){work=item;ticket=found;break}
                            if(run.active.get())params.completeWork(item)
                        }
                        val current=ticket
                        if(current==null){
                            if(!run.active.get()||drained)break
                            if(waiting){delay(10);continue}
                            if(dequeued==16){yield();continue}
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
                    // Do not manufacture a completion ACK for unknown work/Binder outcomes.
                }finally{
                    run.active.set(false);runtime.endRun(run.key);runtime.owners.stop(run.tickets)
                    try{runtime.owners.closeCold(onlyIfIdle=true)}catch(_:Exception){/* Unconfirmed close keeps its sealed owner. */}
                    synchronized(guard){if(running===run)running=null}
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        val runtime=AndroidReminderRuntime.get(this)
        val key=runtime.runKey(params)?:return false
        val run=synchronized(guard){running?.takeIf{it.key==key}}
        run?.let{it.active.set(false);runtime.endRun(it.key);runtime.owners.stop(it.tickets)}
        return false
    }
    override fun onDestroy(){
        val runtime=AndroidReminderRuntime.get(this)
        synchronized(guard){running?.let{it.active.set(false);runtime.endRun(it.key);runtime.owners.stop(it.tickets)}}
        super.onDestroy()
    }
}
