package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Product SQL/ciphers/files and independently keyed owners; never Android/SAF/SQLCipher acceptance. */
class FullBackupTest {
    @Test fun localConnectionCatalogIsExcludedAndEmptyRecordRestorePreservesItsExactBytes():Unit=runBlocking {
        FullFixture().use { source ->FullFixture(99).use { target ->
            for(f in listOf(source,target)) f.db.transaction { sql->ModelConnectionSqlSchema.migration12To13.forEach { sql.execute(it) };sql.execute("PRAGMA user_version=13") }
            source.entry();source.gateway.let { SqlModelConnectionCatalog(it) }.change(ModelConnectionCatalogTest.change("source-only",0,"source-config"))
            val catalog=SqlModelConnectionCatalog(target.gateway)
            val local=catalog.change(ModelConnectionCatalogTest.change("target-local",0,"target-config",ModelConnectionCatalogTest.details(name="合成本机设置")))!!
            val before=target.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray
            val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password())
            try { target.backup.restore(pack) { true } } finally { pack.release() }
            assertEquals(listOf(local),catalog.configurations())
            assertArrayEquals(before,target.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray)
            assertEquals(listOf("target-local"),target.db.read("SELECT id FROM model_configurations").map { it[0] })
            assertEquals(1,target.db.read("SELECT id FROM entries").size)
            assertEquals("LOCAL_ONLY",target.db.read("SELECT privacyPolicy FROM entries").single().single())
        } }
    }
    @Test fun aiNoneDeviceDefaultEmptySlotAllowsRestoreButManualRangeChangesStillRefuse(): Unit = runBlocking {
        FullFixture().use { source ->
            val id=source.entry();val original=source.capture.edit(source.capture.current(),"合成包内草稿")
            val bytes=export(source)
            for (edited in listOf(false,true)) FullFixture(99).use { target ->
                var reads=0
                val capture=SqlCaptureRepository(target.gateway,target.media,target.tags) { reads++;ProcessingPolicy.AI_NONE }
                var empty=capture.current()
                assertEquals(ProcessingPolicy.AI_NONE,empty.policy);assertEquals(1L,empty.version)
                if(edited) {
                    empty=capture.changePolicy(empty,ProcessingPolicy.LOCAL_ONLY)
                    empty=capture.changePolicy(empty,ProcessingPolicy.AI_NONE)
                }
                val pack=target.backup.open({bytes.inputStream()},password())
                try {
                    if(edited) {
                        fails { target.backup.restore(pack) { true } }
                        assertCaptureDraftEquals(empty,capture.current());assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
                    } else {
                        target.backup.restore(pack) { true }
                        assertCaptureDraftEquals(original,capture.current())
                        assertEquals(listOf(id),target.db.read("SELECT id FROM entries").map { it[0] })
                    }
                    assertEquals(1,reads) // Imported/existing draft never consults the device default.
                } finally { pack.release() }
            }
        }
    }
    @Test fun allRecordsBeyondSelectedPackageLimitIncludeTrashHistoryFavoritesAndCrossKeyImages():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val ids=(1..26).map { source.entry(text="合成原文 $it") }
            val id=ids.first();source.revise(id,"合成第二版")
            source.db.write("UPDATE entries SET isFavorite=1,favoriteVersion=1,lastFavoriteMutationId='favorite',lastFavoriteSourceRevision=2 WHERE id=?",id)
            val bin=SqlRecycleBinRepository(source.gateway,source.tags,SqlErasureFileHooks::fenced)
            bin.apply(RecycleMutation(SourceVersion(ids.last(),1),bin.read(ids.last()).generation,false,uuid()))
            source.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),uuid()))
            val people=SqlManualPeopleRepository(source.gateway,source.key)
            people.replace(ManualPeopleMutation(people.read(id),listOf("合成人物"),uuid()))
            source.db.write("INSERT INTO entry_event_dates VALUES(?,1,'date',2,'2026-10-01','Asia/Shanghai')",id)
            val a=source.image(id,1);val b=source.image(id,2)
            val snapshot=source.backup.capture(uuid(),100)
            assertEquals(26,snapshot.summary.records);assertEquals(1,snapshot.summary.trash)
            assertEquals(27,snapshot.summary.revisions);assertEquals(2,snapshot.summary.images)
            val bytes=export(source,snapshot);snapshot.release()
            val verified=target.backup.open({bytes.inputStream()},password())
            val result=target.backup.restore(verified) { true };assertFalse(result.replayed)
            assertEquals(ids.toSet(),target.db.read("SELECT id FROM entries").map { it[0] }.toSet())
            assertEquals("合成第二版",target.db.read("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=2",id).single().single())
            assertEquals(listOf(1L,1L,"favorite",2L),target.db.read("SELECT isFavorite,favoriteVersion,lastFavoriteMutationId,lastFavoriteSourceRevision FROM entries WHERE id=?",id).single())
            assertEquals(listOf("合成标签"),target.tags.read(id).names)
            assertEquals(listOf("合成人物"),SqlManualPeopleRepository(target.gateway,target.key).read(id).names)
            assertEquals(listOf("2026-10-01","Asia/Shanghai"),target.db.read("SELECT localDate,timeZone FROM entry_event_dates WHERE entryId=?",id).single())
            assertEquals(1L,target.db.read("SELECT deleted FROM remote_source_access WHERE entryId=?",ids.last()).single().single())
            for(image in listOf(a,b)) {
                assertArrayEquals(PNG,target.media.read(image.id))
                assertFalse(source.mediaRoot.resolve(image.id+".aead").readBytes().contentEquals(target.mediaRoot.resolve(image.id+".aead").readBytes()))
            }
            assertTrue(target.db.read("PRAGMA foreign_key_check").isEmpty());verified.release();people.wipeKey()
        } }
    }
    @Test fun captureDraftWithUnfinishedUnicodeAndImagesAndEditVersionsRemainDrafts():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();val edit=OriginalEditDraft(uuid(),id,1,1,7,"合成原文","编辑尚未完成\uD83D",OriginalEditDraftState.ACTIVE)
            source.edits.packageFiles { it.installFull(edit) }
            val draft=source.capture.edit(source.capture.current(),"捕获尚未完成\uD83D")
            val image=AttachmentIntent(uuid(),draft.id,1,"image/png",hash(PNG),PNG.size.toLong())
            val ready=source.capture.add(draft,image,PNG)
            val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password())
            target.capture.current() // Precisely default empty slot is an allowed initialization.
            target.backup.restore(pack) { true }
            val restored=target.capture.current();assertEquals(ready.id,restored.id);assertEquals(ready.version,restored.version)
            assertEquals(ready.text,restored.text);assertArrayEquals(PNG,target.capture.image(restored,image.id))
            assertEquals(edit,target.edits.read(id));assertEquals(1L,target.db.read("SELECT count(*) FROM entries").single().single())
            assertEquals(0L,target.db.read("SELECT count(*) FROM attachments WHERE entryId=?",draft.id).single().single())
            fails { target.capture.save(restored,500,"UTC") } // Unfinished input remains a draft after recovery.
            val completed=target.capture.edit(restored,"捕获已完成🙂")
            val saved=target.capture.save(completed,500,"UTC");assertEquals(draft.id,saved.id)
            assertTrue(target.capture.save(completed,500,"UTC").replayed)
            assertArrayEquals(PNG,target.media.read(image.id));pack.release()
        } }
    }
    @Test fun manualMemoryCorrectionHistoryAndCandidateDecisionsSurviveNewKeyAndContinueCas():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry(text="合成原文：今天很开心。")
            val memories=SqlSourceMemoryRepository(source.gateway,source.key)
            val preview=memories.preview(SourceVersion(id,1));val content=SourceMemoryContent(SourceMemoryRules.reference(preview,preview.original),SourceMemoryKind.EVENT,SourceMemoryAssertion.SELF_REPORT,"手工备注")
            var saved=memories.create(SourceMemoryCreate("memory",preview,content,"create",30))
            fun change(action:SourceMemoryAction,c:SourceMemoryContent?=null)=SourceMemoryMutation(saved.id,saved.source,saved.sourceHash,saved.generation,saved.version,saved.current!!.revision,saved.current!!.review,action,c,uuid(),40)
            saved=memories.mutate(change(SourceMemoryAction.ACCEPT))
            saved=memories.mutate(change(SourceMemoryAction.CORRECT,content.copy(note="手工修正")))
            saved=memories.mutate(change(SourceMemoryAction.REJECT))
            val candidates=SqlLocalCandidateRepository(source.gateway,source.key)
            val job=candidates.begin(LocalCandidateStart("batch",SourceVersion(id,1),20))
            val batch=candidates.complete(job,LocalExcerptRules.extract(job))
            val reviewed=candidates.review(ExcerptReviewRequest(batch.id,batch.source,batch.sourceHash,batch.generation,batch.version,batch.items.first().id,ExcerptReview.ACCEPTED,"review",50))
            val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password());target.backup.restore(pack) { true }
            val next=SqlSourceMemoryRepository(target.gateway,target.key)
            val restored=next.memories(SourceVersion(id,1)).single();assertEquals(saved.version,restored.version);assertEquals(saved.versions,restored.versions)
            val reopened=next.mutate(SourceMemoryMutation(restored.id,restored.source,restored.sourceHash,restored.generation,restored.version,restored.current!!.revision,restored.current!!.review,SourceMemoryAction.REOPEN,null,"reopen",60))
            assertEquals(saved.version+1,reopened.version)
            val targetCandidates=SqlLocalCandidateRepository(target.gateway,target.key)
            val actual=targetCandidates.batches(SourceVersion(id,1)).single();assertEquals(reviewed.items,actual.items)
            memories.wipeKey();candidates.wipeKey();next.wipeKey();targetCandidates.wipeKey();pack.release()
        } }
    }
    @Test fun remoteAuthorityAndReminderCommandsNeverBecomeRestoredExecution():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry()
            source.db.write("UPDATE entries SET privacyPolicy='REMOTE_ALLOWED' WHERE id=?",id)
            source.db.write("UPDATE remote_source_access SET connection=?,consent=? WHERE entryId=?",byteArrayOf(1,2),byteArrayOf(3,4),id)
            val tasks=SqlManualTaskRepository(source.gateway,source.key)
            val schedule=ManualTaskDateInput("2027-01-01","12:00","UTC").parse()!!
            val task=tasks.create(ManualTaskCreate("task","合成提醒",10,"UTC","create",schedule))
            tasks.changeReminder(TaskReminderMutation(task,TaskReminderMode.EXACT,20,"remind"))
            val proposal=TaskProposal("p".repeat(128),3,TaskSourceBinding(SourceVersion(id,1),OriginalExports.hash("合成原文"),
                source.db.read("SELECT generation FROM remote_source_access WHERE entryId=?",id).single().single() as Long,0,2),
                "合成被拒绝提案",ManualTaskDateInput("2027-01-02"),10,"UTC",20,TaskProposalState.REJECTED)
            val proposalCipher=TaskProposalCipher(source.key);val proof="a".repeat(64)
            source.db.write("INSERT INTO manual_tasks VALUES(?,?,?,?)",TaskProposals.storageId(proposal.id),proposal.version,proof,proposalCipher.seal(proposal,proof));proposalCipher.wipe()
            val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password());target.backup.restore(pack) { true }
            assertEquals("LOCAL_ONLY",target.db.read("SELECT privacyPolicy FROM entries WHERE id=?",id).single().single())
            assertEquals(listOf(null,null),target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?",id).single())
            val targetTasks=SqlManualTaskRepository(target.gateway,target.key)
            val restored=targetTasks.snapshots().single();assertEquals(schedule,restored.schedule)
            val targetProposals=SqlTaskProposalRepository(target.gateway,target.key,targetTasks)
            assertEquals(proposal,targetProposals.list().single().proposal);targetProposals.wipeKey()
            assertEquals(TaskReminderPhase.REVOKED,restored.reminder!!.phase);assertEquals(TaskReminderMode.EXACT,restored.reminder!!.mode)
            assertTrue(restored.reminder!!.commands.isEmpty());assertTrue(target.db.read("SELECT * FROM remote_text_jobs").isEmpty())
            tasks.wipeKey();targetTasks.wipeKey();pack.release()
        } }
    }
    @Test fun corruptionTruncationSubstitutionAndTrailingDataCannotProduceImportPreview():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            source.image(source.entry());val bytes=export(source)
            for(bad in listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0),bytes.copyOf().also { it[it.lastIndex-40]=(it[it.lastIndex-40].toInt() xor 1).toByte() })) {
                fails { target.backup.open({bad.inputStream()},password()) }
                assertTrue(target.db.read("SELECT * FROM entries").isEmpty());assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
            }
            val pack=target.backup.open({bytes.inputStream()},password());bytes[bytes.lastIndex]=0
            fails { target.backup.restore(pack) { true } }
            assertTrue(target.db.read("SELECT * FROM entries").isEmpty());pack.release()
        } }
    }
    @Test fun publicEnvelopeBoundsRejectBeforeKdfAndWipePasswords():Unit=runBlocking {
        FullFixture().use { source ->
            source.entry();val bytes=export(source);var kdf=0
            val cipher=RecordMediaPackageCipher(java.security.SecureRandom()) { kdf++ }
            for(offset in listOf(8,12,40)) {
                val bad=bytes.copyOf().also { java.nio.ByteBuffer.wrap(it,offset,4).putInt(Int.MAX_VALUE) }
                val password=password();fails { cipher.openFull({bad.inputStream()},password) };assertTrue(password.all { it=='\u0000' })
            }
            assertEquals(0,kdf)
        }
    }
    @Test fun wholeInventoryChangesInvalidateExportIncludingNewlyInsertedRecord():Unit=runBlocking {
        FullFixture().use { source ->
            source.entry();val snapshot=source.backup.capture(uuid(),100)
            source.entry();fails { source.backup.seal(snapshot,password()) };snapshot.release()
            val second=source.backup.capture(uuid(),100);val prepared=source.backup.seal(second,password())
            source.entry();fails { prepared.writeTo(ByteArrayOutputStream()) { true } };second.release()
        }
    }
    @Test fun overLimitOrFailedAttachmentOrPendingCaptureIsWholeFailure():Unit=runBlocking {
        FullFixture().use { source ->
            val id=source.entry();val image=source.image(id)
            source.db.write("UPDATE attachments SET state='CORRUPT' WHERE id=?",image.id)
            fails { source.backup.capture(uuid(),100) }
            source.db.write("UPDATE attachments SET state='READY' WHERE id=?",image.id)
            repeat(256) { source.entry() }
            fails { source.backup.capture(uuid(),100) }
        }
    }
    @Test fun nonemptyUnknownAndDeletedTargetsAreRefusedWithoutChangingRows():Unit=runBlocking {
        FullFixture().use { source ->
            source.entry();val bytes=export(source)
            for(kind in listOf("entry","deleted","draft","unknown","proof")) FullFixture(99).use { target ->
                when(kind) {
                    "entry" -> target.entry()
                    "deleted" -> target.db.write("UPDATE remote_source_access SET deleted=1 WHERE entryId=?",target.entry())
                    "draft" -> target.capture.edit(target.capture.current(),"现有输入")
                    "unknown" -> target.db.write("INSERT INTO record_package_receipts VALUES('unknown','anchor',1,'UNKNOWN','opaque')")
                    "proof" -> target.db.write("INSERT INTO permanent_erasure_receipts VALUES('gone',1,1,'proof','DONE','','')")
                }
                val before=target.db.read("SELECT * FROM entries")
                val pack=target.backup.open({bytes.inputStream()},password());fails { target.backup.restore(pack) { true } }
                assertEquals(before,target.db.read("SELECT * FROM entries"));pack.release()
            }
        }
    }
    @Test fun pendingFilesResumeAndLostCommitAcknowledgementOnlyReplays():Unit=runBlocking {
        for(point in listOf("full-intent","media:created","media:package-image-encrypted","package-image-published","edit:encrypted","edit:published","full-files","full-before-commit","full-committed")) {
            FullFixture().use { source -> FullFixture(99).use { target ->
                val id=source.entry();source.image(id)
                source.edits.write(null,OriginalEditDraft(uuid(),id,1,1,1,"合成原文","合成编辑",OriginalEditDraftState.ACTIVE))
                val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password())
                var fired=false;target.checkpoint={ if(it==point && !fired) { fired=true;throw java.io.IOException("synthetic failure") } }
                target.mediaCheckpoint={ target.checkpoint(if(point.startsWith("media:")) "media:"+it else it) }
                target.editCheckpoint={ target.checkpoint("edit:"+it) }
                fails { target.backup.restore(pack) { true } };assertTrue(point,fired)
                target.checkpoint={};target.mediaCheckpoint={};target.editCheckpoint={}
                val result=target.backup.restore(pack) { true }
                assertEquals(point=="full-committed",result.replayed)
                assertEquals(1L,target.db.read("SELECT count(*) FROM entries").single().single())
                assertArrayEquals(PNG,target.media.read(target.db.read("SELECT id FROM attachments").single().single() as String));pack.release()
            } }
        }
    }
    @Test fun finalEmptyGateCatchesConcurrentInsertAndPreservesFailureFiles():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            source.image(source.entry());val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password())
            var inserted:String?=null
            target.checkpoint={ if(it=="full-files" && inserted==null) inserted=target.entry(text="并发合成资料") }
            fails { target.backup.restore(pack) { true } }
            assertEquals(listOf(inserted),target.db.read("SELECT id FROM entries").map { it[0] })
            assertTrue(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") });pack.release()
        } }
    }
    @Test fun doneReplayDoesNotResetLaterRevisionOrResurrectPermanentDeletion():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},password())
            target.backup.restore(pack) { true };target.revise(id,"后续合成修改")
            assertTrue(target.backup.restore(pack) { true }.replayed)
            assertEquals(2L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",id).single().single())
            val bin=SqlRecycleBinRepository(target.gateway,target.tags,SqlErasureFileHooks::fenced)
            val deleted=bin.apply(RecycleMutation(SourceVersion(id,2),bin.read(id).generation,false,uuid()))
            val eraser=SqlPermanentErasureRepository(target.gateway,target.media,target.edits,target.packages)
            eraser.erase(PermanentErasureIntent(SourceVersion(id,2),deleted.generation,uuid()))
            assertTrue(target.backup.restore(pack) { true }.replayed);assertTrue(target.db.read("SELECT * FROM entries").isEmpty());pack.release()
        } }
    }
    @Test fun authenticatedMalformedMemoryAndExcerptPayloadsCannotAcknowledgeFullRestore():Unit=runBlocking {
        for(kind in listOf("empty-memory","invalidated-payload","wrong-quote")) FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();val snapshot=source.backup.capture(uuid(),100) as FullBackupData
            val profile=if(kind=="wrong-quote") 1L else 2L
            val state=if(kind=="invalidated-payload") "INVALIDATED" else "READY"
            val row=listOf<Any?>("bad",id,1L,OriginalExports.hash("合成原文"),1L,profile,1L,state,"bad",20L,byteArrayOf())
            val aad=PackageWriter(4096).use { w ->
                w.string(if(profile==1L) "AIyou/local-candidates/1" else "AIyou/user-source-memory/envelope/1",64)
                w.string("bad",512);w.string(id,512);w.i64(1);w.string(row[3] as String,64);w.i64(1);w.i32(profile.toInt());w.i64(1)
                w.string(state,16);w.string("bad",512);w.i64(20);w.bytes()
            }
            val portable=if(profile==2L) SourceMemoryCipher(source.key).let { c ->
                val encrypted=c.seal(aad,SourceMemoryCipher.Payload(5,"UTC",ProcessingPolicy.LOCAL_ONLY,0,emptyList(),"CREATE",0,0,null,20))
                try { c.portable(aad,SourceVersion(id,1),encrypted,false) } finally { encrypted.fill(0);c.wipe() }
            } else LocalCandidateCipher(source.key).let { c ->
                val encrypted=c.seal(aad,LocalCandidateCipher.Payload(listOf(ReviewedExcerpt("excerpt-1",ExcerptProposal(0,1,"X"),ExcerptReview.PROPOSED)),"COMPLETE",0,"",20))
                try { c.portable(aad,encrypted,false) } finally { encrypted.fill(0);c.wipe() }
            }
            val tables=snapshot.tables.toMutableList();tables[8]=listOf(row.toMutableList().also { it[10]=portable })
            val malformed=FullBackupData(snapshot.id,100,tables,snapshot.images,snapshot.edits,null)
            val prepared=RecordMediaPackageCipher().sealFull(malformed,password(),{error("no images")},{true})
            val out=ByteArrayOutputStream();prepared.writeTo(out) {true};prepared.release()
            var pack:VerifiedFullBackup?=null
            fails { pack=target.backup.open({out.toByteArray().inputStream()},password());target.backup.restore(checkNotNull(pack)) {true} }
            assertTrue(target.db.read("SELECT * FROM entries").isEmpty())
            assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
            assertTrue(target.db.read("SELECT state FROM record_package_receipts WHERE operation='FULL_BACKUP_IMPORT_V8'").none { (it[0] as String).startsWith("DONE:") })
            pack?.release();malformed.release();snapshot.release();aad.fill(0)
        } }
    }
    @Test fun existingSelectedPackageMediaActivitiesOriginsAndDeletedProofsRemainReplayable():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry()
            val tasks=SqlManualTaskRepository(source.gateway,source.key)
            val proposals=SqlTaskProposalRepository(source.gateway,source.key,tasks)
            val activities=SqlRecordPackageActivities(source.gateway,tasks,proposals)
            val packages=SqlRecordPackageRepository(source.gateway,source.tags,activities=activities)
            val originals=SqlOriginalExportRepository(source.gateway,source.tags,activities)
            val selected=SqlRecordMediaPackageRepository(source.gateway,originals,source.media,packages,activities,source.edits)
            tasks.create(ManualTaskCreate("task","合成旧包待办",20,"UTC","create"))
            val ids=mutableListOf<String>();val documents=mutableListOf<ByteArray>()
            repeat(2) {
                val snapshot=originals.capture(listOf(SourceVersion(id,1)),uuid(),100+it.toLong())
                val prepared=selected.seal(selected.previewActivities(snapshot),password());val out=ByteArrayOutputStream()
                prepared.writeTo(out) {true};prepared.release();documents+=out.toByteArray()
                val pack=selected.open({documents.last().inputStream()},password());val copy=uuid();ids+=copy
                selected.restore(pack,listOf(copy),300+it.toLong());pack.release()
            }
            source.revise(ids.first(),"后来追加合成修订")
            val bin=SqlRecycleBinRepository(source.gateway,source.tags,SqlErasureFileHooks::fenced)
            val before=bin.read(ids.last());val deleted=bin.apply(RecycleMutation(SourceVersion(ids.last(),1),before.generation,false,uuid()))
            SqlPermanentErasureRepository(source.gateway,source.media,source.edits,packages).erase(PermanentErasureIntent(SourceVersion(ids.last(),1),deleted.generation,uuid()))
            val bytes=exportReordered(source);val full=target.backup.open({bytes.inputStream()},password());target.backup.restore(full) {true}
            assertEquals(2L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",ids.first()).single().single())
            assertTrue(target.db.read("SELECT id FROM entries WHERE id=?",ids.last()).isEmpty())
            assertEquals(1L,target.db.read("SELECT count(*) FROM permanent_erasure_receipts WHERE phase='DONE'").single().single())
            val targetTasks=SqlManualTaskRepository(target.gateway,target.key)
            assertEquals(tasks.snapshots().map {it.title},targetTasks.snapshots().map {it.title})
            val targetProposals=SqlTaskProposalRepository(target.gateway,target.key,targetTasks)
            val targetActivities=SqlRecordPackageActivities(target.gateway,targetTasks,targetProposals)
            val targetPackages=SqlRecordPackageRepository(target.gateway,target.tags,activities=targetActivities)
            val targetOriginals=SqlOriginalExportRepository(target.gateway,target.tags,targetActivities)
            val targetSelected=SqlRecordMediaPackageRepository(target.gateway,targetOriginals,target.media,targetPackages,targetActivities,target.edits)
            documents.forEachIndexed { index,document ->
                val opened=targetSelected.open({document.inputStream()},password())
                val replay=targetSelected.restore(opened,listOf(uuid()),900)
                assertTrue(replay.replayed);assertEquals(if(index==1) 1 else 0,replay.deletedCount);opened.release()
            }
            full.release();tasks.wipeKey();proposals.wipeKey();targetTasks.wipeKey();targetProposals.wipeKey()
        } }
    }
    @Test fun authenticatedInvalidEditFavoriteDateAndCellTypesAreRejectedBeforeAnyIntent():Unit=runBlocking {
        for(kind in listOf("edit-id","edit-version","favorite","event","type","ledger")) FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();val base=source.backup.capture(uuid(),100) as FullBackupData
            val tables=base.tables.toMutableList();var edits=base.edits
            when(kind) {
                "edit-id","edit-version" -> edits=listOf(OriginalEditDraft(if(kind=="edit-id") "bad" else uuid(),id,1,1,if(kind=="edit-version") 0 else 1,"合成原文","合成编辑",OriginalEditDraftState.ACTIVE))
                "favorite" -> tables[0]=listOf(base.tables[0].single().toMutableList().also { it[5]=1L })
                "event" -> tables[6]=listOf(listOf(id,1L,"event",1L,"2026-02-31","UTC"))
                "type" -> tables[0]=listOf(base.tables[0].single().toMutableList().also { it[7]=byteArrayOf(1) })
                "ledger" -> tables[12]=listOf(listOf("PACKAGE_RESTORE:"+uuid(),id,1L,"PACKAGE_RESTORE","garbage"))
            }
            val bad=FullBackupData(base.id,base.created,tables,emptyList(),edits,null)
            val bytes=uncheckedAuthenticatedNoImages(bad)
            var pack:VerifiedFullBackup?=null
            fails { pack=target.backup.open({bytes.inputStream()},password()) }
            assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
            assertTrue(target.db.read("SELECT * FROM entries").isEmpty())
            pack?.release();bad.release();base.release()
        } }
    }
    @Test fun authenticatedRowAndEditOrderDoNotChangeIdentityOrImageFrameBindings():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            // SQLite UTF-8 BINARY and Kotlin UTF-16 have different order for these valid opaque IDs.
            val ids=listOf(source.entry("\uE000"),source.entry("\uD800\uDC00"))
            ids.forEach { id ->
                source.revise(id,"合成第二版")
                source.image(id,2)
                source.edits.packageFiles { it.installFull(OriginalEditDraft(uuid(),id,2,2,3,"合成第二版","尚未完成",OriginalEditDraftState.ACTIVE)) }
            }
            val bytes=exportReordered(source)
            val pack=target.backup.open({bytes.inputStream()},password())
            assertFalse(target.backup.restore(pack) {true}.replayed)
            assertTrue(target.backup.completed(pack));assertTrue(target.backup.restore(pack) {true}.replayed)
            assertEquals(ids.toSet(),target.db.read("SELECT id FROM entries").map { it[0] }.toSet())
            ids.forEach { id -> assertEquals(source.edits.read(id),target.edits.read(id)) }
            source.db.read("SELECT id FROM attachments").forEach { assertArrayEquals(PNG,target.media.read(it[0] as String)) }
            pack.release()
        } }
    }
    private suspend fun exportReordered(source:FullFixture):ByteArray {
        val base=source.backup.capture(uuid(),100) as FullBackupData
        val reordered=FullBackupData(base.id,base.created,base.tables.map { it.reversed() },base.images.reversed(),base.edits.reversed(),base.capture)
        try {
            val prepared=RecordMediaPackageCipher().sealFull(reordered,password(),{ source.media.read(it.attachmentId) },{true})
            val out=ByteArrayOutputStream()
            try { prepared.writeTo(out) {true};return out.toByteArray() } finally { prepared.release() }
        } finally { reordered.release();base.release() }
    }
    /** Adversarial authenticated document generator intentionally bypasses the product semantic encoder. */
    private fun uncheckedAuthenticatedNoImages(d:FullBackupData):ByteArray {
        val metadata=PackageWriter(FullBackupCodec.MAX_METADATA).use { w ->
            fun text(value:String) { w.i32(value.length);val bytes=ByteArray(value.length*2);value.forEachIndexed { i,c ->bytes[2*i]=(c.code ushr 8).toByte();bytes[2*i+1]=c.code.toByte() };w.raw(bytes);bytes.fill(0) }
            w.string("AIyou-logical-full-v8",32);w.i32(8);w.i32(1);w.string(d.id,36);w.i64(d.created);w.i32(d.tables.size)
            FullBackupTables.specs.forEachIndexed { index,spec ->
                w.string(spec.name,64);w.string(spec.columns,512);w.i32(d.tables[index].size)
                d.tables[index].forEach { row ->row.forEach { cell ->when(cell) {
                    null->w.i32(0)
                    is Long->{w.i32(1);w.i64(cell)}
                    is String->{w.i32(2);text(cell)}
                    is ByteArray->{w.i32(3);w.i32(cell.size);w.raw(cell)}
                } } }
            }
            w.i32(0);w.i32(d.edits.size);d.edits.forEach { RecordEditDraftPackageCodec.write(w,it) };w.bytes()
        }
        val salt=ByteArray(16) { it.toByte() };val prefix=ByteArray(8) { (it+30).toByte() }
        val header=java.nio.ByteBuffer.allocate(40).put("AIYOUPK8".toByteArray()).putInt(8).putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
        val password=password();val key=RecordPackageCrypto.derive(password,salt);password.fill('\u0000')
        val out=ByteArrayOutputStream();out.write(header)
        fun frame(index:Int,binding:ByteArray,plain:ByteArray) {
            val n=java.nio.ByteBuffer.allocate(4).putInt(index).array()
            val encrypted=RecordPackageCrypto.crypt(javax.crypto.Cipher.ENCRYPT_MODE,key,prefix+n,header+binding+n,plain)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(encrypted.size).array());out.write(encrypted);encrypted.fill(0)
        }
        return try { val binding=packageHash(metadata);frame(0,ByteArray(32),metadata);frame(1,binding,binding);out.toByteArray() }
        finally { key.fill(0);metadata.fill(0) }
    }
    private suspend fun export(f:FullFixture,snapshot:FullBackupSnapshot?=null):ByteArray {
        val captured=snapshot ?: f.backup.capture(uuid(),100)
        val payload=f.backup.seal(captured,password());val out=ByteArrayOutputStream()
        try { payload.writeTo(out) { true };return out.toByteArray() } finally { payload.release();captured.release() }
    }
    private suspend fun fails(block:suspend ()->Any?) { var failure:Throwable?=null;try { block() } catch(e:Exception) { failure=e };assertNotNull("Expected bounded failure",failure) }
    companion object {
        val PNG=byteArrayOf(-119,80,78,71,13,10,26,10,1,2,3,4)
        fun uuid()=UUID.randomUUID().toString()
        fun hash(bytes:ByteArray)=packageHex(packageHash(bytes))
        fun password()="synthetic backup password".toCharArray()
    }
}

internal class FullFixture(seed:Int=1):AutoCloseable {
    val key=ByteArray(32) { (it+seed).toByte() }
    val root=Files.createTempDirectory("aiyou-full-synthetic-")
    private val bridge=root.resolve("sqlite_bridge.py")
    val mediaRoot=root.resolve("blobs").toFile();private val editRoot=root.resolve("edits").toFile()
    val db:PythonSqliteDatabase
    val gateway:DrainingRecordsSqlDatabase
    val tags:SqlManualTagRepository
    val packages:SqlRecordPackageRepository
    val media:EncryptedAttachmentStore
    val edits:EncryptedOriginalEditStore
    val capture:SqlCaptureRepository
    val backup:SqlFullBackupRepository
    var checkpoint:(String)->Unit={};var mediaCheckpoint:(String)->Unit={};var editCheckpoint:(String)->Unit={}
    init {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use { Files.copy(checkNotNull(it),bridge) }
        db=PythonSqliteDatabase(root.resolve("records.sqlite"),bridge,true,12)
        db.transaction { sql ->
            sql.execute("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            (RemoteTextSqlSchema.guards+LocalSearchSqlSchema.guards+PermanentErasureSqlSchema.guards+RecordFavoriteSqlSchema.guards+
                RecordEventDateSqlSchema.guards+LocalCandidateSqlSchema.guards+ManualPeopleSqlSchema.guards).forEach { sql.execute(it) }
            sql.execute(ManualTagSqlSchema.guard);sql.execute(ManualTaskSqlSchema.guard)
        }
        gateway=DrainingRecordsSqlDatabase(db);val hooks=SqlErasureFileHooks(gateway)
        tags=SqlManualTagRepository(gateway,key);packages=SqlRecordPackageRepository(gateway,tags)
        media=EncryptedAttachmentStore(mediaRoot,PermanentErasureFixture.Ledger(gateway),key.copyOf()) { mediaCheckpoint(it) }.also { it.installErasureHooks(hooks) }
        edits=EncryptedOriginalEditStore(editRoot,key.copyOf()) { editCheckpoint(it) }.also { it.installErasureHooks(hooks) }
        capture=SqlCaptureRepository(gateway,media,tags)
        val originals=SqlOriginalExportRepository(gateway,tags)
        val selected=SqlRecordMediaPackageRepository(gateway,originals,media,packages,edits=edits)
        backup=SqlFullBackupRepository(gateway,key,media,edits,selected) { checkpoint(it) }
    }
    fun entry(id:String=FullBackupTest.uuid(),text:String="合成原文"):String {
        db.transaction { sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(id))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(id,text,OriginalExports.hash(text)))
            sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
        };return id
    }
    fun revise(id:String,text:String) { db.transaction { sql ->
        sql.execute("INSERT INTO entry_revisions VALUES(?,2,?,?,6)",listOf(id,text,OriginalExports.hash(text)))
        sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?",listOf(id))
    } }
    suspend fun image(id:String,revision:Int=1):AttachmentIntent {
        val bytes=FullBackupTest.PNG;val intent=AttachmentIntent(FullBackupTest.uuid(),id,revision,"image/png",FullBackupTest.hash(bytes),bytes.size.toLong())
        media.save(intent) { bytes.inputStream() };return intent
    }
    override fun close() {
        gateway.seal();media.close();edits.close();media.awaitIdle();edits.awaitIdle();gateway.awaitIdle();tags.wipeKey();key.fill(0);db.close()
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
