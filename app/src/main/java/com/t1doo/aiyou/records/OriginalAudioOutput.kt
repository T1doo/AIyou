package com.t1doo.aiyou.records

/** Inert handle first; each platform operation retains ownership even when it throws. */
interface OriginalAudioOutput {
    fun focus():Boolean
    fun open()
    fun play()
    fun write(samples:ShortArray,count:Int):Int
    fun playedFrames():Long
    /** Immediate pause/flush/release + focus/receiver ACK, or throw and retain the handle. */
    fun close()
}
