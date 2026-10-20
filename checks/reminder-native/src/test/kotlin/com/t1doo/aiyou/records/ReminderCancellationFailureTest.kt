package com.t1doo.aiyou.records

import android.Manifest
import android.app.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,shadows=[CancelAlarmShadow::class,CancelNoticeShadow::class])
class ReminderCancellationFailureTest {
    @After fun reset(){CancelFailures.alarm=false;CancelFailures.notice=false}
    @Test fun eitherCancellationFailureLeavesAuthenticatedOutboxUnacknowledged(){
        val app:Application=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        for(alarmFails in listOf(false,true)){
            NativeSqlFixture().use{f->
                val task=f.task(if(alarmFails)"alarm-failure" else "notice-failure",true)
                val driver=AndroidReminderDriver(app,f.stores.resources.envelopes,{f.now})
                val schedule=task.reminder!!.commands.single();assertEquals(TaskReminderPhase.SCHEDULED,driver.schedule(schedule))
                val cancelled=f.stores.resources.tasks.changeReminder(TaskReminderMutation(task,null,3,"revoke-"+task.id))
                val command=cancelled.reminder!!.commands.single{it.kind==TaskReminderCommandKind.CANCEL}
                val before=f.stores.resources.tasks.runtimeView(task.id)!!.task
                CancelFailures.alarm=alarmFails;CancelFailures.notice=!alarmFails
                val gate=ReminderExecutionGate();val owner=gate.openOwner(true)
                val coordinator=ReminderRuntimeCoordinator(owner,{f.stores.resources.tasks},driver,{f.now})
                try{
                    assertEquals(0,coordinator.drain())
                    assertEquals(before,f.stores.resources.tasks.runtimeView(task.id)!!.task)
                    assertTrue(f.stores.resources.tasks.isReminderCommandCurrent(command))
                }finally{owner.closeAndAwait();CancelFailures.alarm=false;CancelFailures.notice=false}
            }
        }
    }
}
internal object CancelFailures {@Volatile var alarm=false;@Volatile var notice=false}
@Implements(AlarmManager::class)
class CancelAlarmShadow:ShadowAlarmManager(){
    @Implementation override fun cancel(operation:PendingIntent){if(CancelFailures.alarm)error("Synthetic alarm cancellation unknown");super.cancel(operation)}
}
@Implements(NotificationManager::class)
class CancelNoticeShadow:ShadowNotificationManager(){
    @Implementation override fun cancel(tag:String?,id:Int){if(CancelFailures.notice)error("Synthetic notification cancellation unknown");super.cancel(tag,id)}
}
