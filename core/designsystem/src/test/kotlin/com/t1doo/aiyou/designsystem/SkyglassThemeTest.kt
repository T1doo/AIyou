package com.t1doo.aiyou.designsystem

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class SkyglassThemeTest {
    @Test fun rgbaDesignTokensAreConvertedToArgbWithoutMovingAlphaIntoRed() {
        val blue=skyglassPalette(SkyglassAppearance.BLUE)
        assertEquals(0xD9EDF5FF.toInt(),blue.readingStart.toArgb())
        assertEquals(0x6FF2F7FF,blue.welcome.toArgb())
        assertEquals(0xFF252B4C.toInt(),skyglassPalette(SkyglassAppearance.NIGHT).input.toArgb())
        assertEquals(0xDAFFF4EA.toInt(),skyglassPalette(SkyglassAppearance.SUNSET).readingStart.toArgb())
    }
    @Test fun motionOverridesPauseWithoutChangingChoiceAndEveryOverrideIndependentlyWins() {
        for(choice in SkyglassMotion.entries) {
            assertEquals(choice!=SkyglassMotion.STILL,skyglassMotionPlaying(choice,true,false,false,false,false))
            assertFalse(skyglassMotionPlaying(choice,false,false,false,false,false))
            assertFalse(skyglassMotionPlaying(choice,true,true,false,false,false))
            assertFalse(skyglassMotionPlaying(choice,true,false,true,false,false))
            assertFalse(skyglassMotionPlaying(choice,true,false,false,true,false))
            assertFalse(skyglassMotionPlaying(choice,true,false,false,false,true))
        }
    }
}
