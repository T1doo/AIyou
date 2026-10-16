package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual task/proposal AEAD, SQL, codecs, App controller and selected-document IO; synthetic data only. */
class OriginalActivityExportTest {
    @Test fun standardJsonlSeparatesAllFormalStatesFromRawDraftAndRetainsExactSelectedSourceWithoutWrites()=H().use { h ->
        val policy=SqlLocalProcessingRepository(h.f.gateway)
        policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"none"))
        val b=h.binding();val source=SqlSourceTaskRepository(h.f.gateway,h.tasks)
        val timed=source.create(SourceTaskCreate(source.preview(b),ManualTaskCreate(SourceTasks.id(b),FORMAL,50,"UTC","source-create",
            ManualTaskDateInput("2026-11-01","01:30","America/New_York",ManualTaskOccurrence.SECOND).parse())))
        h.tasks.change(ManualTaskMutation(timed.id,1,ManualTaskStatus.COMPLETED,60,"complete"))
        h.task("open");val cancelled=h.task("cancelled");h.tasks.change(ManualTaskMutation(cancelled.id,1,ManualTaskStatus.CANCELLED,60,"cancel"))
        h.draft();val rejected=h.draft("rejected")
        h.proposals.write(TaskProposalWrite(rejected.id,1,b,RAW,rejected.dateInput,50,"UTC",60,"reject",TaskProposalAction.REJECT))
        val snapshot=h.snapshot();val before=h.f.snapshotOld();val a=h.originals.captureActivities(snapshot)
        val payload=OriginalActivityExportCodec.render(snapshot,a,OriginalExportFormat.JSONL)
        try {
            val lines=payload.copyBytes().toString(Charsets.UTF_8).trimEnd('\n').split('\n');val rows=lines.map {Json.parseToJsonElement(it).jsonObject}
            val m=rows.first();assertEquals(1,m["formatVersion"]!!.jsonPrimitive.int);assertFalse(m["encrypted"]!!.jsonPrimitive.boolean)
            assertEquals(1,m["recordCount"]!!.jsonPrimitive.int);assertEquals(3,m["taskCount"]!!.jsonPrimitive.int);assertEquals(1,m["proposalCount"]!!.jsonPrimitive.int)
            assertEquals(setOf("OPEN","COMPLETED","CANCELLED"),rows.filter {it.type()=="manualTask"}.map {it["status"]!!.jsonPrimitive.content}.toSet())
            val t=rows.single {it.type()=="manualTask" && it["id"]!!.jsonPrimitive.content==timed.id}
            val s=t["schedule"]!!.jsonObject;assertEquals(-18000,s["offsetSeconds"]!!.jsonPrimitive.int);assertEquals("SECOND",s["occurrence"]!!.jsonPrimitive.content)
            assertEquals(FORMAL,t["title"]!!.jsonPrimitive.content);assertEquals(0,t["source"]!!.jsonObject["includedOriginalIndex"]!!.jsonPrimitive.int)
            val original=rows.single {it.type()=="currentOriginal"};assertEquals(b.generation,original["sourceGeneration"]!!.jsonPrimitive.long)
            val d=rows.single {it.type()=="taskProposal"};assertEquals("DRAFT",d["reviewState"]!!.jsonPrimitive.content);assertEquals(RAW,d["titleInput"]!!.jsonPrimitive.content)
            assertEquals("2026-",d["dateInput"]!!.jsonObject["date"]!!.jsonPrimitive.content);assertEquals("03:",d["dateInput"]!!.jsonObject["time"]!!.jsonPrimitive.content)
            assertEquals("SECOND",d["dateInput"]!!.jsonObject["occurrence"]!!.jsonPrimitive.content)
            val ref=d["source"]!!.jsonObject;assertEquals(b.sourceHash,ref["contentHash"]!!.jsonPrimitive.content);assertEquals(b.generation,ref["generation"]!!.jsonPrimitive.long)
            assertEquals(b.start,ref["startCodePoint"]!!.jsonPrimitive.int);assertEquals(b.end,ref["endCodePoint"]!!.jsonPrimitive.int)
            assertEquals(OriginalExports.hash(lines.drop(1).dropLast(1).joinToString("\n",postfix="\n")),rows.last()["entitiesSha256"]!!.jsonPrimitive.content)
            assertFalse(payload.toString().contains(RAW));assertFalse(a.toString().contains(RAW));assertEquals(before,h.f.snapshotOld())
            assertEquals(ProcessingPolicy.AI_NONE,policy.read(h.f.source).policy);assertTrue(h.f.db.read("SELECT id FROM remote_text_jobs").isEmpty())
        } finally {payload.release()}
    }
    @Test fun taskOnlyMarkdownAndJsonlPreserveLiteralDraftWithoutPretendingItsSourceIsIncluded():Unit=H().use { h ->
        h.draft();h.task();val snapshot=h.snapshot(false);val a=h.originals.captureActivities(snapshot)
        for(format in listOf(OriginalExportFormat.JSONL,OriginalExportFormat.MARKDOWN)) {
            val p=OriginalActivityExportCodec.render(snapshot,a,format)
            try {
                val text=p.copyBytes().toString(Charsets.UTF_8)
                val row=text.lines().first {it.startsWith("{\"type\":\"taskProposal\"")}.let {Json.parseToJsonElement(it).jsonObject}
                assertEquals(JsonNull,row["source"]!!.jsonObject["includedOriginalIndex"]);assertEquals("source",row["source"]!!.jsonObject["sourceId"]!!.jsonPrimitive.content)
                assertFalse(text.contains("{\"type\":\"currentOriginal\""));assertTrue(text.contains("\"recordCount\":0"))
                if(format==OriginalExportFormat.MARKDOWN) {assertTrue(text.contains("````text\n$RAW\n````"));assertTrue(text.contains("DRAFT，未执行"))}
            } finally {p.release();assertThrows(IllegalStateException::class.java) {p.copyBytes()}}
        }
        assertThrows(IllegalArgumentException::class.java) {OriginalActivityExportCodec.render(snapshot,a,OriginalExportFormat.MEDIA_ZIP)}
    }
    @Test fun changedOriginalGenerationAndTamperedSelectedMappingCannotAcquireAFalseSourceAssociation():Unit=H().use { h ->
        h.draft();val snapshot=h.snapshot();val a=h.originals.captureActivities(snapshot)
        val port=SqlLocalProcessingRepository(h.f.gateway);port.change(LocalProcessingChange(port.read(h.f.source),LocalProcessingChoice.AI_NONE,"changed-generation"))
        assertFalse(h.originals.areActivitiesCurrent(snapshot,a));assertThrows(OriginalExportConflict::class.java) {h.originals.captureActivities(snapshot)}
        val fresh=h.snapshot();val next=h.originals.captureActivities(fresh);assertEquals(-1,next.drafts.single().sourceRecord)
        val forged=RecordPackageActivities(emptyList(),listOf(next.drafts.single().copy(sourceRecord=0)),next.sourceGenerations)
        assertThrows(IllegalArgumentException::class.java) {OriginalActivityExportCodec.render(fresh,forged,OriginalExportFormat.JSONL)}
    }
    @Test fun textExportDoesNotAuthenticateExcludedImagesAndEmptyScopeIsAnExplicitFailure()=H().use { h ->
        assertThrows(OriginalExportEmptyScope::class.java) {h.originals.captureActivities(h.snapshot(false))}
        h.draft();h.f.db.transaction {it.execute("INSERT INTO attachments VALUES(?, 'source',1,'image/png',?,5,'FAILED')",listOf(UUID.randomUUID().toString(),"a".repeat(64)))}
        val s=h.snapshot();assertEquals(1,s.records.single().imageCount)
        OriginalActivityExportCodec.render(s,h.originals.captureActivities(s),OriginalExportFormat.JSONL).release()
        assertEquals("FAILED",h.f.db.read("SELECT state FROM attachments").single().single());assertEquals(0,h.tasks.snapshots().size)
    }
    @Test fun oversizedFormalInventoryAndCorruptDraftFailWholeSnapshotAndEncodedSizeIsBounded() {
        H().use {h ->h.draft();h.f.db.transaction {it.execute("UPDATE manual_tasks SET version=2,lastMutationId='synthetic-corrupt',ciphertext=? WHERE id=?",listOf(byteArrayOf(1,2,3),TaskProposals.storageId("draft")))}
            assertThrows(OriginalExportStorageFailure::class.java) {h.originals.captureActivities(h.snapshot(false))};assertEquals(1L,h.f.db.read("SELECT count(*) FROM manual_tasks").single().single())}
        val s=OriginalExportSnapshot("size",1,emptyList(),true)
        val tasks=(0 until 256).map {RecordPackageTask(ManualTaskSnapshot("task-$it",1,"x"+"\u2028".repeat(238)+"x",ManualTaskStatus.OPEN,1,"UTC",1))}
        val binding=TaskSourceBinding(SourceVersion("source",1),"a".repeat(64),1,0,1)
        val drafts=(0 until 256).map {RecordPackageDraft(TaskProposal("draft-$it",1,binding,"\u0001".repeat(960),ManualTaskDateInput(),1,"UTC",1))}
        val a=RecordPackageActivities(tasks,drafts)
        assertThrows(OriginalExportTooLarge::class.java) {OriginalActivityExportCodec.render(s,a,OriginalExportFormat.MARKDOWN)}
    }
    @Test fun formalInventoryAt256IsCompleteAnd257RejectsWholePreviewWithActionableLimitWithoutWrites()=H().use {h ->
        repeat(256) {h.task("task-$it")};val snapshot=h.snapshot(false)
        assertEquals(256,h.originals.captureActivities(snapshot).tasks.size)
        h.task("task-256");val before=h.f.snapshotOld()
        val limit=assertThrows(RecordPackageActivityLimit::class.java) {h.originals.captureActivities(snapshot)}
        assertEquals(RecordPackageActivityBound.FORMAL_TASKS,limit.bound)
        h.controller.open();h.controller.includeActivities(h.ui().session,true);h.controller.prepare(h.ui().session);h.drain()
        assertEquals(OriginalExportPhase.FAILED,h.ui().phase);assertNull(h.ui().snapshot);assertNull(h.ui().activities)
        assertNull(h.ui().requestKey);assertFalse(h.ui().hasFile);assertFalse(h.ui().riskAccepted)
        assertTrue(h.ui().error!!.contains("正式待办超过 256 条"));assertTrue(h.ui().error!!.contains("取消“包含全部正式待办与已保存待确认草稿”"))
        assertEquals(before,h.f.snapshotOld());assertEquals(257,h.tasks.snapshots().size);assertTrue(h.queue.isEmpty())
    }
    @Test fun authenticatedDraftAndPhysicalHistoryBoundsAreDistinctAndNeverTruncate():Unit {
        H().use {h ->
            h.seedProposals(0,256,TaskProposalState.DRAFT)
            assertEquals(256,h.originals.captureActivities(h.snapshot(false)).drafts.size)
            h.seedProposals(256,1,TaskProposalState.DRAFT);val before=h.f.snapshotOld()
            assertEquals(RecordPackageActivityBound.SAVED_DRAFTS,
                assertThrows(RecordPackageActivityLimit::class.java) {h.originals.captureActivities(h.snapshot(false))}.bound)
            assertEquals(before,h.f.snapshotOld())
        }
        H().use {h ->
            h.task();h.seedProposals(0,4096,TaskProposalState.REJECTED)
            val a=h.originals.captureActivities(h.snapshot(false));assertEquals(1,a.tasks.size);assertTrue(a.drafts.isEmpty())
            h.seedProposals(4096,1,TaskProposalState.REJECTED);val before=h.f.snapshotOld()
            assertEquals(RecordPackageActivityBound.PROPOSAL_LEDGER,
                assertThrows(RecordPackageActivityLimit::class.java) {h.originals.captureActivities(h.snapshot(false))}.bound)
            assertEquals(before,h.f.snapshotOld())
        }
    }
    @Test fun activityPickerCancelReselectIgnoresOldKeyAndDuplicatePickedWritesExactlyOnce():Unit {
        for(format in listOf(OriginalExportFormat.MARKDOWN,OriginalExportFormat.JSONL)) H().use {h ->
            h.task();h.draft();h.preview();h.controller.format(h.ui().session,format);h.ready()
            val session=h.ui().session;val oldKey=h.ui().requestKey!!;val oldFile=Document()
            h.controller.picked(session,oldKey,null)
            assertEquals(OriginalExportPhase.PREVIEW,h.ui().phase);assertFalse(h.ui().riskAccepted);assertNull(h.ui().requestKey)
            h.choose();assertTrue(h.queue.isEmpty());h.ready();val key=h.ui().requestKey!!;assertNotEquals(oldKey,key)
            h.controller.picked(session,oldKey,oldFile);h.controller.picked(session,oldKey,null)
            assertEquals(OriginalExportPhase.PICKING,h.ui().phase);assertEquals(key,h.ui().requestKey);assertTrue(h.queue.isEmpty())
            val file=Document();val duplicate=Document();h.controller.picked(session,key,file);h.controller.picked(session,key,duplicate)
            assertEquals(1,h.queue.size);h.drain();h.controller.picked(session,key,duplicate);h.drain()
            assertEquals(OriginalExportPhase.DONE,h.ui().phase);assertEquals(1,file.opens);assertEquals(1,file.reads)
            assertEquals(0,oldFile.opens);assertEquals(0,duplicate.opens);assertEquals(0,file.deletes)
            val text=file.bytes().toString(Charsets.UTF_8);assertTrue(text.contains("\"type\":\"manualTask\""));assertTrue(text.contains("\"type\":\"taskProposal\""))
            assertEquals(1,h.tasks.snapshots().size);assertEquals(TaskProposalState.DRAFT,h.proposals.list().single().proposal.state)
            assertEquals("合成未保存输入",h.local.text)
        }
    }
    @Test fun activityLimitReachedDuringWriteKeepsTypedFailureAfterReadbackAndPreservesSelectedFile()=H().use {h ->
        repeat(256) {h.task("task-$it")};h.preview();h.ready();val file=Document()
        file.onWrite={file.onWrite=null;h.task("task-256")}
        h.controller.picked(h.ui().session,h.ui().requestKey!!,file);h.drain()
        assertEquals(OriginalExportPhase.FAILED,h.ui().phase);assertNull(h.ui().notice)
        assertTrue(h.ui().error!!.contains("正式待办超过 256 条"));assertTrue(h.ui().error!!.contains("所选文件尚未确认"))
        assertTrue(h.ui().hasFile);assertTrue(file.closed);assertTrue(file.inputClosed)
        assertEquals(1,file.opens);assertEquals(1,file.reads);assertEquals(0,file.deletes)
        assertEquals(257,h.tasks.snapshots().size)
    }
    @Test fun actualControllerRequiresExplicitScopeAndConsentAndRejectsStaleScopeCallbacks()=H().use {h ->
        h.task();h.draft();h.controller.open();assertFalse(h.ui().includeActivities);h.controller.prepare(h.ui().session);assertTrue(h.queue.isEmpty())
        h.preview();val old=h.ui();assertEquals(0,old.snapshot!!.records.size);assertEquals(1,old.activities!!.drafts.size)
        h.controller.choose(old.session,old.snapshot.id,old.format,old.epoch);assertTrue(h.queue.isEmpty())
        h.accept();h.controller.format(old.session,OriginalExportFormat.JSONL);assertFalse(h.ui().riskAccepted)
        h.controller.acceptRisk(old.session,old.snapshot.id,old.format,old.epoch,true);assertFalse(h.ui().riskAccepted)
        h.ready();val staleKey=h.ui().requestKey!!;h.controller.close(old.session);h.preview();val file=Document()
        h.controller.picked(old.session,staleKey,file);assertEquals(0,file.opens);assertTrue(h.queue.isEmpty())
        h.controller.format(h.ui().session,OriginalExportFormat.MEDIA_ZIP);assertFalse(h.ui().includeActivities);assertNull(h.ui().activities);assertNull(h.ui().snapshot)
        h.controller.prepare(h.ui().session);assertTrue(h.queue.isEmpty());assertEquals("合成未保存输入",h.local.text)
    }
    @Test fun actualControllerRejectsTaskOrDraftChangesBeforePickerAndBeforeWriting() {
        H().use {h ->val task=h.task();h.draft();h.preview();h.accept();h.tasks.change(ManualTaskMutation(task.id,1,ManualTaskStatus.COMPLETED,60,"done"))
            h.choose();h.drain();assertEquals(OriginalExportPhase.CHANGED,h.ui().phase);assertNull(h.ui().requestKey)}
        H().use {h ->h.task();val draft=h.draft();h.preview();h.ready();val key=h.ui().requestKey!!
            h.proposals.write(TaskProposalWrite(draft.id,1,draft.binding,RAW,draft.dateInput,50,"UTC",60,"reject",TaskProposalAction.REJECT))
            val file=Document();h.controller.picked(h.ui().session,key,file);h.drain();assertEquals(OriginalExportPhase.CHANGED,h.ui().phase)
            assertEquals(0,file.opens);assertEquals(0,file.deletes);assertTrue(h.ui().hasFile);assertNull(h.ui().notice)}
    }
    @Test fun actualControllerWritesStandardTextAndReadbackThenFailsAnyActivityChangeDuringWrite() {
        H().use {h ->h.task();h.draft();h.preview();h.controller.format(h.ui().session,OriginalExportFormat.JSONL);h.ready();val file=Document()
            h.controller.picked(h.ui().session,h.ui().requestKey!!,file);h.drain();assertEquals(OriginalExportPhase.DONE,h.ui().phase)
            assertTrue(file.closed);assertTrue(file.inputClosed);assertEquals(1,file.reads);assertEquals(0,file.deletes)
            assertEquals(1,file.bytes().toString(Charsets.UTF_8).lines().filter {it.startsWith("{\"type\":\"taskProposal\"")}.size)
            assertEquals(1,h.proposals.list().size);assertEquals(1,h.tasks.snapshots().size);assertEquals("合成未保存输入",h.local.text)}
        H().use {h ->val task=h.task();h.draft();h.preview();h.ready();val file=Document()
            file.onWrite={file.onWrite=null;h.tasks.change(ManualTaskMutation(task.id,1,ManualTaskStatus.COMPLETED,60,"during"))}
            h.controller.picked(h.ui().session,h.ui().requestKey!!,file);h.drain();assertEquals(OriginalExportPhase.CHANGED,h.ui().phase)
            assertNull(h.ui().notice);assertTrue(h.ui().error!!.contains("文件可能不完整"));assertEquals(0,file.deletes);assertTrue(file.closed)}
    }
    @Test fun actualActivityExportCancellationAndReadbackFailureLeaveDocumentUntilExactExplicitDeletion() {
        for(mode in listOf("cancel","read","close","mismatch")) H().use {h ->
            h.task();h.draft();h.preview();h.ready();val session=h.ui().session;val key=h.ui().requestKey!!;val file=Document(mode)
            if(mode=="cancel") file.onWrite={h.controller.cancel(session)}
            h.controller.picked(session,key,file);h.drain()
            assertEquals(if(mode=="cancel") OriginalExportPhase.CANCELLED else OriginalExportPhase.FAILED,h.ui().phase)
            assertNull(h.ui().notice?.takeUnless {mode=="cancel"});assertTrue(file.closed);assertEquals(0,file.deletes);assertTrue(h.ui().hasFile)
            h.controller.deleteSelectedFile(session,"foreign");assertEquals(0,file.deletes);h.controller.deleteSelectedFile(session,key);h.drain();assertEquals(1,file.deletes)
            assertFalse(h.ui().riskAccepted);assertEquals(1,h.proposals.list().size)
        }
    }
    @Test fun unreadableActivityAfterDocumentReadbackRemainsUnknownWithNoSuccessOrAutomaticDeletion()=H().use {h ->
        h.task();h.draft();h.preview();h.ready();val file=Document()
        file.onWrite={file.onWrite=null;h.f.db.transaction {it.execute("UPDATE manual_tasks SET version=2,lastMutationId='synthetic-corrupt',ciphertext=? WHERE id='task'",listOf(byteArrayOf(1,2,3)))}}
        h.controller.picked(h.ui().session,h.ui().requestKey!!,file);h.drain()
        assertEquals(OriginalExportPhase.FAILED,h.ui().phase);assertNull(h.ui().notice);assertFalse(h.ui().error!!.contains("已变化"))
        assertTrue(h.ui().error!!.contains("尚未确认"));assertTrue(h.ui().hasFile);assertTrue(file.closed);assertTrue(file.inputClosed);assertEquals(0,file.deletes)
    }
    private class H:AutoCloseable {
        val f=LocalCandidateFixture();val tags=SqlManualTagRepository(f.gateway,LocalCandidateFixture.KEY)
        val tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY);val proposals=SqlTaskProposalRepository(f.gateway,LocalCandidateFixture.KEY,tasks)
        private val activities=SqlRecordPackageActivities(f.gateway,tasks,proposals)
        val originals=SqlOriginalExportRepository(f.gateway,tags,activities)
        var local=LocalRecordUiState(ready=true,opening=false,supportsOriginalExport=true,text="合成未保存输入")
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        val controller=OriginalExportController({local},{local=it(local)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
        val store=object:EntryRepository {
            override val originalExport=originals;override val manualTasks=tasks;override val taskProposals=proposals
            override suspend fun entries()=emptyList<StoredEntry>();override suspend fun readDraft()=null
            override suspend fun updateDraft(text:String)=error("No write")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No write")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No write")
        }
        fun binding():TaskSourceBinding {val p=SqlLocalProcessingRepository(f.gateway).read(f.source);return TaskSourceBinding(p.source,p.sourceHash,p.generation,0,5)}
        fun task(id:String="task")=tasks.create(ManualTaskCreate(id,FORMAL,50,"UTC","create-$id"))
        fun draft(id:String="draft")=proposals.write(TaskProposalWrite(id,0,binding(),RAW,ManualTaskDateInput("2026-","03:","UTC",ManualTaskOccurrence.SECOND),50,"UTC",50,"save-$id"))
        // Authenticate oversized persisted inventories without weakening the normal 256-DRAFT write guard.
        fun seedProposals(start:Int,count:Int,state:TaskProposalState) {
            val cipher=TaskProposalCipher(LocalCandidateFixture.KEY);val binding=binding();val hash="a".repeat(64)
            try {f.db.transaction {sql ->repeat(count) {offset ->
                val p=TaskProposal("inventory-${start+offset}",1,binding,RAW,ManualTaskDateInput(),50,"UTC",50,state)
                sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",
                    listOf(TaskProposals.storageId(p.id),1,hash,cipher.seal(p,hash)))
            }}} finally {cipher.wipe()}
        }
        fun snapshot(records:Boolean=true)=if(records) originals.capture(listOf(f.source),"synthetic-open-activities",100) else OriginalExportSnapshot("synthetic-task-only",100,emptyList(),true)
        fun ui()=checkNotNull(local.originalExport)
        fun drain()=runBlocking {while(queue.isNotEmpty()) queue.removeFirst()(store)}
        fun preview() {if(local.originalExport==null) controller.open();controller.includeActivities(ui().session,true);controller.prepare(ui().session);drain();assertEquals(OriginalExportPhase.PREVIEW,ui().phase)}
        fun accept() {val s=ui();controller.acceptRisk(s.session,s.snapshot!!.id,s.format,s.epoch,true)}
        fun choose() {val s=ui();controller.choose(s.session,s.snapshot!!.id,s.format,s.epoch)}
        fun ready() {accept();choose();drain();assertEquals(OriginalExportPhase.PICKING,ui().phase)}
        override fun close() {controller.dispose();proposals.wipeKey();tasks.wipeKey();tags.wipeKey();f.close()}
    }
    private class Document(val mode:String=""):SelectedExportDocument {
        private val out=ByteArrayOutputStream();var opens=0;var deletes=0;var reads=0;var closed=false;var inputClosed=false;var onWrite:(()->Unit)?=null
        fun bytes()=out.toByteArray()
        override fun openForWrite():OutputStream {opens++;return object:OutputStream() {
            override fun write(v:Int) {out.write(v);onWrite?.invoke()}
            override fun write(b:ByteArray,o:Int,n:Int) {out.write(b,o,n);onWrite?.invoke()}
            override fun close() {closed=true;if(mode=="close") error("Synthetic provider close")}
        }}
        override fun openForRead():InputStream {reads++;if(mode=="read") error("Synthetic readback denied")
            return object:ByteArrayInputStream(if(mode=="mismatch") byteArrayOf(0) else bytes()) {override fun close() {inputClosed=true}}}
        override fun delete():Boolean {deletes++;return true}
    }
    companion object {
        private const val RAW="合成🙂e\u0301\n```\n<script>x</script> [私密](https://example.invalid)\"\\\t\u0000"
        private const val FORMAL="合成🙂e\u0301```<script>x</script> [私密](https://example.invalid)\"\\"
        private fun JsonObject.type()=getValue("type").jsonPrimitive.content
    }
}
