package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.data.SourceFactInvariantTest.Companion.TEXT
import com.t1doo.aiyou.data.SourceFactInvariantTest.Companion.id
import com.t1doo.aiyou.data.SourceFactInvariantTest.Companion.request
import com.t1doo.aiyou.data.SourceFactInvariantTest.Companion.change
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data, actual SQL and cell/backup AEAD; no device or real user migration. */
class SourceFactRestoreBoundaryTest {
    @Test fun v14MixedOldProfilesAndRangedHistoryRekeyWithoutRestoringRemoteAuthority():Unit=runBlocking {
        FullFixture().use { f -> FullFixture(101).use { target ->
            val facts=SqlSourceFactRepository(f.gateway,f.key)
            val audio=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
            val images=SqlImageTextRepository(f.gateway,f.key,f.media)
            val preferences=SqlSourcePreferenceRepository(f.gateway,f.key)
            val events=SqlSourceMemoryRepository(f.gateway,f.key)
            val excerpts=SqlLocalCandidateRepository(f.gateway,f.key)
            try {
                val source=SourceVersion(f.entry(text=TEXT),1)
                f.db.write("UPDATE entries SET privacyPolicy='REMOTE_ALLOWED' WHERE id=?",source.sourceId)
                val accepted=facts.mutate(change(facts.create(request(facts,source)),FactAction.ACCEPT))
                val a=audio.preview(AudioTranscriptSqlTest.audio(f))
                val ranged=audio.write(AudioTranscriptWrite(id(),a,null,"合成人工片段",false,id(),10,AudioSampleRange(1,4)))
                val whole=audio.write(AudioTranscriptWrite(ranged.id,a,ranged,"合成整段当前版",false,id(),11,null))
                val image=images.preview(f.image(f.entry()))
                val imageText=images.write(ImageTextSqlTest.create(image,"合成文字，不是真实OCR"))!!
                val prefSource=SourceVersion(f.entry(text="我平时更喜欢淡茶。"),1)
                val p=preferences.preview(prefSource)
                preferences.create(PreferenceCreate(id(),p,id(),"合成普通偏好",PreferenceContent(SourceMemoryRules.reference(p,"我平时更喜欢淡茶")),true,id(),20))
                val e=events.preview(source)
                events.create(SourceMemoryCreate(id(),e,SourceMemoryContent(SourceMemoryRules.reference(e,"我的合成杯子是绿色🙂"),SourceMemoryKind.EVENT,SourceMemoryAssertion.SELF_REPORT,"合成人工分类"),id(),20))
                val ex=excerpts.begin(LocalCandidateStart(id(),source,20));excerpts.complete(ex,LocalExcerptRules.extract(ex))
                val bytes=AudioTranscriptSqlTest.export(f)
                assertEquals("AIYOUF14",String(bytes.copyOfRange(0,8),Charsets.US_ASCII))
                val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                try {
                    target.backup.restore(pack){true};assertTrue(target.backup.completed(pack))
                    val dest=SqlSourceFactRepository(target.gateway,target.key)
                    val destAudio=SqlAudioTranscriptRepository(target.gateway,target.key,target.media)
                    val destImages=SqlImageTextRepository(target.gateway,target.key,target.media)
                    try {
                        val actual=dest.facts(source).single()
                        assertEquals(accepted.contents,actual.contents);assertEquals(accepted.reviews,actual.reviews)
                        assertEquals(ProcessingPolicy.LOCAL_ONLY,actual.policy)
                        assertEquals(listOf(null,null),target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?",source.sourceId).single())
                        assertTrue(destAudio.read(a)!!.same(whole));assertNull(destAudio.read(a)!!.current.range)
                        assertEquals(AudioSampleRange(1,4),destAudio.read(a)!!.history.first().range)
                        assertTrue(destImages.read(image)!!.same(imageText))
                        assertArrayEquals(f.media.read(a.intent.id),target.media.read(a.intent.id))
                        val before=f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",accepted.id).single().single() as ByteArray
                        val after=target.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",accepted.id).single().single() as ByteArray
                        assertFalse(before.contentEquals(after))
                        val exported=target.backup.capture(id(),100) as FullBackupData
                        try { assertEquals(14,exported.version);assertEquals(setOf(1L,2L,3L,4L,5L,6L),exported.tables[8].map {it[5]}.toSet()) }
                        finally {exported.release()}
                    } finally {dest.wipeKey();destAudio.wipeKey();destImages.wipeKey()}
                } finally {pack.release()}
            } finally {facts.wipeKey();audio.wipeKey();images.wipeKey();preferences.wipeKey();events.wipeKey();excerpts.wipeKey()}
        } }
    }

    @Test fun pendingLostAckDoneAndReexportNeverResurrectAWithdrawnOrErasedFact():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=SqlSourceFactRepository(f.gateway,f.key)
            try {
                val source=SourceVersion(f.entry(text=TEXT),1)
                repo.mutate(change(repo.create(request(repo,source)),FactAction.ACCEPT))
                val bytes=AudioTranscriptSqlTest.export(f)
                for(point in listOf("full-intent","full-before-commit","full-committed"))FullFixture(102).use { target ->
                    target.capture.current()
                    val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                    try {
                        var failed=false;target.checkpoint={if(it==point&&!failed){failed=true;error("Synthetic v14 checkpoint")}}
                        try {assertThrows(Exception::class.java){runBlocking{target.backup.restore(pack){true}}}}
                        finally {target.checkpoint={}}
                        assertTrue(failed);assertEquals(point=="full-committed",target.backup.completed(pack))
                        val receipt=target.db.read("SELECT sourceRevision,operation,state FROM record_package_receipts WHERE operation='FULL_BACKUP_IMPORT_V14'").single()
                        assertEquals(listOf(14L,"FULL_BACKUP_IMPORT_V14"),receipt.take(2))
                        assertTrue((receipt[2] as String).startsWith(if(point=="full-committed")"DONE:"else "PENDING:"))
                        assertEquals(point=="full-committed",target.backup.restore(pack){true}.replayed)
                        val dest=SqlSourceFactRepository(target.gateway,target.key)
                        try {
                            val accepted=dest.facts(source).single();dest.mutate(change(accepted,FactAction.REVOKE))
                            assertTrue(target.backup.restore(pack){true}.replayed)
                            assertEquals(FactReview.REVOKED,dest.facts(source).single().review!!.review)
                        }finally{dest.wipeKey()}
                        // Delete the source and derived row through the existing permanent-erasure path.
                        val bin=SqlRecycleBinRepository(target.gateway,target.tags,SqlErasureFileHooks::fenced)
                        val deleted=bin.apply(RecycleMutation(source,bin.read(source.sourceId).generation,false,id()))
                        SqlPermanentErasureRepository(target.gateway,target.media,target.edits,target.packages).erase(PermanentErasureIntent(source,deleted.generation,id()))
                        assertTrue(target.backup.restore(pack){true}.replayed)
                        assertTrue(target.db.read("SELECT id FROM local_candidate_batches WHERE ruleVersion=6").isEmpty())
                        assertTrue(target.db.read("SELECT id FROM entries WHERE id=?",source.sourceId).isEmpty())
                        val next=AudioTranscriptSqlTest.export(target)
                        assertEquals("AIYOUF14",String(next.copyOfRange(0,8),Charsets.US_ASCII))
                        FullFixture(103).use { third ->
                            val p=third.backup.open({next.inputStream()},FullBackupTest.password())
                            try {third.backup.restore(p){true};assertTrue(third.db.read("SELECT id FROM local_candidate_batches WHERE ruleVersion=6").isEmpty())}
                            finally{p.release()}
                        }
                    }finally{pack.release()}
                }
            }finally{repo.wipeKey()}
        }
    }

    @Test fun authenticatedV11V12V13ContainersCannotSmuggleProfileSixBeforeAnyIntent():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=SqlSourceFactRepository(f.gateway,f.key)
            try {
                repo.create(request(repo,SourceVersion(f.entry(text=TEXT),1)))
                val doc=f.backup.capture(id(),100) as FullBackupData
                try {
                    val metadata=FullBackupCodec.encode(doc)
                    for(version in 11..13) {
                        val modified=metadata.copyOf();val name="AIyou-logical-full-v14".toByteArray(Charsets.US_ASCII)
                        assertArrayEquals(name,modified.copyOfRange(4,4+name.size))
                        modified[4+name.size-1]=('0'.code+version%10).toByte()
                        ByteBuffer.wrap(modified,4+name.size,4).putInt(version)
                        val bytes=sealSyntheticMetadata(modified,version)
                        FullFixture(104).use { target ->
                            target.capture.current();val before=target.db.read("SELECT * FROM record_package_receipts")
                            var intents=0;target.checkpoint={intents++}
                            assertThrows(RecordPackageInvalid::class.java){runBlocking{target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
                            assertEquals(0,intents);assertEquals(before,target.db.read("SELECT * FROM record_package_receipts"))
                            assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
                        }
                    }
                }finally{doc.release()}
            }finally{repo.wipeKey()}
        }
    }

    @Test fun allReadyReviewsShare128SlotsAndAuthenticated129IsRejectedBeforeRestoreIntent():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=SqlSourceFactRepository(f.gateway,f.key)
            try {
                val sources=List(7){SourceVersion(f.entry(text=TEXT),1)}
                val made=List(128){n->repo.create(request(repo,sources[n/20]))}
                repo.mutate(change(made[0],FactAction.REJECT))
                repo.mutate(change(repo.mutate(change(made[1],FactAction.ACCEPT)),FactAction.REVOKE))
                assertThrows(FactLimit::class.java){repo.create(request(repo,sources.last()))}
                val bytes=AudioTranscriptSqlTest.export(f)
                FullFixture(105).use { target ->
                    val p=target.backup.open({bytes.inputStream()},FullBackupTest.password())
                    try {
                        target.backup.restore(p){true}
                        assertEquals(128L,target.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=6 AND state='READY'").single().single())
                    }finally{p.release()}
                }
                val doc=f.backup.capture(id(),100) as FullBackupData
                try {
                    val extra=doc.tables[8].single{it[0]==made.last().id}.toMutableList().also {
                        it[0]=id();it[10]=(it[10] as ByteArray).copyOf()
                    }
                    val tables=doc.tables.mapIndexed {n,rows->if(n==8)rows+listOf(extra)else rows}
                    val forged=FullBackupData(doc.id,doc.created,tables,doc.images,doc.edits,doc.capture,doc.audios)
                    val prepared=RecordMediaPackageCipher().sealFull(forged,FullBackupTest.password(),{error("No media")},{true})
                    val over=try{ByteArrayOutputStream().also{prepared.writeTo(it){true}}.toByteArray()}finally{prepared.release();(extra[10] as ByteArray).fill(0)}
                    FullFixture(106).use { target ->
                        var intents=0;target.checkpoint={intents++}
                        assertThrows(FactLimit::class.java){runBlocking{target.backup.open({over.inputStream()},FullBackupTest.password())}}
                        assertEquals(0,intents);assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
                        assertTrue(target.db.read("SELECT * FROM entries").isEmpty())
                    }
                }finally{doc.release()}
            }finally{repo.wipeKey()}
        }
    }

    /** Deliberately wrong semantic version; authentic framing, fixed synthetic salt only. */
    private fun sealSyntheticMetadata(metadata:ByteArray,version:Int):ByteArray {
        val salt=ByteArray(16){it.toByte()};val prefix=ByteArray(8){(it+40).toByte()}
        val header=ByteBuffer.allocate(40).put("AIYOUF$version".toByteArray(Charsets.US_ASCII)).putInt(version).putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
        val password=FullBackupTest.password();val key=try{RecordPackageCrypto.derive(password,salt)}finally{password.fill('\u0000')}
        val out=ByteArrayOutputStream();out.write(header)
        fun frame(index:Int,binding:ByteArray,plain:ByteArray) {
            val n=ByteBuffer.allocate(4).putInt(index).array();val ciphertext=RecordPackageCrypto.crypt(Cipher.ENCRYPT_MODE,key,prefix+n,header+binding+n,plain)
            try{out.write(ByteBuffer.allocate(4).putInt(ciphertext.size).array());out.write(ciphertext)}finally{ciphertext.fill(0)}
        }
        return try {val binding=packageHash(metadata);frame(0,ByteArray(32),metadata);frame(1,binding,binding);out.toByteArray()}
        finally{key.fill(0);metadata.fill(0)}
    }
}
