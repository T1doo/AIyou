package com.t1doo.aiyou.records

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.t1doo.aiyou.model.LocalPcmAudio

/** Application context only. Constructing this handle does not request focus or open audio. */
internal class PlatformOriginalAudioOutput(context:Context,private val stop:()->Unit):OriginalAudioOutput {
    private val app=context.applicationContext
    private val manager=checkNotNull(app.getSystemService(AudioManager::class.java))
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build()
    private val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> if(change!=AudioManager.AUDIOFOCUS_GAIN)stop() },Handler(Looper.getMainLooper())).build()
    private var focusHeld=false
    private var registered=false
    private var track:AudioTrack?=null
    private var buildUnknown=false
    private val noisy=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) { if(intent?.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY)stop() }
    }
    override fun focus():Boolean {
        check(!registered && !focusHeld)
        val filter=IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registered=true
        if(Build.VERSION.SDK_INT>=33)app.registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(noisy,filter)
        // If Binder throws, an explicit abandon of this same request is still required.
        focusHeld=true
        val result=manager.requestAudioFocus(request)
        require(result in setOf(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,AudioManager.AUDIOFOCUS_REQUEST_FAILED))
        if(result==AudioManager.AUDIOFOCUS_REQUEST_FAILED)focusHeld=false
        return result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun open() {
        check(track==null && !buildUnknown && focusHeld)
        val minimum=AudioTrack.getMinBufferSize(LocalPcmAudio.SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(minimum>0)
        buildUnknown=true
        track=AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(LocalPcmAudio.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum,4096)).build()
        buildUnknown=false
        check(track?.state==AudioTrack.STATE_INITIALIZED)
    }
    override fun play() { checkNotNull(track).play() }
    override fun write(samples:ShortArray,count:Int)=checkNotNull(track).write(samples,0,count,AudioTrack.WRITE_NON_BLOCKING)
    override fun playedFrames()=checkNotNull(track).playbackHeadPosition.toLong() and 0xffffffffL
    override fun close() {
        track?.let { t -> t.pause();t.flush();t.release();track=null }
        // Each successful ACK is remembered; retry only the resources still owned by this handle.
        if(focusHeld) { check(manager.abandonAudioFocusRequest(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED);focusHeld=false }
        if(registered) { app.unregisterReceiver(noisy);registered=false }
        check(!buildUnknown) { "AudioTrack construction outcome unknown" }
    }
}
