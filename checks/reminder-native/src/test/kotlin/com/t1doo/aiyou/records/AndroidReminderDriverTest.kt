package com.t1doo.aiyou.records

import android.Manifest
import android.app.*
import android.content.Intent
import com.t1doo.aiyou.data.ReminderEnvelopeCipher
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

/** API conditions are explicitly simulated on the fixed API35 JVM SDK; this is not API26/31/33/37 device execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class AndroidReminderDriverTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val alarm get()=requireNotNull(app.getSystemService(AlarmManager::class.java))
    private val notifications get()=requireNotNull(app.getSystemService(NotificationManager::class.java))
    private val date=ManualTaskDateInput("2042-11-19","09:30","UTC").parse()!!
    private val due=date.instant()!!.toEpochMilli()
    private val key=ByteArray(32){(it+7).toByte()}
    private val token=TaskReminderToken("synthetic-task","a".repeat(64),1)
    private fun command(token:TaskReminderToken=this.token,mode:TaskReminderMode=TaskReminderMode.EXACT,kind:TaskReminderCommandKind=TaskReminderCommandKind.SCHEDULE)=TaskReminderCommand(token,kind,date,mode)
    @After fun wipe(){key.fill(0)}
    @Test fun currentCapabilityConditionsKeepExactChoiceAndAccountForGlobalChannelAndRuntimePermission(){
        ReminderEnvelopeCipher(key,app.packageName).use{codec->
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            for(api in listOf(26,31,33,37)){
                val driver=AndroidReminderDriver(app,codec,{due-60_000},api)
                val observed=driver.availability()
                assertTrue(observed.platformConnected)
                assertEquals(TaskReminderPermission.GRANTED,observed.notifications)
                assertEquals(if(api<31)TaskReminderPermission.GRANTED else TaskReminderPermission.NOT_REQUESTED,observed.exactAlarms)
                if(api>=31){assertEquals(TaskReminderPhase.EXACT_REQUIRED,driver.schedule(command()));assertTrue(Shadows.shadowOf(alarm).scheduledAlarms.isEmpty())}
            }
            val driver=AndroidReminderDriver(app,codec,{due-60_000},33)
            Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(TaskReminderPhase.NOTIFICATION_REQUIRED,driver.schedule(command(mode=TaskReminderMode.APPROXIMATE)))
            assertTrue(Shadows.shadowOf(alarm).scheduledAlarms.isEmpty())
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            Shadows.shadowOf(notifications).setNotificationsEnabled(false)
            assertEquals(TaskReminderPermission.NOT_REQUESTED,driver.availability().notifications)
            Shadows.shadowOf(notifications).setNotificationsEnabled(true)
            notifications.createNotificationChannel(NotificationChannel(AndroidReminderDriver.CHANNEL,"合成测试渠道",NotificationManager.IMPORTANCE_NONE))
            assertFalse(driver.availability().channelEnabled)
        }
    }
    @Test fun channelGroupBlockingIsObservedOnlyWhereThePlatformSupportsIt(){
        ReminderEnvelopeCipher(key,app.packageName).use{codec->
            val driver=AndroidReminderDriver(app,codec,{due-60_000},28);driver.availability()
            val group=NotificationChannelGroup("synthetic-group","合成测试分组")
            org.robolectric.util.ReflectionHelpers.setField(group,"mBlocked",true)
            notifications.createNotificationChannelGroup(group)
            val channel=notifications.getNotificationChannel(AndroidReminderDriver.CHANNEL);channel.group=group.id
            notifications.createNotificationChannel(channel)
            assertFalse(driver.availability().channelEnabled)
            assertTrue(AndroidReminderDriver(app,codec,{due-60_000},26).availability().channelEnabled)
        }
    }
    @Test fun resealingKeepsStablePendingIntentAndExactCancellationLeavesNewVersionAndOtherTasks(){
        ReminderEnvelopeCipher(key,app.packageName).use{codec->
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val driver=AndroidReminderDriver(app,codec,{due-60_000})
            assertEquals(TaskReminderPhase.SCHEDULED,driver.schedule(command()))
            val before=Shadows.shadowOf(alarm).scheduledAlarms.single().operation
            driver.schedule(command());assertEquals(before,Shadows.shadowOf(alarm).scheduledAlarms.single().operation)
            val next=token.copy(scheduleVersion=2);val other=token.copy(taskId="other-synthetic-task")
            driver.schedule(command(next));driver.schedule(command(other));assertEquals(3,Shadows.shadowOf(alarm).scheduledAlarms.size)
            driver.cancel(command(kind=TaskReminderCommandKind.CANCEL));assertEquals(2,Shadows.shadowOf(alarm).scheduledAlarms.size)
            val remaining=Shadows.shadowOf(alarm).scheduledAlarms.map{Shadows.shadowOf(it.operation).savedIntent.dataString}.toSet()
            assertEquals(setOf("aiyou-reminder://alarm/v1/"+codec.identity(next),"aiyou-reminder://alarm/v1/"+codec.identity(other)),remaining)
        }
    }
    @Test fun collidingJavaTokenHashesStillProduceDifferentFullPendingIntentIdentities(){
        ReminderEnvelopeCipher(key,app.packageName).use{codec->
            val first=token.copy(taskId="FB");val second=token.copy(taskId="Ea")
            assertEquals(first.hashCode(),second.hashCode())
            assertNotEquals(codec.identity(first),codec.identity(second))
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val driver=AndroidReminderDriver(app,codec,{due-60_000})
            driver.schedule(command(first));driver.schedule(command(second))
            assertEquals(2,Shadows.shadowOf(alarm).scheduledAlarms.size)
        }
    }
    @Test fun dueCrossedDuringEnvelopePreparationDoesNotReplaceExistingLegalLateAlarm(){
        ReminderEnvelopeCipher(key,app.packageName).use{codec->
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            var now=due-60_000;val initial=AndroidReminderDriver(app,codec,{now});initial.schedule(command())
            val existing=Shadows.shadowOf(alarm).scheduledAlarms.single()
            val crossing=object:ReminderEnvelopePort by codec {
                override fun seal(token:TaskReminderToken,purpose:ReminderEnvelopePurpose)=codec.seal(token,purpose).also{now=due}
            }
            val driver=AndroidReminderDriver(app,crossing,{now})
            assertThrows(IllegalStateException::class.java){driver.schedule(command())}
            assertSame(existing,Shadows.shadowOf(alarm).scheduledAlarms.single())
        }
    }
    @Test fun notificationIsGenericAndClickEnvelopeIsDifferentPurposeDirectActivityNavigation(){
        ReminderEnvelopeCipher(key,app.packageName).use{codec->
            val driver=AndroidReminderDriver(app,codec,{due})
            driver.availability()
            assertEquals(ReminderPostObservation.POST_RETURNED,driver.post(token,"controlled-specification"))
            val notification=Shadows.shadowOf(notifications).allNotifications.single()
            assertEquals("爱忆有一条提醒",notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("打开应用查看待办",notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility)
            assertNotNull(notification.publicVersion)
            val click=Shadows.shadowOf(notification.contentIntent).savedIntent
            assertEquals(com.t1doo.aiyou.MainActivity::class.java.name,click.component!!.className)
            assertEquals(token,codec.open(ReminderIntents.read(click,true)!!,ReminderEnvelopePurpose.CLICK))
            assertNull(ReminderIntents.read(click,false))
            ReminderIntents.consume(click);assertNull(ReminderIntents.read(click,true))
        }
    }
}
