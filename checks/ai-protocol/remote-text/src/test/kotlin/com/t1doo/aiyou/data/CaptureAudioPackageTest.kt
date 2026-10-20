package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureAudioPackageTest {
    @Test fun pureAudioSelectionUsesV9AndRestoresNewIdsWithSameBytesAndOneReceipt():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(7).use { target ->
            val ready=audio(source);source.capture.save(ready,10,"UTC")
            val repo=selected(source);val snapshot=SqlOriginalExportRepository(source.gateway,source.tags)
                .capture(listOf(SourceVersion(ready.id,1)),uuid(),20)
            val preview=repo.preview(snapshot);assertTrue(preview.images.isEmpty());assertEquals(1,preview.audios.size)
            val bytes=document(repo.seal(preview,password()))
            assertEquals("AIYOUPK9",String(bytes.copyOfRange(0,8),Charsets.US_ASCII))
            val pack=repo.open({bytes.inputStream()},password())
            try {
                assertEquals(1,pack.audios.size);val copy=uuid();val destination=selected(target)
                destination.restore(pack,listOf(copy),30)
                assertTrue(destination.restore(pack,listOf(copy),40).replayed)
                val restored=target.media.list(copy,1).single()
                assertNotEquals(ready.audio!!.intent.id,restored.intent.id)
                assertArrayEquals(CaptureAudioSqlTest.wave(),target.media.read(restored.intent.id))
                assertEquals(1,target.db.read("SELECT id FROM entries").size)
                assertEquals("",target.db.read("SELECT originalText FROM entry_revisions").single().single())
                val full=target.backup.capture(uuid(),50)
                try { assertEquals(1,full.summary.audioClips) } finally { full.release() }
                val journal=target.db.read("SELECT id,state FROM record_package_receipts WHERE operation='PACKAGE_MEDIA_INTENT'").single()
                val framed=java.util.Base64.getDecoder().decode(journal[1] as String)
                val reader=PackageReader(framed);assertEquals(6,reader.i32())
                val legacy=reader.raw(reader.i32(),32760);reader.end()
                try {
                    target.db.transaction { it.execute("UPDATE record_package_receipts SET state=? WHERE id=?",listOf(java.util.Base64.getEncoder().encodeToString(legacy),journal[0])) }
                    fails { destination.restore(pack,listOf(copy),60) }
                } finally {
                    framed.fill(0);legacy.fill(0)
                    target.db.transaction { it.execute("UPDATE record_package_receipts SET state=? WHERE id=?",listOf(journal[1],journal[0])) }
                }
                assertThrows(OriginalExportConflict::class.java) { runBlocking { repo.exportOpenZip(preview) } }
            } finally { pack.release() }
        } }
    }
    @Test fun fullV9RestoresAudioDraftAndImagesUnderNewKeyWithoutChangingLogicalIds():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(9).use { target ->
            val entry=source.entry();var draft=source.capture.current()
            val image=MixedCaptureSqlTest.intent(draft);draft=source.capture.add(draft,image,MixedCaptureSqlTest.png)
            val ready=audio(source,draft)
            val snapshot=source.backup.capture(uuid(),100)
            assertEquals(1,snapshot.summary.audioClips);assertEquals(1,snapshot.summary.images)
            val bytes=try { document(source.backup.seal(snapshot,password())) } finally { snapshot.release() }
            assertEquals("AIYOUFB9",String(bytes.copyOfRange(0,8),Charsets.US_ASCII))
            val pack=target.backup.open({bytes.inputStream()},password())
            try {
                val restored=target.backup.restore(pack){true};assertFalse(restored.replayed)
                assertTrue(target.backup.restore(pack){true}.replayed)
                val after=target.capture.peek()!!
                assertTrue(ready.sameSnapshot(after));assertArrayEquals(CaptureAudioSqlTest.wave(),target.capture.audio(after))
                assertArrayEquals(MixedCaptureSqlTest.png,target.capture.image(after,image.id))
                assertEquals(entry,target.db.read("SELECT id FROM entries").single().single())
                assertEquals(0L,target.db.read("SELECT count(*) FROM remote_text_jobs").single().single())
            } finally { pack.release() }
        } }
    }
    @Test fun fullV9PreservesAudioOnlyOriginalAndRejectsCrossKindOrTruncatedDocuments():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(3).use { target ->
            val ready=audio(source);source.capture.save(ready,1,"UTC")
            val snapshot=source.backup.capture(uuid(),10)
            val bytes=try { document(source.backup.seal(snapshot,password())) } finally { snapshot.release() }
            val pack=target.backup.open({bytes.inputStream()},password())
            try {
                target.backup.restore(pack){true}
                val item=target.media.list(ready.id,1).single();assertArrayEquals(CaptureAudioSqlTest.wave(),target.media.read(item.intent.id))
                assertEquals("",target.db.read("SELECT originalText FROM entry_revisions").single().single())
            } finally { pack.release() }
            fails { selected(target).open({bytes.inputStream()},password()) }
            fails { target.backup.open({bytes.copyOf(bytes.size-1).inputStream()},password()) }
            fails { target.backup.open({(bytes+byteArrayOf(0)).inputStream()},password()) }
        } }
    }
    @Test fun authenticatedForeignAudioEvidenceCannotAuthorizeEmptyOriginalInV9():Unit=runBlocking {
        FullFixture().use { source ->
            val ready=audio(source);source.capture.save(ready,1,"UTC")
            val repo=selected(source);val snapshot=SqlOriginalExportRepository(source.gateway,source.tags)
                .capture(listOf(SourceVersion(ready.id,1)),uuid(),10)
            val bytes=document(repo.seal(repo.preview(snapshot),password()))
            val header=bytes.copyOfRange(0,40);val firstSize=ByteBuffer.wrap(bytes,40,4).int
            val key=RecordPackageCrypto.derive(password(),header.copyOfRange(16,32))
            fun crypt(mode:Int,index:Int,binding:ByteArray,value:ByteArray):ByteArray {
                val seq=ByteBuffer.allocate(4).putInt(index).array()
                return RecordPackageCrypto.crypt(mode,key,header.copyOfRange(32,40)+seq,header+binding+seq,value)
            }
            try {
                val metadata=crypt(Cipher.DECRYPT_MODE,0,ByteArray(32),bytes.copyOfRange(44,44+firstSize))
                val id=ready.id.toByteArray();val offset=(0..metadata.size-id.size).first { at -> id.indices.all { metadata[at+it]==id[it] } }
                uuid().toByteArray().copyInto(metadata,offset)
                val hash=packageHash(metadata)
                fun frame(index:Int,binding:ByteArray,value:ByteArray):ByteArray {
                    val encrypted=crypt(Cipher.ENCRYPT_MODE,index,binding,value)
                    return ByteBuffer.allocate(4).putInt(encrypted.size).array()+encrypted
                }
                val forged=header+frame(0,ByteArray(32),metadata)+frame(1,hash,CaptureAudioSqlTest.wave())+frame(2,hash,hash)
                fails { repo.open({forged.inputStream()},password()) }
                metadata.fill(0)
            } finally { key.fill(0) }
        }
    }
    @Test fun reservedDraftBlocksActualRestoreAndDeletionBeforeFilesOrSqlCanChange():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(2).use { target ->
            val sourceId=source.entry();val snap=source.backup.capture(uuid(),10)
            val bytes=try { document(source.backup.seal(snap,password())) } finally { snap.release() }
            val repo=SqlFullBackupRepository(target.gateway,target.key,target.media,target.edits,selected(target),target.capture.mutationGate)
            val pack=repo.open({bytes.inputStream()},password())
            val selectionSource=selected(source)
            val selectedSnapshot=SqlOriginalExportRepository(source.gateway,source.tags).capture(listOf(SourceVersion(sourceId,1)),uuid(),12)
            val selectedBytes=document(selectionSource.seal(selectionSource.preview(selectedSnapshot),password()))
            val selectedPack=selectionSource.open({selectedBytes.inputStream()},password())
            val base=target.capture.current();val gate=target.capture.mutationGate;val r=gate.freeze(base.id)!!;gate.reserve(r,base,base.text,0)
            try {
                assertThrows(CaptureConflict::class.java) { runBlocking { repo.restore(pack){true} } }
                val plain=SqlRecordPackageRepository(target.gateway,target.tags,captureGate=gate)
                val selectedTarget=SqlRecordMediaPackageRepository(target.gateway,SqlOriginalExportRepository(target.gateway,target.tags),
                    target.media,plain,edits=target.edits,captureGate=gate)
                assertThrows(CaptureConflict::class.java) { runBlocking { selectedTarget.restore(selectedPack,listOf(uuid()),20) } }
                assertThrows(CaptureConflict::class.java) { plain.restoreWithFiles(selectedPack,listOf(uuid()),20) {} }
                val erasure=SqlPermanentErasureRepository(target.gateway,target.media,target.edits,target.packages,captureGate=gate)
                assertThrows(CaptureConflict::class.java) { runBlocking { erasure.erase(PermanentErasureIntent(SourceVersion(base.id,1),0,uuid())) } }
                assertTrue(base.sameSnapshot(target.capture.peek()!!))
                assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
                assertTrue(target.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") })
            } finally { gate.finish(r,target.capture.peek()!!);pack.release();selectedPack.release() }
        } }
    }
    @Test fun audioSelectionRetainsHistoryPeopleAndSavedEditDraftScope():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(8).use { target ->
            val ready=audio(source);source.capture.save(ready,1,"UTC")
            val people=SqlManualPeopleRepository(source.gateway,source.key)
            source.db.transaction { people.createWithin(it,ready.id,listOf("合成人物"),"SYNTHETIC_AUDIO_PERSON") }
            source.edits.write(null,OriginalEditDraft(uuid(),ready.id,1,1,1,"","合成保留编辑"))
            val originals=SqlOriginalExportRepository(source.gateway,source.tags,people=people)
            val repo=SqlRecordMediaPackageRepository(source.gateway,originals,source.media,
                SqlRecordPackageRepository(source.gateway,source.tags,people=people),edits=source.edits)
            val snapshot=originals.capture(listOf(SourceVersion(ready.id,1)),uuid(),10)
            val preview=repo.previewEditDrafts(snapshot)
            val bytes=document(repo.seal(preview,password()));val pack=repo.open({bytes.inputStream()},password())
            try {
                assertTrue(pack.includesHistory);assertEquals(1,pack.audios.size);assertEquals(1,pack.editDrafts!!.size)
                val targetPeople=SqlManualPeopleRepository(target.gateway,target.key)
                val destination=SqlRecordMediaPackageRepository(target.gateway,SqlOriginalExportRepository(target.gateway,target.tags,people=targetPeople),
                    target.media,SqlRecordPackageRepository(target.gateway,target.tags,people=targetPeople),edits=target.edits)
                val copy=uuid();destination.restore(pack,listOf(copy),20)
                assertEquals(listOf("合成人物"),targetPeople.read(copy).names)
                assertEquals("合成保留编辑",target.edits.read(copy)!!.text)
                assertArrayEquals(CaptureAudioSqlTest.wave(),target.media.read(target.media.list(copy,1).single().intent.id))
            } finally { pack.release() }
        } }
    }
    private suspend fun fails(action:suspend()->Any?) { var failed=false;try { action() } catch(_:Exception) { failed=true };assertTrue("Expected rejection",failed) }
    private suspend fun audio(f:FullFixture,base:CaptureDraft?=null):CaptureDraft {
        val r=CaptureAudioSqlTest.reserve(f.capture,base ?: f.capture.current(),CaptureAudioSqlTest.wave())
        return f.capture.addAudio(r,CaptureAudioSqlTest.wave()).also { f.capture.mutationGate.finish(r,it) }
    }
    private fun selected(f:FullFixture)=SqlRecordMediaPackageRepository(f.gateway,SqlOriginalExportRepository(f.gateway,f.tags),f.media,f.packages,edits=f.edits)
    private suspend fun document(prepared:PreparedRecordMediaPackage):ByteArray = try {
        ByteArrayOutputStream().also { prepared.writeTo(it){true} }.toByteArray()
    } finally { prepared.release() }
    private fun password()="synthetic audio archive password".toCharArray()
    private fun uuid()=UUID.randomUUID().toString()
}
