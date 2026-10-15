package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MixedCaptureSqlTest {
    @Test fun authenticatedV2EmptyBodyWithoutMatchingImageIsRejectedAndV1StillRejectsEmpty():Unit=runBlocking {
        fixture().use { f->
            val c=repo(f);val base=c.current();val full=c.add(base,intent(base),png);c.save(full,10,"UTC")
            val snapshot=SqlOriginalExportRepository(f.gateway,f.tags).capture(listOf(SourceVersion(full.id,1)),uuid(),20)
            assertThrows(IllegalArgumentException::class.java) { RecordMediaPreview(snapshot,emptyList()) }
            val original=JcaRecordPackageCipher().encode(snapshot,true)
            val metadata=PackageWriter(RecordMediaPackages.MAX_METADATA).use { w->
                w.string("AIyou-images-v2",32);w.i32(2);w.i32(1)
                w.strings(listOf("currentOriginals","manualTags","readyOriginalImages"),3,32)
                w.strings(listOf("originalHistory","tasks","drafts","aiDerived","serviceKeys","deviceKeys"),6,32)
                w.i32(original.size);w.raw(original);w.i32(0);w.bytes()
            }
            val salt=ByteArray(16){it.toByte()};val prefix=ByteArray(8){(it+16).toByte()}
            val header="AIYOUPK2".toByteArray()+java.nio.ByteBuffer.allocate(8).putInt(2).putInt(RecordPackages.ITERATIONS).array()+salt+prefix
            val key=RecordPackageCrypto.derive("synthetic package password".toCharArray(),salt);val hash=packageHash(metadata)
            fun frame(index:Int,binding:ByteArray,bytes:ByteArray):ByteArray {
                val seq=java.nio.ByteBuffer.allocate(4).putInt(index).array()
                val encrypted=RecordPackageCrypto.crypt(javax.crypto.Cipher.ENCRYPT_MODE,key,prefix+seq,header+binding+seq,bytes)
                return java.nio.ByteBuffer.allocate(4).putInt(encrypted.size).array()+encrypted
            }
            try {
                val bad=header+frame(0,ByteArray(32),metadata)+frame(1,hash,hash)
                fails { RecordMediaPackageCipher().open({bad.inputStream()},"synthetic package password".toCharArray()) }
                assertThrows(RecordPackageInvalid::class.java) { JcaRecordPackageCipher().seal(snapshot,"synthetic package password".toCharArray()) }
            } finally { key.fill(0);metadata.fill(0);original.fill(0) }
        }
    }
    @Test fun encryptedImageOnlyDraftReopensAndCommitsEmptyBodyOnlyAfterAllImagesAuthenticate(): Unit=runBlocking {
        fixture().use { f ->
            var c=repo(f);var d=c.current();val id=d.id;val i=intent(d)
            d=c.add(d,i,png);assertEquals(CaptureImageState.READY,d.images.single().state)
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty());assertArrayEquals(png,c.image(d,i.id))
            val raw=f.mediaRoot.resolve(i.id+".aead").readBytes();assertFalse(raw.contentEquals(png))
            f.reopen();c=repo(f);c.recover();d=c.current();assertEquals(id,d.id);assertEquals("",d.text)
            val result=c.save(d,10,"UTC");assertFalse(result.replayed)
            assertEquals(listOf("","AI_NONE"),f.db.read("SELECT r.originalText,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id WHERE e.id=?",id).single())
            assertArrayEquals(png,f.media.read(i.id));assertEquals(AttachmentState.READY,f.media.list(id,1).single().state)
            assertNotEquals(id,c.current().id)
        }
    }
    @Test fun legacyTextDraftMigrationIsAtomicAndMixedBodyIsExactWithoutPlaceholder(): Unit=runBlocking {
        fixture().use { f ->
            val id=uuid();f.db.write("INSERT INTO capture_draft VALUES(1,?,7,?)",id,"合成此刻🙂")
            val c=repo(f);var d=c.current();assertEquals(id,d.id);assertEquals("合成此刻🙂",d.text)
            assertTrue(f.db.read("SELECT id FROM capture_draft").isEmpty())
            d=c.add(d,intent(d),png);c.save(d,12,"Asia/Shanghai")
            assertEquals("合成此刻🙂",f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?",id).single().single())
            assertEquals(1,f.media.list(id,1).size)
        }
    }
    @Test fun emptyWithoutImagesRejectedAndUnfinishedUtf16DraftSurvivesReopen(): Unit=runBlocking {
        fixture().use { f ->
            var c=repo(f);var d=c.current();fails { c.save(d,1,"UTC") }
            d=c.edit(d," ");fails { c.save(d,1,"UTC") }
            d=c.edit(d,"合成\uD83D");f.reopen();c=repo(f);assertEquals("合成\uD83D",c.current().text)
            fails { c.save(d,1,"UTC") };assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
        }
    }
    @Test fun lostImageAndSaveAcknowledgementsReuseStableIdsAndNeverResurrectDeletedRecord(): Unit=runBlocking {
        fixture().use { f ->
            var c=repo(f);val base=c.current();val image=intent(base)
            f.mediaCheckpoint={ if(it=="capture-published") error("Synthetic lost image publication ACK") }
            fails { c.add(base,image,png) };f.mediaCheckpoint={};f.reopen();c=repo(f);c.recover()
            val ready=c.current();assertEquals(image,ready.images.single().intent)
            var saved=false
            f.db.afterSql={ sql ->if(sql.startsWith("INSERT INTO record_package_receipts") && sql.contains("'CAPTURE_SAVED'")) saved=true
                if(sql=="COMMIT" && saved) { saved=false;error("Synthetic final commit ACK loss") } }
            fails { c.save(ready,99,"UTC") };f.db.afterSql=null;f.reopen();c=repo(f)
            assertTrue(c.save(ready,999,"UTC").replayed);assertEquals(1,f.db.read("SELECT id FROM entries").size)
            assertEquals(99L,f.db.read("SELECT capturedAtMillis FROM entries").single().single())
            assertTrue(f.erase.erase(f.intent(ready.id)).complete)
            val retry=c.save(ready,999,"UTC");assertTrue(retry.replayed && retry.deleted)
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty());assertTrue(f.db.read("SELECT id FROM attachments").isEmpty())
        }
    }
    @Test fun partialFileFailurePreservesIntentAndCanRetrySameOriginalAfterColdReopen(): Unit=runBlocking {
        fixture().use { f ->
            var c=repo(f);val base=c.current();val i=intent(base)
            f.mediaCheckpoint={ if(it=="created") error("Synthetic file failure") }
            fails { c.add(base,i,png) };assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
            f.reopen();f.mediaCheckpoint={};c=repo(f);c.recover();val failed=c.current()
            assertEquals(CaptureImageState.FAILED,failed.images.single().state);fails { c.save(failed,1,"UTC") }
            val ready=c.add(failed,i,png);assertEquals(CaptureImageState.READY,ready.images.single().state)
            assertArrayEquals(png,c.image(ready,i.id));c.save(ready,3,"UTC")
        }
    }
    @Test fun actualDatabaseAbortKeepsAllDraftInputAndNoPartialVisibleRecord(): Unit=runBlocking {
        fixture().use { f ->
            val c=repo(f);var d=c.edit(c.current(),"合成图文")
            d=c.add(d,intent(d),png);d=c.add(d,intent(d),png)
            f.db.write("CREATE TEMP TRIGGER reject_capture BEFORE INSERT ON record_package_receipts WHEN NEW.operation='CAPTURE_SAVED' BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
            fails { c.save(d,1,"UTC") };assertTrue(f.db.read("SELECT id FROM entries").isEmpty());assertTrue(f.db.read("SELECT id FROM attachments").isEmpty())
            assertEquals(d.text,c.current().text);assertEquals(2,c.current().images.size)
            f.db.write("DROP TRIGGER reject_capture");c.save(d,2,"UTC");assertEquals(2,f.media.list(d.id,1).size)
        }
    }
    @Test fun removeAndDiscardCleanupResumeAndLateOldIntentsCannotAttachToNewDraft(): Unit=runBlocking {
        fixture().use { f ->
            var c=repo(f);val base=c.current();val image=intent(base);val full=c.add(base,image,png)
            f.mediaCheckpoint={ if(it=="package-cleaned") error("Synthetic cleanup ACK loss") }
            fails { c.remove(full.id,image.id) };f.reopen();f.mediaCheckpoint={};c=repo(f);c.recover()
            val empty=c.current();assertTrue(empty.images.isEmpty());fails { c.add(base,image,png) }
            val next=c.add(empty,intent(empty),png)
            f.mediaCheckpoint={ if(it=="package-cleaned") error("Synthetic discard cleanup failure") }
            fails { c.discard(next.id) };f.reopen();f.mediaCheckpoint={};c=repo(f);c.recover()
            val fresh=c.current();assertNotEquals(next.id,fresh.id);assertEquals(fresh.id,c.discard(next.id).id)
            fails { c.add(next,intent(next),png) };assertFalse(f.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") || it.name.endsWith(".tmp") })
        }
    }
    @Test fun missingOrCorruptStagedOriginalNeverSilentlyDegradesToTextOnlySave(): Unit=runBlocking {
        fixture().use { f ->
            var c=repo(f);var d=c.edit(c.current(),"合成保留文字");val i=intent(d);d=c.add(d,i,png)
            f.mediaRoot.resolve(i.id+".aead").let { file ->val b=file.readBytes();b[b.lastIndex]=(b.last().toInt() xor 1).toByte();file.writeBytes(b) }
            fails { c.save(d,1,"UTC") };f.reopen();c=repo(f);c.recover();val bad=c.current()
            assertEquals(CaptureImageState.FAILED,bad.images.single().state);fails { c.save(bad,1,"UTC") }
            val cleared=c.remove(bad.id,i.id);assertEquals("合成保留文字",cleared.text);assertTrue(cleared.images.isEmpty())
            c.save(cleared,2,"UTC")
        }
    }
    @Test fun imageOnlyLifecycleSupportsFavoritesLabelsSearchTrashRestoreAndPermanentErase(): Unit=runBlocking {
        fixture().use { f ->
            val c=repo(f);val base=c.current();val d=c.add(base,intent(base),png)
            c.save(d,10,"UTC");val favorites=SqlRecordFavoriteRepository(f.gateway)
            assertTrue(favorites.set(RecordFavoriteMutation(d.id,1,OriginalExports.hash(""),0,true,uuid())).isFavorite)
            f.tags.replace(ManualTagMutation(d.id,1,listOf("合成照片"),uuid()))
            val search=SqlLocalSearchRepository(f.gateway,f.tags);var n=0;while(search.batch().remaining) check(++n<100)
            assertEquals(listOf(d.id),search.candidates(LocalSearchRequest("合成照片")).entries.map { it.id })
            assertTrue(search.candidates(LocalSearchRequest("不存在的画面内容")).entries.isEmpty())
            val original=f.bin.read(d.id);val trash=f.bin.apply(RecycleMutation(SourceVersion(d.id,1),original.generation,false,uuid()))
            assertTrue(trash.deleted);val restored=f.bin.apply(RecycleMutation(SourceVersion(d.id,1),trash.generation,true,uuid()))
            assertFalse(restored.deleted);assertEquals("",restored.entry.originalText)
            assertTrue(f.erase.erase(f.intent(d.id)).complete);assertTrue(c.save(d,20,"UTC").deleted)
        }
    }
    @Test fun pureImageV2RoundtripAndOpenTextExportPreserveRealEmptyBodyWhileV1RejectsIt(): Unit=runBlocking {
        fixture().use { f ->
            val c=repo(f);val base=c.current();val d=c.add(base,intent(base),png);c.save(d,10,"UTC")
            val originals=SqlOriginalExportRepository(f.gateway,f.tags);val snapshot=originals.capture(listOf(SourceVersion(d.id,1)),uuid(),20)
            assertEquals(1,snapshot.records.single().imageCount)
            val plain=OriginalExportCodec.render(snapshot,OriginalExportFormat.JSONL)
            try { assertTrue(plain.copyBytes().toString(Charsets.UTF_8).contains("\"originalText\":\"\"")) } finally { plain.release() }
            assertThrows(RecordPackageInvalid::class.java) { JcaRecordPackageCipher().seal(snapshot,"synthetic package password".toCharArray()) }
            val packages=SqlRecordMediaPackageRepository(f.gateway,originals,f.media,f.packages);val prepared=packages.seal(packages.preview(snapshot),"synthetic package password".toCharArray())
            val out=ByteArrayOutputStream();try { prepared.writeTo(out){true} } finally { prepared.release() }
            val bytes=out.toByteArray();val pack=packages.open({bytes.inputStream()},"synthetic package password".toCharArray())
            try {
                assertEquals("",pack.records.single().originalText);val copy=uuid();packages.restore(pack,listOf(copy),30)
                assertArrayEquals(png,f.media.read(f.media.list(copy,1).single().intent.id));assertEquals("",f.bin.read(copy).entry.originalText)
            } finally { pack.release() }
        }
    }
    @Test fun staleEditAndRepeatedSaveCannotOverwriteNewerDraftOrCreateSecondRecord(): Unit=runBlocking {
        fixture().use { f ->
            val c=repo(f);val base=c.current();val newer=c.edit(base,"先写入的文字")
            fails { c.edit(base,"迟到输入") };assertEquals(newer.text,c.current().text)
            val d=c.add(newer,intent(newer),png);c.save(d,5,"UTC");assertTrue(c.save(d,999,"Asia/Shanghai").replayed)
            assertEquals(1,f.db.read("SELECT id FROM entries").size);assertEquals(1,f.media.list(d.id,1).size)
        }
    }
    @Test fun imageLimitsAndImmutableRetryBytesRejectBeforeVisibleRecords(): Unit=runBlocking {
        fixture().use { f ->
            val c=repo(f);val d=c.current();val i=intent(d)
            fails { c.add(d,i,png+byteArrayOf(1)) };assertTrue(c.current().images.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { CaptureDraft(d.id,1,"",List(21){CaptureDraftImage(intent(d),CaptureImageState.READY)}) }
            val images=List(5){CaptureDraftImage(AttachmentIntent(uuid(),d.id,1,"image/png","a".repeat(64),16L*1024*1024),CaptureImageState.READY)}
            assertThrows(IllegalArgumentException::class.java) { CaptureDraft(d.id,1,"",images) }
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
        }
    }
    private suspend fun fails(block:suspend()->Any?) { var failed=false;try { block() } catch(_:Exception){failed=true};assertTrue("Expected fail closed",failed) }
    companion object {
        val png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")
        fun uuid()=UUID.randomUUID().toString()
        internal fun fixture()=PermanentErasureFixture().also { f->f.db.transaction { sql->RecordFavoriteSqlSchema.migration8To9.forEach { sql.execute(it) };sql.execute("PRAGMA user_version=9") } }
        internal fun repo(f:PermanentErasureFixture)=SqlCaptureRepository(f.gateway,f.media,f.tags)
        fun intent(d:CaptureDraft)=AttachmentIntent(uuid(),d.id,1,"image/png",PermanentErasureFixture.hash(png),png.size.toLong())
    }
}
