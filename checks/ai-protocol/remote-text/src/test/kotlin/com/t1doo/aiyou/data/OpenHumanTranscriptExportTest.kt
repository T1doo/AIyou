package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.*
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic encrypted store, production ZIP/controller and bounded document IO; no SAF/device. */
class OpenHumanTranscriptExportTest {
    @Test fun explicitScopeStreamsCurrentUnicodeTextExactAudioRangeAndCompleteFileHashes():Unit=runBlocking {
        FullFixture().use {f->
            val h=Harness(f);val old=h.save("合成旧文字");h.save("合成🙂\r\n```\n杯子是绿色",old,AudioSampleRange(1,3))
            val p=h.preview(true);val document=Document();h.stream(p,document)
            assertThrows(IllegalArgumentException::class.java){runBlocking {h.media.seal(p,"synthetic package password".toCharArray())}}
            val files=unzip(document.bytes);val manifest=json(files.getValue("manifest.json"))
            assertEquals(5,manifest.getValue("formatVersion").jsonPrimitive.int)
            assertEquals(1,manifest.getValue("humanTranscriptCount").jsonPrimitive.int)
            assertTrue(manifest.getValue("scope").jsonArray.any {it.jsonPrimitive.content=="currentHumanTranscripts"})
            val text=json(files.getValue("human-transcripts.jsonl"))
            assertEquals("合成🙂\r\n```\n杯子是绿色",text.getValue("text").jsonPrimitive.content)
            assertEquals(2,text.getValue("textRevision").jsonPrimitive.int)
            assertEquals(h.audio.id,text.getValue("attachmentId").jsonPrimitive.content)
            assertEquals(h.audio.sha256,text.getValue("audioSha256").jsonPrimitive.content)
            assertEquals(h.source.sourceHash,text.getValue("sourceHash").jsonPrimitive.content)
            assertEquals(h.source.generation,text.getValue("sourceGeneration").jsonPrimitive.long)
            assertEquals(h.audio.revision,text.getValue("sourceRevision").jsonPrimitive.int)
            assertEquals(h.audio.entryId,text.getValue("sourceId").jsonPrimitive.content)
            assertEquals(1,text.getValue("range").jsonObject.getValue("startFrame").jsonPrimitive.int)
            assertEquals(3,text.getValue("range").jsonObject.getValue("endFrameExclusive").jsonPrimitive.int)
            assertEquals(44100,text.getValue("sampleRate").jsonPrimitive.int)
            assertArrayEquals(CaptureAudioSqlTest.wave(),files.getValue("audio/${h.audio.id}.wav"))
            assertFalse(files.getValue("records.md").toString(Charsets.UTF_8).contains("合成旧文字"))
            for(row in manifest.getValue("files").jsonArray.map {it.jsonObject}) {
                val bytes=files.getValue(row.getValue("path").jsonPrimitive.content)
                assertEquals(bytes.size.toLong(),row.getValue("byteSize").jsonPrimitive.long)
                assertEquals(FullBackupTest.hash(bytes),row.getValue("sha256").jsonPrimitive.content)
            }
            assertEquals(files.size-1,manifest.getValue("files").jsonArray.size)
            assertTrue(document.outputClosed&&document.inputClosed);assertEquals(0,document.deletes)
            h.picker(true);val selected=Document();val key=h.ui.requestKey!!
            h.controller.picked(h.ui.session,key,selected);h.drain()
            assertEquals(OriginalExportPhase.DONE,h.ui.phase);assertTrue(selected.outputClosed&&selected.inputClosed)
            assertFalse(h.ui.riskAccepted);assertEquals(1,selected.opens)
            h.controller.picked(h.ui.session,key,selected);h.drain();assertEquals(1,selected.opens)
            assertEquals("合成🙂\r\n```\n杯子是绿色",json(unzip(selected.bytes).getValue("human-transcripts.jsonl")).getValue("text").jsonPrimitive.content)
        }
    }
    @Test fun enabledEmptyScopeIsVersionedAndInvalidatesOnNewTextWhileDisabledScopeDoesNotReadIt():Unit=runBlocking {
        FullFixture().use {f->
            val h=Harness(f);val off=h.preview(false);val empty=h.preview(true)
            assertNull(off.openTranscripts);assertTrue(empty.openTranscripts!!.items.isEmpty())
            for((p,version) in listOf(off to 4,empty to 5)) {
                val d=Document();h.stream(p,d);val files=unzip(d.bytes)
                assertEquals(version,json(files.getValue("manifest.json")).getValue("formatVersion").jsonPrimitive.int)
                assertEquals(version==5,files.containsKey("human-transcripts.jsonl"))
                if(version==5)assertEquals(0,files.getValue("human-transcripts.jsonl").size)
            }
            val added=h.save("合成新建人工稿");assertFalse(h.media.isCurrent(empty));assertTrue(h.media.isCurrent(off))
            h.texts.write(AudioTranscriptWrite(added.id,added.anchor,added,null,true,uuid(),20))
            assertFalse("Create then withdraw cannot revive an empty scope",h.media.isCurrent(empty))
            val withdrawn=h.preview(true);assertTrue(withdrawn.openTranscripts!!.items.isEmpty())
            val clean=Document();h.stream(withdrawn,clean);assertEquals(0,unzip(clean.bytes).getValue("human-transcripts.jsonl").size)
            f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE ruleVersion=4",byteArrayOf(0))
            assertTrue(h.media.isCurrent(off)) // The unchecked route has no new transcript read prerequisite.
            assertThrows(Exception::class.java){runBlocking {h.preview(true)}}
        }
    }
    @Test fun rangeOnlyCorrectionWithdrawalSourceChangeAndForeignProofInvalidateExactScope():Unit=runBlocking {
        for(mode in listOf("range","withdraw","generation","revision","deleted")) FullFixture().use {f->
            val h=Harness(f);val saved=h.save("合成有效人工稿",range=AudioSampleRange(0,2));val p=h.preview(true)
            when(mode) {
                "range"->h.save(saved.current.text,saved,AudioSampleRange(1,3))
                "withdraw"->h.texts.write(AudioTranscriptWrite(saved.id,saved.anchor,saved,null,true,uuid(),20))
                "generation"->f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",h.audio.entryId)
                "revision"->f.revise(h.audio.entryId,"合成修订原文")
                else->f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",h.audio.entryId)
            }
            assertFalse(mode,h.media.isCurrent(p));assertThrows(Exception::class.java){runBlocking {h.media.exportOpenZip(p)}}
        }
        FullFixture().use {f->
            val h=Harness(f);h.save("合成原稿");val p=h.preview(true)
            val forged=object:OpenTranscriptScope {override val items=p.openTranscripts!!.items}
            assertFalse(h.media.isCurrent(RecordMediaPreview(p.snapshot,p.images,audios=p.audios,openTranscripts=forged)))
            val other=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
            val repo=SqlRecordMediaPackageRepository(f.gateway,h.originals,f.media,f.packages,transcripts=other)
            assertFalse(repo.isCurrent(p));other.wipeKey()
        }
    }
    @Test fun realInputCloseChangesCannotPublishDoneAndExplicitFileCleanupRemainsSeparate():Unit=runBlocking {
        for(mode in listOf("new","correct","withdraw","source")) FullFixture().use {f->
            val h=Harness(f);val old=if(mode=="new")null else h.save("合成确认文字")
            h.picker(true);val d=Document();d.onInputClose={when(mode){
                "new"->runBlocking {h.save("合成后到文字")}
                "correct"->runBlocking {h.save(old!!.current.text,old,AudioSampleRange(1,3))}
                "withdraw"->runBlocking {h.texts.write(AudioTranscriptWrite(old!!.id,old.anchor,old,null,true,uuid(),20))}
                else->f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",h.audio.entryId)
            }}
            val s=h.ui.session;val key=h.ui.requestKey!!
            h.controller.picked(s,key,d);h.drain()
            assertTrue(d.outputClosed&&d.inputClosed);assertEquals(OriginalExportPhase.CHANGED,h.ui.phase)
            assertTrue(h.ui.hasFile);assertNull(h.ui.notice);assertFalse(h.ui.riskAccepted);assertEquals(0,d.deletes)
            h.controller.prepare(s);h.controller.picked(s,key,Document());assertTrue(h.queue.isEmpty())
            h.controller.deleteSelectedFile(s,key);h.drain();assertEquals(1,d.deletes);assertFalse(h.ui.hasFile)
        }
    }
    @Test fun failedReadCommitAckNeverInvokesFinalPublication():Unit=runBlocking {
        FullFixture().use {f->
            val h=Harness(f);h.save("合成原稿");val p=h.preview(true)
            var calls=0;var failAt=Int.MAX_VALUE
            val gateway=object:RecordsSqlDatabase {override fun <T> transaction(block:(RecordsSqlConnection)->T):T {
                val value=f.gateway.transaction(block);calls++;if(calls==failAt)throw IOException("synthetic read ACK loss");return value
            }}
            val t=SqlAudioTranscriptRepository(gateway,f.key,f.media)
            val repo=SqlRecordMediaPackageRepository(gateway,h.originals,f.media,f.packages,transcripts=t)
            val scope=repo.previewOpenZipWithTranscripts(p.snapshot){true}
            // isCurrent has two shared-connection checks; the third is the final post-IO read commit.
            failAt=calls+3;var published=0
            assertThrows(IOException::class.java){runBlocking {repo.confirmOpenZip(scope){published++}}}
            assertEquals(0,published);t.wipeKey()
        }
    }
    @Test fun closeUnknownAndCancelKeepSelectedFileAndRejectLateDestinationWithoutAutomaticDelete():Unit=runBlocking {
        for(cancel in listOf(false,true)) FullFixture().use {f->
            val h=Harness(f);h.save("合成私密转写");h.picker(true)
            val s=h.ui.session;val key=h.ui.requestKey!!;val d=Document(failOutputClose=!cancel)
            if(cancel)d.onWrite={h.controller.cancel(s)}
            h.controller.picked(s,key,d);h.drain()
            assertEquals(if(cancel)OriginalExportPhase.CANCELLED else OriginalExportPhase.FAILED,h.ui.phase)
            assertTrue(h.ui.hasFile);assertNull(h.ui.notice?.takeIf {it.contains("已写入并")});assertEquals(0,d.deletes)
            h.controller.close(s);h.controller.open();val late=Document();h.controller.picked(s,key,late);h.drain()
            assertEquals(0,late.opens);assertFalse(h.ui.includeTranscripts)
        }
    }
    private class Harness(val f:FullFixture) {
        val audio=runBlocking {AudioTranscriptSqlTest.audio(f)}
        val texts=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
        val originals=SqlOriginalExportRepository(f.gateway,f.tags)
        val media=SqlRecordMediaPackageRepository(f.gateway,originals,f.media,f.packages,transcripts=texts)
        val source=runBlocking {texts.preview(audio)}
        suspend fun save(text:String,old:AudioTranscriptSnapshot?=null,range:AudioSampleRange?=null)=
            texts.write(AudioTranscriptWrite(old?.id?:uuid(),source,old,text,false,uuid(),10,range))
        fun snapshot()=originals.capture(listOf(source.source),uuid(),30)
        suspend fun preview(include:Boolean)=if(include)media.previewOpenZipWithTranscripts(snapshot()){true} else media.previewOpenZip(snapshot()){true}
        suspend fun stream(p:RecordMediaPreview,d:Document){val ready=media.exportOpenZip(p);try{OriginalExportIo.writeZipAndVerify(d,ready){true}}finally{ready.release()}}
        var local=LocalRecordUiState(ready=true,opening=false,supportsOriginalExport=true,entries=snapshot().records.map {it.entry})
        val queue=ArrayDeque<suspend (EntryRepository)->Unit>()
        private val store=object:EntryRepository {
            override val originalExport=originals;override val mediaPackages=media
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("unrelated")
            override suspend fun entries()=local.entries
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("unrelated")
        }
        val controller=OriginalExportController({local},{local=it(local)},{queue.add(it);true},Clock.systemUTC())
        val ui get()=local.originalExport!!
        suspend fun drain(){while(queue.isNotEmpty())queue.removeFirst()(store)}
        suspend fun picker(include:Boolean){controller.open();controller.toggle(ui.session,audio.entryId,1);controller.format(ui.session,OriginalExportFormat.MEDIA_ZIP)
            assertFalse(ui.includeTranscripts);controller.includeTranscripts(ui.session,include);controller.prepare(ui.session);drain()
            assertEquals(OriginalExportPhase.PREVIEW,ui.phase);controller.choose(ui.session,ui.snapshot!!.id,ui.format,ui.epoch);assertTrue(queue.isEmpty())
            controller.acceptRisk(ui.session,ui.snapshot!!.id,ui.format,ui.epoch,true);controller.choose(ui.session,ui.snapshot!!.id,ui.format,ui.epoch);drain()
            assertEquals(OriginalExportPhase.PICKING,ui.phase)}
    }
    private class Document(val failOutputClose:Boolean=false):SelectedExportDocument {
        var bytes=byteArrayOf();var outputClosed=false;var inputClosed=false;var opens=0;var deletes=0
        var onInputClose:(()->Unit)?=null;var onWrite:(()->Unit)?=null
        override fun openForWrite():OutputStream {opens++;return object:ByteArrayOutputStream(){
            override fun write(b:ByteArray,off:Int,len:Int){super.write(b,off,len);onWrite?.invoke()}
            override fun close(){bytes=toByteArray();outputClosed=true;if(failOutputClose)throw IOException("synthetic close ACK loss")}
        }}
        override fun openForRead():InputStream=object:ByteArrayInputStream(bytes){override fun close(){inputClosed=true;onInputClose?.invoke()}}
        override fun delete():Boolean {deletes++;bytes=byteArrayOf();return true}
    }
    companion object {
        private fun uuid()=UUID.randomUUID().toString()
        private fun json(bytes:ByteArray)=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        private fun unzip(bytes:ByteArray):Map<String,ByteArray> = buildMap {ZipInputStream(bytes.inputStream(),Charsets.UTF_8).use {z->while(true){val e=z.nextEntry?:break;put(e.name,z.readBytes())}}}
    }
}
