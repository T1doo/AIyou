package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** Production SQL/FTS/proof/matcher on bounded host SQLite; no Android or image decryption. */
class CurrentImageSearchSqlTest {
    private fun image(f: RecordEventDateFixture, id: String, type: String = "image/png",
        revision: Int = 1, state: String = "READY") = UUID.randomUUID().toString().also {
        f.db.write("INSERT INTO attachments VALUES(?,?,?,?,?,9,?)",it,id,revision,type,"a".repeat(64),state)
    }
    private fun request() = LocalSearchRequest("",onlyImages=true)
    private fun ids(f: RecordEventDateFixture) = f.search.candidates(request()).entries.map { it.id }.toSet()

    @Test fun defaultIncludesTextAndImageFilterRequiresCurrentPngOrJpegNotOnlyHistory(): Unit = RecordEventDateFixture().use { f ->
        val png=f.entry(text="");image(f,png)
        val jpeg=f.entry();image(f,jpeg,"image/jpeg")
        val text=f.entry();val history=f.entry();image(f,history);f.append(history)
        val audio=f.entry();image(f,audio,"audio/mp4")
        f.drain()
        assertFalse(LocalSearchRequest("").onlyImages)
        assertNull(f.search.candidates(LocalSearchRequest("")).currentImageEntryIds)
        assertEquals(setOf(png,jpeg,text,history,audio),f.search.candidates(LocalSearchRequest("")).entries.map{it.id}.toSet())
        val proof=f.search.candidates(request());assertEquals(setOf(png,jpeg),proof.currentImageEntryIds)
        assertEquals(setOf(png,jpeg),proof.entries.map{it.id}.toSet())
        assertTrue(f.search.publishCurrent(proof){})
        assertTrue(f.search.resolve(proof,png){assertEquals("",it.originalText)})
    }

    @Test fun keywordExactCapturedOrEventDateTagFavoriteAndImageConditionsAllCombine(): Unit = runBlocking {
        RecordEventDateFixture().use { f ->
            val id=f.entry(text="合成 needle cafe");image(f,id)
            val plain=f.entry(text="合成 needle cafe")
            for(source in listOf(id,plain)) {
                f.tags.replace(ManualTagMutation(source,0,listOf("合成标签"),UUID.randomUUID().toString()))
                f.favorites.set(f.favorite(source,true));f.dates.set(f.intent(source,RecordEventDate("2000-01-01","UTC")))
            }
            f.drain()
            suspend fun search(input:OriginalSearchInput)=LocalOriginalSearchMatcher.indexed(
                f.search.candidates(LocalSearchRequest("")).entries,input,f.search)
            val input=OriginalSearchInput("needle",from="1970-01-01",through="1970-01-01",tag="合成标签",
                tagSnapshot=f.tags.snapshots(),onlyFavorites=true,favoriteSnapshot=f.favorites.snapshots(),onlyImages=true)
            assertEquals(listOf(id),search(input).hits.map{it.entry.id})
            assertEquals(listOf(id),search(input.copy(query="needle cafe",mode=OriginalSearchMode.EXACT)).hits.map{it.entry.id})
            assertTrue(search(input.copy(query="missing")).hits.isEmpty())
            assertTrue(search(input.copy(tag="其他标签")).hits.isEmpty())
            assertTrue(search(input.copy(from="1970-01-02",through="1970-01-02")).hits.isEmpty())
            val event=input.copy(dateBasis=RecordDateBasis.EVENT,from="2000-01-01",through="2000-01-01",eventDateSnapshot=f.dates.snapshots())
            assertEquals(listOf(id),search(event).hits.map{it.entry.id})
            assertTrue(search(event.copy(from="1970-01-01",through="1970-01-01")).hits.isEmpty())
            f.favorites.set(f.favorite(id,false))
            assertTrue(search(input.copy(favoriteSnapshot=f.favorites.snapshots())).hits.isEmpty())
            val cleared=search(OriginalSearchInput());assertEquals(OriginalSearchStage.BROWSE,cleared.stage)
            val justImages=search(OriginalSearchInput(onlyImages=true));assertEquals(OriginalSearchStage.READY,justImages.stage)
            assertEquals(listOf(id),justImages.hits.map{it.entry.id})
        }
    }

    @Test fun attachmentIdentityStateHashSizeAndRemovalInvalidateWithoutTextEpochChange(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val pic=image(f,id);f.drain()
        for(change in listOf("state='CORRUPT'","sha256='"+"b".repeat(64)+"'","byteSize=10","mediaType='image/jpeg'")) {
            val proof=f.search.candidates(request());val epoch=f.search.epoch()
            f.db.write("UPDATE attachments SET "+change+" WHERE id=?",pic)
            assertEquals(epoch,f.search.epoch())
            assertFalse(f.search.publishCurrent(proof){fail("Old image association published")})
            assertFalse(f.search.resolve(proof,id){fail("Old image association opened")})
        }
        val before=f.search.candidates(request());f.db.write("DELETE FROM attachments WHERE id=?",pic)
        assertTrue(ids(f).isEmpty());assertFalse(f.search.publishCurrent(before){})
    }

    @Test fun newlyQualifyingImageRejectsEvenEmptyProofAndHistoricalChangesDoNotChangeMembership(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val historic=image(f,id);f.append(id);f.drain()
        val empty=f.search.candidates(request());assertTrue(empty.entries.isEmpty())
        f.db.write("UPDATE attachments SET state='FAILED' WHERE id=?",historic)
        assertTrue(f.search.publishCurrent(empty){})
        val epoch=f.search.epoch();image(f,id,revision=2)
        assertEquals(epoch,f.search.epoch());assertFalse(f.search.publishCurrent(empty){fail("Stale empty result")})
        val current=f.search.candidates(request());image(f,id,"image/jpeg",2)
        assertFalse(f.search.resolve(current,id){fail("Added image reused old proof")})
        assertEquals(setOf(id),ids(f))
    }

    @Test fun editRecycleRestoreAndPermanentSourceDeletionRejectOldImageResults(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();image(f,id);f.drain();val first=f.search.candidates(request())
        f.append(id);assertFalse(f.search.publishCurrent(first){});assertTrue(ids(f).isEmpty())
        image(f,id,revision=2);val current=f.search.candidates(request())
        val bin=SqlRecycleBinRepository(f.gateway,f.tags);val row=bin.read(id)
        val trash=bin.apply(RecycleMutation(SourceVersion(id,2),row.generation,false,UUID.randomUUID().toString()))
        assertTrue(ids(f).isEmpty());assertFalse(f.search.resolve(current,id){})
        bin.apply(RecycleMutation(SourceVersion(id,2),trash.generation,true,UUID.randomUUID().toString()))
        assertEquals(setOf(id),ids(f));assertFalse(f.search.publishCurrent(current){})
        val restored=f.search.candidates(request())
        f.db.transaction { sql ->
            for(table in listOf("attachments","local_jobs","remote_source_access","entry_revisions","entries"))
                sql.execute("DELETE FROM "+table+" WHERE "+if(table=="entries")"id=?" else "entryId=?",listOf(id))
        }
        assertTrue(ids(f).isEmpty());assertFalse(f.search.resolve(restored,id){})
    }

    @Test fun failedPictureRemainsAssociatedWhileUnreadableLedgerAndUnsupportedOwnerFailExplicitly(): Unit = runBlocking {
        RecordEventDateFixture().use { f ->
            val id=f.entry();val pic=image(f,id,state="FAILED");f.drain()
            val all=f.search.candidates(LocalSearchRequest("")).entries
            val input=OriginalSearchInput(onlyImages=true)
            assertEquals(listOf(id),LocalOriginalSearchMatcher.indexed(all,input,f.search).hits.map{it.entry.id})
            val fallback=LocalOriginalSearchMatcher.match(all,input)
            assertEquals(OriginalSearchStage.FAILED,fallback.stage);assertTrue(fallback.hits.isEmpty())
            assertTrue(fallback.message!!.contains("重试本机读取"))
            f.db.write("UPDATE attachments SET sha256='bad' WHERE id=?",pic)
            val failed=LocalOriginalSearchMatcher.indexed(all,input,f.search)
            assertEquals(OriginalSearchStage.FAILED,failed.stage);assertTrue(failed.hits.isEmpty())
            assertTrue(failed.message!!.contains("原文已保留"))
            f.db.write("UPDATE attachments SET sha256=? WHERE id=?","a".repeat(64),pic)
            val old=f.search.candidates(request());var callback=false
            f.db.beforeSql={if(it.startsWith("SELECT id,revision,mediaType"))error("Synthetic image ledger read failure")}
            assertThrows(LocalSearchFailure::class.java){f.search.publishCurrent(old){callback=true}}
            assertFalse(callback);f.db.beforeSql=null
        }
    }

    @Test fun finalAttachmentRecheckAndActualPublicationShareWriteLease(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val pic=image(f,id);f.drain();val proof=f.search.candidates(request())
        f.second().use { second ->
            val pool=Executors.newSingleThreadExecutor();val entered=CountDownLatch(1);var writer:Future<*>?=null
            try {
                second.beforeSql={if(it=="BEGIN IMMEDIATE")entered.countDown()}
                assertTrue(f.search.publishCurrent(proof){
                    writer=pool.submit{second.write("DELETE FROM attachments WHERE id=?",pic)}
                    assertTrue(entered.await(2,TimeUnit.SECONDS))
                    assertThrows(TimeoutException::class.java){writer!!.get(100,TimeUnit.MILLISECONDS)}
                })
                writer!!.get(2,TimeUnit.SECONDS)
                assertFalse(f.search.resolve(proof,id){fail("Post-publication removal reused proof")})
            } finally {second.beforeSql=null;pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS))}
        }
    }
}
