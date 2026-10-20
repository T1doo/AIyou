package com.t1doo.aiyou.records

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Actual platform adapter/focus/receiver on API35 JVM shadows; no hardware or system permission operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class AudioPlatformLifecycleTest {
    @Test fun inertHandleRequestsOnlyImmediateTransientFocusAndNoisyListenerEndsWithRelease() {
        val app=RuntimeEnvironment.getApplication();val manager=app.getSystemService(AudioManager::class.java)
        val shadow=Shadows.shadowOf(manager);val stops=AtomicInteger()
        val output=PlatformOriginalAudioOutput(app) { stops.incrementAndGet() }
        assertNull(shadow.lastAudioFocusRequest)
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(output.focus())
        val request=shadow.lastAudioFocusRequest.audioFocusRequest
        assertFalse(request.acceptsDelayedFocusGain())
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.focusGain)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,stops.get())
        output.close();assertSame(request,shadow.lastAbandonedAudioFocusRequest)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,stops.get());output.close()
    }
    @Test fun refusedFocusDoesNotBecomeOwnedOrLeaveNoisyReceiverAfterClose() {
        val app=RuntimeEnvironment.getApplication();val shadow=Shadows.shadowOf(app.getSystemService(AudioManager::class.java))
        val stops=AtomicInteger();val output=PlatformOriginalAudioOutput(app) { stops.incrementAndGet() }
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(output.focus());output.close();assertNull(shadow.lastAbandonedAudioFocusRequest)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,stops.get())
    }
}
