package com.t1doo.aiyou.records

import android.content.Intent
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
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
        assertTrue(request.willPauseWhenDucked())
        assertEquals(AudioAttributes.CONTENT_TYPE_UNKNOWN,request.audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.focusGain)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,stops.get())
        output.close();assertSame(request,shadow.lastAbandonedAudioFocusRequest)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,stops.get());output.close()
    }
    @Test fun duckCallbackReleasesActualAdapterTrackFocusAndReceiverAndGainCannotRestart() {
        val app=RuntimeEnvironment.getApplication();val shadow=Shadows.shadowOf(app.getSystemService(AudioManager::class.java))
        val stops=AtomicInteger()
        lateinit var output:PlatformOriginalAudioOutput
        output=PlatformOriginalAudioOutput(app) { stops.incrementAndGet();output.close() }
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(output.focus())
        val request=shadow.lastAudioFocusRequest.audioFocusRequest
        assertTrue(request.willPauseWhenDucked());assertFalse(request.acceptsDelayedFocusGain())
        output.open();output.play()
        val track=ReflectionHelpers.getField<AudioTrack>(output,"track")
        assertEquals(AudioTrack.STATE_INITIALIZED,track.state)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING,track.playState)
        request.onAudioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(1,stops.get());assertSame(request,shadow.lastAbandonedAudioFocusRequest)
        assertEquals(AudioTrack.STATE_UNINITIALIZED,track.state)
        assertNull(ReflectionHelpers.getField<AudioTrack?>(output,"track"))
        request.onAudioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,stops.get());assertEquals(AudioTrack.STATE_UNINITIALIZED,track.state)
        assertNull(ReflectionHelpers.getField<AudioTrack?>(output,"track"));assertFalse(ReflectionHelpers.getField<Boolean>(output,"focusHeld"))
        output.close()
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
