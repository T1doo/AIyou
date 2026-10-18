package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Production ViewModel with real package/files/SQLite; formal revision uses the named host SQL port, not Room. */
class RestoredOriginalEditViewModelTest {
    @Test(timeout=15_000) fun migratedHistoricalEditResumesExplicitlyAndSavesNextVersionWithoutChangingCaptureOrSource():Unit=runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id=source.entry(text="合成旧原文");RecordHistoryMediaTest.revise(source,id,2,"合成当前原文",9)
            val draft=OriginalEditDraft(RecordEditDraftPackageTest.uuid(),id,2,1,1,"合成当前原文","合成迁移后继续编辑")
            source.edits.write(null,draft);val sr=RecordEditDraftPackageTest.repo(source)
            val bytes=RecordEditDraftPackageTest.export(sr,sr.previewEditDrafts(RecordEditDraftPackageTest.snapshot(source,id,2)))
            val repository=RecordEditDraftPackageTest.repo(target);val pack=repository.open({bytes.inputStream()},RecordEditDraftPackageTest.password());val newId=RecordEditDraftPackageTest.uuid()
            try {repository.restore(pack,listOf(newId),40)} finally {pack.release()}
            val base=SqliteVmEntryRepository(target.db,target.gateway,RemoteTextTransactions(target.gateway)) {}
            val capture=base.updateDraft("合成尚未提交的此刻输入");val closed=CountDownLatch(1)
            val owner=object:EntryRepository by base,AutoCloseable {
                override val sourceBoundText:SourceBoundTextRepository?=null
                override val editDrafts=target.edits
                override suspend fun prepare() {repository.recover()}
                override suspend fun reviseFromDraft(draft:OriginalEditDraft,createdAtMillis:Long):StoredEntry {
                    check(target.edits.read(draft.entryId)==draft)
                    return base.revise(draft.entryId,draft.baseRevision,draft.text,createdAtMillis)
                }
                override fun close() {closed.countDown()}
            }
            val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC),bindAi={error("No model capability")})
            suspend fun state(test:(LocalRecordUiState)->Boolean)=withTimeout(3_000) {vm.state.first(test)}
            try {
                val ready=state {it.ready&&!it.opening};assertNull(ready.originalEdit);assertEquals(capture.text,ready.text)
                val ref=ready.pendingEdits.single();assertEquals(newId,ref.entryId)
                vm.resumeOriginalEdit(ref.entryId,ref.id);val edit=state {it.originalEdit?.loadingDraft==false}.originalEdit!!
                assertFalse(edit.conflict);assertEquals(draft.text,edit.text);assertEquals(2,edit.baseRevision);assertEquals(1,edit.sourceRevision)
                assertEquals(2,base.entries().single().currentRevision)
                vm.saveOriginalEdit(edit.sessionId);state {it.originalEdit==null&&it.entries.single().currentRevision==3}
                assertEquals(listOf("合成旧原文","合成当前原文",draft.text),base.revisions(newId).map {it.originalText})
                assertEquals(ProcessingPolicy.AI_NONE,base.entries().single().policy);assertEquals(capture,base.readDraft())
                assertEquals(OriginalEditDraftState.COMMITTED,target.edits.read(newId)!!.state);assertEquals(draft,source.edits.read(id))
                assertTrue(target.db.read("SELECT id FROM remote_text_jobs").isEmpty())
                val replay=repository.open({bytes.inputStream()},RecordEditDraftPackageTest.password())
                try {assertTrue(repository.restore(replay,listOf(RecordEditDraftPackageTest.uuid()),999).replayed)} finally {replay.release()}
                assertEquals(OriginalEditDraftState.COMMITTED,target.edits.read(newId)!!.state)
            } finally {vm.clearForHostTest();assertTrue(closed.await(3,TimeUnit.SECONDS))}
        } }
    }
}
