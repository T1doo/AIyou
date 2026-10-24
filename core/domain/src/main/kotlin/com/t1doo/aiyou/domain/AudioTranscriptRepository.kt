package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
interface AudioTranscriptPage { val keyword:String;val cursor:PreferenceCursor?;val next:PreferenceCursor?;val items:List<AudioTranscriptSnapshot> }
/** Opaque owner-issued proof. Implementing this interface cannot mint the repository's private token. */
interface AudioTranscriptPlaybackReference { val anchor:AudioTranscriptSource;val range:AudioSampleRange? }
class AudioTranscriptPlayback(val wave:ByteArray,val range:AudioSampleRange?) {
    override fun toString()="AudioTranscriptPlayback(redacted)"
}
interface AudioTranscriptRepository {
    /** Authenticate the exact saved version before the query's final resolve/ACK. */
    suspend fun playbackReference(snapshot:AudioTranscriptSnapshot):AudioTranscriptPlaybackReference = throw AudioTranscriptUnavailable()
    fun ownsPlaybackReference(reference:AudioTranscriptPlaybackReference):Boolean = false
    /** Caller owns and must wipe the full authenticated WAV; no native operation occurs here. */
    suspend fun playback(reference:AudioTranscriptPlaybackReference,pin:CaptureDraftGate.PlaybackPin):AudioTranscriptPlayback = throw AudioTranscriptUnavailable()
    /** Full AEAD/hash/canonical-WAV authentication, followed by a fresh SQL source check. */
    suspend fun preview(intent:AttachmentIntent):AudioTranscriptSource
    suspend fun read(anchor:AudioTranscriptSource):AudioTranscriptSnapshot?
    suspend fun write(request:AudioTranscriptWrite):AudioTranscriptSnapshot
    suspend fun search(keyword:String,cursor:PreferenceCursor?=null):AudioTranscriptPage
    suspend fun resolve(page:AudioTranscriptPage,item:AudioTranscriptSnapshot?=null,publish:()->Unit):Boolean
}
class AudioTranscriptConflict:IllegalStateException("Audio transcript or source changed")
class AudioTranscriptUnavailable:IllegalStateException("Audio transcript storage unavailable")
class AudioTranscriptLimit:IllegalStateException("Audio transcript capacity exceeded")
