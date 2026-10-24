package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.t1doo.aiyou.data.SourceFactSqlTest.Companion.change
import com.t1doo.aiyou.data.SourceFactSqlTest.Companion.id

class SourceFactBackupTest {
    @Test fun v14PreservesABBCBothSlotsAndRestoresUnderDifferentOwnerKey():Unit=runBlocking {
        FullFixture().use { f -> FullFixture(99).use { target ->
            val repo=SqlSourceFactRepository(f.gateway,f.key);val dest=SqlSourceFactRepository(target.gateway,target.key)
            try {
                val source=SourceVersion(f.entry(text="我的合成杯子是绿色。合成普通自述。"),1)
                val a=repo.mutate(change(create(repo,source),FactAction.ACCEPT))
                val b=repo.mutate(change(create(repo,source,a),FactAction.ACCEPT,a))
                val c=repo.mutate(change(create(repo,source,b),FactAction.ACCEPT,b))
                val expected=repo.facts(source).associateBy { it.id };val bytes=export(f)
                assertEquals("AIYOUF14",String(bytes.copyOfRange(0,8),Charsets.US_ASCII))
                val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {
                    assertEquals(3,pack.summary.memoriesAndReviews);assertFalse(target.backup.restore(pack){true}.replayed)
                    assertTrue(target.backup.restore(pack){true}.replayed);assertTrue(target.backup.completed(pack))
                } finally { pack.release() }
                dest.facts(source).forEach { actual -> assertTrue(actual.same(expected.getValue(actual.id))) }
                assertEquals(listOf(c.id),dest.capture(FactReview.ACCEPTED).items.map { it.id })
                val after=dest.facts(source).single { it.id==b.id };assertNotEquals(after.review!!.predecessor!!.operationId,after.review!!.successor!!.operationId)
                val exported=export(target);assertEquals("AIYOUF14",String(exported.copyOfRange(0,8),Charsets.US_ASCII))
                assertEquals(0L,target.db.read("SELECT count(*) FROM remote_text_jobs").single().single())
                assertThrows(RecordPackageInvalid::class.java) { runBlocking { target.backup.open({bytes.copyOf(bytes.size-1).inputStream()},FullBackupTest.password()) } }
                assertThrows(RecordPackageInvalid::class.java) { runBlocking { target.backup.open({(bytes+byteArrayOf(0)).inputStream()},FullBackupTest.password()) } }
            } finally {repo.wipeKey();dest.wipeKey()}
        } }
    }
    @Test fun correctedB1HistoryDoesNotMoveToB2AcrossBackupWithAudioDateAndCaptureDraft():Unit=runBlocking {
        FullFixture().use { f -> FullFixture(77).use { target ->
            val repo=SqlSourceFactRepository(f.gateway,f.key);val dest=SqlSourceFactRepository(target.gateway,target.key)
            try {
                val source=SourceVersion(f.entry(text="我的合成杯子是绿色。"),1)
                val a=repo.mutate(change(create(repo,source),FactAction.ACCEPT));val b=repo.mutate(change(create(repo,source,a),FactAction.ACCEPT,a))
                val revised=repo.mutate(change(b,FactAction.CORRECT,content=b.current!!.content.copy(interval=FactInterval(null,"2026-11-01","Asia/Shanghai"))))
                val accepted=repo.mutate(change(revised,FactAction.ACCEPT));repo.mutate(change(create(repo,source,accepted),FactAction.ACCEPT,accepted))
                val r=CaptureAudioSqlTest.reserve(f.capture,f.capture.current(),CaptureAudioSqlTest.wave());val draft=f.capture.addAudio(r,CaptureAudioSqlTest.wave());f.capture.mutationGate.finish(r,draft)
                val date=RecordEventDate("2026-10-21","Asia/Shanghai");val dates=SqlRecordEventDateRepository(f.gateway)
                dates.set(RecordEventDateMutation(source.sourceId,1,OriginalExports.hash("我的合成杯子是绿色。"),0,date,id(),repo.preview(source).generation))
                val bytes=export(f);val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {target.backup.restore(pack){true}} finally {pack.release()}
                assertTrue(draft.sameSnapshot(target.capture.peek()!!));assertArrayEquals(CaptureAudioSqlTest.wave(),target.capture.audio(target.capture.peek()!!))
                assertEquals(date,SqlRecordEventDateRepository(target.gateway).read(source.sourceId).date)
                val finalB=dest.facts(source).single {it.id==b.id};assertEquals(a.id,finalB.reviews[0].predecessor!!.id)
                assertNull(finalB.reviews[1].predecessor);assertNotNull(finalB.reviews[1].successor)
                assertEquals(FactInterval(null,"2026-11-01","Asia/Shanghai"),finalB.current!!.content.interval)
            } finally {repo.wipeKey();dest.wipeKey()}
        } }
    }
    @Test fun invalidatedFactStillSelectsV14AndNeverRestoresOldQuote():Unit=runBlocking {
        FullFixture().use { f -> FullFixture(44).use { target ->
            val repo=SqlSourceFactRepository(f.gateway,f.key)
            try {
                val source=SourceVersion(f.entry(text="我的合成杯子是绿色。"),1);val saved=create(repo,source)
                f.revise(source.sourceId,"合成改文，不保留旧偏好 authority")
                val bytes=export(f);assertEquals("AIYOUF14",String(bytes.copyOfRange(0,8),Charsets.US_ASCII))
                val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {target.backup.restore(pack){true}} finally {pack.release()}
                assertEquals(listOf("INVALIDATED",0L),target.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE id=?",saved.id).single())
                assertTrue(target.db.read("SELECT operation FROM record_package_receipts WHERE operation='FULL_BACKUP_IMPORT_V14'").isNotEmpty())
            } finally {repo.wipeKey()}
        } }
    }
    @Test fun formatTruthAndEofRejectOldVersionMasqueradeAndUnsupportedProfile() {
        FullFixture().use { f ->
            val repo=SqlSourceFactRepository(f.gateway,f.key)
            try {
                val source=SourceVersion(f.entry(text="我的合成杯子是绿色。"),1);create(repo,source)
                val data=runBlocking {f.backup.capture(id(),100)} as FullBackupData
                try {
                    val bytes=FullBackupCodec.encode(data);val decoded=FullBackupCodec.decode(bytes)
                    try {assertEquals(14,decoded.version)} finally {decoded.release()}
                    assertThrows(Exception::class.java) { FullBackupCodec.decode(bytes+byteArrayOf(0)) }
                    val wrong=bytes.copyOf();val name="AIyou-logical-full-v14".toByteArray();val at=(0..wrong.size-name.size).first { n -> name.indices.all { wrong[n+it]==name[it] } }
                    wrong[at+name.size-2]='0'.code.toByte();assertThrows(Exception::class.java) { FullBackupCodec.decode(wrong) }
                } finally {data.release()}
            } finally {repo.wipeKey()}
        }
    }
    @Test fun v14LostCommitAckUsesSingleRestoreReceiptAndRejectsMismatchedLiveEndpointsBeforeIntent():Unit=runBlocking {
        FullFixture().use { f -> FullFixture(88).use { target ->
            val repo=SqlSourceFactRepository(f.gateway,f.key)
            try {
                val source=SourceVersion(f.entry(text="我的合成杯子是绿色。"),1)
                val a=repo.mutate(change(create(repo,source),FactAction.ACCEPT));repo.mutate(change(create(repo,source,a),FactAction.ACCEPT,a))
                val bytes=export(f);val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {
                    var lost=false;target.checkpoint={if(it=="full-committed" && !lost) {lost=true;error("Synthetic lost v14 restore ACK")}}
                    assertThrows(Exception::class.java) {runBlocking {target.backup.restore(pack){true}}};assertTrue(lost);target.checkpoint={}
                    val before=target.db.read("SELECT id,version,payload FROM local_candidate_batches ORDER BY id").map {it.take(2).toString()+packageHex(packageHash(it[2] as ByteArray))}
                    assertTrue(target.backup.restore(pack){true}.replayed)
                    assertEquals(before,target.db.read("SELECT id,version,payload FROM local_candidate_batches ORDER BY id").map {it.take(2).toString()+packageHex(packageHash(it[2] as ByteArray))})
                    assertEquals(1L,target.db.read("SELECT count(*) FROM record_package_receipts WHERE operation='FULL_BACKUP_IMPORT_V14'").single().single())
                } finally {pack.release()}
                val base=f.backup.capture(id(),100) as FullBackupData
                try {
                    val tables=base.tables.mapIndexed { index,rows -> if(index!=8)rows else rows.map {row ->
                        val bytes=row[10] as ByteArray;val p=FactEncoding.decode(bytes.copyOfRange(1,bytes.size),source)
                        if(p.reviews.last().predecessor==null)row else row.toMutableList().also {edited ->
                            val last=p.reviews.last();edited[10]=byteArrayOf(6)+FactEncoding.encode(p.copy(reviews=p.reviews.dropLast(1)+last.copy(predecessor=last.predecessor!!.copy(operationId=id()))))
                        }
                    } }
                    val malformed=FullBackupData(base.id,base.created,tables,base.images,base.edits,base.capture,base.audios)
                    val sealed=RecordMediaPackageCipher().sealFull(malformed,FullBackupTest.password(),{error("No media in graph fixture")},{true})
                    val bad=try {ByteArrayOutputStream().also {sealed.writeTo(it){true}}.toByteArray()} finally {sealed.release()}
                    FullFixture(66).use { empty ->
                        assertThrows(Exception::class.java) {runBlocking {empty.backup.open({bad.inputStream()},FullBackupTest.password())}}
                        assertTrue(empty.db.read("SELECT * FROM record_package_receipts").isEmpty());assertTrue(empty.db.read("SELECT * FROM entries").isEmpty())
                    }
                } finally {base.release()}
            } finally {repo.wipeKey()}
        } }
    }
    @Test fun restoredAcceptedSelfReportKeepsUnknownValidityAndNeverRestoresRemotePermission():Unit=runBlocking {
        FullFixture().use { f -> FullFixture(61).use { target ->
            val repo=SqlSourceFactRepository(f.gateway,f.key);val dest=SqlSourceFactRepository(target.gateway,target.key)
            try {
                val source=SourceVersion(f.entry(text="我的合成杯子是绿色。"),1)
                f.db.write("UPDATE entries SET privacyPolicy='REMOTE_ALLOWED' WHERE id=?",source.sourceId)
                val accepted=repo.mutate(change(create(repo,source),FactAction.ACCEPT));assertEquals(ProcessingPolicy.REMOTE_ALLOWED,accepted.policy)
                val bytes=export(f);val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {target.backup.restore(pack){true}} finally {pack.release()}
                val actual=dest.facts(source).single();assertEquals(ProcessingPolicy.LOCAL_ONLY,actual.policy)
                assertEquals(accepted.contents,actual.contents);assertEquals(accepted.reviews,actual.reviews)
                assertEquals(FactInterval(),actual.current!!.content.interval)
                assertEquals(listOf(null,null),target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?",source.sourceId).single())
            } finally {repo.wipeKey();dest.wipeKey()}
        } }
    }
    @Test fun perSourceTwentyRestoresButAuthenticatedTwentyFirstIsRejectedBeforeIntentAndByDatabaseValidation():Unit=runBlocking {
        FullFixture().use { source ->
            val repo=SqlSourceFactRepository(source.gateway,source.key)
            try {
                val first=SourceVersion(source.entry(text="我的合成杯子是绿色。"),1)
                repeat(20) {create(repo,first)}
                FullFixture(72).use { target ->
                    val bytes=export(source);val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                    try {
                        assertFalse(target.backup.restore(pack){true}.replayed)
                        val restored=SqlSourceFactRepository(target.gateway,target.key)
                        try {assertEquals(20,restored.facts(first).size);assertEquals(20,restored.captureSource(first).items.size)} finally {restored.wipeKey()}
                    } finally {pack.release()}
                }
                // Start from 21 independently valid records spread over two legal sources, then
                // authenticate an adversarial document rebinding the last record to the first.
                // Both originals and immutable source metadata are identical; no other invariant fails.
                val second=SourceVersion(source.entry(text="我的合成杯子是绿色。"),1);val last=create(repo,second)
                val base=source.backup.capture(id(),100) as FullBackupData
                try {
                    val rebound=base.tables[8].single {it[0]==last.id}.toMutableList().also {it[1]=first.sourceId}
                    val tables=base.tables.mapIndexed { index,rows -> if(index==8)rows.map {if(it[0]==last.id)rebound else it} else rows }
                    val document=FullBackupData(base.id,base.created,tables,base.images,base.edits,base.capture,base.audios)
                    val prepared=RecordMediaPackageCipher().sealFull(document,FullBackupTest.password(),{error("No media in capacity fixture")},{true})
                    val bytes=try {ByteArrayOutputStream().also {prepared.writeTo(it){true}}.toByteArray()} finally {prepared.release()}
                    FullFixture(73).use { target ->
                        fun state()=target.db.read("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").flatMap { row ->
                            val table=row.single() as String
                            target.db.read("SELECT * FROM "+table+" ORDER BY 1").map { cells ->table+":"+cells.joinToString("|") {if(it is ByteArray)packageHex(packageHash(it)) else it.toString()} }
                        }
                        val before=state();val checkpoints=mutableListOf<String>();target.checkpoint={checkpoints+=it}
                        assertThrows(FactLimit::class.java) {runBlocking {target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
                        assertTrue(checkpoints.isEmpty());assertEquals(before,state())
                        assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
                        assertTrue(target.mediaRoot.walkTopDown().filter {it.isFile}.toList().isEmpty())
                    }
                    // Exercise the shared live-database/export path with the same individually
                    // authenticated record. Bypass only the public creation capacity check here.
                    val installed=rebound.toMutableList().also {it[10]=repo.portable(rebound,true)}
                    try {
                        source.db.transaction {sql ->
                            sql.execute("DELETE FROM local_candidate_batches WHERE id=?",listOf(last.id))
                            sql.execute("INSERT INTO local_candidate_batches(id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)",installed)
                        }
                        assertEquals(21L,source.db.read("SELECT count(*) FROM local_candidate_batches WHERE entryId=? AND ruleVersion=6 AND state='READY'",first.sourceId).single().single())
                        assertThrows(FactLimit::class.java) {repo.captureSource(first)}
                        assertThrows(FactLimit::class.java) {runBlocking {source.backup.capture(id(),101)}}
                    } finally {(installed[10] as ByteArray).fill(0)}
                } finally {base.release()}
            } finally {repo.wipeKey()}
        }
    }
    private fun create(repo:SqlSourceFactRepository,source:SourceVersion,topic:SourceFactSnapshot?=null):SourceFactSnapshot {
        val preview=repo.preview(source)
        return repo.create(FactCreate(id(),preview,topic?.topicId ?: id(),topic?.topicLabel ?: "合成杯子颜色",FactContent(SourceMemoryRules.reference(preview,"我的合成杯子是绿色")),true,id(),40,topic))
    }
    private suspend fun export(f:FullFixture):ByteArray {
        val snapshot=f.backup.capture(id(),100)
        try {
            val sealed=f.backup.seal(snapshot,FullBackupTest.password())
            return try {ByteArrayOutputStream().also {sealed.writeTo(it){true}}.toByteArray()} finally {sealed.release()}
        } finally {snapshot.release()}
    }
}
