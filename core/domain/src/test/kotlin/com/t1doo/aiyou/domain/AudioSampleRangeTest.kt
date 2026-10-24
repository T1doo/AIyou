package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class AudioSampleRangeTest {
    @Test fun halfOpenCeilBoundariesUseActualSamplesInsteadOfFlooredDisplayDuration() {
        assertEquals(1L, LocalPcmAudio.Info(46).durationMillis)
        assertEquals(AudioSampleRange(45, 46), AudioSampleRange.parse("0.001", "", true, 46))
        assertEquals(AudioSampleRange(45, 89), AudioSampleRange.parse("0.001", "0.002", false, 100))
        assertEquals(AudioSampleRange(0, 1), AudioSampleRange.parse("0", "", true, 1))
        assertEquals(AudioSampleRange(0, LocalPcmAudio.MAX_SAMPLES), AudioSampleRange.parse("00.000", "60.000", false, LocalPcmAudio.MAX_SAMPLES))
        assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse("0", "0.002", false, 88) }
        assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse("0.002", "", true, 88) }
        assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse("0.001", "", true, 45) }
    }
    @Test fun malformedOverflowZeroLengthAndTailWithHiddenInputAreRejectedWithoutCoercion() {
        for (bad in listOf("", " ", "-1", "+1", ".5", "1.", "1e1", "NaN", "１", "0.0001", "60.001", "99", "9".repeat(100000), "\uD800")) {
            assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse(bad, "1", false, LocalPcmAudio.MAX_SAMPLES) }
            assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse("0", bad, false, LocalPcmAudio.MAX_SAMPLES) }
        }
        for ((a,b) in listOf("0" to "0", "1" to "0.999", "0.001" to "0.001"))
            assertEquals(AudioSampleRangeProblem.ORDER, assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse(a,b,false,LocalPcmAudio.MAX_SAMPLES) }.problem)
        assertEquals(AudioSampleRangeProblem.END, assertThrows(AudioSampleRangeInvalid::class.java) { AudioSampleRange.parse("0","1",true,LocalPcmAudio.MAX_SAMPLES) }.problem)
        assertThrows(IllegalArgumentException::class.java) { AudioSampleRange(-1,1) }
        assertThrows(IllegalArgumentException::class.java) { AudioSampleRange(0,0) }
        assertThrows(IllegalArgumentException::class.java) { AudioSampleRange(0,LocalPcmAudio.MAX_SAMPLES+1) }
        assertThrows(IllegalArgumentException::class.java) { AudioSampleRange(0,2).validate(1) }
        assertFalse(AudioSampleRange(123,456).toString().contains("123"))
    }
}
