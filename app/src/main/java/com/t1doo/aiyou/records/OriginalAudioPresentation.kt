package com.t1doo.aiyou.records

/** Stable announcement and second-granularity progress are separate accessibility nodes.
 * This is memory-only presentation; playback authentication, timing and release stay with their owner.
 */
internal data class OriginalAudioPresentation(val visible:Boolean,val announcement:String,val progress:String?,
    val busy:Boolean,val stopLabel:String) {
    override fun toString()="OriginalAudioPresentation(redacted)"
}

internal fun originalAudioPresentation(ui:OriginalAudioUi):OriginalAudioPresentation {
    val announcement=when(ui.phase) {
        OriginalAudioPhase.IDLE -> ui.error.orEmpty()
        OriginalAudioPhase.LOADING -> "正在认证原音和来源"
        OriginalAudioPhase.FOCUS -> "正在准备前台播放"
        OriginalAudioPhase.PLAYING -> "正在播放原音"
        OriginalAudioPhase.CLOSING -> "正在关闭播放；请等待实际释放确认"
        OriginalAudioPhase.CLOSING_UNKNOWN -> ui.error ?: "停止播放结果待核对，请核对同一次播放"
    }
    val progress=if(ui.phase==OriginalAudioPhase.PLAYING) {
        val duration=ui.durationMillis.coerceAtLeast(0)
        val playedSeconds=ui.playedMillis.coerceIn(0,duration)/1000
        val total=if(duration==0L&&ui.durationSamples>0) "不足1毫秒" else if(duration<1000) "$duration 毫秒" else {
            val fraction=duration%1000
            (duration/1000).toString()+(if(fraction==0L) "" else "."+fraction.toString().padStart(3,'0').trimEnd('0'))+" 秒"
        }
        "已播放 $playedSeconds 秒 · 总长 $total"
    } else null
    return OriginalAudioPresentation(ui.busy||ui.error!=null,announcement,progress,ui.busy,
        if(ui.phase==OriginalAudioPhase.CLOSING_UNKNOWN)"核对停止同一次播放"else"停止播放")
}
