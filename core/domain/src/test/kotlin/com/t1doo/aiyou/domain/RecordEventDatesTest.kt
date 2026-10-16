package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class RecordEventDatesTest {
    @Test fun explicitCivilDayRetainsItsIanaZoneEvenWhenThatDayHasNoInstant() {
        val date = RecordEventDate("2011-12-30", "Pacific/Apia")
        assertEquals("2011-12-30", date.localDate)
        assertEquals("Pacific/Apia", date.timeZone)
        assertEquals(RecordEventDate("2024-02-29", "Asia/Shanghai"), RecordEventDate("2024-02-29", "Asia/Shanghai"))
        assertEquals("0001-01-01", RecordEventDate("0001-01-01", "Etc/UTC").localDate)
        assertEquals("9999-12-31", RecordEventDate("9999-12-31", "America/New_York").localDate)
    }
    @Test fun relativeWordsInvalidDaysTimesAndOffsetOnlyZonesCannotBecomeAnEventDate() {
        listOf("", "昨天", "2026-2-03", "2026-02-29", "2026-04-31", "0000-01-01",
            "2026-10-16T09:00", " 2026-10-16", "2026-10-16 ").forEach { input ->
            assertThrows(Exception::class.java) { RecordEventDate(input, "Asia/Shanghai") }
        }
        listOf("", "+08:00", "UTC+08:00", "Asia/Made_Up", "EST", "Asia/Shanghai ").forEach { zone ->
            assertThrows(IllegalArgumentException::class.java) { RecordEventDate("2026-10-16", zone) }
        }
    }
    @Test fun unknownAndConfirmedClearAreDistinctAndMutationCarriesNoInventedClock() {
        val unknown = RecordEventDateSnapshot("synthetic-entry", 0, null)
        val clear = RecordEventDateSnapshot("synthetic-entry", 2, null)
        assertEquals(0L, unknown.version); assertEquals(2L, clear.version)
        assertNull(unknown.date); assertNull(clear.date)
        assertThrows(IllegalArgumentException::class.java) { RecordEventDateSnapshot("synthetic-entry", 0,
            RecordEventDate("2026-10-16", "Asia/Shanghai")) }
        val intent = RecordEventDateMutation("synthetic-entry", 1, OriginalExports.hash("合成原文"), 1, null, "synthetic-clear")
        assertNull(intent.date); assertEquals(1L, intent.expectedVersion)
        assertFalse(intent.toString().contains("synthetic-entry"))
        assertFalse(RecordEventDate("2026-10-16", "Asia/Shanghai").toString().contains("2026"))
    }
}
