package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Real product SQL/AEAD on file-backed SQLite; no Android or device execution claim. */
class ManualTaskDateSqlTest {
    @Test fun frozenIndependentV1BytesReadWithoutRewriteThenExplicitlyUpgrade(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        // Independently generated Python cryptography AESGCM fixture of the frozen pre-date v1 wire.
        val legacy = Base64.getDecoder().decode(LEGACY)
        f.db.write("INSERT INTO manual_tasks VALUES(?,?,?,?)", "legacy", 1, "legacy-create", legacy)
        val original = f.legacy()
        f.reopen()
        val old = f.store.snapshots().single()
        assertEquals("legacy literal 明天三点", old.title); assertNull(old.schedule); assertArrayEquals(legacy, f.blob("legacy"))
        assertEquals(old, f.store.create(ManualTaskCreate("legacy", old.title, 55, "Asia/Shanghai", "legacy-create")))
        assertArrayEquals(legacy, f.blob("legacy"))
        val due = date("2042-11-19", zone = "Pacific/Chatham")
        val saved = f.store.reschedule(request("legacy", 1, ManualTaskDateOperation.SET, due))
        assertEquals(2L, saved.version); assertEquals(5.toByte(), f.blob("legacy")[0])
        assertNull(saved.schedule!!.time); assertNull(saved.schedule!!.instant())
        f.reopen(); assertEquals(saved, f.store.snapshots().single()); assertEquals(original, f.legacy())
        val marker = "2042-11-19".toByteArray()
        java.nio.file.Files.list(f.directory).use { files -> files.filter { java.nio.file.Files.isRegularFile(it) }.forEach {
            val bytes = java.nio.file.Files.readAllBytes(it)
            assertFalse(bytes.indices.any { at -> at + marker.size <= bytes.size && marker.indices.all { n -> bytes[at + n] == marker[n] } })
        } }
    }
    @Test fun authenticatedTrailingBytesRemainRejectedForBothWireVersions(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val cipher = ManualTaskCipher(ManualTaskSqlTest.KEY)
        try {
            val legacy = Base64.getDecoder().decode(LEGACY)
            val forgedV1 = authenticatedTail(legacy, "legacy", 1, "legacy-create")
            assertThrows(IllegalArgumentException::class.java) { cipher.open("legacy", 1, "legacy-create", forgedV1) }
            val created = f.store.create(ManualTaskSqlTest.create("one"))
            val blob = f.blob("one")
            val forgedV2 = authenticatedTail(blob, "one", 1, "create-one")
            assertThrows(IllegalArgumentException::class.java) { cipher.open("one", 1, "create-one", forgedV2) }
            assertThrows(javax.crypto.AEADBadTagException::class.java) { cipher.open("one", 1, "create-one", blob.copyOf().also { it[0] = 3 }) }
            assertThrows(javax.crypto.AEADBadTagException::class.java) { cipher.open("one", 1, "create-one", blob.copyOf().also { it[0] = 4 }) }
            assertThrows(javax.crypto.AEADBadTagException::class.java) { cipher.open("one", 1, "create-one", blob.copyOf().also { it[0] = 2 }) }
            assertThrows(IllegalArgumentException::class.java) { cipher.open("one", 1, "create-one", blob.copyOf().also { it[0] = 6 }) }
            assertThrows(Exception::class.java) { cipher.open("one", 1, "create-one", blob.copyOf().also { it[0] = 1 }) }
            f.db.write("UPDATE manual_tasks SET version=version+1,ciphertext=? WHERE id=?", forgedV2, created.id)
            assertThrows(ManualTaskStorageFailure::class.java) { f.store.snapshots() }
            f.reopen(); assertArrayEquals(forgedV2, f.blob("one"))
        } finally { cipher.wipe() }
    }
    @Test fun explicitDateOnlyAndTimedCreationKeepPrecisionZoneAndColdReopen(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val day = date("2026-10-15")
        val timed = date("2026-10-15", "09:30", "Europe/Paris")
        val one = f.store.create(ManualTaskCreate("one", "明天三点只是标题", 55, "UTC", "a", day))
        val two = f.store.create(ManualTaskCreate("two", "合成定时计划", 55, "UTC", "b", timed))
        val none = f.store.create(ManualTaskSqlTest.create("none", "明天三点"))
        assertNull(none.schedule); assertNull(one.schedule!!.instant())
        assertEquals(Instant.parse("2026-10-15T07:30:00Z"), two.schedule!!.instant())
        assertEquals("UTC", two.creationTimeZone); assertEquals("Europe/Paris", two.schedule!!.timeZone)
        f.reopen(); assertEquals(listOf(one, two, none).toSet(), f.store.snapshots().toSet())
    }
    @Test fun invalidDatesTimeWithoutDateAndDstGapNeverBecomeImplicitInstants() {
        fun invalid(input: ManualTaskDateInput, reason: ManualTaskDateError) =
            assertEquals(reason, assertThrows(ManualTaskDateInvalid::class.java) { input.parse() }.reason)
        invalid(ManualTaskDateInput("", "09:00", "UTC"), ManualTaskDateError.TIME_WITHOUT_DATE)
        invalid(ManualTaskDateInput("2026-02-29"), ManualTaskDateError.DATE)
        invalid(ManualTaskDateInput("2026-10-15", "24:00"), ManualTaskDateError.TIME)
        invalid(ManualTaskDateInput("2026-10-15", "12:00", "+08:00"), ManualTaskDateError.ZONE)
        invalid(ManualTaskDateInput("2026-03-08", "02:30", "America/New_York"), ManualTaskDateError.GAP)
        invalid(ManualTaskDateInput("2026-11-01", "01:30", "America/New_York"), ManualTaskDateError.OVERLAP)
    }
    @Test fun explicitDstOverlapChoicesHaveDifferentAuthenticatedOffsetsAcrossReopen(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val first = date("2026-11-01", "01:30", "America/New_York", ManualTaskOccurrence.FIRST)
        val second = date("2026-11-01", "01:30", "America/New_York", ManualTaskOccurrence.SECOND)
        assertEquals(3600L, second.instant()!!.epochSecond - first.instant()!!.epochSecond)
        val saved = f.store.create(ManualTaskCreate("one", "合成重复时刻", 55, "UTC", "create-one", second))
        f.reopen(); assertEquals(second, f.store.snapshots().single().schedule)
        assertEquals(ManualTaskOccurrence.SECOND, ManualTaskDateInput.from(second, "UTC").occurrence)
        assertEquals(saved, f.store.create(ManualTaskCreate("one", saved.title, 55, "UTC", "create-one", second)))
    }
    @Test fun setModifyPostponeClearAndStatusKeepExactReceiptsAndMonotoneAudit(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.store.create(ManualTaskSqlTest.create("one")); val initial = f.blob("one")
        val set = request("one", 1, ManualTaskDateOperation.SET, date("2026-10-15"), at = 1)
        val a = f.store.reschedule(set); assertEquals(55L, a.updatedAtMillis); val aBytes = f.blob("one")
        assertFalse(initial.contentEquals(aBytes)); assertEquals(a, f.store.reschedule(set)); assertArrayEquals(aBytes, f.blob("one"))
        val b = f.store.reschedule(request("one", 2, ManualTaskDateOperation.SET, date("2026-10-15", "12:00")))
        val later = request("one", 3, ManualTaskDateOperation.POSTPONE, date("2026-10-16", "12:00"))
        val c = f.store.reschedule(later); assertEquals(c, f.store.reschedule(later))
        val clear = request("one", 4, ManualTaskDateOperation.CLEAR, null)
        val d = f.store.reschedule(clear); val bytes = f.blob("one")
        assertEquals(5L, d.version); assertNull(d.schedule); assertEquals(d, f.store.reschedule(clear)); assertArrayEquals(bytes, f.blob("one"))
        assertEquals(a.title, b.title); assertEquals(a.creationTimeZone, c.creationTimeZone)
        assertThrows(ManualTaskConflict::class.java) { f.store.reschedule(set) }
        val terminal = f.store.change(ManualTaskSqlTest.change("one", 5, ManualTaskStatus.COMPLETED))
        assertThrows(ManualTaskConflict::class.java) { f.store.reschedule(request("one", 6, ManualTaskDateOperation.SET, date("2027-01-01"))) }
        f.reopen(); assertEquals(terminal, f.store.snapshots().single())
    }
    @Test fun sameMutationWithChangedDateOperationOrTimestampCannotReplay(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.store.create(ManualTaskSqlTest.create("one"))
        val req = request("one", 1, ManualTaskDateOperation.SET, date("2026-10-15"))
        f.store.reschedule(req); val before = f.blob("one")
        for (bad in listOf(
            request("one", 1, ManualTaskDateOperation.SET, date("2026-10-16"), req.mutationId),
            request("one", 1, ManualTaskDateOperation.POSTPONE, req.schedule, req.mutationId),
            request("one", 1, ManualTaskDateOperation.SET, req.schedule, req.mutationId, 100))) {
            assertThrows(ManualTaskConflict::class.java) { f.store.reschedule(bad) }
        }
        assertThrows(ManualTaskConflict::class.java) { f.store.change(ManualTaskMutation("one", 1, ManualTaskStatus.CANCELLED, 99, req.mutationId)) }
        assertArrayEquals(before, f.blob("one"))
    }
    @Test fun postponementIsStrictlyLaterAndCannotSilentlyChangePrecisionOrZone(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val old = date("2026-10-15"); f.store.create(ManualTaskCreate("one", "合成日期", 55, "UTC", "a", old))
        val before = f.blob("one")
        for (due in listOf(old, date("2026-10-14"), date("2026-10-16", "09:00"), date("2026-10-16", zone = "Europe/Paris"))) {
            assertThrows(ManualTaskDateInvalid::class.java) { f.store.reschedule(request("one", 1, ManualTaskDateOperation.POSTPONE, due)) }
        }
        assertArrayEquals(before, f.blob("one"))
        assertEquals(2L, f.store.reschedule(request("one", 1, ManualTaskDateOperation.POSTPONE, date("2026-10-16"))).version)
    }
    @Test fun actualSqlAbortAndLostCommitAckPreserveAtomicityAndOneStableVersion(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.store.create(ManualTaskSqlTest.create("one")); val before = f.blob("one")
        val req = request("one", 1, ManualTaskDateOperation.SET, date("2026-10-15"))
        f.db.write("CREATE TRIGGER synthetic_date_abort AFTER UPDATE ON manual_tasks BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.reschedule(req) }; assertArrayEquals(before, f.blob("one"))
        f.db.write("DROP TRIGGER synthetic_date_abort")
        var lost = false
        f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic lost ACK") } }
        try { assertThrows(ManualTaskStorageFailure::class.java) { f.store.reschedule(req) } } finally { f.db.afterSql = null }
        assertTrue(lost); f.reopen(); val bytes = f.blob("one")
        assertEquals(2L, f.store.reschedule(req).version); assertArrayEquals(bytes, f.blob("one"))
    }
    @Test fun independentScheduleAndTerminalCasHaveOnlyOneWinnerAndNeverReopen(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        for (status in listOf(ManualTaskStatus.COMPLETED, ManualTaskStatus.CANCELLED)) {
            val id = status.name; f.store.create(ManualTaskCreate(id, "同名任务", 55, "UTC", "a-" + id, date("2026-10-15")))
            val second = f.second(); val other = SqlManualTaskRepository(second, ManualTaskSqlTest.KEY)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            try {
                val operations = listOf<() -> Unit>(
                    { f.store.reschedule(request(id, 1, ManualTaskDateOperation.POSTPONE, date("2026-10-16"))); Unit },
                    { other.change(ManualTaskSqlTest.change(id, 1, status)); Unit })
                val futures = operations.map { action -> pool.submit<String> { go.await()
                    try { action(); "saved" } catch (_: ManualTaskConflict) { "conflict" } } }
                go.countDown(); assertEquals(listOf("conflict", "saved"), futures.map { it.get(3, TimeUnit.SECONDS) }.sorted())
                val current = f.store.snapshots().single { it.id == id }; assertEquals(2L, current.version)
                if (current.status != ManualTaskStatus.OPEN)
                    assertThrows(ManualTaskConflict::class.java) { f.store.reschedule(request(id, 2, ManualTaskDateOperation.SET, date("2028-01-01"))) }
                else { f.store.change(ManualTaskSqlTest.change(id, 2, status))
                    assertThrows(ManualTaskConflict::class.java) { f.store.reschedule(request(id, 3, ManualTaskDateOperation.SET, date("2028-01-01"))) } }
            } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); other.wipeKey(); second.close() }
        }
    }
    @Test fun groupsUseChosenZoneAndDatePrecisionAndTerminalStatusWins() {
        val now = Instant.parse("2026-10-14T16:00:00Z")
        fun task(due: ManualTaskSchedule?) = ManualTaskSnapshot("one", 1, "明天三点", ManualTaskStatus.OPEN, 55, "UTC", 55, due)
        assertEquals(ManualTaskGroup.UNDATED, ManualTaskDates.group(task(null), now))
        assertEquals(ManualTaskGroup.TODAY, ManualTaskDates.group(task(date("2026-10-15", zone = "Asia/Shanghai")), now))
        assertEquals(ManualTaskGroup.TODAY, ManualTaskDates.group(task(date("2026-10-14", zone = "America/Los_Angeles")), now))
        assertEquals(ManualTaskGroup.PAST, ManualTaskDates.group(task(date("2026-10-14", zone = "Asia/Shanghai")), now))
        assertEquals(ManualTaskGroup.UPCOMING, ManualTaskDates.group(task(date("2026-10-16", zone = "Asia/Shanghai")), now))
        val exact = task(date("2026-10-15", "00:00", "Asia/Shanghai"))
        assertEquals(ManualTaskGroup.TODAY, ManualTaskDates.group(exact, now))
        assertEquals(ManualTaskGroup.PAST, ManualTaskDates.group(exact, now.plusMillis(1)))
        assertEquals(ManualTaskGroup.COMPLETED, ManualTaskDates.group(exact.copy(status = ManualTaskStatus.COMPLETED), now.plusMillis(1)))
        assertEquals(ManualTaskGroup.CANCELLED, ManualTaskDates.group(exact.copy(status = ManualTaskStatus.CANCELLED), now))
    }
    companion object {
        private const val LEGACY = "AQcICQoLDA0ODxAREu8vPI4ALiRlSX43LPnuDZ9oqck/nC10hvoNtn/L3IrytSFCEJsE3a6tKu8HJ7NoKlnXgS3Q8vdz1/adt/XrnuWUbzbJgCv/MyXnthhd+fXlO+8EuJXM14Xm0rUVwgIyXw=="
        fun date(day: String, time: String = "", zone: String = "UTC", occurrence: ManualTaskOccurrence? = null) =
            checkNotNull(ManualTaskDateInput(day, time, zone, occurrence).parse())
        fun request(id: String, version: Long, kind: ManualTaskDateOperation, due: ManualTaskSchedule?,
            mutation: String = "date-" + version + "-" + kind.name, at: Long = 99) =
            ManualTaskScheduleMutation(id, version, kind, due, at, mutation)
        /** Independent AEAD forging helper: valid authentication, invalid strict-EOF payload. */
        private fun authenticatedTail(raw: ByteArray, id: String, version: Long, mutation: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(ManualTaskSqlTest.KEY, "HmacSHA256"))
            val key = mac.doFinal("com.t1doo.aiyou.manual-tasks.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
            val aad = ByteArrayOutputStream().also { b -> DataOutputStream(b).use {
                it.writeUTF("AIyou manual tasks v" + raw[0]); it.writeUTF(id); it.writeLong(version); it.writeUTF(mutation)
            } }.toByteArray()
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(1, 13))); c.updateAAD(aad)
                val plain = c.doFinal(raw, 13, raw.size - 13)
                val nonce = ByteArray(12) { (it + 89).toByte() }
                try {
                    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); c.updateAAD(aad)
                    return byteArrayOf(raw[0]) + nonce + c.doFinal(plain + byteArrayOf(7))
                } finally { plain.fill(0) }
            } finally { key.fill(0) }
        }
    }
}
