package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class FactRecapRangeTest {
    @Test fun midnightIsHalfOpenAndSelectedZoneControlsRecordedDay() {
        val r=FactRecapRange(LocalDate.parse("2026-10-15"),FactRecapPeriod.DAY,"Asia/Shanghai")
        assertEquals(Instant.parse("2026-10-14T16:00:00Z").toEpochMilli(),r.startMillis)
        assertEquals(Instant.parse("2026-10-15T16:00:00Z").toEpochMilli(),r.endMillis)
        assertEquals(LocalDate.parse("2026-10-15"),r.day(r.startMillis))
        assertEquals(LocalDate.parse("2026-10-16"),r.day(r.endMillis))
    }
    @Test fun springAndAutumnDaysAreTwentyThreeAndTwentyFiveHours() {
        fun hours(date:String)=FactRecapRange(LocalDate.parse(date),FactRecapPeriod.DAY,"America/New_York").let{(it.endMillis-it.startMillis)/3600000}
        assertEquals(23L,hours("2026-03-08"));assertEquals(25L,hours("2026-11-01"))
    }
    @Test fun weekStartsMondayAndHandlesYearBoundary() {
        val r=FactRecapRange(LocalDate.parse("2026-01-01"),FactRecapPeriod.WEEK,"UTC")
        assertEquals(LocalDate.parse("2025-12-29"),r.firstDay);assertEquals(LocalDate.parse("2026-01-05"),r.endDay)
        assertEquals(7,r.days.size);assertEquals(DayOfWeek.MONDAY,r.firstDay.dayOfWeek)
    }
    @Test fun dstWeeksUseCalendarDaysInsteadOfFixedHours() {
        fun hours(date:String)=FactRecapRange(LocalDate.parse(date),FactRecapPeriod.WEEK,"America/New_York").let{(it.endMillis-it.startMillis)/3600000}
        assertEquals(167L,hours("2026-03-08"));assertEquals(169L,hours("2026-11-01"))
    }
    @Test fun skippedCivilDayHasAnHonestEmptyInstantRange() {
        val r=FactRecapRange(LocalDate.parse("2011-12-30"),FactRecapPeriod.DAY,"Pacific/Apia")
        assertEquals(r.startMillis,r.endMillis);assertEquals(1,r.days.size)
    }
    @Test fun invalidZoneAndOutOfBoundDatesRejectWithoutGuessing() {
        assertThrows(Exception::class.java){FactRecapRange(LocalDate.parse("2026-10-15"),FactRecapPeriod.DAY,"Made/Up")}
        assertThrows(IllegalArgumentException::class.java){FactRecapRange(LocalDate.parse("2101-01-01"),FactRecapPeriod.DAY,"UTC")}
    }
}
