package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
interface AudioTranscriptPage { val keyword:String;val cursor:PreferenceCursor?;val next:PreferenceCursor?;val items:List<AudioTranscriptSnapshot> }
interface AudioTranscriptRepository {
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
