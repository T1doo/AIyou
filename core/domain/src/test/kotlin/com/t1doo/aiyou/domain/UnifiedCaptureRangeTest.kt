package com.t1doo.aiyou.domain

import java.time.*
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class UnifiedCaptureRangeTest {
    @Test fun strictFourDigitDatesEmptyEndsAndConstructedValuesHaveOneContract(){
        assertTrue(UnifiedCaptureRange.parse("","").isUnbounded)
        assertEquals(LocalDate.of(2024,2,29),UnifiedCaptureRange.parse("2024-02-29","").from)
        assertEquals(LocalDate.of(0,1,1),UnifiedCaptureRange.parse("0000-01-01","").from)
        assertEquals(LocalDate.of(9999,12,31),UnifiedCaptureRange.parse("","9999-12-31").through)
        for(v in listOf(" ","2026-2-01","2026-02-29","2024-02-30","2026-01-01 ","２０２６-01-01","\uD800","2026-01-01\n","1".repeat(1000))) {
            assertEquals(UnifiedCaptureRangeProblem.FROM,assertThrows(UnifiedCaptureRangeInvalid::class.java){UnifiedCaptureRange.parse(v,"")}.problem)
            assertEquals(UnifiedCaptureRangeProblem.THROUGH,assertThrows(UnifiedCaptureRangeInvalid::class.java){UnifiedCaptureRange.parse("",v)}.problem)
        }
        assertEquals(UnifiedCaptureRangeProblem.ORDER,assertThrows(UnifiedCaptureRangeInvalid::class.java){UnifiedCaptureRange.parse("2026-02-02","2026-02-01")}.problem)
        assertThrows(IllegalArgumentException::class.java){UnifiedCaptureRange(LocalDate.of(10000,1,1))}
        assertThrows(IllegalArgumentException::class.java){UnifiedCaptureRange(LocalDate.of(2026,2,2),LocalDate.of(2026,2,1))}
        assertFalse(UnifiedCaptureRange.parse("2024-02-29","").toString().contains("2024"))
    }
    @Test fun sameInstantUsesSavedZoneAndNeverTheSystemZone(){
        val range=UnifiedCaptureRange.parse("2026-10-22","2026-10-22");val at=Instant.parse("2026-10-21T16:30:00Z").toEpochMilli()
        val saved=TimeZone.getDefault()
        try{for(zone in listOf("Pacific/Kiritimati","America/Los_Angeles")){
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertTrue(range.contains(at,"Asia/Shanghai"));assertFalse(range.contains(at,"UTC"));assertFalse(range.contains(at,"America/Los_Angeles"))
        }}finally{TimeZone.setDefault(saved)}
    }
    @Test fun inclusiveCalendarDaysHaveAnExclusiveNextDayInstantWithNoFixed24HourAssumption(){
        val zone=ZoneId.of("America/New_York")
        for((day,hours) in listOf("2026-03-08" to 23L,"2026-11-01" to 25L)){
            val date=LocalDate.parse(day);val start=date.atStartOfDay(zone).toInstant().toEpochMilli();val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val range=UnifiedCaptureRange.parse(day,day)
            assertEquals(hours*3_600_000,end-start);assertFalse(range.contains(start-1,zone.id));assertTrue(range.contains(start,zone.id))
            assertTrue(range.contains(end-1,zone.id));assertFalse(range.contains(end,zone.id))
        }
    }
    @Test fun skippedCalendarDayAndExtremeInputDatesDoNotInventInstantsOrOverflow(){
        val skipped=UnifiedCaptureRange.parse("2011-12-30","2011-12-30")
        val start=Instant.parse("2011-12-29T00:00:00Z")
        for(hour in 0..72)assertFalse(skipped.contains(start.plusSeconds(hour*3600L).toEpochMilli(),"Pacific/Apia"))
        assertTrue(UnifiedCaptureRange.parse("0000-01-01","9999-12-31").contains(0,"UTC"))
        assertTrue(UnifiedCaptureRange.parse("","9999-12-31").contains(Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli(),"UTC"))
    }
    @Test fun malformedSavedZoneFailsEvenWhenUnboundedRatherThanFallingBack(){
        assertThrows(DateTimeException::class.java){UnifiedCaptureRange().contains(0,"Not/A_Time_Zone")}
    }
}
