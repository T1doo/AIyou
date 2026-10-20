package com.t1doo.aiyou.records

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.t1doo.aiyou.MainActivity
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*

/** Platform effects only. Invoked by the current owner, outside all SQL transactions. */
internal class AndroidReminderDriver(private val context:Context, private val envelopes:ReminderEnvelopePort,
    private val clock:()->Long={System.currentTimeMillis()},private val platformApi:Int=Build.VERSION.SDK_INT):ReminderRuntimeDriver {
    private val alarms get()=context.getSystemService(AlarmManager::class.java)
    private val notices get()=context.getSystemService(NotificationManager::class.java)
    private fun channel():NotificationChannel? {
        val manager=notices?:return null
        if(manager.getNotificationChannel(CHANNEL)==null)manager.createNotificationChannel(NotificationChannel(CHANNEL,"待办提醒",NotificationManager.IMPORTANCE_DEFAULT).apply{
            description="仅显示通用提醒；打开应用后查看待办"
            lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        return manager.getNotificationChannel(CHANNEL)
    }
    override fun availability():TaskReminderAvailability {
        val alarm=alarms;val manager=notices
        if(alarm==null||manager==null)return TaskReminderAvailability()
        val channel=channel()
        val allowed=(platformApi<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&manager.areNotificationsEnabled()
        val group=if(platformApi>=28 && Build.VERSION.SDK_INT>=28)channel?.group?.let{manager.getNotificationChannelGroup(it)} else null
        return TaskReminderAvailability(true,
            if(allowed)TaskReminderPermission.GRANTED else TaskReminderPermission.NOT_REQUESTED,
            if(platformApi<31 || Build.VERSION.SDK_INT<31 || alarm.canScheduleExactAlarms())TaskReminderPermission.GRANTED else TaskReminderPermission.NOT_REQUESTED,
            channel!=null&&channel.importance!=NotificationManager.IMPORTANCE_NONE&&(platformApi<28||Build.VERSION.SDK_INT<28||group?.isBlocked!=true))
    }
    override fun schedule(command:TaskReminderCommand):TaskReminderPhase {
        availability().blocked(command.mode)?.let{return it}
        val envelope=envelopes.seal(command.token,ReminderEnvelopePurpose.ALARM)
        val intent=ReminderIntents.alarm(context,envelope)
        val pending=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val whenMillis=requireNotNull(command.schedule.instant()).toEpochMilli()
        // The final clock read follows all potentially blocking capability/codec/PendingIntent work.
        check(whenMillis>clock()){"Reminder time crossed during platform preparation"}
        val alarm=requireNotNull(alarms)
        if(command.mode==TaskReminderMode.EXACT)alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMillis,pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMillis,pending)
        return TaskReminderPhase.SCHEDULED
    }
    override fun cancel(command:TaskReminderCommand):TaskReminderPhase {
        val identity=envelopes.identity(command.token)
        val intent=ReminderIntents.alarmIdentity(context,identity)
        val pending=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        val alarm=requireNotNull(alarms);val manager=requireNotNull(notices)
        if(pending!=null){alarm.cancel(pending);pending.cancel()}
        manager.cancel(identity,0)
        return TaskReminderPhase.REVOKED
    }
    override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation {
        // An exception after entering this method is an unknown call outcome, never proof of failure.
        val envelope=envelopes.seal(token,ReminderEnvelopePurpose.CLICK)
        val click=PendingIntent.getActivity(context,0,ReminderIntents.click(context,envelope),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public=Notification.Builder(context,CHANNEL).setSmallIcon(com.t1doo.aiyou.R.drawable.ic_aiyou)
            .setContentTitle("爱忆有一条提醒").setContentText("打开应用查看待办").build()
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(com.t1doo.aiyou.R.drawable.ic_aiyou)
            .setContentTitle("爱忆有一条提醒").setContentText("打开应用查看待办")
            .setContentIntent(click).setOnlyAlertOnce(true).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        requireNotNull(notices).notify(envelope.identity,0,notification)
        return ReminderPostObservation.POST_RETURNED
    }
    companion object {const val CHANNEL="aiyou_manual_reminders_v1"}
}

internal object ReminderIntents {
    const val ALARM="com.t1doo.aiyou.REMINDER_ALARM_V1"
    const val CLICK="com.t1doo.aiyou.REMINDER_CLICK_V1"
    private const val PAYLOAD="reminder-envelope-v1"
    private fun uri(purpose:String,id:String)=Uri.parse("aiyou-reminder://$purpose/v1/$id")
    fun alarmIdentity(context:Context,id:String)=Intent(context,AlarmReminderReceiver::class.java).setAction(ALARM).setData(uri("alarm",id))
    fun alarm(context:Context,envelope:ReminderEnvelope)=alarmIdentity(context,envelope.identity).putExtra(PAYLOAD,envelope.bytes())
    fun click(context:Context,envelope:ReminderEnvelope)=Intent(context,MainActivity::class.java).setAction(CLICK).setData(uri("click",envelope.identity)).putExtra(PAYLOAD,envelope.bytes())
    fun read(intent:Intent?,click:Boolean=false):ReminderEnvelope?=try {
        val expected=if(click)CLICK else ALARM
        if(intent?.action!=expected)null else {
            val uri=intent.data
            val id=uri?.lastPathSegment
            if(id==null||!id.matches(Regex("[a-f0-9]{64}"))||uri.toString()!=uri(if(click)"click" else "alarm",id).toString()||intent.clipData!=null||intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)null
            else if(intent.extras?.keySet()!=setOf(PAYLOAD))null
            else intent.getByteArrayExtra(PAYLOAD)?.takeIf{it.size in 29..2048 && it.size+uri.toString().toByteArray(Charsets.UTF_8).size<=2048}?.let{ReminderEnvelope(id,it)}
        }
    }catch(_:Exception){null}
    fun consume(intent:Intent?){if(intent?.action==CLICK){intent.removeExtra(PAYLOAD);intent.data=null;intent.action=null}}
}
