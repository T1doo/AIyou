package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic media and actual portable-cell AEAD/SQL. No device or real user backup. */
class AudioTranscriptRangeBackupTest {
    @Test fun v13MixedProfilesRekeyWholeCurrentWithRangedHistoryAndRetainVersionAfterInvalidation():Unit=runBlocking {
        FullFixture().use {source->FullFixture(93).use {target->
            val r=SqlAudioTranscriptRepository(source.gateway,source.key,source.media)
            val images=SqlImageTextRepository(source.gateway,source.key,source.media)
            try {
                val audio=AudioTranscriptSqlTest.audio(source);val a=r.preview(audio)
                val one=r.write(request(a,range=AudioSampleRange(1,4)))
                val whole=r.write(request(a,one,range=null))
                val legacyAnchor=r.preview(AudioTranscriptSqlTest.audio(source))
                val legacy=r.write(AudioTranscriptSqlTest.write(legacyAnchor,text="合成旧整段"))
                val image=source.image(source.entry());val imageAnchor=images.preview(image)
                val imageText=images.write(ImageTextSqlTest.create(imageAnchor,"合成端口文字，非真实OCR"))!!
                val before=source.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",whole.id).single().single() as ByteArray
                val bytes=AudioTranscriptSqlTest.export(source)
                assertEquals("AIYOUF13",String(bytes.copyOfRange(0,8),Charsets.US_ASCII))
                val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {
                    assertFalse(target.backup.completed(pack))
                    assertFalse(target.backup.restore(pack){true}.replayed)
                    assertTrue(target.backup.completed(pack));assertTrue(target.backup.restore(pack){true}.replayed)
                    val dest=SqlAudioTranscriptRepository(target.gateway,target.key,target.media)
                    val destImages=SqlImageTextRepository(target.gateway,target.key,target.media)
                    try {
                        assertTrue(dest.read(a)!!.same(whole));assertTrue(dest.read(legacyAnchor)!!.same(legacy))
                        assertNull(dest.read(a)!!.current.range);assertEquals(AudioSampleRange(1,4),dest.read(a)!!.history.first().range)
                        assertTrue(destImages.read(imageAnchor)!!.same(imageText))
                        assertArrayEquals(source.media.read(audio.id),target.media.read(audio.id))
                        assertFalse(before.contentEquals(target.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",whole.id).single().single() as ByteArray))
                        // Compare decoded portable logic, never random ciphertext identity.
                        val doc=target.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
                        try {
                            assertEquals(13,doc.version)
                            val p=TranscriptEncoding.decodePortable(doc.tables[8].single{it[0]==whole.id}[10] as ByteArray)
                            assertEquals(whole.anchor,p.anchor);assertEquals(whole.history,p.history)
                            assertEquals(1,TranscriptEncoding.portableVersion(doc.tables[8].single{it[0]==legacy.id}[10] as ByteArray))
                        }finally{doc.release()}
                        target.revise(audio.entryId,"恢复后原文修订")
                        assertTrue(target.backup.restore(pack){true}.replayed)
                        assertEquals(2L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",audio.entryId).single().single())
                        val bin=SqlRecycleBinRepository(target.gateway,target.tags,SqlErasureFileHooks::fenced)
                        val deleted=bin.apply(RecycleMutation(SourceVersion(audio.entryId,2),bin.read(audio.entryId).generation,false,FullBackupTest.uuid()))
                        SqlPermanentErasureRepository(target.gateway,target.media,target.edits,target.packages).erase(PermanentErasureIntent(SourceVersion(audio.entryId,2),deleted.generation,FullBackupTest.uuid()))
                        assertTrue(target.backup.restore(pack){true}.replayed)
                        assertTrue(target.db.read("SELECT id FROM entries WHERE id=?",audio.entryId).isEmpty())
                        assertTrue(target.db.read("SELECT id FROM local_candidate_batches WHERE id=?",whole.id).isEmpty())
                    }finally{dest.wipeKey();destImages.wipeKey()}
                }finally{pack.release()}
                val next=AudioTranscriptSqlTest.export(target)
                assertEquals("AIYOUF13",String(next.copyOfRange(0,8),Charsets.US_ASCII))
                FullFixture(94).use {third->val p=third.backup.open({next.inputStream()},FullBackupTest.password());try{third.backup.restore(p){true};assertTrue(third.backup.completed(p))}finally{p.release()}}
            }finally{r.wipeKey();images.wipeKey()}
        }}
    }
    @Test fun v13PendingAndLostFinalAckUseSameIntentAndInitializedEmptyTargetOnly():Unit=runBlocking {
        FullFixture().use {source->
            val r=SqlAudioTranscriptRepository(source.gateway,source.key,source.media)
            try {
                val a=r.preview(AudioTranscriptSqlTest.audio(source));val current=r.write(request(a,range=AudioSampleRange(0,1)))
                val withdrawn=r.write(AudioTranscriptSqlTest.write(a,current,withdraw=true))
                val bytes=AudioTranscriptSqlTest.export(source)
                for(point in listOf("none","full-intent","full-before-commit","full-committed"))FullFixture(95).use {target->
                    target.capture.current() // Exactly default empty initialized slot is legal.
                    val p=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                    try {
                        var failed=false
                        if(point!="none") {
                            target.checkpoint={if(it==point&&!failed){failed=true;error("Synthetic v13 checkpoint")}}
                            try {assertThrows(Exception::class.java){runBlocking{target.backup.restore(p){true}}}}finally{target.checkpoint={}}
                            assertTrue(failed)
                            assertEquals(point=="full-committed",target.backup.completed(p))
                            val receipt=target.db.read("SELECT sourceRevision,operation,state FROM record_package_receipts WHERE id=?","FULL_BACKUP:"+(p as AuthenticatedFullBackup).data.id).single()
                            assertEquals(listOf(13L,"FULL_BACKUP_IMPORT_V13"),receipt.take(2))
                            assertTrue((receipt[2] as String).startsWith(if(point=="full-committed")"DONE:"else "PENDING:"))
                        }
                        assertEquals(point=="full-committed",target.backup.restore(p){true}.replayed)
                        assertTrue(target.backup.completed(p));assertTrue(target.backup.restore(p){true}.replayed)
                        val dest=SqlAudioTranscriptRepository(target.gateway,target.key,target.media)
                        try {assertTrue(dest.read(a)!!.same(withdrawn));assertTrue(dest.search("").items.isEmpty())}finally{dest.wipeKey()}
                    }finally{p.release()}
                }
                FullFixture(96).use {nonempty->
                    nonempty.entry(text="不允许覆盖的合成目标")
                    val p=nonempty.backup.open({bytes.inputStream()},FullBackupTest.password())
                    try {assertThrows(Exception::class.java){runBlocking{nonempty.backup.restore(p){true}}}}finally{p.release()}
                    assertEquals(1,nonempty.db.read("SELECT id FROM entries").size)
                    assertTrue(nonempty.db.read("SELECT * FROM record_package_receipts").isEmpty())
                }
            }finally{r.wipeKey()}
        }
    }
    @Test fun authenticatedOldDeclaredVersionsAndMalformedRangesRejectBeforeAnyRestoreIntent():Unit=runBlocking {
        FullFixture().use {source->
            val r=SqlAudioTranscriptRepository(source.gateway,source.key,source.media)
            try {
                val a=r.preview(AudioTranscriptSqlTest.audio(source));r.write(request(a,range=AudioSampleRange(1,4)))
                val doc=source.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
                try {
                    // Valid outer AEAD and matching old header/manifest: only the new inner v2 is incompatible.
                    for(version in listOf(11,12))reject(raw(source,doc,version))
                    val row=doc.tables[8].single();val portable=row[10] as ByteArray
                    for(mode in listOf("too-long","zero","reversed","flag","unknown-version","trailing","no-range-v2")) {
                        val bad=when(mode) {
                            "unknown-version"->portable.copyOf().also {ByteBuffer.wrap(it,1+4+"AIyou-human-audio-transcript".length,4).putInt(3)}
                            "trailing"->portable+byteArrayOf(0)
                            "no-range-v2"->portable.copyOfRange(0,portable.size-8).also {ByteBuffer.wrap(it,it.size-4,4).putInt(0)}
                            else->portable.copyOf().also {
                                when(mode) {
                                    "too-long"->ByteBuffer.wrap(it,it.size-4,4).putInt(5)
                                    "zero"->ByteBuffer.wrap(it,it.size-4,4).putInt(1)
                                    "reversed"->ByteBuffer.wrap(it,it.size-8,8).putInt(3).putInt(2)
                                    "flag"->ByteBuffer.wrap(it,it.size-12,4).putInt(2)
                                }
                            }
                        }
                        val tables=doc.tables.mapIndexed {index,rows->if(index==8)listOf(row.toMutableList().also{it[10]=bad})else rows}
                        val changed=FullBackupData(doc.id,doc.created,tables,doc.images,doc.edits,doc.capture,doc.audios)
                        assertThrows(Exception::class.java){FullBackupCodec.encode(changed)}
                        reject(raw(source,changed,13));bad.fill(0)
                    }
                }finally{doc.release()}
            }finally{r.wipeKey()}
        }
    }
    @Test fun authenticatedWrongWavAndTrailingEofRejectWithoutIntentAndSecondReadCannotSubstitute():Unit=runBlocking {
        FullFixture().use {source->
            val r=SqlAudioTranscriptRepository(source.gateway,source.key,source.media)
            try {
                val a=r.preview(AudioTranscriptSqlTest.audio(source));r.write(request(a,range=AudioSampleRange(0,1)))
                val doc=source.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
                try {
                    val broken=source.media.read(a.intent.id).also{it[0]=0}
                    val wrong=a.intent.copy(sha256=FullBackupTest.hash(broken))
                    val old=doc.tables[8].single();val oldPayload=TranscriptEncoding.decodePortable(old[10] as ByteArray)
                    val tables=doc.tables.mapIndexed {index,rows->when(index) {
                        3->listOf(listOf(wrong.id,wrong.entryId,wrong.revision.toLong(),wrong.mediaType,wrong.sha256,wrong.byteSize,"READY"))
                        8->listOf(old.toMutableList().also{it[10]=byteArrayOf(4)+TranscriptEncoding.encode(oldPayload.copy(anchor=oldPayload.anchor.copy(intent=wrong)))})
                        else->rows
                    }}
                    val changed=FullBackupData(doc.id,doc.created,tables,doc.images,doc.edits,doc.capture,listOf(RecordPackageAudio(wrong.entryId,1,wrong.id,wrong.mediaType,wrong.byteSize,wrong.sha256)))
                    // Descriptor length/hash and outer AEAD are valid: scanFull must inspect the actual WAV.
                    reject(raw(source,changed,13){broken.copyOf()})
                    val valid=raw(source,doc,13)
                    reject(valid+byteArrayOf(0))
                    FullFixture(98).use {target->
                        var sourceBytes=valid
                        val p=target.backup.open({sourceBytes.inputStream()},FullBackupTest.password())
                        sourceBytes=valid+byteArrayOf(0)
                        try {assertThrows(Exception::class.java){runBlocking{target.backup.restore(p){true}}}}finally{p.release()}
                        assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
                        assertTrue(target.db.read("SELECT state FROM record_package_receipts WHERE state LIKE 'DONE:%'").isEmpty())
                    }
                    broken.fill(0)
                }finally{doc.release()}
            }finally{r.wipeKey()}
        }
    }
    @Test fun retainedV13ReceiptIsStrictAndPortableRangeIsRevalidatedBeforePending():Unit=runBlocking {
        FullFixture().use {source->
            val r=SqlAudioTranscriptRepository(source.gateway,source.key,source.media)
            try {
                val a=r.preview(AudioTranscriptSqlTest.audio(source));r.write(request(a,range=AudioSampleRange(0,1)))
                val doc=source.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
                try {
                    val id=FullBackupTest.uuid()
                    val tables=doc.tables.mapIndexed {n,rows->if(n==12)rows+listOf(listOf("FULL_BACKUP:$id",id,12L,"FULL_BACKUP_IMPORT_V13","DONE:"+"0".repeat(64)))else rows}
                    reject(raw(source,FullBackupData(doc.id,doc.created,tables,doc.images,doc.edits,doc.capture,doc.audios),13))
                    val valid=raw(source,doc,13)
                    FullFixture(99).use {target->
                        val pack=target.backup.open({valid.inputStream()},FullBackupTest.password()) as AuthenticatedFullBackup
                        try {
                            // Internal adversarial mutation after initial authentication must not create PENDING.
                            val portable=pack.data.tables[8].single()[10] as ByteArray
                            ByteBuffer.wrap(portable,portable.size-4,4).putInt(a.samples+1)
                            var intents=0;target.checkpoint={intents++}
                            assertThrows(Exception::class.java){runBlocking{target.backup.restore(pack){true}}}
                            assertEquals(0,intents);assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
                            assertTrue(target.db.read("SELECT * FROM entries").isEmpty())
                        }finally{pack.release()}
                    }
                }finally{doc.release()}
            }finally{r.wipeKey()}
        }
    }
    private suspend fun reject(bytes:ByteArray) {
        FullFixture(97).use {target->
            target.capture.current();val before=target.db.read("SELECT * FROM record_package_receipts")
            var checkpoints=0;target.checkpoint={checkpoints++}
            assertThrows(Exception::class.java){runBlocking{target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
            assertEquals(0,checkpoints);assertEquals(before,target.db.read("SELECT * FROM record_package_receipts"))
            assertTrue(target.db.read("SELECT * FROM entries").isEmpty());assertTrue(target.mediaRoot.walkTopDown().none{it.isFile})
        }
    }
    private fun request(a:AudioTranscriptSource,old:AudioTranscriptSnapshot?=null,range:AudioSampleRange?)=
        AudioTranscriptWrite(old?.id?:FullBackupTest.uuid(),a,old,"合成人工片段",false,FullBackupTest.uuid(),10+(old?.version?:0),range)

    /** Intentionally bypass semantic encoder, but use real AEAD/framing and actual media bytes. */
    private suspend fun raw(source:FullFixture,d:FullBackupData,version:Int,read:suspend (RecordPackageAttachment)->ByteArray={source.media.read(it.attachmentId)}):ByteArray {
        require(version in 11..13)
        val metadata=PackageWriter(FullBackupCodec.MAX_METADATA).use {w->
            fun text(s:String) {w.i32(s.length);val b=ByteArray(s.length*2);try{s.forEachIndexed{n,c->b[n*2]=(c.code ushr 8).toByte();b[n*2+1]=c.code.toByte()};w.raw(b)}finally{b.fill(0)}}
            w.string("AIyou-logical-full-v$version",32);w.i32(version);w.i32(1);w.string(d.id,36);w.i64(d.created);w.i32(d.tables.size)
            FullBackupTables.specs.forEachIndexed {n,spec->
                w.string(spec.name,64);w.string(spec.columns,512);w.i32(d.tables[n].size)
                d.tables[n].forEach {row->row.forEach {v->when(v) {
                    null->w.i32(0)
                    is Long->{w.i32(1);w.i64(v)}
                    is String->{w.i32(2);text(v)}
                    is ByteArray->{w.i32(3);w.i32(v.size);w.raw(v)}
                    else->error("Synthetic logical cell")
                }}}
            }
            for(media in listOf(d.images,d.audios)) {w.i32(media.size);media.forEach{m->w.string(m.sourceId,512);w.i32(m.sourceRevision);w.string(m.attachmentId,36);w.string(m.mediaType,32);w.i64(m.byteSize);w.raw(packageUnhex(m.sha256))}}
            w.i32(d.edits.size);d.edits.forEach{RecordEditDraftPackageCodec.write(w,it)};w.bytes()
        }
        val salt=ByteArray(16){it.toByte()};val prefix=ByteArray(8){(it+40).toByte()}
        val header=ByteBuffer.allocate(40).put("AIYOUF$version".toByteArray(Charsets.US_ASCII)).putInt(version).putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
        val password=FullBackupTest.password();val key=try{RecordPackageCrypto.derive(password,salt)}finally{password.fill('\u0000')}
        val out=ByteArrayOutputStream();out.write(header)
        fun frame(index:Int,binding:ByteArray,plain:ByteArray) {
            val n=ByteBuffer.allocate(4).putInt(index).array();val encrypted=RecordPackageCrypto.crypt(Cipher.ENCRYPT_MODE,key,prefix+n,header+binding+n,plain)
            try{out.write(ByteBuffer.allocate(4).putInt(encrypted.size).array());out.write(encrypted)}finally{encrypted.fill(0)}
        }
        return try {
            val binding=packageHash(metadata);frame(0,ByteArray(32),metadata)
            d.media.forEachIndexed {n,m->val b=read(m);try{frame(n+1,binding,b)}finally{b.fill(0)}}
            frame(d.media.size+1,binding,binding);out.toByteArray()
        }finally{key.fill(0);metadata.fill(0)}
    }
}
