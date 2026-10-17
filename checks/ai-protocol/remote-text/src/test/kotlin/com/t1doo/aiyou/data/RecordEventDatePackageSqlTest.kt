package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordEventDatePackageSqlTest {
    @Test(timeout=20_000) fun currentAndHistoryNewCopiesRestoreEventDayAndKeepCaptureAndRevisionTimes(): Unit = runBlocking {
        for (fullHistory in listOf(false, true)) fixture().use { source -> fixture().use { target ->
            val id = seed(source, "合成初版")
            source.db.transaction { sql ->
                sql.execute("INSERT INTO entry_revisions VALUES(?,2,?,?,17)", listOf(id, TEXT, OriginalExports.hash(TEXT)))
                sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?", listOf(id))
            }
            set(source, id, 2, 0, DATE)
            val s = ports(source); val snapshot = s.originals.capture(listOf(SourceVersion(id, 2)), uuid(), 50)
            assertEquals(DATE, snapshot.records.single().eventDate.date)
            val bytes = export(s.media, if (fullHistory) s.media.previewHistory(snapshot) else s.media.preview(snapshot))
            val newId = uuid(); val t = ports(target); val restored = restore(t.media, bytes, newId)
            assertFalse(restored.replayed); assertEquals(DATE, SqlRecordEventDateRepository(target.gateway).read(newId).date)
            assertEquals(1L, SqlRecordEventDateRepository(target.gateway).read(newId).version)
            assertEquals(listOf(5L, "UTC", "AI_NONE"), target.db.read("SELECT capturedAtMillis,captureTimeZone,privacyPolicy FROM entries WHERE id=?", newId).single())
            assertEquals(if (fullHistory) listOf(5L, 17L) else listOf(100L),
                target.db.read("SELECT createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision", newId).map { it.single() })
            target.reopen()
            assertEquals(DATE, SqlRecordEventDateRepository(target.gateway).read(newId).date)
        }}
    }

    @Test(timeout=20_000) fun lostCommitAckColdReplayNeverResetsLaterEventCorrectionOrClear(): Unit = runBlocking {
        fixture().use { source -> fixture().use { target ->
            val id = seed(source, TEXT); set(source, id, 1, 0, DATE)
            val s = ports(source); val snapshot = s.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50)
            val bytes = export(s.media, s.media.preview(snapshot)); val newId = uuid(); var final = false; var lost = false
            target.db.afterSql = { sql ->
                if (sql.startsWith("INSERT INTO record_package_receipts") && sql.contains("'PACKAGE_RESTORE'")) final = true
                if (sql == "COMMIT" && final && !lost) { lost = true; error("Synthetic event restore ACK loss") }
            }
            try { restore(ports(target).media, bytes, newId); fail("Lost ACK") } catch (_: RecordPackageStorageFailure) { assertTrue(lost) }
            target.db.afterSql = null; target.reopen()
            val corrected = RecordEventDate("2025-01-02", "Asia/Shanghai")
            set(target, newId, 1, 1, corrected)
            val beforeCorrectionReplay = eventRows(target)
            assertTrue(restore(ports(target).media, bytes, uuid()).replayed)
            assertEquals(beforeCorrectionReplay, eventRows(target)); assertEquals(corrected, SqlRecordEventDateRepository(target.gateway).read(newId).date)
            set(target, newId, 1, 2, null); target.reopen()
            val beforeClearReplay = eventRows(target)
            assertTrue(restore(ports(target).media, bytes, uuid()).replayed)
            assertEquals(beforeClearReplay, eventRows(target)); assertNull(SqlRecordEventDateRepository(target.gateway).read(newId).date)
            assertEquals(1, target.db.read("SELECT id FROM entries").size)
        }}
    }

    @Test(timeout=20_000) fun eventRowFailureRollsBackWholeRestoreAndLegacyTargetRefusesV5(): Unit = runBlocking {
        fixture().use { source ->
            val id = seed(source, TEXT); set(source, id, 1, 0, DATE)
            val s = ports(source); val snapshot = s.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50)
            val bytes = export(s.media, s.media.preview(snapshot))
            fixture().use { target ->
                val plannedId = uuid()
                target.db.write("CREATE TEMP TRIGGER refuse_event BEFORE INSERT ON entry_event_dates BEGIN SELECT RAISE(ABORT,'synthetic event failure'); END")
                try { restore(ports(target).media, bytes, plannedId); fail("Expected rollback") } catch (_: RecordPackageStorageFailure) {}
                assertRolledBack(target, includesEventTable = true)
                assertEquals(1, target.db.read("SELECT id FROM record_package_receipts").size)
                assertJournal(target, snapshot.id, plannedId, "PENDING")
                ports(target).media.recover()
                assertRolledBack(target, includesEventTable = true)
                assertEquals(1, target.db.read("SELECT id FROM record_package_receipts").size)
                assertJournal(target, snapshot.id, plannedId, "ABORTED")
                // Closing this owned connection also retires its synthetic TEMP trigger.
                target.reopen()
                val differentRequestedId = uuid(); assertNotEquals(plannedId, differentRequestedId)
                val result = restore(ports(target).media, bytes, differentRequestedId)
                assertFalse(result.replayed); assertEquals(listOf(plannedId), result.ids); assertEquals(100L, result.importedAtMillis)
                assertEquals(DATE, SqlRecordEventDateRepository(target.gateway).read(plannedId).date)
                assertEquals(1L, SqlRecordEventDateRepository(target.gateway).read(plannedId).version)
                assertEquals(listOf(listOf(plannedId)), target.db.read("SELECT id FROM entries"))
                assertEquals(TEXT, target.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?", plannedId).single().single())
                assertEquals(1, target.db.read("SELECT id FROM record_package_receipts WHERE operation='PACKAGE_RESTORE'").size)
                assertEquals(1, target.db.read("SELECT id FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN'").size)
                assertJournal(target, snapshot.id, plannedId, "DONE")
                val receipts = target.db.read("SELECT * FROM record_package_receipts ORDER BY id"); val dates = eventRows(target)
                val replay = restore(ports(target).media, bytes, uuid())
                assertTrue(replay.replayed); assertEquals(listOf(plannedId), replay.ids)
                assertEquals(receipts, target.db.read("SELECT * FROM record_package_receipts ORDER BY id")); assertEquals(dates, eventRows(target))
                assertEquals(1, target.db.read("SELECT id FROM entries").size)
            }
            PermanentErasureFixture().use { legacy ->
                val plannedId = uuid()
                try { restore(ports(legacy).media, bytes, plannedId); fail("Legacy schema must not lose event date") } catch (_: RecordPackageStorageFailure) {}
                assertRolledBack(legacy, includesEventTable = false)
                assertEquals(1, legacy.db.read("SELECT id FROM record_package_receipts").size)
                assertJournal(legacy, snapshot.id, plannedId, "PENDING")
            }
        }
    }

    @Test(timeout=20_000) fun eventChangeOrClearInvalidatesOriginalAndMediaPreviews(): Unit = runBlocking {
        fixture().use { source ->
            val id = seed(source, TEXT); val p = ports(source)
            val unknown = p.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50); val media = p.media.preview(unknown)
            set(source, id, 1, 0, DATE)
            assertFalse(p.originals.isCurrent(unknown)); assertFalse(p.media.isCurrent(media))
            val known = p.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50); val withDate = p.media.preview(known)
            set(source, id, 1, 1, null)
            assertFalse(p.originals.isCurrent(known)); assertFalse(p.media.isCurrent(withDate))
            assertEquals(TEXT, source.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?", id).single().single())
            assertEquals(5L, source.db.read("SELECT capturedAtMillis FROM entries WHERE id=?", id).single().single())
        }
    }

    @Test(timeout=20_000) fun erasureReceiptRemainsAuthoritativeAndV5ReplayNeverRevivesEventMetadata(): Unit = runBlocking {
        fixture().use { source -> fixture().use { target ->
            val id = seed(source, TEXT); set(source, id, 1, 0, DATE)
            val s = ports(source); val bytes = export(s.media, s.media.preview(s.originals.capture(listOf(SourceVersion(id, 1)), uuid(), 50)))
            val newId = uuid(); restore(ports(target).media, bytes, newId)
            assertTrue(target.erase.erase(target.intent(newId)).complete); target.reopen()
            val result = restore(ports(target).media, bytes, uuid())
            assertTrue(result.replayed); assertEquals(1, result.deletedCount)
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty()); assertTrue(eventRows(target).isEmpty())
            assertTrue(ports(target).packages.origins().isEmpty())
        }}
    }

    private data class Ports(val originals: SqlOriginalExportRepository, val media: SqlRecordMediaPackageRepository, val packages: SqlRecordPackageRepository)
    private fun ports(f: PermanentErasureFixture): Ports {
        val originals = SqlOriginalExportRepository(f.gateway, f.tags)
        val packages = SqlRecordPackageRepository(f.gateway, f.tags)
        return Ports(originals, SqlRecordMediaPackageRepository(f.gateway, originals, f.media, packages), packages)
    }
    private fun fixture(): PermanentErasureFixture = PermanentErasureFixture().also { f -> f.db.transaction { sql ->
        RecordFavoriteSqlSchema.migration8To9.forEach { sql.execute(it) }
        LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) }
        RecordEventDateSqlSchema.migration10To11.forEach { sql.execute(it) }
        sql.execute("PRAGMA user_version=11")
    }}
    /** This v11-only fixture names columns; the frozen v8 fixture intentionally keeps its own schema contract. */
    private fun seed(f: PermanentErasureFixture, text: String): String {
        val id = uuid()
        f.db.transaction { sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')", listOf(id))
            sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,1,?,?,5)", listOf(id,text,OriginalExports.hash(text)))
            sql.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,1,0,NULL,NULL)", listOf(id))
            sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'LOCAL_INDEX','PENDING')", listOf("INDEX:"+id,id))
        }
        return id
    }
    private fun set(f: PermanentErasureFixture, id: String, revision: Int, expected: Long, date: RecordEventDate?) =
        SqlRecordEventDateRepository(f.gateway).set(RecordEventDateMutation(id, revision,
            f.db.read("SELECT contentHash FROM entry_revisions WHERE entryId=? AND revision=?", id, revision).single().single() as String,
            expected, date, uuid(),SqlLocalProcessingRepository(f.gateway).versions().getValue(id).generation))
    private fun eventRows(f: PermanentErasureFixture) = f.db.read("SELECT * FROM entry_event_dates ORDER BY entryId")
    private fun assertRolledBack(f: PermanentErasureFixture, includesEventTable: Boolean) {
        val tables = listOf("entries", "entry_revisions", "entry_manual_tags", "remote_source_access", "local_jobs", "attachments") +
            if (includesEventTable) listOf("entry_event_dates") else emptyList()
        for (table in tables) assertTrue(table, f.db.read("SELECT * FROM $table").isEmpty())
        assertTrue(f.db.read("SELECT id FROM record_package_receipts WHERE operation IN ('PACKAGE_RESTORE','PACKAGE_ORIGIN')").isEmpty())
    }
    private fun assertJournal(f: PermanentErasureFixture, backupId: String, plannedId: String, phase: String) {
        val row = f.db.read("SELECT id,entryId,sourceRevision,operation,state FROM record_package_receipts WHERE operation='PACKAGE_MEDIA_INTENT'").single()
        assertEquals(listOf("PACKAGE_MEDIA:" + backupId, plannedId, 1L, "PACKAGE_MEDIA_INTENT"), row.take(4))
        val state = row[4] as String; val raw = Base64.getDecoder().decode(state)
        try {
            assertEquals(state, Base64.getEncoder().encodeToString(raw))
            val reader = PackageReader(raw)
            assertEquals(2, reader.i32()); assertEquals(backupId, reader.string(36))
            RecordPackages.digest(packageHex(reader.raw(32)))
            assertEquals(100L, reader.i64()); assertEquals(phase, reader.string(16))
            assertEquals(listOf(plannedId), reader.strings(20, 36)); assertEquals(0, reader.i32()); reader.end()
        } finally { raw.fill(0) }
    }
    private suspend fun export(media: SqlRecordMediaPackageRepository, preview: RecordMediaPreview): ByteArray {
        val prepared = media.seal(preview, password())
        return try { ByteArrayOutputStream().use { out -> prepared.writeTo(out) { true }; out.toByteArray() } } finally { prepared.release() }
    }
    private suspend fun restore(media: SqlRecordMediaPackageRepository, bytes: ByteArray, id: String): RecordPackageRestoreResult {
        val pack = media.open({ bytes.inputStream() }, password())
        return try { media.restore(pack, listOf(id), 100) } finally { pack.release() }
    }
    companion object {
        private const val TEXT = "合成事件日期原文"
        private val DATE = RecordEventDate("2024-02-29", "America/New_York")
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic event SQL recovery 42".toCharArray()
    }
}
