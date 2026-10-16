package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real product JCA/framing, SQL transactions and controller; synthetic SQLite/platform adapters only. */
class RecordPackageActivitiesTest {
    @Test(timeout=20_000) fun kdfAndDocumentCloseFailuresConsumePassphraseAndNeverExposePreparedOrVerifiedCapability():Unit=runBlocking {
        PermanentErasureFixture().use {f -> Ports(f).use {p ->
            p.task("合成异常路径");val preview=p.preview(emptyList());val pass=password()
            rejects<RecordPackageInvalid> {RecordMediaPackageCipher(beforeKdf={error("Synthetic KDF failure")}).seal(preview,pass,{error("No image expected")},{true})}
            assertTrue(pass.all {it=='\u0000'})
            val bytes=p.export(preview);var closed=false;val second=password()
            rejects<RecordPackageInvalid> {RecordMediaPackageCipher().open({object:ByteArrayInputStream(bytes) {override fun close() {closed=true;error("Synthetic provider close failure")}}},second)}
            assertTrue(closed);assertTrue(second.all {it=='\u0000'});assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
        }}
    }
    @Test(timeout=20_000) fun independentV3NodeFixtureAndAuthenticatedMalformedMappingsCountsAndTerminalAreChecked():Unit=runBlocking {
        val fixture=Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/record-package-v3/fixture.json")).bufferedReader().use {it.readText()}).jsonObject
        assertEquals("v24.19.0",fixture.getValue("generatorNode").jsonPrimitive.content)
        val password=fixture.getValue("password").jsonPrimitive.content
        val bytes=unhex(fixture.getValue("envelopeHex").jsonPrimitive.content)
        val cipher=RecordMediaPackageCipher();val pass=password.toCharArray();val pack=cipher.open({bytes.inputStream()},pass)
        try {
            assertTrue(pass.all {it=='\u0000'});assertEquals(fixture.getValue("packageDigest").jsonPrimitive.content,pack.digest)
            assertEquals(ManualTaskStatus.COMPLETED,pack.activities!!.tasks.single().task.status)
            assertEquals("2026-",pack.activities!!.drafts.single().proposal.dateInput.date)
            assertEquals(listOf(7L),pack.activities!!.sourceGenerations)
            assertThrows(UnsupportedOperationException::class.java) {(pack.activities!!.tasks as MutableList).clear()}
        } finally {pack.release()}
        for((_,value) in fixture.getValue("malformedEnvelopes").jsonObject) {
            val bad=unhex(value.jsonPrimitive.content)
            rejects<RecordPackageInvalid> {cipher.open({bad.inputStream()},password.toCharArray())}
        }
    }
    @Test(timeout=20_000) fun mixedV3RestoresImagesTaskStatesExplicitDstAndRawDraftWithoutConfirmingOrAuthorizing():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target,ByteArray(32) {(it+73).toByte()}).use {t ->
                val id=source.entry();source.media.save(source.attachment(id,PNG)) {PNG.inputStream()}
                val binding=binding(source,id);val confirmed=s.draft(binding,"合成已确认任务");s.proposals.confirm(TaskProposalConfirm(confirmed,30,uuid()))
                val draft=s.draft(binding," 合成尚未确认输入 ",ManualTaskDateInput("2026-","","UTC"))
                val due=ManualTaskDateInput("2026-11-01","01:30","America/New_York",ManualTaskOccurrence.SECOND).parse()!!
                val open=s.task("合成明确重复时刻",due);val complete=s.task("合成完成");val cancelled=s.task("合成取消")
                s.tasks.change(ManualTaskMutation(complete.id,1,ManualTaskStatus.COMPLETED,40,uuid()))
                s.tasks.change(ManualTaskMutation(cancelled.id,1,ManualTaskStatus.CANCELLED,41,uuid()))
                val bytes=s.export(s.preview(listOf(id)));val newId=uuid()
                val verified=t.open(bytes)
                try {assertTrue(t.tasks.snapshots().isEmpty());assertTrue(t.proposals.list().isEmpty());assertEquals(4,verified.activities!!.tasks.size)} finally {verified.release()}
                val result=t.restore(bytes,listOf(newId),100)
                assertEquals(4,result.taskIds.size);assertEquals(1,result.proposalIds.size)
                val restored=t.tasks.snapshots();assertEquals(4,restored.size)
                assertEquals(due,restored.single {it.title==open.title}.schedule)
                assertEquals(ManualTaskStatus.COMPLETED,restored.single {it.title==complete.title}.status)
                assertEquals(ManualTaskStatus.CANCELLED,restored.single {it.title==cancelled.title}.status)
                val proposal=t.proposals.list().single();assertTrue(proposal.sourceAvailable)
                assertEquals(draft.titleInput,proposal.proposal.titleInput);assertEquals(draft.dateInput,proposal.proposal.dateInput)
                assertNotEquals(draft.id,proposal.proposal.id);assertEquals(SourceVersion(newId,1),proposal.proposal.binding.source)
                assertEquals(1L,proposal.proposal.binding.generation)
                val linked=restored.single {it.sourceReceipt!=null};var located:TaskSourceBinding?=null
                assertTrue(SqlSourceTaskRepository(target.gateway,t.tasks).locate(linked.id,linked.version) {located=it})
                assertEquals(newId,located!!.source.sourceId)
                assertEquals("AI_NONE",target.db.read("SELECT privacyPolicy FROM entries WHERE id=?",newId).single().single())
                assertEquals(listOf(null,null),target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?",newId).single())
                assertArrayEquals(PNG,target.media.read(target.media.list(newId,1).single().intent.id))
                assertTrue(target.db.read("SELECT id FROM manual_tasks WHERE id=?",draft.id).isEmpty())
                assertEquals(4,t.tasks.snapshots().size) // No draft confirmation or reminder side effect.
            }}
        }}
    }
    @Test(timeout=20_000) fun taskOnlyHasNoPhantomRecordAndUnmappedSourcesCannotBindMatchingTargetOriginal():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val id=source.entry();s.task("合成独立任务");val original=s.draft(binding(source,id),"合成来源不随包")
                target.entry(id) // Deliberately identical old source ID, text, revision and generation.
                val preview=s.preview(emptyList());assertEquals(-1,preview.activities!!.drafts.single().sourceRecord)
                val bytes=s.export(preview);assertEquals("AIYOUPK3",bytes.copyOfRange(0,8).toString(Charsets.US_ASCII))
                val result=t.restore(bytes,emptyList(),100);assertTrue(result.ids.isEmpty())
                assertEquals(1,target.db.read("SELECT id FROM entries").size);assertEquals(1,t.tasks.snapshots().size)
                val p=t.proposals.list().single();assertFalse(p.sourceAvailable)
                assertTrue(p.proposal.binding.source.sourceId.startsWith(RecordPackageActivities.UNAVAILABLE_PREFIX))
                assertNotEquals(id,p.proposal.binding.source.sourceId);assertEquals(original.titleInput,p.proposal.titleInput)
                assertThrows(TaskProposalConflict::class.java) {t.proposals.confirm(TaskProposalConfirm(p.proposal,200,uuid()))}
                assertTrue(target.db.read("SELECT id FROM local_jobs WHERE operation='PACKAGE_RESTORE'").isEmpty())
                assertEquals(1,t.tasks.snapshots().size)
            }}
        }}
    }
    @Test(timeout=20_000) fun changedGenerationKeepsDraftUnavailableInsteadOfGrantingAuthorityOnImport():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val id=source.entry();s.draft(binding(source,id),"合成旧代次草稿")
                source.db.write("UPDATE remote_source_access SET generation=2 WHERE entryId=?",id)
                val p=s.preview(listOf(id));assertEquals(-1,p.activities!!.drafts.single().sourceRecord)
                assertEquals(listOf(2L),p.activities!!.sourceGenerations)
                t.restore(s.export(p),listOf(uuid()),100)
                assertFalse(t.proposals.list().single().sourceAvailable);assertTrue(t.tasks.snapshots().isEmpty())
            }}
        }}
    }
    @Test(timeout=20_000) fun lostFinalCommitAckColdReplayPreservesLaterTaskAndConsumedDraftWithoutAnyRewrite():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s ->
                val id=source.entry();s.task("合成稍后更新");s.draft(binding(source,id),"合成可确认草稿")
                val bytes=s.export(s.preview(listOf(id)));val newId=uuid();var t=Ports(target)
                try {
                    var committing=false;var lost=false
                    target.db.afterSql={sql ->
                        if(sql.startsWith("INSERT INTO record_package_receipts")&&sql.contains("'PACKAGE_RESTORE'")) committing=true
                        if(sql=="COMMIT"&&committing&&!lost) {lost=true;error("Synthetic lost activity commit ACK")}
                    }
                    rejects<RecordPackageStorageFailure> {t.restore(bytes,listOf(newId),100)};assertTrue(lost)
                    target.db.afterSql=null;t.close();target.reopen();t=Ports(target)
                    val old=t.tasks.snapshots().single();t.tasks.change(ManualTaskMutation(old.id,old.version,ManualTaskStatus.COMPLETED,200,uuid()))
                    val p=t.proposals.list().single().proposal;t.proposals.confirm(TaskProposalConfirm(p,201,uuid()))
                    val before=t.taskBytes();val retry=t.restore(bytes,listOf(uuid()),999)
                    assertTrue(retry.replayed);assertEquals(listOf(newId),retry.ids);assertEquals(100L,retry.importedAtMillis)
                    assertEquals(before,t.taskBytes());assertEquals(2,t.tasks.snapshots().size);assertTrue(t.proposals.list().isEmpty())
                } finally {target.db.afterSql=null;t.close()}
            }
        }}
    }
    @Test(timeout=20_000) fun actualPermanentErasureRemovesMappedDraftButReplayKeepsFormalTaskAndNeverResurrects():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val id=source.entry();s.task("合成独立保留");s.draft(binding(source,id),"合成随后删除草稿")
                val bytes=s.export(s.preview(listOf(id)));val restored=t.restore(bytes,listOf(uuid()),100)
                val erase=SqlPermanentErasureRepository(target.gateway,target.media,target.edits,t.packages,t.proposals::readAllWithin)
                assertTrue(erase.erase(target.intent(restored.ids.single())).complete)
                assertTrue(t.proposals.list().isEmpty());val before=t.taskBytes()
                val replay=t.restore(bytes,listOf(uuid()),300)
                assertTrue(replay.replayed);assertEquals(1,replay.deletedCount);assertEquals(before,t.taskBytes())
                assertTrue(target.db.read("SELECT id FROM entries").isEmpty());assertEquals(1,t.tasks.snapshots().size)
                assertEquals(1,target.db.read("SELECT id FROM record_package_receipts WHERE operation='PACKAGE_TASK_RESTORE'").size)
            }}
        }}
    }
    @Test(timeout=20_000) fun draftInsertFailureRollsBackRecordsTasksImagesAndReceiptsAndRecoverAllowsOneRetry():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val id=source.entry();source.media.save(source.attachment(id,PNG)) {PNG.inputStream()};s.task("合成整批任务");s.draft(binding(source,id),"合成整批草稿")
                val bytes=s.export(s.preview(listOf(id)))
                target.db.write("CREATE TEMP TRIGGER reject_activity BEFORE INSERT ON manual_tasks WHEN substr(NEW.id,1,14)='TASK_PROPOSAL:' BEGIN SELECT RAISE(ABORT,'synthetic draft abort'); END")
                rejects<RecordPackageStorageFailure> {t.restore(bytes,listOf(uuid()),100)}
                assertTrue(target.db.read("SELECT id FROM entries").isEmpty());assertTrue(t.taskBytes().isEmpty());assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
                assertTrue(target.db.read("SELECT id FROM record_package_receipts WHERE operation IN ('PACKAGE_RESTORE','PACKAGE_TASK_RESTORE')").isEmpty())
                target.db.write("DROP TRIGGER reject_activity");t.repo.recover()
                val retry=t.restore(bytes,listOf(uuid()),200);assertFalse(retry.replayed)
                assertEquals(1,t.tasks.snapshots().size);assertEquals(1,t.proposals.list().size);assertEquals(1,target.db.read("SELECT id FROM entries").size)
            }}
        }}
    }
    @Test(timeout=20_000) fun selectedDocumentChangedAfterPreviewCannotCommitAnyActivityPrefix():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val id=source.entry();source.media.save(source.attachment(id,PNG)) {PNG.inputStream()};s.task("合成完整认证")
                val bytes=s.export(s.preview(listOf(id)));var selected=bytes
                val verified=t.repo.open({selected.inputStream()},password())
                try {
                    selected=bytes.copyOf().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
                    rejects<RecordPackageStorageFailure> {t.repo.restore(verified,listOf(uuid()),100)}
                    assertTrue(target.db.read("SELECT id FROM entries").isEmpty());assertTrue(t.tasks.snapshots().isEmpty())
                    assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
                } finally {verified.release()}
            }}
        }}
    }
    @Test(timeout=20_000) fun activityChangesBeforeWriteAndOversizedInventoryFailInsteadOfSilentlyDroppingItems():Unit=runBlocking {
        PermanentErasureFixture().use {f -> Ports(f).use {p ->
            val task=p.task("合成待变化");val preview=p.preview(emptyList());val prepared=p.repo.seal(preview,password())
            p.tasks.change(ManualTaskMutation(task.id,1,ManualTaskStatus.COMPLETED,100,uuid()))
            assertFalse(p.repo.isCurrent(preview));val out=ByteArrayOutputStream()
            rejects<IllegalStateException> {prepared.writeTo(out) {true}};assertEquals(0,out.size());prepared.release()
            repeat(256) {p.task("合成超限 $it")}
            rejects<RecordPackageStorageFailure> {p.preview(emptyList())}
        }}
    }
    @Test(timeout=20_000) fun sameBackupIdDifferentAuthenticatedContentConflictsWithoutOverwritingImportedTask():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val task=s.task("合成包身份");val preview=s.preview(emptyList());val first=s.export(preview);t.restore(first,emptyList(),100)
                s.tasks.change(ManualTaskMutation(task.id,1,ManualTaskStatus.COMPLETED,50,uuid()))
                val different=s.export(s.repo.previewActivities(preview.snapshot));val before=t.taskBytes()
                rejects<RecordPackageStorageFailure> {t.restore(different,emptyList(),200)}
                assertEquals(before,t.taskBytes());assertEquals(ManualTaskStatus.OPEN,t.tasks.snapshots().single().status)
            }}
        }}
    }
    @Test(timeout=20_000) fun independentSqliteOwnersRaceTaskOnlyPackageAndCommitExactlyOneTaskAndDraft():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s ->
                val id=source.entry();s.task("合成并发任务");s.draft(binding(source,id),"合成并发草稿");val bytes=s.export(s.preview(emptyList()))
                val secondPlanned=CountDownLatch(1);val firstCommitted=CountDownLatch(1);var finalReceipt=false
                target.db.afterSql={sql ->
                    if(sql.startsWith("INSERT INTO record_package_receipts")&&sql.contains("'PACKAGE_RESTORE'")) finalReceipt=true
                    if(sql=="COMMIT"&&finalReceipt) firstCommitted.countDown()
                }
                target.second().use {other ->
                    val first=ObservedDatabase(target.gateway,"first") {n ->if(n==1) check(secondPlanned.await(5,TimeUnit.SECONDS))}
                    val second=ObservedDatabase(other,"second") {n ->if(n==1) {secondPlanned.countDown();check(firstCommitted.await(5,TimeUnit.SECONDS))}}
                    Ports(target,database=first).use {one -> Ports(target,database=second).use {two ->
                    assertNotEquals(target.db.pid,other.pid);val pool=Executors.newFixedThreadPool(2);val go=CountDownLatch(1)
                    try {
                        val futures=listOf(one,two).map {p ->pool.submit<RecordPackageRestoreResult> {go.await();runBlocking {p.restore(bytes,emptyList(),100)}}}
                        go.countDown();val results=futures.map {it.get(8,TimeUnit.SECONDS)}
                        assertEquals(1,results.count {it.replayed});assertEquals(results[0].taskIds,results[1].taskIds)
                        assertEquals(results[0].proposalIds,results[1].proposalIds);assertEquals(1,one.tasks.snapshots().size);assertEquals(1,one.proposals.list().size)
                    } finally {secondPlanned.countDown();firstCommitted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));target.db.afterSql=null}
                }}}
            }
        }}
    }
    /** Failure-only bounded synthetic diagnostics preserve the exception hidden by public storage errors. */
    private class ObservedDatabase(private val delegate:RecordsSqlDatabase,private val label:String,private val after:(Int)->Unit):RecordsSqlDatabase {
        private val calls=AtomicInteger()
        override fun <T> transaction(block:(RecordsSqlConnection)->T):T = try {
            val n=calls.incrementAndGet();delegate.transaction(block).also {after(n)}
        } catch(e:Exception) {
            System.err.println("Synthetic race $label: ${e.javaClass.name}: ${e.message?.take(200)}\n"+e.stackTrace.take(20).joinToString("\n"));throw e
        }
    }
    @Test(timeout=20_000) fun actualControllerTaskOnlyPreviewAndReadbackRetryRefreshListsWithoutConfirmingDraft():Unit=runBlocking {
        PermanentErasureFixture().use {source -> PermanentErasureFixture().use {target ->
            Ports(source).use {s -> Ports(target).use {t ->
                val id=source.entry();s.task("合成控制器任务");s.draft(binding(source,id),"合成控制器草稿");val bytes=s.export(s.preview(emptyList()))
                var local=LocalRecordUiState(ready=true,opening=false,supportsRecordPackages=true,text="合成仍在编辑的记录输入")
                val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var failRead=true
                val owner=object:EntryRepository {
                    override val originalExport=t.originals;override val mediaPackages=t.repo;override val recordPackages=t.packages;override val manualTags=target.tags
                    override val manualTasks=object:ManualTaskRepository by t.tasks {override fun snapshots():List<ManualTaskSnapshot> {if(failRead) {failRead=false;error("Synthetic activity readback failure")};return t.tasks.snapshots()}}
                    override val taskProposals=t.proposals
                    override suspend fun entries()=emptyList<StoredEntry>()
                    override suspend fun readDraft()=null
                    override suspend fun updateDraft(text:String)=error("Not requested")
                    override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not requested")
                    override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
                    override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not requested")
                }
                val c=RecordPackageController({local},{local=it(local)},{queue.add(it);true},JcaRecordPackageCipher(),Clock.fixed(Instant.ofEpochMilli(500),ZoneOffset.UTC))
                suspend fun drain() {while(queue.isNotEmpty()) queue.removeFirst()(owner)}
                fun confirm() {val ui=local.recordPackage!!;val pack=ui.verified!!;c.confirmRestore(ui.session,pack.manifest.backupId,pack.digest,ui.epoch)}
                try {
                    c.open();val session=local.recordPackage!!.session;c.selectExport(session);c.includeActivities(session,true);c.prepare(session);drain()
                    assertEquals(RecordPackagePhase.FAILED,local.recordPackage!!.phase) // Empty target inventory is never a successful empty backup.
                    c.chooseRestore(session);val key=local.recordPackage!!.requestKey!!;assertTrue(c.claimPicker(session,key))
                    c.picked(session,key,object:SelectedRecordPackageDocument {override fun openForRead()=bytes.inputStream();override fun openForWrite():OutputStream=error("Not requested")})
                    c.authenticate(session,password());drain();assertEquals(RecordPackagePhase.RESTORE_PREVIEW,local.recordPackage!!.phase)
                    assertTrue(t.tasks.snapshots().isEmpty());confirm();drain();assertEquals(RecordPackagePhase.UNCERTAIN,local.recordPackage!!.phase)
                    val before=t.taskBytes();assertEquals(1,t.tasks.snapshots().size);assertEquals(1,t.proposals.list().size)
                    confirm();drain();assertEquals(RecordPackagePhase.DONE,local.recordPackage!!.phase);assertEquals(before,t.taskBytes())
                    assertEquals(1,local.tasks.items.size);assertEquals(1,local.taskProposals.items.size);assertEquals("合成仍在编辑的记录输入",local.text)
                } finally {c.dispose()}
            }}
        }}
    }
    private class Ports(val f:PermanentErasureFixture,key:ByteArray=PermanentErasureFixture.KEY,database:RecordsSqlDatabase=f.gateway):AutoCloseable {
        val tasks=SqlManualTaskRepository(database,key);val proposals=SqlTaskProposalRepository(database,key,tasks)
        val activities=SqlRecordPackageActivities(database,tasks,proposals)
        val packages=SqlRecordPackageRepository(database,f.tags,activities=activities)
        val originals=SqlOriginalExportRepository(database,f.tags)
        val repo=SqlRecordMediaPackageRepository(database,originals,f.media,packages,activities)
        fun task(title:String,schedule:ManualTaskSchedule?=null)=tasks.create(ManualTaskCreate(uuid(),title,10,"UTC",uuid(),schedule))
        fun draft(b:TaskSourceBinding,title:String,date:ManualTaskDateInput=ManualTaskDateInput())=proposals.write(TaskProposalWrite(uuid(),0,b,title,date,10,"UTC",10,uuid()))
        suspend fun preview(ids:List<String>):RecordMediaPreview {
            val snapshot=if(ids.isEmpty()) OriginalExportSnapshot(uuid(),50,emptyList(),true) else originals.capture(ids.map {SourceVersion(it,1)},uuid(),50)
            return repo.previewActivities(snapshot)
        }
        suspend fun export(p:RecordMediaPreview):ByteArray {val prepared=repo.seal(p,password());return ByteArrayOutputStream().use {o ->try {prepared.writeTo(o) {true};o.toByteArray()} finally {prepared.release()}}}
        suspend fun open(bytes:ByteArray)=repo.open({bytes.inputStream()},password())
        suspend fun restore(bytes:ByteArray,ids:List<String>,at:Long):RecordPackageRestoreResult {val pack=open(bytes);return try {repo.restore(pack,ids,at)} finally {pack.release()}}
        fun taskBytes()=f.db.read("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks ORDER BY id").map {it.take(3)+Base64.getEncoder().encodeToString(it[3] as ByteArray)}
        override fun close() {proposals.wipeKey();tasks.wipeKey()}
    }
    companion object {
        private fun uuid()=UUID.randomUUID().toString()
        private fun password()="synthetic activities recovery 42".toCharArray()
        private fun binding(f:PermanentErasureFixture,id:String):TaskSourceBinding {val access=SqlLocalProcessingRepository(f.gateway).read(SourceVersion(id,1));return TaskSourceBinding(access.source,access.sourceHash,access.generation,0,5)}
        private fun unhex(s:String)=ByteArray(s.length/2) {s.substring(it*2,it*2+2).toInt(16).toByte()}
        private suspend inline fun <reified T:Throwable> rejects(action:suspend ()->Any?) {try {action();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}}
        private val PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    }
}
