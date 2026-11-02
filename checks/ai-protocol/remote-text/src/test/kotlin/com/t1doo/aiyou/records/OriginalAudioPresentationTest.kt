package com.t1doo.aiyou.records

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OriginalAudioPresentationTest {
    @Test fun pcmTicksDoNotChangeTheLiveAnnouncementOrFloodPresentationUpdates():Unit=runBlocking {
        val base=OriginalAudioUi(phase=OriginalAudioPhase.PLAYING,durationMillis=12_345,durationSamples=544_415)
        val states=(0L..1000L step 10).map {base.copy(playedMillis=it)}
        val presentation=states.asFlow().map(::originalAudioPresentation).distinctUntilChanged().toList()
        assertEquals(2,presentation.size) // Whole-second changes only, not 101 PCM ticks.
        assertEquals(1,presentation.map {it.announcement}.distinct().size)
        assertEquals("正在播放原音",presentation.first().announcement)
        assertNotEquals(presentation.first().progress,presentation.last().progress)
        assertTrue(presentation.last().progress!!.contains("1 秒"))
        assertTrue(presentation.last().progress!!.contains("12.345 秒"))
    }
    @Test fun phaseAndReleaseErrorsRemainAnnouncedWhileUnverifiedStopRemainsActionable() {
        val playing=OriginalAudioUi(phase=OriginalAudioPhase.PLAYING,durationMillis=1200)
        val closing=originalAudioPresentation(playing.copy(phase=OriginalAudioPhase.CLOSING))
        val unknown=originalAudioPresentation(playing.copy(phase=OriginalAudioPhase.CLOSING_UNKNOWN,error="合成释放结果待核对"))
        assertNotEquals(originalAudioPresentation(playing).announcement,closing.announcement)
        assertEquals("合成释放结果待核对",unknown.announcement)
        assertTrue(unknown.visible);assertTrue(unknown.busy);assertNull(unknown.progress)
        assertEquals("核对停止同一次播放",unknown.stopLabel)
        assertFalse(originalAudioPresentation(OriginalAudioUi()).visible)
        assertEquals("合成播放错误",originalAudioPresentation(OriginalAudioUi(error="合成播放错误")).announcement)
    }
    @Test fun tinyFragmentsAndPositionBoundsDoNotInventWholeDurationOrPlayedTime() {
        val tiny=originalAudioPresentation(OriginalAudioUi(phase=OriginalAudioPhase.PLAYING,durationSamples=1))
        assertTrue(tiny.progress!!.contains("不足1毫秒"))
        val base=OriginalAudioUi(phase=OriginalAudioPhase.PLAYING,durationMillis=1500)
        assertEquals(originalAudioPresentation(base).progress,originalAudioPresentation(base.copy(playedMillis=-1)).progress)
        assertEquals(originalAudioPresentation(base.copy(playedMillis=1500)).progress,
            originalAudioPresentation(base.copy(playedMillis=Long.MAX_VALUE)).progress)
        assertTrue(originalAudioPresentation(base).progress!!.contains("1.5 秒"))
    }
}
