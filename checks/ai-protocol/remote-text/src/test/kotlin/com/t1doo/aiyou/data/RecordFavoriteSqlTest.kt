package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordFavoriteSqlTest {
    @Test fun explicitChoiceSurvivesColdOwnerAndPreservesOriginalTagsAndBodyIndex(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); val original=f.current(id)
        f.base.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),PermanentErasureFixture.uuid()))
        f.readyIndex(); val index=f.indexRows(); val tag=f.base.tags.read(id); val epoch=f.epoch()
        val saved=f.favorites.set(f.intent(id,true))
        assertTrue(saved.isFavorite); assertEquals(1L,saved.version); assertEquals(original,f.current(id))
        assertEquals(tag.version,f.base.tags.read(id).version); assertEquals(tag.names,f.base.tags.read(id).names)
        assertEquals(index,f.indexRows()); assertEquals(epoch+1,f.epoch())
        f.base.reopen(); assertTrue(f.favorites.read(id).isFavorite); assertEquals(1L,f.favorites.read(id).version)
        assertEquals(original,f.current(id)); assertEquals(index,f.indexRows())
    }

    @Test fun lostAcknowledgementRetriesOneExplicitIntentWithoutSecondWrite(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); val intent=f.intent(id,true); var lose=true
        val fault=object: RecordsSqlDatabase {
            override fun <T> transaction(block:(RecordsSqlConnection)->T):T {
                val value=f.base.gateway.transaction(block)
                if(lose){lose=false;throw IllegalStateException("Synthetic lost acknowledgement")}
                return value
            }
        }
        val port=SqlRecordFavoriteRepository(fault)
        assertThrows(RecordFavoriteStorageFailure::class.java){port.set(intent)}
        val committed=f.favoriteColumns(id); val epoch=f.epoch()
        assertTrue(port.set(intent).isFavorite); assertEquals(committed,f.favoriteColumns(id)); assertEquals(epoch,f.epoch())
        assertThrows(RecordFavoriteConflict::class.java){port.set(RecordFavoriteMutation(id,1,intent.sourceHash,0,false,intent.mutationId,intent.sourceGeneration))}
        assertEquals(committed,f.favoriteColumns(id))
    }

    @Test fun knownReceiptSurvivesTextEditButStaleSourceAndLaterChoiceNeverOverwrite(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); val first=f.intent(id,true); f.favorites.set(first); f.revise(id)
        assertTrue(f.favorites.set(first).isFavorite); assertEquals(1L,f.favorites.read(id).version)
        assertThrows(RecordFavoriteConflict::class.java){f.favorites.set(RecordFavoriteMutation(id,1,first.sourceHash,1,false,PermanentErasureFixture.uuid(),first.sourceGeneration))}
        val second=f.intent(id,false); f.favorites.set(second)
        assertThrows(RecordFavoriteConflict::class.java){f.favorites.set(first)}
        assertThrows(RecordFavoriteConflict::class.java){f.favorites.set(RecordFavoriteMutation(id,2,"0".repeat(64),1,false,second.mutationId,second.sourceGeneration))}
        assertFalse(f.favorites.read(id).isFavorite); assertEquals(2L,f.favorites.read(id).version)
    }

    @Test fun actualSqlAbortRollsBackChoiceReceiptAndEpoch(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); f.readyIndex(); val original=f.current(id); val index=f.indexRows(); val epoch=f.epoch()
        f.db.write("CREATE TRIGGER synthetic_favorite_abort BEFORE UPDATE OF favoriteVersion ON entries BEGIN SELECT RAISE(ABORT,'synthetic favorite abort'); END")
        assertThrows(RecordFavoriteStorageFailure::class.java){f.favorites.set(f.intent(id,true))}
        assertEquals(listOf(listOf(0L,0L,null,0L)),f.favoriteColumns(id)); assertEquals(epoch,f.epoch())
        assertEquals(original,f.current(id)); assertEquals(index,f.indexRows())
    }

    @Test(timeout=12_000) fun independentSqliteOwnersHaveOneCasWinner(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); val one=f.intent(id,true); val two=f.intent(id,true)
        f.base.second().use { other ->
            val pool=Executors.newFixedThreadPool(2); val go=CountDownLatch(1)
            try {
                val ports=listOf(f.favorites,SqlRecordFavoriteRepository(other)); val intents=listOf(one,two)
                val runs=ports.indices.map { i -> pool.submit<Boolean>{go.await();try{ports[i].set(intents[i]);true}catch(_:RecordFavoriteConflict){false}} }
                go.countDown(); assertEquals(1,runs.count { it.get(5,TimeUnit.SECONDS) })
                assertEquals(1L,f.favorites.read(id).version); assertTrue(f.favorites.read(id).isFavorite)
            } finally {go.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))}
        }
    }

    @Test fun trashRestoreAndPermanentEraseKeepThenRemoveAllFavoriteMetadata(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); val other=f.entry(); f.favorites.set(f.intent(id,true)); val stored=f.favoriteColumns(id)
        var bin=f.base.bin.read(id)
        f.base.bin.apply(RecycleMutation(SourceVersion(id,1),bin.generation,false,PermanentErasureFixture.uuid()))
        assertThrows(RecordFavoriteUnavailable::class.java){f.favorites.read(id)}
        assertFalse(f.favorites.snapshots().containsKey(id)); assertEquals(stored,f.favoriteColumns(id))
        bin=f.base.bin.read(id); f.base.bin.apply(RecycleMutation(SourceVersion(id,1),bin.generation,true,PermanentErasureFixture.uuid()))
        assertTrue(f.favorites.read(id).isFavorite); assertEquals(stored,f.favoriteColumns(id))
        runBlocking { f.base.erase.erase(f.base.intent(id)) }
        assertTrue(f.favoriteColumns(id).isEmpty()); assertEquals("DONE",f.db.read("SELECT phase FROM permanent_erasure_receipts WHERE entryId=?",id).single().single())
        assertFalse(f.favorites.snapshots().containsKey(id)); assertFalse(f.favorites.read(other).isFavorite)
    }

    @Test fun authenticatedPackageNewCopiesStartUnfavoritedWithoutChangingExistingChoice(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); f.favorites.set(f.intent(id,true)); val before=f.favoriteColumns(id)
        val newId=PermanentErasureFixture.uuid()
        f.base.packages.restore(RecordPackageCipherTest.verified(),listOf(newId),100)
        assertFalse(f.favorites.read(newId).isFavorite); assertEquals(0L,f.favorites.read(newId).version)
        assertTrue(f.favorites.read(id).isFavorite); assertEquals(before,f.favoriteColumns(id))
    }

    @Test(timeout=30_000) fun favoriteConditionPrecedesBothBudgetsAndCombinesDateAndAuthenticatedTag(): Unit = RecordFavoriteFixture().use { f ->
        val text="合成 needle 大范围"; val hash=OriginalExports.hash(text)
        f.db.transaction { sql ->
            val seq="WITH RECURSIVE seq(n) AS (VALUES(0) UNION ALL SELECT n+1 FROM seq WHERE n<10000) "
            sql.execute(seq+"INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) SELECT 'bulk-'||printf('%05d',n),1,5,'UTC','LOCAL_ONLY' FROM seq")
            sql.execute(seq+"INSERT INTO entry_revisions SELECT 'bulk-'||printf('%05d',n),1,?,?,5 FROM seq",listOf(text,hash))
        }
        val id=f.entry("zz-favorite"); f.base.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),PermanentErasureFixture.uuid()));f.favorites.set(f.intent(id,true))
        val result=f.search.candidates(LocalSearchRequest("needle",from="1970-01-01",through="1970-01-01",tag="合成标签",onlyFavorites=true))
        assertEquals(listOf(id),result.entries.map{it.id}); assertTrue(result.favorites!!.getValue(id).isFavorite)
        assertEquals(setOf(id),result.favorites!!.keys)
        assertTrue(f.search.candidates(LocalSearchRequest("needle",from="1970-01-02",onlyFavorites=true)).entries.isEmpty())
        assertTrue(f.search.candidates(LocalSearchRequest("needle",tag="其他标签",onlyFavorites=true)).entries.isEmpty())
        assertThrows(LocalSearchTooBroad::class.java){f.search.candidates(LocalSearchRequest(""))}
    }

    @Test fun choiceInvalidatesReadyResolveAndAbaWithoutDeletingActualBodyIndex(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); f.readyIndex(); val index=f.indexRows(); val search=f.search
        val all=search.candidates(LocalSearchRequest("needle")); f.favorites.set(f.intent(id,true))
        assertFalse(search.publishCurrent(all){fail("Old proof published")}); assertFalse(search.resolve(all,id){fail("Old proof opened")})
        assertEquals(index,f.indexRows())
        val selected=search.candidates(LocalSearchRequest("",onlyFavorites=true)); assertTrue(search.publishCurrent(selected){})
        f.favorites.set(f.intent(id,false)); f.favorites.set(f.intent(id,true))
        assertFalse(search.resolve(selected,id){fail("ABA proof opened")}); assertEquals(index,f.indexRows())
    }

    @Test fun malformedMetadataAndRetiredOwnerCannotBecomeAnEmptyFavoriteList(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry(); f.favorites.set(f.intent(id,true))
        val rejected=assertThrows(SqliteBridgeFailure::class.java){f.db.write("UPDATE entries SET favoriteVersion=0 WHERE id=?",id)}
        assertEquals("SQLITE_CONSTRAINT_TRIGGER",rejected.category)
        f.db.transaction { sql ->
            for(name in listOf("favorite_values_insert","favorite_values_update","favorite_version_guard"))sql.execute("DROP TRIGGER "+name)
            sql.execute("UPDATE entries SET isFavorite=0,lastFavoriteMutationId=' ' WHERE id=?",listOf(id))
        }
        assertThrows(RecordFavoriteStorageFailure::class.java){f.favorites.snapshots()}
        assertThrows(LocalSearchFailure::class.java){f.search.candidates(LocalSearchRequest("",onlyFavorites=true))}
        val port=f.favorites;f.base.gateway.seal(); assertThrows(RecordFavoriteStorageFailure::class.java){port.read(id)}
    }

    @Test fun additiveMigrationRollbackKeepsPriorRowsAndColdDefaults(): Unit = RecordFavoriteFixture(false).use { f ->
        val id=f.entry(); val before=f.db.read("SELECT * FROM entries"); val revisions=f.db.read("SELECT * FROM entry_revisions");val columns=f.db.read("PRAGMA table_info(entries)")
        f.db.beforeSql={sql->if(sql.contains("ADD COLUMN favoriteVersion"))throw IllegalStateException("Synthetic migration abort")}
        assertThrows(IllegalStateException::class.java){f.upgrade()};f.db.beforeSql=null
        assertEquals(columns,f.db.read("PRAGMA table_info(entries)"));assertEquals(before,f.db.read("SELECT * FROM entries"));assertEquals(revisions,f.db.read("SELECT * FROM entry_revisions"))
        f.upgrade(); assertEquals(before,f.db.read("SELECT id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy FROM entries"));assertEquals(revisions,f.db.read("SELECT * FROM entry_revisions"))
        f.base.reopen(); assertFalse(f.favorites.read(id).isFavorite);assertEquals(0L,f.favorites.read(id).version)
    }

    @Test fun metadataBudgetFailsClosedInsteadOfReturningAnEmptyOrPartialMap(): Unit = RecordFavoriteFixture().use { f ->
        val one=f.entry();val two=f.entry();f.favorites.set(f.intent(two,true))
        val limited=SqlRecordFavoriteRepository(f.base.gateway,2)
        assertEquals(setOf(one,two),limited.snapshots().keys)
        f.entry()
        assertThrows(RecordFavoriteTooBroad::class.java){limited.snapshots()}
        assertThrows(RecordFavoriteTooBroad::class.java){f.base.gateway.transaction{limited.validateWithin(it)}}
        assertTrue(limited.read(two).isFavorite)
    }

    @Test fun metadataValidationUsesBoundedKeysetPagesWithoutSkippingBoundaryRows(): Unit = RecordFavoriteFixture().use { f ->
        repeat(257){n->f.entry("page-"+n.toString().padStart(4,'0'))}
        f.favorites.set(f.intent("page-0256",true))
        val limits=mutableListOf<Long>()
        f.db.afterSql={sql->if(sql.contains("e.favoriteVersion")&&sql.contains("ORDER BY e.id LIMIT ?")){
            limits+=1L
        }}
        val snapshot=f.favorites.snapshots();f.db.afterSql=null
        assertEquals(257,snapshot.size);assertTrue(snapshot.getValue("page-0256").isFavorite)
        assertEquals(2,limits.size)
    }

    @Test fun preparedErasureFenceRejectsFavoriteReadsAndWritesWithoutRemovingPendingEvidence(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry();f.favorites.set(f.intent(id,true));val old=f.favoriteColumns(id)
        val intent=f.intent(id,false);val erase=f.base.intent(id)
        f.base.checkpoint={if(it=="prepared")error("Synthetic PREPARED interruption")}
        runBlocking { assertThrows(PermanentErasureIncomplete::class.java){runBlocking{f.base.erase.erase(erase)}} }
        assertEquals(PermanentErasurePhase.PREPARED,f.base.erase.read(id)!!.phase)
        assertThrows(RecordFavoriteUnavailable::class.java){f.favorites.read(id)}
        assertThrows(RecordFavoriteUnavailable::class.java){f.favorites.set(intent)}
        assertFalse(f.favorites.snapshots().containsKey(id));assertEquals(old,f.favoriteColumns(id))
    }

    @Test fun illegalValuesAndOverflowCannotAdvanceFlagOrReceipt(): Unit = RecordFavoriteFixture().use { f ->
        val id=f.entry();val before=f.favoriteColumns(id)
        for(assignment in listOf("isFavorite=2","favoriteVersion=-1","favoriteVersion=1.5",
            "isFavorite=1","lastFavoriteSourceRevision=1","lastFavoriteMutationId='receipt'")) {
            val failure=assertThrows(SqliteBridgeFailure::class.java){f.db.write("UPDATE entries SET "+assignment+" WHERE id=?",id)}
            assertEquals("SQLITE_CONSTRAINT_TRIGGER",failure.category);assertEquals(before,f.favoriteColumns(id))
        }
        f.db.write("DROP TRIGGER favorite_version_guard")
        f.db.write("UPDATE entries SET isFavorite=1,favoriteVersion=9223372036854775807,lastFavoriteMutationId='synthetic-max',lastFavoriteSourceRevision=1 WHERE id=?",id)
        f.db.write(RecordFavoriteSqlSchema.guards.last())
        val max=f.favoriteColumns(id)
        assertThrows(IllegalArgumentException::class.java){f.intent(id,false)}
        val failure=assertThrows(SqliteBridgeFailure::class.java){f.db.write("UPDATE entries SET isFavorite=0,lastFavoriteMutationId='synthetic-next' WHERE id=?",id)}
        assertEquals("SQLITE_CONSTRAINT_TRIGGER",failure.category);assertEquals(max,f.favoriteColumns(id))
    }

    @Test fun triggerReplacementFailureRollsBackAllColumnsAndPreservesPriorTrigger(): Unit = RecordFavoriteFixture(false).use { f ->
        f.entry();val columns=f.db.read("PRAGMA table_info(entries)")
        val trigger=f.db.read("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='local_search_entries_update'")
        f.db.beforeSql={sql->if(sql.startsWith("CREATE TRIGGER local_search_entries_update"))error("Synthetic DDL installation failure")}
        assertThrows(IllegalStateException::class.java){f.upgrade()};f.db.beforeSql=null
        assertEquals(columns,f.db.read("PRAGMA table_info(entries)"));assertEquals(trigger,
            f.db.read("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='local_search_entries_update'"))
        assertEquals(8L,f.db.read("PRAGMA user_version").single().single())
        assertTrue(f.db.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'favorite_%'").isEmpty())
    }
}
