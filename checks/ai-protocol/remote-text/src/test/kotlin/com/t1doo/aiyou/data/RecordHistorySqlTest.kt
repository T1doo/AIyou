package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production framed AEAD, SQLite, encrypted task/tag owners and permanent-erasure protocol. */
class RecordHistorySqlTest {
    @Test(timeout=20_000) fun authenticatedFullChainRestoresExactTimesCurrentIndexAndColdOriginProof():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            Ports(source).use { s -> Ports(target).use { t ->
                val id=source.entry(text=FIRST);append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
                source.tags.replace(ManualTagMutation(id,0,listOf("合成历史"),uuid()))
                val snapshot=s.snapshot(id,3);val preview=s.media.previewHistory(snapshot)
                val bytes=s.export(preview);assertEquals("AIYOUPK4",bytes.copyOfRange(0,8).toString(Charsets.US_ASCII))
                val newId=uuid();val result=t.restore(bytes,listOf(newId),100)
                assertFalse(result.replayed);assertEquals(listOf(newId),result.ids)
                assertEquals(listOf(3L,5L,"UTC","AI_NONE"),target.db.read("SELECT currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy FROM entries WHERE id=?",newId).single())
                assertEquals(source.db.read("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision",id),
                    target.db.read("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision",newId))
                assertEquals(listOf(1L,0L,null,null),target.db.read("SELECT generation,deleted,connection,consent FROM remote_source_access WHERE entryId=?",newId).single())
                assertEquals(listOf(3L,3L),target.db.read("SELECT sourceRevision FROM local_jobs WHERE entryId=? AND operation IN ('LOCAL_INDEX','PACKAGE_ORIGIN') ORDER BY operation",newId).map {it.single()})
                val origin=t.packages.origins().getValue(newId)
                assertEquals(3,origin.sourceRevision);assertEquals(3,origin.restoredRevisionCount)
                assertEquals(recordHistoryDigest(preview.history!!.single().history),origin.historyDigest)
                assertEquals(listOf("合成历史"),target.tags.read(newId).names)
                assertFalse(origin.toString().contains(FIRST));assertFalse(preview.toString().contains(SECOND))
            }}
            target.reopen();Ports(target).use { cold ->
                val origin=cold.packages.origins().values.single()
                assertEquals(3,origin.restoredRevisionCount);assertNotNull(origin.historyDigest)
                assertEquals(3L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",origin.newId).single().single())
            }
        }}
    }

    @Test(timeout=20_000) fun frozenV1V2V3CurrentOnlyImportsStillStartAtOneWithImportTimestampAndLegacyReceipt():Unit=runBlocking {
        PermanentErasureFixture().use { source -> Ports(source).use { s ->
            val id=source.entry(text=FIRST);append(source,id,LAST,2,12);val snapshot=s.snapshot(id,2)
            val legacy=JcaRecordPackageCipher().seal(snapshot,password())
            val v1=try {legacy.copyBytes()} finally {legacy.release()}
            val v2=s.export(s.media.preview(snapshot));s.tasks.create(ManualTaskCreate(uuid(),"合成兼容任务",10,"UTC",uuid()))
            val v3=s.export(s.media.previewActivities(snapshot))
            listOf(v1,v2,v3).forEachIndexed { index,bytes -> PermanentErasureFixture().use { target ->
                Ports(target).use { t ->
                    val newId=uuid();t.restore(bytes,listOf(newId),100+index.toLong())
                    assertEquals(listOf(listOf(1L,LAST,OriginalExports.hash(LAST),100+index.toLong())),
                        target.db.read("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=?",newId))
                    assertEquals(1L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",newId).single().single())
                    val origin=t.packages.origins().getValue(newId);assertEquals(1,origin.restoredRevisionCount);assertNull(origin.historyDigest)
                    val raw=Base64.getDecoder().decode((target.db.read("SELECT state FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN'").single().single() as String).substring(10))
                    try {assertEquals(1,PackageReader(raw).i32())} finally {raw.fill(0)}
                    val before=state(target);assertTrue(t.restore(bytes,listOf(uuid()),999).replayed);assertEquals(before,state(target))
                }
                target.reopen();Ports(target).use { assertEquals(1,it.packages.origins().size) }
            }}
        }}
    }

    @Test(timeout=20_000) fun middleVersionTamperWithUnchangedLastVersionRefusesOriginsReplayAndErasureBeforeAnyWrite():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            Ports(source).use { s -> Ports(target).use { t ->
                val id=source.entry(text=FIRST);append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
                val bytes=s.export(s.media.previewHistory(s.snapshot(id,3)));val newId=uuid();t.restore(bytes,listOf(newId),100)
                val last=target.db.read("SELECT originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? AND revision=3",newId)
                val changed="合成被替换的中间证据"
                target.db.write("UPDATE entry_revisions SET originalText=?,contentHash=? WHERE entryId=? AND revision=2",changed,OriginalExports.hash(changed),newId)
                assertEquals(last,target.db.read("SELECT originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? AND revision=3",newId))
                val before=state(target)
                assertThrows(RecordPackageConflict::class.java) {t.packages.origins()}
                rejects<RecordPackageStorageFailure> {t.restore(bytes,listOf(uuid()),999)}
                assertEquals(before,state(target))
                val intent=target.intent(newId);val trashed=state(target)
                rejects<PermanentErasureIncomplete> {target.erase.erase(intent)}
                assertEquals(trashed,state(target));assertTrue(target.db.read("SELECT entryId FROM permanent_erasure_receipts").isEmpty())
            }}
        }}
    }

    @Test(timeout=20_000) fun missingPrefixOrChangedOriginalTimestampCannotBeAcceptedAsAnIntactHistory():Unit=runBlocking {
        PermanentErasureFixture().use { source -> Ports(source).use { s ->
            val id=source.entry(text=FIRST);append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
            val bytes=s.export(s.media.previewHistory(s.snapshot(id,3)))
            for (kind in listOf("missing","timestamp")) PermanentErasureFixture().use { target -> Ports(target).use { t ->
                val newId=uuid();t.restore(bytes,listOf(newId),100)
                if(kind=="missing") target.db.write("DELETE FROM entry_revisions WHERE entryId=? AND revision=2",newId)
                else target.db.write("UPDATE entry_revisions SET createdAtMillis=createdAtMillis+1 WHERE entryId=? AND revision=2",newId)
                val before=state(target);assertThrows(RecordPackageConflict::class.java) {t.packages.origins()}
                rejects<RecordPackageStorageFailure> {t.restore(bytes,listOf(uuid()),999)};assertEquals(before,state(target))
            }}
        }}
    }

    @Test(timeout=20_000) fun lostFinalCommitAckColdRetryAfterUserAppendKeepsCurrentPolicyTagsAndDraftBytes():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target -> Ports(source).use { s ->
            val id=source.entry(text=FIRST);append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
            val bytes=s.export(s.media.previewHistory(s.snapshot(id,3)));val newId=uuid();var t=Ports(target)
            try {
                var final=false;var lost=false
                target.db.afterSql={ sql ->
                    if(sql.startsWith("INSERT INTO record_package_receipts")&&sql.contains("'PACKAGE_RESTORE'")) final=true
                    if(sql=="COMMIT"&&final&&!lost) {lost=true;error("Synthetic history commit ACK lost")}
                }
                rejects<RecordPackageStorageFailure> {t.restore(bytes,listOf(newId),100)};assertTrue(lost)
                target.db.afterSql=null;t.close();target.reopen();t=Ports(target)
                append(target,newId,"合成用户后续第4版",4,201)
                target.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id=?",newId)
                assertEquals(0L,target.tags.read(newId).version)
                target.tags.replace(ManualTagMutation(newId,0,listOf("合成用户后改"),uuid()))
                val binding=binding(target,newId,4);t.proposals.write(TaskProposalWrite(uuid(),0,binding,"合成稍后草稿",ManualTaskDateInput(),202,"UTC",202,uuid()))
                val before=state(target);val retry=t.restore(bytes,listOf(uuid()),999)
                assertTrue(retry.replayed);assertEquals(listOf(newId),retry.ids);assertEquals(100L,retry.importedAtMillis)
                assertEquals(before,state(target));assertEquals(4L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",newId).single().single())
                assertEquals(3,t.packages.origins().getValue(newId).restoredRevisionCount)
            } finally {target.db.afterSql=null;t.close()}
        }}}
    }

    @Test(timeout=20_000) fun failureAtMiddleRevisionRollsBackWholePrefixAndColdRecoveryAllowsExactlyOneCompleteRetry():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target -> Ports(source).use { s ->
            val id=source.entry(text=FIRST);append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
            val bytes=s.export(s.media.previewHistory(s.snapshot(id,3)));val newId=uuid();var t=Ports(target)
            try {
                target.db.write("CREATE TEMP TRIGGER reject_middle_history BEFORE INSERT ON entry_revisions WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT,'synthetic history prefix abort'); END")
                rejects<RecordPackageStorageFailure> {t.restore(bytes,listOf(newId),100)}
                for(table in listOf("entries","entry_revisions","entry_manual_tags","remote_source_access","attachments","local_jobs","manual_tasks")) assertTrue(table,target.db.read("SELECT * FROM $table").isEmpty())
                assertTrue(target.db.read("SELECT id FROM record_package_receipts WHERE operation IN ('PACKAGE_ORIGIN','PACKAGE_RESTORE')").isEmpty())
                t.close();target.reopen();t=Ports(target);t.media.recover()
                val retry=t.restore(bytes,listOf(uuid()),999);assertFalse(retry.replayed);assertEquals(listOf(newId),retry.ids)
                assertEquals(3,target.db.read("SELECT revision FROM entry_revisions").size);assertEquals(1,target.db.read("SELECT id FROM entries").size)
                val before=state(target);assertTrue(t.restore(bytes,listOf(uuid()),1000).replayed);assertEquals(before,state(target))
            } finally {t.close()}
        }}}
    }

    @Test(timeout=20_000) fun erasurePreparedPlannedAndDoneColdReplaysNeverRecreateAnyHistoryPrefix():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target -> Ports(source).use { s ->
            val id=source.entry(text=FIRST);append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
            val bytes=s.export(s.media.previewHistory(s.snapshot(id,3)));val newId=uuid();var t=Ports(target)
            try {
                t.restore(bytes,listOf(newId),100);val intent=target.intent(newId)
                for(stop in listOf("prepared","planned-committed")) {
                    target.checkpoint={if(it==stop) error("Synthetic history erasure stop")}
                    rejects<PermanentErasureIncomplete> {target.erase.erase(intent)}
                    t.close();target.reopen();t=Ports(target);val before=state(target)
                    val replay=t.restore(bytes,listOf(uuid()),999);assertEquals(1,replay.erasingCount);assertEquals(0,replay.deletedCount)
                    assertEquals(before,state(target));assertFalse(t.packages.origins().containsKey(newId))
                }
                target.checkpoint={};assertTrue(target.erase.erase(intent).complete)
                t.close();target.reopen();t=Ports(target);val before=state(target)
                val replay=t.restore(bytes,listOf(uuid()),999);assertEquals(1,replay.deletedCount);assertEquals(0,replay.erasingCount)
                assertEquals(before,state(target));assertTrue(target.db.read("SELECT entryId FROM entry_revisions WHERE entryId=?",newId).isEmpty())
                assertTrue(t.packages.origins().isEmpty())
            } finally {target.checkpoint={};t.close()}
        }}}
    }

    @Test(timeout=20_000) fun currentSourceMapsToRestoredRevisionWhileHistoricalDraftStaysUnavailableAndUnconfirmed():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            Ports(source).use { s -> Ports(target).use { t ->
                val id=source.entry(text=FIRST);val old=s.proposals.write(TaskProposalWrite(uuid(),0,binding(source,id,1),"合成旧版本草稿",ManualTaskDateInput(),10,"UTC",10,uuid()))
                append(source,id,SECOND,2,12);append(source,id,LAST,3,37)
                val current=s.proposals.write(TaskProposalWrite(uuid(),0,binding(source,id,3),"合成当前来源待确认",ManualTaskDateInput(),40,"UTC",40,uuid()))
                val confirm=s.proposals.write(TaskProposalWrite(uuid(),0,binding(source,id,3),"合成已确认来源任务",ManualTaskDateInput(),40,"UTC",40,uuid()))
                s.proposals.confirm(TaskProposalConfirm(confirm,41,uuid()))
                val preview=s.media.previewHistory(s.snapshot(id,3),true)
                assertEquals(-1,preview.activities!!.drafts.single {it.proposal.id==old.id}.sourceRecord)
                assertEquals(0,preview.activities!!.drafts.single {it.proposal.id==current.id}.sourceRecord)
                val newId=uuid();val result=t.restore(s.export(preview),listOf(newId),100)
                val proposals=t.proposals.list();assertEquals(2,proposals.size);assertEquals(1,result.taskIds.size)
                val good=proposals.single {it.proposal.titleInput==current.titleInput}
                assertTrue(good.sourceAvailable);assertEquals(SourceVersion(newId,3),good.proposal.binding.source);assertEquals(1L,good.proposal.binding.generation)
                val stale=proposals.single {it.proposal.titleInput==old.titleInput}
                assertFalse(stale.sourceAvailable);assertTrue(stale.proposal.binding.source.sourceId.startsWith(RecordPackageActivities.UNAVAILABLE_PREFIX))
                assertEquals(1L,stale.proposal.binding.source.revision)
                assertThrows(TaskProposalConflict::class.java) {t.proposals.confirm(TaskProposalConfirm(stale.proposal,200,uuid()))}
                assertEquals(1,t.tasks.snapshots().size);val task=t.tasks.snapshots().single();assertNull(task.reminder)
                var located:TaskSourceBinding?=null
                assertTrue(SqlSourceTaskRepository(target.gateway,t.tasks).locate(task.id,task.version) {located=it})
                assertEquals(SourceVersion(newId,3),located!!.source)
            }}
        }}
    }

    private class Ports(val f:PermanentErasureFixture):AutoCloseable {
        val tasks=SqlManualTaskRepository(f.gateway,PermanentErasureFixture.KEY)
        val proposals=SqlTaskProposalRepository(f.gateway,PermanentErasureFixture.KEY,tasks)
        private val activities=SqlRecordPackageActivities(f.gateway,tasks,proposals)
        val packages=SqlRecordPackageRepository(f.gateway,f.tags,activities=activities)
        private val originals=SqlOriginalExportRepository(f.gateway,f.tags)
        val media=SqlRecordMediaPackageRepository(f.gateway,originals,f.media,packages,activities)
        fun snapshot(id:String,revision:Int)=originals.capture(listOf(SourceVersion(id,revision.toLong())),uuid(),50)
        suspend fun export(preview:RecordMediaPreview):ByteArray {
            val prepared=media.seal(preview,password())
            return ByteArrayOutputStream().use {out -> try {prepared.writeTo(out) {true};out.toByteArray()} finally {prepared.release()} }
        }
        suspend fun restore(bytes:ByteArray,ids:List<String>,at:Long):RecordPackageRestoreResult {
            val pack=media.open({bytes.inputStream()},password())
            return try {media.restore(pack,ids,at)} finally {pack.release()}
        }
        override fun close() {proposals.wipeKey();tasks.wipeKey()}
    }
    companion object {
        private const val FIRST="合成初稿：后来删掉的文字🙂"
        private const val SECOND="合成中间修订证据"
        private const val LAST="合成当前版本"
        private fun uuid()=UUID.randomUUID().toString()
        private fun password()="synthetic history recovery 42".toCharArray()
        private fun append(f:PermanentErasureFixture,id:String,text:String,revision:Int,created:Long)=f.db.transaction {sql ->
            sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)",listOf(id,revision,text,OriginalExports.hash(text),created))
            sql.execute("UPDATE entries SET currentRevision=? WHERE id=?",listOf(revision,id));Unit
        }
        private fun binding(f:PermanentErasureFixture,id:String,revision:Int):TaskSourceBinding {
            val access=SqlLocalProcessingRepository(f.gateway).read(SourceVersion(id,revision.toLong()))
            return TaskSourceBinding(access.source,access.sourceHash,access.generation,0,5)
        }
        private fun state(f:PermanentErasureFixture):List<Pair<String,List<List<Any?>>>> = listOf("entries","entry_revisions","entry_manual_tags","remote_source_access","local_jobs","record_package_receipts","permanent_erasure_receipts","manual_tasks","attachments").map {table ->
            table to f.db.read("SELECT * FROM $table ORDER BY 1").map {row -> row.map {if(it is ByteArray) Base64.getEncoder().encodeToString(it) else it} }
        }
        private suspend inline fun <reified T:Throwable> rejects(action:suspend ()->Any?) {
            try {action();fail("Expected ${T::class.java.simpleName}")} catch(failure:Throwable) {if(failure !is T) throw failure}
        }
    }
}
