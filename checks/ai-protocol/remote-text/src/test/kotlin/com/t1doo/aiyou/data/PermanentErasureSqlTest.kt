package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production transactions/AEAD/files on actual FK-enabled SQLite. These are host tests, not Room. */
class PermanentErasureSqlTest {
    @Test fun durableSqlCreatedProofAllowsExplicitFailedAttachmentRetryAfterColdOwnerWithoutWeakeningUnknownFileRefusal(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); val bytes = "合成完整附件".toByteArray(); val request = f.attachment(id, bytes)
            try { f.media.save(request) { ByteArrayInputStream(bytes.dropLast(1).toByteArray()) }; fail("Expected failed copy") }
            catch (_: AttachmentIntegrityFailure) { }
            assertEquals(1, f.db.read("SELECT identity FROM owned_file_creations WHERE entryId=? AND state='CREATED'", id).size)
            f.reopen(); f.media.recover()
            assertEquals(AttachmentState.FAILED, f.media.list(id, 1).single().state)
            var opens = 0
            val saved = f.media.save(request) { opens++; ByteArrayInputStream(bytes) }
            assertEquals(AttachmentState.READY, saved.state); assertEquals(request, saved.intent); assertEquals(1, opens)
            assertArrayEquals(bytes, f.media.read(request.id))
            assertTrue(f.db.read("SELECT * FROM owned_file_creations WHERE entryId=?", id).isEmpty())
            assertFalse(File(f.mediaRoot, request.id + ".tmp").exists())
            val unknown = f.attachment(id, bytes)
            val target = File(f.mediaRoot, unknown.id + ".tmp")
            val foreignBytes = byteArrayOf(7, 8, 9); target.writeBytes(foreignBytes)
            try { f.media.save(unknown) { opens++; ByteArrayInputStream(bytes) }; fail("Unknown occupied file must be retained") }
            catch (_: IllegalStateException) { }
            assertTrue(f.db.read("SELECT * FROM owned_file_creations WHERE name=?", target.name).isEmpty())
            f.reopen(); f.media.recover()
            assertThrows(AttachmentIntegrityFailure::class.java) { runBlocking { f.media.save(unknown) { opens++; ByteArrayInputStream(bytes) } } }
            assertEquals(1, opens); assertArrayEquals(foreignBytes, target.readBytes())
        }
    }
    @Test fun completeErasesAllHistoryDerivedTagsMediaAndEditsPreservingIndependentData(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); val other = f.entry(text="其他原文，保留")
            f.tasks.create(ManualTaskCreate("independent","合成独立待办",5,"UTC","task-create"))
            f.tags.replace(ManualTagMutation(id,0,listOf("合成私密标签"),"tag-create"))
            val bytes = "合成私密媒体".toByteArray(); val media = f.attachment(id,bytes)
            f.media.save(media) { ByteArrayInputStream(bytes) }; f.edits.write(null,f.draft(id))
            OriginalExportSqlTest.revise(f.db,id,"合成第二版",2)
            val second = f.attachment(id,bytes,2); f.media.save(second) { ByteArrayInputStream(bytes) }
            val index = SqlLocalSearchRepository(f.gateway,f.tags)
            repeat(4) { index.batch() }
            val task = f.tasks.snapshots(); val otherRows = f.db.read("SELECT * FROM entry_revisions WHERE entryId=?",other)
            val intent = f.intent(id); val epoch = f.db.read("SELECT epoch FROM local_search_epoch").single().single() as Long
            assertTrue(f.erase.erase(intent).complete)
            assertTrue(f.db.read("PRAGMA foreign_key_check").isEmpty())
            assertEquals(task.map { it.title },f.tasks.snapshots().map { it.title })
            assertEquals(otherRows,f.db.read("SELECT * FROM entry_revisions WHERE entryId=?",other))
            for (table in listOf("entries","entry_revisions","attachments","entry_manual_tags","local_jobs","remote_source_access","text_annotations","remote_text_jobs","local_search_documents","local_search_fts","owned_file_creations")) {
                assertTrue(table,f.db.read("SELECT * FROM $table WHERE ${if(table=="entries") "id" else "entryId"}=?",id).isEmpty())
            }
            assertTrue((f.db.read("SELECT epoch FROM local_search_epoch").single().single() as Long) > epoch)
            assertEquals(listOf(".store.lock"),f.mediaRoot.list()!!.toList())
            assertEquals(listOf(".store.lock"),f.editRoot.list()!!.toList())
            f.reopen(); assertTrue(f.erase.erase(intent).complete)
            var opens = 0
            assertThrows(AttachmentConflict::class.java) { runBlocking { f.media.save(media) { opens++; ByteArrayInputStream(bytes) } } }
            assertEquals(0,opens); assertNull(f.edits.read(id))
            assertThrows(OriginalEditDraftConflict::class.java) { runBlocking { f.edits.write(null,f.draft(id)) } }
            assertThrows(RecycleConflict::class.java) { f.bin.apply(RecycleMutation(intent.source,intent.generation,true,"late-restore")) }
            val reuse=assertThrows(SqliteBridgeFailure::class.java) { f.entry(id) }
            assertTrue(reuse.message!!.contains("SQLITE_CONSTRAINT_TRIGGER"))
            assertEquals("",f.db.read("SELECT plan FROM permanent_erasure_receipts WHERE entryId=?",id).single().single())
        }
    }
    @Test fun activeStaleAndConflictingConfirmationsNeverPrepareOrTouchFiles(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry()
            assertThrows(PermanentErasureConflict::class.java) { runBlocking { f.erase.erase(PermanentErasureIntent(SourceVersion(id,1),1,"active")) } }
            val intent = f.intent(id)
            assertThrows(PermanentErasureConflict::class.java) { runBlocking { f.erase.erase(PermanentErasureIntent(SourceVersion(id,2),intent.generation,"wrong-revision")) } }
            assertThrows(PermanentErasureConflict::class.java) { runBlocking { f.erase.erase(PermanentErasureIntent(intent.source,intent.generation+1,"wrong-generation")) } }
            assertNull(f.erase.read(id)); assertFalse(f.mediaRoot.exists()); assertFalse(f.editRoot.exists())
            f.checkpoint = { if (it=="prepared") error("Synthetic prepared interruption") }
            assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(intent) } }
            assertThrows(PermanentErasureConflict::class.java) { runBlocking { f.erase.erase(PermanentErasureIntent(intent.source,intent.generation,"different-confirmation")) } }
            assertEquals(PermanentErasurePhase.PREPARED,f.erase.read(id)!!.phase)
        }
    }
    @Test(timeout=12000) fun realHeldWriterLockAcrossIndependentSqliteConnectionsLeavesPreparedUntilWriterExits(): Unit = runBlocking {
        PermanentErasureFixture().use { f -> f.second().use { other ->
            assertNotEquals(f.db.pid,other.pid)
            val id = f.entry(); val bytes = "synthetic media".toByteArray(); val media = f.attachment(id,bytes)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
            f.mediaCheckpoint = { if (it=="intent") { entered.countDown(); check(release.await(3,TimeUnit.SECONDS)) } }
            val writer = pool.submit<Unit> { runBlocking { try { f.media.save(media) { ByteArrayInputStream(bytes) }; fail("Fenced writer published") } catch (_: AttachmentConflict) {} catch (_: IllegalStateException) {} } }
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS)); val intent = f.intent(id)
                val gateway = DrainingRecordsSqlDatabase(other); val tags = SqlManualTagRepository(gateway,PermanentErasureFixture.KEY)
                val hooks = SqlErasureFileHooks(gateway)
                val otherMedia = EncryptedAttachmentStore(f.mediaRoot,PermanentErasureFixture.Ledger(gateway),PermanentErasureFixture.KEY.copyOf()).also { it.installErasureHooks(hooks) }
                val otherEdits = EncryptedOriginalEditStore(f.editRoot,PermanentErasureFixture.KEY.copyOf()).also { it.installErasureHooks(hooks) }
                try {
                    val erase = SqlPermanentErasureRepository(gateway,otherMedia,otherEdits,SqlRecordPackageRepository(gateway,tags))
                    assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { erase.erase(intent) } }
                    assertEquals(PermanentErasurePhase.PREPARED,erase.read(id)!!.phase)
                    release.countDown(); writer.get(3,TimeUnit.SECONDS)
                    assertTrue(erase.erase(intent).complete); assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isEmpty())
                } finally { release.countDown(); gateway.seal(); otherMedia.close(); otherEdits.close(); otherMedia.awaitIdle(); otherEdits.awaitIdle(); gateway.awaitIdle(); tags.wipeKey() }
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)) }
        } }
    }
    @Test fun uncertainPlannedCommitAcknowledgementCannotAuthorizeUnlinkAndColdRetryUsesSamePlan(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry(); val bytes="synthetic media".toByteArray(); val media=f.attachment(id,bytes)
            f.media.save(media) { ByteArrayInputStream(bytes) }; val file=File(f.mediaRoot,media.id+".aead")
            val cipher=file.readBytes(); val intent=f.intent(id); var planned=false; var lost=false
            f.db.beforeSql={ if (it.startsWith("UPDATE permanent_erasure_receipts SET phase='PLANNED'")) planned=true }
            f.db.afterSql={ if (it=="COMMIT" && planned && !lost) { lost=true; error("Synthetic lost PLANNED ACK") } }
            assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(intent) } }
            assertTrue(lost); assertArrayEquals(cipher,file.readBytes())
            f.db.beforeSql=null; f.db.afterSql=null
            assertEquals(PermanentErasurePhase.PLANNED,f.erase.read(id)!!.phase)
            val plan=f.db.read("SELECT plan FROM permanent_erasure_receipts WHERE entryId=?",id).single().single()
            assertTrue((plan as String).isNotEmpty()); f.reopen(); assertTrue(f.erase.erase(intent).complete); assertFalse(file.exists())
        }
    }
    @Test fun interruptedUnlinkAndFinalSqlRollbackResumeSameDurableIntentAfterColdReopen(): Unit = runBlocking {
        for (failureStage in listOf("unlinked","files-durable","final-sql","done-committed")) PermanentErasureFixture().use { f ->
            val id=f.entry(); val bytes="synthetic media".toByteArray(); val media=f.attachment(id,bytes)
            f.media.save(media) { ByteArrayInputStream(bytes) }; f.edits.write(null,f.draft(id)); val intent=f.intent(id)
            f.checkpoint={ if(it==failureStage) error("Synthetic erasure interruption") }
            assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(intent) } }
            val phase=f.erase.read(id)!!.phase
            assertEquals(if(failureStage=="done-committed") PermanentErasurePhase.DONE else PermanentErasurePhase.PLANNED,phase)
            f.checkpoint={}; f.reopen(); assertTrue(f.erase.erase(intent).complete)
            assertTrue(f.db.read("PRAGMA foreign_key_check").isEmpty())
        }
    }
    @Test fun preoccupiedUnknownTemporaryAndSymlinkRemainUntouchedAndNeverBecomeDone(): Unit = runBlocking {
        for (symlink in listOf(false,true)) PermanentErasureFixture().use { f ->
            val id=f.entry(); val intent=f.attachment(id,byteArrayOf(1,2,3)); PermanentErasureFixture.Ledger(f.gateway).insert(LocalAttachment(intent,AttachmentState.WRITING))
            f.mediaRoot.mkdir(); val outside=f.root.resolve("unknown"); Files.write(outside,byteArrayOf(7,8,9))
            val target=File(f.mediaRoot,intent.id+".tmp").toPath()
            if(symlink) Files.createSymbolicLink(target,outside) else Files.write(target,byteArrayOf(4,5,6))
            val erase=f.intent(id)
            assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(erase) } }
            assertEquals(PermanentErasurePhase.PREPARED,f.erase.read(id)!!.phase)
            assertTrue(Files.exists(target)); assertArrayEquals(byteArrayOf(7,8,9),Files.readAllBytes(outside))
            if(!symlink) assertArrayEquals(byteArrayOf(4,5,6),Files.readAllBytes(target))
        }
    }
    @Test fun replacedCiphertextOrDirectoryAfterPlanningIsPreservedAndDoesNotComplete(): Unit = runBlocking {
        for (replaceDirectory in listOf(false,true)) PermanentErasureFixture().use { f ->
            val id=f.entry(); val bytes="synthetic media".toByteArray(); val media=f.attachment(id,bytes)
            f.media.save(media) { ByteArrayInputStream(bytes) }; val file=File(f.mediaRoot,media.id+".aead"); val original=file.readBytes(); val intent=f.intent(id)
            var replaced=false
            f.checkpoint={ if(it=="planned-readback" && !replaced) {
                replaced=true
                if(replaceDirectory) { Files.move(f.mediaRoot.toPath(),f.root.resolve("moved-blobs")); f.mediaRoot.mkdir() }
                else { Files.delete(file.toPath()); Files.write(file.toPath(),byteArrayOf(9,9,9)) }
            } }
            assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(intent) } }
            assertTrue(replaced); assertEquals(PermanentErasurePhase.PLANNED,f.erase.read(id)!!.phase)
            if(replaceDirectory) assertArrayEquals(original,Files.readAllBytes(f.root.resolve("moved-blobs").resolve(media.id+".aead")))
            else assertArrayEquals(byteArrayOf(9,9,9),file.readBytes())
        }
    }
    @Test fun legacyFullPartialAuthenticatesCanonicalAadButUnprovenTruncatedPartialIsPreserved(): Unit = runBlocking {
        for(truncated in listOf(false,true)) PermanentErasureFixture().use { f ->
            val id=f.entry(); f.editCheckpoint={ if(it=="encrypted") error("Synthetic legacy partial") }
            assertThrows(IllegalStateException::class.java) { runBlocking { f.edits.write(null,f.draft(id)) } }
            f.editCheckpoint={}
            val partial=f.editRoot.listFiles()!!.single { it.name.contains(".partial.") }
            f.db.write("DELETE FROM owned_file_creations WHERE entryId=?",id)
            if(truncated) partial.writeBytes(byteArrayOf(1,2,3))
            val original=partial.readBytes(); val intent=f.intent(id); f.reopen()
            if(truncated) {
                assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(intent) } }
                assertArrayEquals(original,partial.readBytes()); assertEquals(PermanentErasurePhase.PREPARED,f.erase.read(id)!!.phase)
            } else { assertTrue(f.erase.erase(intent).complete); assertFalse(partial.exists()) }
        }
    }
    @Test fun durableCreatedHeaderProofAllowsOwnedInterruptedPartialCleanupAfterColdReopen(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry(); f.editCheckpoint={ if(it=="encrypted") error("Synthetic interrupted partial") }
            assertThrows(IllegalStateException::class.java) { runBlocking { f.edits.write(null,f.draft(id)) } }; f.editCheckpoint={}
            val partial=f.editRoot.listFiles()!!.single { it.name.contains(".partial.") }
            java.io.RandomAccessFile(partial,"rw").use { it.setLength(13); it.fd.sync() }
            val intent=f.intent(id); f.reopen(); assertTrue(f.erase.erase(intent).complete); assertFalse(partial.exists())
        }
    }
    @Test fun independentPackageLedgerSurvivesFirstMiddleAndAllMemberErasureWithoutResurrection(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val snapshot=OriginalExportSnapshot(PermanentErasureFixture.uuid(),10,(0..2).map { RecordPackageCipherTest.record("synthetic-$it","合成包原文$it") })
            val pack=RecordPackageCipherTest.verified(snapshot); val ids=List(3) { PermanentErasureFixture.uuid() }
            f.packages.restore(pack,ids,100)
            val group=f.db.read("SELECT * FROM record_package_receipts WHERE operation='PACKAGE_RESTORE'").single()
            for(index in ids.indices) {
                assertTrue(f.erase.erase(f.intent(ids[index])).complete); f.reopen()
                val result=f.packages.restore(pack,List(3) { PermanentErasureFixture.uuid() },999)
                assertTrue(result.replayed); assertEquals(ids,result.ids); assertEquals(index+1,result.deletedCount); assertEquals(0,result.erasingCount)
                assertEquals(ids.drop(index+1).toSet(),f.packages.origins().keys)
                assertEquals(group,f.db.read("SELECT * FROM record_package_receipts WHERE operation='PACKAGE_RESTORE'").single())
            }
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
            f.db.write("DELETE FROM record_package_receipts WHERE operation='PACKAGE_RESTORE'")
            f.reopen(); assertThrows(RecordPackageConflict::class.java) { f.packages.restore(pack,List(3) { PermanentErasureFixture.uuid() },999) }
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty()); assertEquals(3,f.db.read("SELECT entryId FROM permanent_erasure_receipts").size)
        }
    }
    @Test fun missingOriginBeforeErasureFailsBeforeFenceAndOriginsCannotSilentlyDropMember(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val pack=RecordPackageCipherTest.verified(); val id=PermanentErasureFixture.uuid(); f.packages.restore(pack,listOf(id),100)
            val intent=f.intent(id); f.db.write("DELETE FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN'")
            assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(intent) } }
            assertNull(f.erase.read(id)); assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isNotEmpty())
            assertThrows(RecordPackageStorageFailure::class.java) { f.packages.origins() }
        }
    }
    @Test fun missingIndependentGroupAndOriginsWithSurvivingLegacyEvidenceNeverCreateAnotherBatch(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val pack=RecordPackageCipherTest.verified(); val id=PermanentErasureFixture.uuid(); f.packages.restore(pack,listOf(id),100)
            f.db.write("DELETE FROM record_package_receipts")
            val old=f.db.read("SELECT * FROM entry_revisions"); val mirrors=f.db.read("SELECT * FROM local_jobs ORDER BY id")
            f.reopen(); assertThrows(RecordPackageConflict::class.java) { f.packages.restore(pack,listOf(PermanentErasureFixture.uuid()),999) }
            assertEquals(old,f.db.read("SELECT * FROM entry_revisions")); assertEquals(mirrors,f.db.read("SELECT * FROM local_jobs ORDER BY id"))
            assertTrue(f.db.read("SELECT * FROM record_package_receipts").isEmpty())
        }
    }
    @Test fun sixtyFivePendingAndMalformedReceiptUseBoundedPagesWithoutBlockingHealthyRecords(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val healthy=f.entry(text="正常记录不受影响")
            f.checkpoint={ if(it=="prepared") error("Synthetic pending") }
            repeat(65) { val id=f.entry(); assertThrows(PermanentErasureIncomplete::class.java) { runBlocking { f.erase.erase(f.intent(id)) } } }
            f.checkpoint={}; f.reopen()
            val first=f.erase.pending(limit=4); assertEquals(4,first.receipts.size); assertNotNull(first.nextEntryId)
            val next=f.erase.pending(limit=64,afterEntryId=first.nextEntryId); assertEquals(61,next.receipts.size); assertNull(next.nextEntryId)
            assertEquals("正常记录不受影响",f.bin.read(healthy).entry.originalText)
            f.db.write("DROP TRIGGER immutable_erasure_intent")
            f.db.write("UPDATE permanent_erasure_receipts SET phase='INVALID' WHERE entryId=?",first.receipts.first().intent.source.sourceId)
            val malformed=f.erase.pending(limit=64); assertEquals(1,malformed.unreadableCount); assertEquals(63,malformed.receipts.size)
            assertTrue(SqlErasureFileHooks(f.gateway).fenced(first.receipts.first().intent.source.sourceId))
            assertEquals("正常记录不受影响",f.bin.read(healthy).entry.originalText)
        }
    }
    @Test fun transplantedOrdinalAndForgedDoneProofsFailWithoutInsertingOrHidingSourceData(): Unit = runBlocking {
        for (corruption in listOf("transplant","ordinal","false-done","missing")) PermanentErasureFixture().use { f ->
            val pack=RecordPackageCipherTest.verified(); val id=PermanentErasureFixture.uuid(); f.packages.restore(pack,listOf(id),100)
            val intent=f.intent(id)
            if(corruption=="false-done") {
                f.checkpoint={if(it=="prepared") error("Synthetic prepared interruption")}
                assertThrows(PermanentErasureIncomplete::class.java) {runBlocking {f.erase.erase(intent)}}; f.checkpoint={}
            } else assertTrue(f.erase.erase(intent).complete)
            f.db.write("DROP TRIGGER immutable_erasure_intent")
            when(corruption) {
                "transplant" -> {
                    val otherPack=RecordPackageCipherTest.verified(); val other=PermanentErasureFixture.uuid()
                    f.packages.restore(otherPack,listOf(other),100); assertTrue(f.erase.erase(f.intent(other)).complete)
                    val proof=f.db.read("SELECT packageProof FROM permanent_erasure_receipts WHERE entryId=?",other).single().single()
                    f.db.write("UPDATE permanent_erasure_receipts SET packageProof=? WHERE entryId=?",proof,id)
                }
                "ordinal" -> {
                    val state=f.db.read("SELECT packageProof FROM permanent_erasure_receipts WHERE entryId=?",id).single().single() as String
                    val raw=java.util.Base64.getDecoder().decode(state.removePrefix("CONFIRMED:")); val input=PackageReader(raw)
                    assertEquals(1,input.i32());assertEquals(3,input.i32());val backup=input.string(36);val digest=input.raw(32)
                    assertEquals(0,input.i32());val target=input.string(36);val record=input.raw(32);input.end()
                    val modified=PackageWriter(1024).use {out ->out.i32(1);out.i32(3);out.string(backup,36);out.raw(digest);out.i32(1);out.string(target,36);out.raw(record);out.bytes()}
                    f.db.write("UPDATE permanent_erasure_receipts SET packageProof=? WHERE entryId=?","CONFIRMED:"+java.util.Base64.getEncoder().encodeToString(modified),id)
                    raw.fill(0);modified.fill(0)
                }
                "false-done" -> f.db.write("UPDATE permanent_erasure_receipts SET phase='DONE',plan='' WHERE entryId=?",id)
                "missing" -> f.db.write("DELETE FROM permanent_erasure_receipts WHERE entryId=?",id)
            }
            val rows=f.db.read("SELECT * FROM entries ORDER BY id")
            assertThrows(RecordPackageStorageFailure::class.java) {f.packages.restore(pack,listOf(PermanentErasureFixture.uuid()),999)}
            assertThrows(RecordPackageStorageFailure::class.java) {f.packages.origins()}
            assertEquals(rows,f.db.read("SELECT * FROM entries ORDER BY id"))
            if(corruption=="false-done") {
                assertThrows(PermanentErasureIncomplete::class.java) {f.erase.read(id)}
                assertThrows(PermanentErasureIncomplete::class.java) {runBlocking {f.erase.erase(intent)}}
                assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isNotEmpty())
            }
        }
    }
    @Test fun freshImportCapacityGatePreventsCommittedUnreadableGroupsButKeepsExistingReplay(): Unit = runBlocking {
        PermanentErasureFixture().use {f ->
            val repo=SqlRecordPackageRepository(f.gateway,f.tags,maxGroups=1)
            val pack=RecordPackageCipherTest.verified();val id=PermanentErasureFixture.uuid();repo.restore(pack,listOf(id),100)
            val rows=f.db.read("SELECT * FROM record_package_receipts ORDER BY id")
            val next=RecordPackageCipherTest.verified()
            assertThrows(RecordPackageConflict::class.java) {repo.restore(next,listOf(PermanentErasureFixture.uuid()),101)}
            assertEquals(rows,f.db.read("SELECT * FROM record_package_receipts ORDER BY id"))
            assertEquals(setOf(id),repo.origins().keys);assertTrue(repo.restore(pack,listOf(PermanentErasureFixture.uuid()),999).replayed)
        }
    }
    @Test fun boundedStartupResumeIsolatesConflictingSourceAndStillRecoversHealthyStorage(): Unit = runBlocking {
        PermanentErasureFixture().use {f ->
            val id=f.entry();val healthy=f.entry(text="正常原文保留");val intent=f.intent(id)
            f.checkpoint={if(it=="prepared") error("Synthetic interruption")}
            assertThrows(PermanentErasureIncomplete::class.java) {runBlocking {f.erase.erase(intent)}};f.checkpoint={}
            // In this owned corruption fixture, bypass normal SQL guards to break only one target.
            f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",id)
            f.reopen();resumePermanentErasure(f.erase);f.media.recover()
            assertEquals(PermanentErasurePhase.PREPARED,f.erase.read(id)!!.phase)
            assertEquals("正常原文保留",f.bin.read(healthy).entry.originalText)
        }
    }
}
