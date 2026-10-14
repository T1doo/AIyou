package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.time.Instant
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production crypto and SQL on exported v6, never an unchecked plaintext import fake. */
class RecordPackageSqlTest {
    @Test fun authenticatedUnicodeImportUsesFreshIdsEncryptedTagsAiNoneAndPreservesLegacyTables(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val old = f.legacy(); val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val pack = RecordPackageCipherTest.verified(); val id = uuid()
            val result = SqlRecordPackageRepository(f.db, tags).restore(pack, listOf(id), 100)
            assertFalse(result.replayed); assertEquals(listOf(id), result.ids); assertEquals(100L, result.importedAtMillis)
            assertTrue(f.legacy().containsAll(old)); assertEquals(6L, f.db.read("PRAGMA user_version").single().single())
            val row = f.db.read("SELECT * FROM entries WHERE id=?", id).single()
            assertEquals(listOf(id, 1L, -1L, "Asia/Shanghai", "AI_NONE"), row)
            assertEquals(listOf(1L, 0L, null, null), f.db.read("SELECT generation,deleted,connection,consent FROM remote_source_access WHERE entryId=?", id).single())
            assertEquals(pack.records.single().tags, tags.read(id).names); assertEquals(1L, tags.read(id).version)
            val blob = f.db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId=?", id).single().single() as ByteArray
            assertFalse(blob.toString(Charsets.UTF_8).contains("合成"))
            assertEquals(pack.records.single().originalText, f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?", id).single().single())
            val origin = SqlRecordPackageRepository(f.db, tags).origins().getValue(id)
            assertEquals(pack.manifest.backupId, origin.backupId); assertEquals(pack.digest, origin.packageDigest)
            assertEquals(3, origin.sourceRevision); assertEquals(2L, origin.sourceTagVersion); assertEquals(-1L, origin.capturedAtMillis)
            assertFalse(origin.toString().contains(origin.sourceId))
        } finally { tags.wipeKey() }
    }
    @Test fun actualReceiptInsertFailureRollsBackEveryNewRowAndLeavesLegacyEvidence(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val before = f.legacy(); val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            f.db.write("CREATE TEMP TRIGGER reject_package BEFORE INSERT ON local_jobs WHEN NEW.operation='PACKAGE_RESTORE' BEGIN SELECT RAISE(ABORT,'synthetic receipt abort'); END")
            val pack = RecordPackageCipherTest.verified()
            assertThrows(RecordPackageStorageFailure::class.java) { SqlRecordPackageRepository(f.db, tags).restore(pack, listOf(uuid()), 100) }
            assertEquals(before, f.legacy())
        } finally { tags.wipeKey() }
    }
    @Test fun lostCommitAcknowledgementColdReopenReturnsOneStableBatchWithoutFurtherWrites(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val pack = RecordPackageCipherTest.verified(); val id = uuid(); var lost = false
        var tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic lost commit ACK") } }
            assertThrows(RecordPackageStorageFailure::class.java) { SqlRecordPackageRepository(f.db, tags).restore(pack, listOf(id), 100) }
            assertTrue(lost); f.db.afterSql = null; tags.wipeKey(); f.reopen(); tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
            val durable = f.legacy()
            val coldPack = RecordPackageCipherTest.verified(RecordPackageCipherTest.snapshot(pack.manifest.backupId))
            val retry = SqlRecordPackageRepository(f.db, tags).restore(coldPack, listOf(uuid()), 999)
            assertTrue(retry.replayed); assertEquals(listOf(id), retry.ids); assertEquals(100L, retry.importedAtMillis)
            assertEquals(durable, f.legacy()); assertEquals(2, f.db.read("SELECT id FROM entries").size)
        } finally { f.db.afterSql = null; tags.wipeKey() }
    }
    @Test(timeout = 12_000) fun independentSqliteProcessesRaceSameBackupIdAndOnlyOneBatchCommits(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.second().use { other ->
            assertNotEquals(f.db.pid, other.pid)
            val pack = RecordPackageCipherTest.verified(); val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
            val otherTags = SqlManualTagRepository(other, ManualTaskSqlTest.KEY)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            try {
                val repos = listOf(SqlRecordPackageRepository(f.db, tags), SqlRecordPackageRepository(other, otherTags))
                val outcomes = repos.map { repo -> pool.submit<RecordPackageRestoreResult> { go.await(); repo.restore(pack, listOf(uuid()), 100) } }
                go.countDown(); val results = outcomes.map { it.get(3, TimeUnit.SECONDS) }
                assertEquals(results[0].ids, results[1].ids); assertEquals(1, results.count { it.replayed })
                assertEquals(2, f.db.read("SELECT id FROM entries").size)
                assertEquals(1, f.db.read("SELECT id FROM local_jobs WHERE operation='PACKAGE_RESTORE'").size)
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tags.wipeKey(); otherTags.wipeKey() }
        }
    }
    @Test fun replayAfterEditTagsTrashAndFreshAuthorizationNeverOverwritesOrRevives(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlRecordPackageRepository(f.db, tags); val pack = RecordPackageCipherTest.verified(); val id = uuid()
            repo.restore(pack, listOf(id), 100)
            OriginalExportSqlTest.revise(f.db, id, "后来用户新原文", 2)
            tags.replace(ManualTagMutation(id, 1, listOf("后来标签"), "synthetic-new-tags"))
            val authority = RemoteTextTransactions(f.db)
            val source = SourceVersion(id, 2)
            val connection = ProviderConnection("synthetic-provider", 1, "https://provider.example.test/v1/",
                ConnectionProtocol.CHAT_COMPLETIONS, "synthetic-model", SecretReference("synthetic-reference"))
            val now = Instant.parse("2026-10-14T12:00:00Z")
            val grant = ConsentGrant("synthetic-new-grant", 1, source, connection, TextPurpose.ORGANIZATION,
                now.minusSeconds(60), now.plusSeconds(3600))
            assertNull(authority.updateAuthorization(source, authority.readAccess(id)!!.generation,
                ProcessingPolicy.REMOTE_ALLOWED, connection, grant))
            assertEquals(grant, authority.readAccess(id)!!.state.consent)
            val freshGrant = f.legacy(); assertTrue(repo.restore(pack, listOf(uuid()), 999).replayed); assertEquals(freshGrant, f.legacy())
            f.db.write("UPDATE remote_source_access SET generation=generation+1,deleted=1,connection=NULL,consent=NULL WHERE entryId=?", id)
            val trashed = f.legacy(); assertEquals(listOf(id), repo.restore(pack, listOf(uuid()), 999).ids)
            assertEquals(trashed, f.legacy()); assertTrue(repo.origins().containsKey(id))
            assertEquals("后来标签", tags.readWithinForTest(f.db, id).names.single())
        } finally { tags.wipeKey() }
    }
    @Test fun sameIdDifferentAuthenticatedDigestAndInvalidNewIdsFailBeforeAnyWrite(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlRecordPackageRepository(f.db, tags); val pack = RecordPackageCipherTest.verified()
            val id = uuid(); repo.restore(pack, listOf(id), 100); val original = f.legacy()
            val conflicting = RecordPackageCipherTest.verified(RecordPackageCipherTest.snapshot(pack.manifest.backupId, "不同合成原文"))
            assertThrows(RecordPackageConflict::class.java) { repo.restore(conflicting, listOf(uuid()), 101) }
            assertEquals(original, f.legacy())
            val another = RecordPackageCipherTest.verified()
            assertThrows(RecordPackageConflict::class.java) { repo.restore(another, listOf(id), 100) }
            assertThrows(RecordPackageStorageFailure::class.java) { repo.restore(another, listOf("invalid"), 100) }
            assertEquals(original, f.legacy())
        } finally { tags.wipeKey() }
    }
    @Test fun fakePreviewCapabilityCannotReachSqlOrWriteEvenWhenFieldsMatchVerifiedData(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val pack = RecordPackageCipherTest.verified(); val fake = object : VerifiedRecordPackage {
                override val manifest = pack.manifest; override val records = pack.records; override val digest = pack.digest
            }
            var called = false; f.db.beforeSql = { called = true }
            assertThrows(RecordPackageInvalid::class.java) { SqlRecordPackageRepository(f.db, tags).restore(fake, listOf(uuid()), 100) }
            assertFalse(called)
        } finally { f.db.beforeSql = null; tags.wipeKey() }
    }
    @Test fun missingCorruptOrSubstitutedOriginAndMissingGroupFailClosedWithoutNewCopies(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlRecordPackageRepository(f.db, tags); val pack = RecordPackageCipherTest.verified(); val id = uuid()
            repo.restore(pack, listOf(id), 100)
            val originId = "PACKAGE_ORIGIN:" + id; val row = f.db.read("SELECT * FROM local_jobs WHERE id=?", originId).single()
            f.db.write("UPDATE local_jobs SET state='CONFIRMED:AAAA' WHERE id=?", originId)
            val corrupt = f.legacy(); assertThrows(RecordPackageStorageFailure::class.java) { repo.restore(pack, listOf(uuid()), 100) }; assertEquals(corrupt, f.legacy())
            f.db.write("UPDATE local_jobs SET state=? WHERE id=?", row[4], originId)
            f.db.write("DELETE FROM local_jobs WHERE id=?", originId)
            val missing = f.legacy(); assertThrows(RecordPackageStorageFailure::class.java) { repo.restore(pack, listOf(uuid()), 100) }; assertEquals(missing, f.legacy())
            f.db.write("INSERT INTO local_jobs VALUES(?,?,?,?,?)", *row.toTypedArray())
            f.db.write("DELETE FROM local_jobs WHERE id=?", "PACKAGE_RESTORE:" + pack.manifest.backupId)
            val missingGroup = f.legacy(); assertThrows(RecordPackageConflict::class.java) { repo.restore(pack, listOf(uuid()), 100) }; assertEquals(missingGroup, f.legacy())
        } finally { tags.wipeKey() }
    }
    @Test(timeout = 12_000) fun closeDrainsAnImportBeforeOwnerKeyRetirementAndLateImportIsRejected(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val gateway = DrainingRecordsSqlDatabase(f.db); val tags = SqlManualTagRepository(gateway, ManualTaskSqlTest.KEY)
        val repo = SqlRecordPackageRepository(gateway, tags); val pack = RecordPackageCipherTest.verified()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val retired = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            f.db.beforeSql = { if (it.startsWith("INSERT INTO entries")) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) } }
            val importing = pool.submit<RecordPackageRestoreResult> { repo.restore(pack, listOf(uuid()), 100) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val closing = pool.submit { gateway.stopAndAwaitIdle(); tags.wipeKey(); retired.countDown() }
            assertFalse(retired.await(40, TimeUnit.MILLISECONDS)); release.countDown()
            assertFalse(importing.get(2, TimeUnit.SECONDS).replayed); closing.get(2, TimeUnit.SECONDS)
            assertThrows(RecordPackageStorageFailure::class.java) { repo.restore(pack, listOf(uuid()), 100) }
        } finally { release.countDown(); f.db.beforeSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tags.wipeKey() }
    }
    private fun uuid() = UUID.randomUUID().toString()
    private fun SqlManualTagRepository.readWithinForTest(db: PythonSqliteDatabase, id: String) =
        db.transaction { readWithin(it, id, true) }
}
