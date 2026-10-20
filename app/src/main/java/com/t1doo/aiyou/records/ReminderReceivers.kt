package com.t1doo.aiyou.records

import android.app.AlarmManager
import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.t1doo.aiyou.model.ReminderRebuildRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

class AlarmReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){
        val envelope=ReminderIntents.read(intent)?:return
        val runtime=AndroidReminderRuntime.get(context)
        val ticket=runtime.owners.capture(envelope)?:return
        handoff(runtime,ticket)
    }
    private fun handoff(runtime:AndroidReminderRuntime,ticket:ReminderProcessOwner.Ticket)=handoffReminder(this,runtime,ticket)
}
class ReminderRebuildReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){
        val reason=when(intent.action){
            Intent.ACTION_BOOT_COMPLETED->ReminderRebuildRequest.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED->ReminderRebuildRequest.PACKAGE
            Intent.ACTION_TIME_CHANGED->ReminderRebuildRequest.CLOCK
            Intent.ACTION_TIMEZONE_CHANGED->ReminderRebuildRequest.ZONE
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED->ReminderRebuildRequest.CAPABILITY
            else->return
        }
        val runtime=AndroidReminderRuntime.get(context)
        if(reason==ReminderRebuildRequest.CLOCK)runtime.owners.clockChanged()
        val ticket=runtime.owners.capture(reasons=reason)?:return
        handoffReminder(this,runtime,ticket)
    }
}
/** Two seconds is only a handoff budget. Timeout retires RAM authority; it cannot cancel Binder IO. */
private fun handoffReminder(receiver:BroadcastReceiver,runtime:AndroidReminderRuntime,ticket:ReminderProcessOwner.Ticket){
    val result=receiver.goAsync();val finished=AtomicBoolean();val handler=Handler(Looper.getMainLooper())
    val deadline=SystemClock.elapsedRealtime()+2000
    fun finish(){if(finished.compareAndSet(false,true))result.finish()}
    val expiry=Runnable{ticket.retire();finish()}
    handler.postDelayed(expiry,2000)
    runtime.scope.launch {
        try{
            if(SystemClock.elapsedRealtime()>=deadline || !runtime.enqueue(ticket))runtime.owners.forget(ticket)
        }catch(_:Exception){runtime.owners.forget(ticket)}
        finally{handler.removeCallbacks(expiry);finish()}
    }
}
