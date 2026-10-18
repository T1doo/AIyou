package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Product streaming AEAD, encrypted edit files and SQLite; these are host recovery checks, not Android restart evidence. */
class RecordEditDraftPackageTest {
    @Test fun explicitScopeRestoresExactUtf16DraftWithFreshIdentityAndCompleteHistoryWithoutCommittingText(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture(ByteArray(32) { (it+17).toByte() }).use { target ->
            val id = source.entry(text = "合成旧正文")
            RecordHistoryMediaTest.revise(source,id,2,"合成当前正文",9)
            val draft = OriginalEditDraft(uuid(),id,2,1,1,"合成当前正文","合成未提交\uD800文字\uDC00")
            source.edits.write(null,draft)
            val r = repo(source); val preview = r.previewEditDrafts(snapshot(source,id,2))
            val bytes = export(r,preview)
            assertEquals("AIYOUPK7",bytes.copyOf(8).toString(Charsets.US_ASCII))
            val t = repo(target); val pack = t.open({ bytes.inputStream() },password()); val newId = uuid()
            try {
                assertEquals(listOf(draft),pack.editDrafts); assertTrue(pack.includesHistory)
                assertTrue(target.db.read("SELECT id FROM entries").isEmpty()); assertTrue(target.edits.pending().drafts.isEmpty())
                t.restore(pack,listOf(newId),40)
                val restored = target.edits.read(newId)!!
                assertNotEquals(draft.id,restored.id); assertEquals(draft.copy(id=restored.id,entryId=newId,version=1),restored)
                assertEquals(listOf(2L,"AI_NONE"),target.db.read("SELECT currentRevision,privacyPolicy FROM entries WHERE id=?",newId).single())
                assertEquals("合成当前正文",target.db.read("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=2",newId).single().single())
                assertEquals(draft,source.edits.read(id)); assertEquals(1,target.edits.pending().drafts.size)
                assertTrue(target.db.read("SELECT id FROM remote_text_jobs").isEmpty())
                assertEquals(listOf(null,null),target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?",newId).single())
                val sealed = target.editRoot.listFiles()!!.single { it.name.endsWith(".edit") }.readBytes()
                assertFalse(sealed.toString(Charsets.UTF_8).contains("合成未提交"))
                target.reopen(); repo(target).recover(); assertEquals(restored,target.edits.read(newId))
            } finally { pack.release() }
        } }
    }

    @Test fun defaultAndHistoryOnlyPackagesKeepExistingFormatsAndExcludeIndependentEdits(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val r=repo(f);val s=snapshot(f,id)
            for ((preview,version) in listOf(r.preview(s) to 2,r.previewHistory(s) to 4)) {
                val bytes=export(r,preview);assertEquals(version,java.nio.ByteBuffer.wrap(bytes,8,4).int)
                val pack=r.open({bytes.inputStream()},password());try {assertNull(pack.editDrafts)} finally {pack.release()}
            }
        }
    }

    @Test fun v7WrapsExistingPeopleEventDateActivitiesHistoryAndImageLayersWithoutLosingEitherDraftScope(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();val plain=snapshot(f,id);val old=plain.records.single();val date=RecordEventDate("2026-10-17","Asia/Shanghai")
            val enriched=OriginalExportRecord(old.entry,old.contentHash,old.tags,old.sourceGeneration,1,
                RecordEventDateSnapshot(id,1,date),ManualPeopleSnapshot(id,1,listOf("合成称呼"),1,old.contentHash,old.sourceGeneration))
            val snapshot=OriginalExportSnapshot(plain.id,plain.createdAtMillis,listOf(enriched))
            val history=RecordPackageOriginal(id,1,old.entry.capturedAtMillis,old.entry.captureTimeZone,old.entry.originalText,old.contentHash,0,emptyList(),
                history=listOf(RecordPackageRevision(1,old.entry.originalText,old.contentHash,5)),eventDate=date,sourcePeopleVersion=1,people=listOf("合成称呼"))
            val png=RecordHistoryMediaTest.image(1);val image=RecordPackageImage(id,1,uuid(),"image/png",png.size.toLong(),PermanentErasureFixture.hash(png))
            val edits=listOf(f.draft(id));val activities=RecordPackageActivities(emptyList(),emptyList(),listOf(old.sourceGeneration))
            val cipher=RecordMediaPackageCipher();val prepared=cipher.seal(RecordMediaPreview(snapshot,listOf(image),activities,listOf(history),edits),password(),{png.copyOf()},{true})
            val bytes=ByteArrayOutputStream().also {prepared.writeTo(it) {true}}.toByteArray();val pack=cipher.open({bytes.inputStream()},password())
            try {
                assertEquals(edits,pack.editDrafts);assertEquals(listOf("合成称呼"),pack.records.single().people);assertEquals(date,pack.records.single().eventDate)
                assertTrue(pack.includesHistory);assertEquals(listOf(image),pack.images);assertNotNull(pack.activities)
                var frames=0;cipher.consume(pack as AuthenticatedMediaPackage) {index,payload->assertEquals(0,index);assertArrayEquals(png,payload);frames++};assertEquals(1,frames)
            } finally {pack.release()}
        }
    }

    @Test fun localRecordWithoutRemoteAccessRowCanRestoreItsAcknowledgedDraft(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.db.write("DELETE FROM remote_source_access WHERE entryId=?",id)
            f.edits.write(null,f.draft(id));val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)))
            val pack=r.open({bytes.inputStream()},password());val newId=uuid()
            try {r.restore(pack,listOf(newId),40);assertEquals(newId,f.edits.pending().drafts.single {it.entryId==newId}.entryId)} finally {pack.release()}
        }
    }

    @Test fun explicitlyIncludedEmptyDraftScopeStillRefusesDeletedOrNewerSelectedSourceRecords(): Unit = runBlocking {
        for (change in listOf("trash","version","erased")) PermanentErasureFixture().use { f ->
            val id=f.entry();val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)))
            when(change) {"trash"->f.intent(id);"version"->RecordHistoryMediaTest.revise(f,id,2,"合成后来的原文",9);"erased"->f.erase.erase(f.intent(id))}
            val pack=r.open({bytes.inputStream()},password());val newId=uuid()
            try {fails {r.restore(pack,listOf(newId),40)};assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",newId).isEmpty())} finally {pack.release()}
        }
    }

    @Test fun previewChecksTheWholeSelectedActiveSetIncludingNewDraftUpdatesDiscardAndSourceChange(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();val r=repo(f);val s=snapshot(f,id)
            val empty=r.previewEditDrafts(s);assertTrue(empty.editDrafts!!.isEmpty());assertTrue(r.isCurrent(empty))
            val draft=f.edits.write(null,f.draft(id));assertFalse(r.isCurrent(empty))
            val shown=r.previewEditDrafts(s);assertTrue(r.isCurrent(shown))
            val next=f.edits.write(draft,draft.copy(version=2,text="合成新输入"));assertFalse(r.isCurrent(shown))
            val updated=r.previewEditDrafts(s)
            f.edits.write(next,next.copy(version=3,text="",originalText="",state=OriginalEditDraftState.DISCARDED))
            assertFalse(r.isCurrent(updated))
            val current=r.previewEditDrafts(s);RecordHistoryMediaTest.revise(f,id,2,"合成新版本",8);assertFalse(r.isCurrent(current))
        }
    }

    @Test fun firstLocalImportRejectsChangedDiscardedReplacedCommittedTrashedAndErasedSources(): Unit = runBlocking {
        for (change in listOf("changed","discarded","replaced","committed","revision","trash","erased","missing")) {
            PermanentErasureFixture().use { f ->
                val id=f.entry();val draft=f.edits.write(null,f.draft(id));val r=repo(f)
                val bytes=export(r,r.previewEditDrafts(snapshot(f,id)))
                when(change) {
                    "changed" -> f.edits.write(draft,draft.copy(version=2,text="合成后来输入"))
                    "discarded","replaced","missing" -> {
                        val end=f.edits.write(draft,draft.copy(version=2,text="",originalText="",state=OriginalEditDraftState.DISCARDED))
                        if(change=="replaced") f.edits.write(end,draft.copy(id=uuid()))
                        if(change=="missing") f.editRoot.listFiles()!!.single {it.name.endsWith(".edit")}.delete()
                    }
                    "committed" -> f.edits.write(draft,draft.copy(version=2,text="",originalText="",state=OriginalEditDraftState.COMMITTED))
                    "revision" -> RecordHistoryMediaTest.revise(f,id,2,"合成新正式原文",10)
                    "trash" -> f.intent(id)
                    "erased" -> f.erase.erase(f.intent(id))
                }
                val pack=r.open({bytes.inputStream()},password());val newId=uuid()
                try { fails { r.restore(pack,listOf(newId),40) };assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",newId).isEmpty());assertNull(f.edits.read(newId)) }
                finally {pack.release()}
            }
        }
    }

    @Test fun replayNeverRewritesLaterInputTerminalDraftOrDeletedCopyAndIgnoresLaterSourceChanges(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();val source=f.edits.write(null,f.draft(id));val r=repo(f)
            val bytes=export(r,r.previewEditDrafts(snapshot(f,id)));val pack=r.open({bytes.inputStream()},password());val newId=uuid()
            try {
                r.restore(pack,listOf(newId),40);val restored=f.edits.read(newId)!!
                f.edits.write(source,source.copy(version=2,text="",originalText="",state=OriginalEditDraftState.DISCARDED))
                val changed=f.edits.write(restored,restored.copy(version=2,text="合成目标后来输入"))
                assertTrue(r.restore(pack,listOf(uuid()),999).replayed);assertEquals(changed,f.edits.read(newId))
                val terminal=f.edits.write(changed,changed.copy(version=3,text="",originalText="",state=OriginalEditDraftState.DISCARDED))
                r.restore(pack,listOf(uuid()),999);assertEquals(terminal,f.edits.read(newId))
                f.erase.erase(f.intent(newId));val result=r.restore(pack,listOf(uuid()),999)
                assertTrue(result.replayed);assertEquals(1,result.deletedCount);assertNull(f.edits.read(newId))
                assertEquals(1,f.db.read("SELECT id FROM entries").size)
            } finally {pack.release()}
        }
    }

    @Test fun absentSourceWithLocalOrphanTerminalOrCorruptEditEvidenceCannotMasqueradeAsFreshDevice(): Unit = runBlocking {
        for (kind in listOf("active","discarded","committed","corrupt")) PermanentErasureFixture().use { f ->
            val id=f.entry();val draft=f.edits.write(null,f.draft(id));val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)))
            if(kind in listOf("discarded","committed")) f.edits.write(draft,draft.copy(version=2,originalText="",text="",
                state=if(kind=="discarded") OriginalEditDraftState.DISCARDED else OriginalEditDraftState.COMMITTED))
            if(kind=="corrupt") f.editRoot.resolve(sourceFile(f,id)).writeBytes(byteArrayOf(1,2,3))
            f.db.transaction {sql->for(table in listOf("remote_source_access","local_jobs","entry_revisions")) sql.execute("DELETE FROM $table WHERE entryId=?",listOf(id));sql.execute("DELETE FROM entries WHERE id=?",listOf(id))}
            val pack=r.open({bytes.inputStream()},password());val newId=uuid()
            try {fails {r.restore(pack,listOf(newId),40)};assertTrue(f.db.read("SELECT id FROM entries").isEmpty());assertNull(f.edits.read(newId))} finally {pack.release()}
        }
    }

    @Test fun everyEditPublicationInterruptionColdRecoversOnlyRegisteredFilesThenRetriesSameMapping(): Unit = runBlocking {
        PermanentErasureFixture().use { source ->
            val id=source.entry();source.edits.write(null,source.draft(id));val sr=repo(source)
            val bytes=export(sr,sr.previewEditDrafts(snapshot(source,id)))
            for (point in listOf("encrypted","published","durable","package-edit-published")) {
                PermanentErasureFixture().use { f ->
                    var fired=false
                    f.editCheckpoint={if(it==point&&!fired) {fired=true;error("Synthetic $point interruption")}}
                    val r=repo(f);val pack=r.open({bytes.inputStream()},password());val newId=uuid()
                    try {
                        fails {r.restore(pack,listOf(newId),40)};assertTrue(point,fired)
                        assertTrue(f.db.read("SELECT id FROM entries").isEmpty());f.editCheckpoint={};f.reopen()
                        val cold=repo(f);cold.recover();assertTrue(f.edits.pending().drafts.isEmpty())
                        assertFalse(f.editRoot.listFiles()!!.any {it.name.endsWith(".edit")||it.name.contains(".partial.")})
                        val result=cold.restore(pack,listOf(uuid()),999);assertEquals(listOf(newId),result.ids);assertEquals(40,result.importedAtMillis)
                        assertEquals(newId,f.edits.pending().drafts.single().entryId)
                    } finally {pack.release()}
                }
            }
        }
    }

    @Test fun lostSqlCommitAcknowledgementReplaysAfterColdOpenWithoutRepublishingOrDuplicatingDraft(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)))
            val pack=r.open({bytes.inputStream()},password());val newId=uuid();var fired=false;var receipt=false
            f.db.afterSql={sql->
                if(sql.startsWith("INSERT INTO record_package_receipts")&&sql.contains("'PACKAGE_RESTORE'")) receipt=true
                if(sql=="COMMIT"&&receipt&&!fired) {fired=true;error("Synthetic lost commit acknowledgement")}}
            try {
                fails {r.restore(pack,listOf(newId),40)};assertTrue(fired);f.db.afterSql=null;f.reopen();val cold=repo(f);cold.recover()
                val restored=f.edits.read(newId)!!;val file=f.editRoot.listFiles()!!.single {it.name.endsWith(".edit")&&it.readBytes().isNotEmpty()&&it.name!=sourceFile(f,id)}
                val ciphertext=file.readBytes();assertTrue(cold.restore(pack,listOf(uuid()),999).replayed)
                assertEquals(restored,f.edits.read(newId));assertArrayEquals(ciphertext,file.readBytes());assertEquals(2,f.db.read("SELECT id FROM entries").size)
            } finally {f.db.afterSql=null;pack.release()}
        }
    }

    @Test fun cleanupRefusesCompetingCanonicalAndUnknownPartialAndPreservesBothForDiagnosis(): Unit = runBlocking {
        for (kind in listOf("canonical","partial")) PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)));val pack=r.open({bytes.inputStream()},password());val newId=uuid()
            var fired=false;f.editCheckpoint={if(it=="package-edit-published"&&!fired) {fired=true;error("Synthetic interruption")}}
            try {
                fails {r.restore(pack,listOf(newId),40)};f.editCheckpoint={};val old=f.edits.read(newId)!!
                val canonical=f.editRoot.resolve(sourceFile(f,newId))
                val preserved=if(kind=="canonical") {
                    f.edits.write(old,old.copy(version=2,text="合成竞争输入"));canonical
                } else f.editRoot.resolve(canonical.name+".partial."+uuid()).also {it.writeBytes(byteArrayOf(8,9,10))}
                val evidence=preserved.readBytes();fails {r.recover()};assertArrayEquals(evidence,preserved.readBytes())
                assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",newId).isEmpty())
            } finally {pack.release()}
        }
    }

    @Test fun independentV7MetadataFixturePreservesUnpairedUnitsAndRejectsDuplicateOrTerminalDrafts(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();val draft=f.draft(id).copy(text="\uD800合成\uDC00");val preview=repo(f).previewHistory(snapshot(f,id));val base=RecordHistoryPackageCodec.encode(preview)
            try {
                fun fixture(drafts:List<OriginalEditDraft>):ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
                    fun string(v:String) {val bytes=v.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes)}
                    string("AIyou-original-edits-v7");out.writeInt(7);out.writeInt(1);out.writeInt(4);out.writeInt(base.size);out.write(base);out.writeInt(drafts.size)
                    drafts.forEach {d->string(d.id);string(d.entryId);out.writeInt(d.baseRevision);out.writeInt(d.sourceRevision);out.writeLong(d.version)
                        for(text in listOf(d.originalText,d.text)) {out.writeInt(text.length);text.forEach {out.writeChar(it.code)}};string(d.state.name)}
                } }.toByteArray()
                val parsed=RecordEditDraftPackageCodec.parse(fixture(listOf(draft))) {raw,version->assertEquals(4,version);RecordHistoryPackageCodec.parse(raw)}
                assertEquals(listOf(draft),parsed.editDrafts)
                for (bad in listOf(listOf(draft,draft),listOf(draft.copy(baseRevision=2)),listOf(draft.copy(state=OriginalEditDraftState.DISCARDED)),listOf(draft.copy(version=0))))
                    assertThrows(IllegalArgumentException::class.java) {RecordEditDraftPackageCodec.parse(fixture(bad)) {raw,_->RecordHistoryPackageCodec.parse(raw)}}
            } finally {base.fill(0)}
        }
    }

    @Test fun authenticationTamperTruncationAndAggregateOversizeCannotYieldImportCapability(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)))
            for(bad in listOf(bytes.copyOf(bytes.size-1),bytes.copyOf().also {it[50]=(it[50].toInt() xor 1).toByte()},bytes+byteArrayOf(0))) fails {r.open({bad.inputStream()},password())}
            val ids=(1..12).map {f.entry(text="合成短正文")}
            ids.forEach {f.edits.write(null,OriginalEditDraft(uuid(),it,1,1,1,"合成短正文","B".repeat(100_000)))}
            val snapshot=SqlOriginalExportRepository(f.gateway,f.tags).capture(ids.map {SourceVersion(it,1)},uuid(),20)
            fails {val preview=r.previewEditDrafts(snapshot);r.seal(preview,password()).release()}
            assertEquals(13,f.db.read("SELECT id FROM entries").size)
        }
    }

    @Test fun imagesAndEditDraftsRecoverAsOneBatchAndSourceDeletionAtFinalSqlCannotCommitPrefix(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id=source.entry();val image=RecordHistoryMediaTest.image(1);source.media.save(source.attachment(id,image)) {image.inputStream()}
            source.edits.write(null,source.draft(id));val sr=repo(source);val bytes=export(sr,sr.previewEditDrafts(snapshot(source,id)))
            val r=repo(target);val pack=r.open({bytes.inputStream()},password());val newId=uuid();var fired=false
            target.editCheckpoint={if(it=="package-edit-published"&&!fired) {fired=true;error("Synthetic joined batch interruption")}}
            try {
                fails {r.restore(pack,listOf(newId),40)};assertTrue(fired);assertTrue(target.mediaRoot.listFiles()!!.any {it.name.endsWith(".aead")})
                target.editCheckpoint={};target.reopen();val cold=repo(target);cold.recover()
                assertFalse(target.mediaRoot.listFiles()!!.any {it.name.endsWith(".aead")||it.name.endsWith(".partial")})
                cold.restore(pack,listOf(uuid()),900);assertArrayEquals(image,target.media.read(target.media.list(newId,1).single().intent.id));assertNotNull(target.edits.read(newId))
            } finally {pack.release()}
            // Same-device final SQL check runs after publication, still inside both file leases.
            val local=repo(source);val own=local.open({bytes.inputStream()},password());val refused=uuid();var deleted=false
            source.editCheckpoint={if(it=="package-edit-published"&&!deleted) {deleted=true;source.intent(id)}}
            try {fails {local.restore(own,listOf(refused),50)};assertTrue(deleted);assertTrue(source.db.read("SELECT id FROM entries WHERE id=?",refused).isEmpty());source.editCheckpoint={};local.recover();assertNull(source.edits.read(refused))}
            finally {source.editCheckpoint={};own.release()}
        } }
    }

    @Test fun cleanupInterruptionCanResumeAndCompetingEditorReceivesBusyWhilePackageOwnsFiles(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val r=repo(f);val bytes=export(r,r.previewEditDrafts(snapshot(f,id)));val pack=r.open({bytes.inputStream()},password());val newId=uuid()
            var published=false
            f.editCheckpoint={if(it=="package-edit-published"&&!published) {published=true;error("Synthetic interruption")}}
            try {
                fails {r.restore(pack,listOf(newId),40)};f.editCheckpoint={};var cleanup=false
                f.editCheckpoint={if(it=="package-edit-cleaned"&&!cleanup) {cleanup=true;error("Synthetic cleanup acknowledgement loss")}}
                fails {r.recover()};assertTrue(cleanup);f.editCheckpoint={};r.recover()
                var busy=false
                f.editCheckpoint={if(it=="package-edit-published") {
                    runBlocking {try {f.edits.read(newId)} catch(_:OriginalEditDraftBusy) {busy=true}}
                }}
                r.restore(pack,listOf(uuid()),999);assertTrue(busy);assertNotNull(f.edits.read(newId));assertEquals(2,f.db.read("SELECT id FROM entries").size)
            } finally {f.editCheckpoint={};pack.release()}
        }
    }

    companion object {
        internal fun repo(f:PermanentErasureFixture)=SqlRecordMediaPackageRepository(f.gateway,SqlOriginalExportRepository(f.gateway,f.tags),f.media,f.packages,edits=f.edits)
        internal fun snapshot(f:PermanentErasureFixture,id:String,revision:Int=1)=SqlOriginalExportRepository(f.gateway,f.tags).capture(listOf(SourceVersion(id,revision.toLong())),uuid(),20)
        internal suspend fun export(r:SqlRecordMediaPackageRepository,p:RecordMediaPreview):ByteArray {
            val prepared=r.seal(p,password());return ByteArrayOutputStream().also {prepared.writeTo(it) {true}}.toByteArray()
        }
        fun password()="synthetic edit package password".toCharArray()
        fun uuid()=UUID.randomUUID().toString()
        private fun sourceFile(f:PermanentErasureFixture,id:String)=java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") {"%02x".format(it)}+".edit"
        private suspend fun fails(block:suspend ()->Unit) {try {block();fail("Expected complete refusal")} catch(e:IllegalStateException) { } catch(e:IllegalArgumentException) { }}
    }
}
