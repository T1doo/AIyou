package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production SQL, ciphers and synthetic encrypted originals; no inference or device claim. */
class UnifiedKeywordSqlTest {
    @Test fun fourTypesGroupExactOriginalWithoutCollapsingEqualTextOrDistinctAttachments():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="我偏好淡茶🙂");h.audio(id);h.audio(id);h.image(id);h.image(id);h.preference(id)
        val other=h.f.entry(text="我偏好淡茶🙂");h.reads=0
        val p=h.repo.search("淡茶",UnifiedKeywordScope.ALL)
        assertEquals(listOf(id,other).sorted(),p.groups.map{it.binding.source.sourceId})
        assertEquals(6,p.groups.single{it.binding.source.sourceId==id}.items.size)
        assertEquals(7,p.groups.flatMap{it.items}.map{it.key}.distinct().size)
        assertEquals(4,h.reads) // each clip/image authenticated once, never once per search page
        val originalBytes=h.f.db.read("SELECT * FROM entry_revisions")
        p.groups.flatMap{it.items}.forEach{e->var admitted=false;assertTrue(h.repo.resolve(p,e){admitted=true});assertTrue(admitted)}
        assertEquals(originalBytes,h.f.db.read("SELECT * FROM entry_revisions"))
        val foreign=SqlUnifiedKeywordRepository(h.f.gateway,h.originals,h.audios,h.images,h.preferences,h.memories)
        assertFalse(foreign.resolve(p){fail("A foreign owner must not accept this proof")})
    }}
    @Test fun fullMatchedSetIncludesOlderPagesWithBoundedSingleMediaPass():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val old=h.f.entry();h.audio(old,"合成很早的淡茶")
        repeat(22){h.audio(h.f.entry(),"合成淡茶 $it")}
        h.reads=0;val p=h.repo.search("淡茶",UnifiedKeywordScope.AUDIO)
        assertEquals(23,p.groups.size);assertEquals(23,h.reads)
        assertTrue(p.groups.any{it.binding.source.sourceId==old})
        assertEquals(23,p.groups.map{it.key}.distinct().size)
        assertEquals(p.groups.map{it.key},h.repo.search("淡茶",UnifiedKeywordScope.AUDIO).groups.map{it.key})
    }}
    @Test fun emptyAcceptedSetBecomingNonemptyCannotValidateAsAnEmptyResult():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="我偏好淡茶🙂");val empty=h.repo.search("淡茶",UnifiedKeywordScope.PREFERENCE)
        assertTrue(empty.groups.isEmpty());h.preference(id)
        assertFalse(h.repo.resolve(empty){fail("Empty proof changed membership")})
        assertEquals(1,h.repo.search("淡茶",UnifiedKeywordScope.PREFERENCE).groups.size)
    }}
    @Test fun emptyOriginalAndImageSetsRejectNewMembershipRatherThanReusingVerifiedEmpty():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val originals=h.repo.search("淡茶",UnifiedKeywordScope.ORIGINAL)
        assertTrue(originals.groups.isEmpty());val id=h.f.entry(text="合成淡茶")
        assertFalse(h.repo.resolve(originals){fail("New original membership")})
        val images=h.repo.search("淡茶",UnifiedKeywordScope.IMAGE)
        assertTrue(images.groups.isEmpty());h.image(id)
        assertFalse(h.repo.resolve(images){fail("New image membership")})
    }}
    @Test fun offPageWithdrawalAndCorrectedReviewInvalidateWholeProofEvenForAnotherItem():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val first=h.audio(h.f.entry(),"合成淡茶");repeat(21){h.audio(h.f.entry(),"合成淡茶")}
        val p=h.repo.search("淡茶",UnifiedKeywordScope.AUDIO)
        val other=p.groups.flatMap{it.items}.filterIsInstance<UnifiedKeywordEvidence.Audio>().first{it.value.id!=first.id}
        h.audios.write(AudioTranscriptSqlTest.write(first.anchor,first,withdraw=true))
        assertFalse(h.repo.resolve(p,other){fail("A different visible item cannot mask withdrawal")})
        val id=h.f.entry(text="我偏好淡茶🙂");val accepted=h.preference(id);val prefs=h.repo.search("淡茶",UnifiedKeywordScope.PREFERENCE)
        h.preferences.mutate(PreferenceMutation(accepted,PreferenceAction.CORRECT,accepted.current!!.content.copy(interval=PreferenceInterval("2026-10-22",null,"UTC")),null,FullBackupTest.uuid(),30,true))
        assertFalse(h.repo.resolve(prefs){fail("Corrected content is not still accepted")})
        assertTrue(h.repo.search("淡茶",UnifiedKeywordScope.PREFERENCE).groups.isEmpty())
    }}
    @Test fun originalChineseCandidatesAreVerifiedAgainstTheLiteralUnicodeBaseline():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val values=listOf("晚餐后散步，淡茶🙂","咖啡 AB12，𠀀","AB 12与咖 啡","合成 Coffee","合成 coffee","淡 茶","合成同文","合成同文")
        val ids=values.map{h.f.entry(text=it)}
        while(h.originals.batch().remaining){}
        for(q in listOf("茶","晚餐","咖啡","咖 啡","AB12","AB 12","🙂","𠀀","Coffee","coffee","，","同文")){
            val expected=ids.zip(values).filter{it.second.contains(q)}.map{it.first}.sorted()
            val found=h.repo.search(q,UnifiedKeywordScope.ORIGINAL)
            assertEquals("Literal Unicode match",expected,found.groups.map{it.binding.source.sourceId}.sorted())
        }
    }}
    @Test fun sourceChangeAfterActualMediaReadAndMalformedNonmatchingCipherRejectSelectedWholeSet():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");h.audio(id);h.image(h.f.entry(),"合成不匹配的图片")
        h.afterRead={h.afterRead=null;h.f.revise(id,"合成新原文")}
        assertThrows(Exception::class.java){runBlocking{h.repo.search("淡茶",UnifiedKeywordScope.ALL)}}
        h.afterRead=null
        val imageRow=h.f.db.read("SELECT id,payload FROM local_candidate_batches WHERE ruleVersion=5").single()
        val bytes=imageRow[1] as ByteArray;bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        h.f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",bytes,imageRow[0])
        assertThrows(Exception::class.java){runBlocking{h.repo.search("未命中",UnifiedKeywordScope.ALL)}}
        assertTrue(h.repo.search("合成",UnifiedKeywordScope.ORIGINAL).groups.isNotEmpty())
    }}
    @Test fun actualEncryptedOriginalCorruptionRejectsProofAndDoesNotSubstituteOriginalBytes():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val a=h.audio(h.f.entry(text="合成淡茶"));val p=h.repo.search("淡茶",UnifiedKeywordScope.ALL)
        val file=h.f.mediaRoot.resolve(a.anchor.intent.id+".aead");assertTrue(file.isFile)
        val bad=file.readBytes();bad[bad.lastIndex]=(bad.last().toInt() xor 1).toByte();file.writeBytes(bad)
        assertThrows(Exception::class.java){runBlocking{h.repo.resolve(p){fail("Corrupt media cannot publish")}}}
        assertArrayEquals(bad,file.readBytes())
    }}
    @Test fun finalJointSqlCommitFailureCannotReturnAUsableSnapshot():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        h.audio(h.f.entry(text="合成淡茶"));var finalTransaction=false;var failed=false
        h.f.db.beforeSql={if(it.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis"))finalTransaction=true}
        h.f.db.beforeCommit={if(finalTransaction){failed=true;error("Synthetic final joint commit failure")}}
        try{assertThrows(Exception::class.java){runBlocking{h.repo.search("淡茶",UnifiedKeywordScope.ALL)}}}
        finally{h.f.db.beforeSql=null;h.f.db.beforeCommit=null}
        assertTrue(failed);assertEquals(1,h.repo.search("淡茶",UnifiedKeywordScope.ALL).groups.size)
    }}
}

internal class UnifiedKeywordFixture(seed:Int=1):AutoCloseable {
    val f=FullFixture(seed);var reads=0;var afterRead:(()->Unit)?=null
    private val media=object:AttachmentStore by f.media{override suspend fun read(id:String)=f.media.read(id).also{reads++;afterRead?.invoke()}}
    val originals=SqlLocalSearchRepository(f.gateway,f.tags)
    val audios=SqlAudioTranscriptRepository(f.gateway,f.key,media)
    val images=SqlImageTextRepository(f.gateway,f.key,media)
    val preferences=SqlSourcePreferenceRepository(f.gateway,f.key)
    val memories=SqlSourceMemoryRepository(f.gateway,f.key)
    val repo=SqlUnifiedKeywordRepository(f.gateway,originals,audios,images,preferences,memories)
    suspend fun audio(id:String,text:String="合成淡茶"):AudioTranscriptSnapshot {
        val bytes=CaptureAudioSqlTest.wave();val i=AttachmentIntent(FullBackupTest.uuid(),id,1,"audio/wav",FullBackupTest.hash(bytes),bytes.size.toLong())
        f.media.save(i){bytes.inputStream()};return audios.write(AudioTranscriptSqlTest.write(audios.preview(i),text=text))
    }
    suspend fun image(id:String,text:String="合成淡茶"):ImageTextSnapshot=images.write(ImageTextSqlTest.create(images.preview(f.image(id)),text))!!
    fun preference(id:String):SourcePreferenceSnapshot {
        val preview=preferences.preview(SourceVersion(id,1));val row=preferences.create(PreferenceCreate(FullBackupTest.uuid(),preview,FullBackupTest.uuid(),"合成口味",PreferenceContent(SourceMemoryRules.reference(preview,"我偏好淡茶🙂")),true,FullBackupTest.uuid(),10))
        return preferences.mutate(PreferenceMutation(row,PreferenceAction.ACCEPT,null,null,FullBackupTest.uuid(),20))
    }
    override fun close(){audios.wipeKey();images.wipeKey();preferences.wipeKey();memories.wipeKey();f.close()}
}
