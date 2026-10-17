package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual v10 export -> product v11 SQL -> product v12 SQL. No Room11 export/native migration claim. */
class ManualPeopleSqlTest {
    @Test fun checkedTenExportThenElevenAndTwelveSqlPreserveEveryExistingRecordDraftAndPolicy(): Unit = ManualPeopleFixture(false).use { f ->
        val id = f.base.entry(); f.base.db.write("INSERT INTO capture_draft VALUES(1,'synthetic-draft',7,'合成草稿')")
        val before = f.base.originalState(); val epoch = f.base.search.epoch()
        assertEquals(11L, f.base.db.read("PRAGMA user_version").single().single())
        f.upgrade(); assertEquals(12L, f.base.db.read("PRAGMA user_version").single().single())
        assertEquals(before, f.base.originalState()); assertEquals(epoch, f.base.search.epoch())
        assertTrue(f.people.read(id).names.isEmpty()); assertEquals(0L, f.people.read(id).version)
        assertTrue(f.base.db.read("PRAGMA foreign_key_check").isEmpty())
        assertEquals("CASCADE", f.base.db.read("PRAGMA foreign_key_list(entry_manual_people)").single()[6])
    }

    @Test fun explicitAddRemoveColdReopenAndExactRetryKeepOriginalAndIndependentTags(): Unit = ManualPeopleFixture().use { f ->
        val id = f.base.entry(); f.base.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),uuid()))
        val before = f.base.originalState(); val tag = f.base.tags.read(id)
        val first = f.mutation(id,listOf("小林", "同名#2🙂")); assertEquals(first.names, f.people.replace(first).names)
        f.reopen(); assertEquals(1L, f.people.replace(first).version)
        val remove = f.mutation(id,listOf("同名#2🙂")); f.people.replace(remove); f.reopen()
        assertEquals(remove.names, f.people.read(id).names)
        assertThrows(ManualPeopleConflict::class.java) { f.people.replace(first) }
        val clear = f.mutation(id,emptyList()); f.people.replace(clear); f.reopen()
        assertTrue(f.people.read(id).names.isEmpty()); assertEquals(3L, f.people.replace(clear).version)
        assertEquals(before, f.base.originalState()); assertEquals(tag.names, f.base.tags.read(id).names)
    }

    @Test fun sourceRevisionHashGenerationAndMetadataCasAllRejectStaleIntent(): Unit = ManualPeopleFixture().use { f ->
        val id = f.base.entry(); val old = f.mutation(id,listOf("旧选择")); f.base.append(id)
        assertThrows(ManualPeopleConflict::class.java) { f.people.replace(old) }
        val before = f.people.read(id)
        val forged = ManualPeopleSnapshot(id,before.version,before.names,before.sourceRevision,"0".repeat(64),before.generation)
        assertThrows(ManualPeopleConflict::class.java) { f.people.replace(ManualPeopleMutation(forged,listOf("伪造"),uuid())) }
        val losing = f.mutation(id,listOf("落后")); val winning = f.mutation(id,listOf("明确选择")); f.people.replace(winning)
        assertThrows(ManualPeopleConflict::class.java) { f.people.replace(losing) }
        assertEquals(winning.names, f.people.read(id).names)
        val aba = f.mutation(id,emptyList())
        f.base.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",id)
        assertThrows(ManualPeopleConflict::class.java) { f.people.replace(aba) }
    }

    @Test fun uncertainCommitAckRetriesExactlyOnceWhileAbortedWritePreservesEpochAndData(): Unit = ManualPeopleFixture().use { f ->
        val id=f.base.entry();val intent=f.mutation(id,listOf("明确关联"));var writing=false;var lost=false
        f.base.db.afterSql={ sql ->
            if(sql.startsWith("INSERT INTO entry_manual_people"))writing=true
            if(sql=="COMMIT"&&writing&&!lost){lost=true;error("Synthetic people ACK lost")}
        }
        assertThrows(ManualPeopleStorageFailure::class.java){f.people.replace(intent)};assertTrue(lost)
        f.base.db.afterSql=null;f.reopen();assertEquals(1L,f.people.replace(intent).version)
        val before=f.rows();val epoch=f.search.epoch()
        f.base.db.write("CREATE TEMP TRIGGER refuse_people BEFORE UPDATE ON entry_manual_people BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(ManualPeopleStorageFailure::class.java){f.people.replace(f.mutation(id,emptyList()))}
        assertEquals(epoch,f.search.epoch());assertEquals(before.map { it.take(3) },f.rows().map { it.take(3) })
        assertEquals(listOf("明确关联"),f.people.read(id).names)
    }

    @Test fun namesAreEncryptedWithSeparateKeyPurposeAndNeverEnterOriginalFts(): Unit = ManualPeopleFixture().use { f ->
        val id=f.base.entry(text="合成 needle 原文");f.people.replace(f.mutation(id,listOf("synthetic-private-person")))
        val cipher=f.rows().single()[3] as ByteArray
        assertFalse(cipher.toString(Charsets.UTF_8).contains("synthetic-private-person"))
        val tagCipher=ManualTagCipher(ManualPeopleFixture.KEY)
        val tagBytes=tagCipher.seal(id,1,"synthetic-copy",listOf("synthetic-private-person"))
        val other=f.base.entry();f.base.db.write("INSERT INTO entry_manual_people VALUES(?,1,'synthetic-copy',?)",other,tagBytes)
        assertThrows(ManualPeopleStorageFailure::class.java){f.people.read(other)}
        f.base.db.write("DELETE FROM entry_manual_people WHERE entryId=?",other);tagCipher.wipe()
        f.base.drain()
        val result=runBlocking{LocalOriginalSearchMatcher.indexed(f.base.search.candidates(LocalSearchRequest("")).entries,
            OriginalSearchInput("synthetic-private-person"),f.search)}
        assertTrue(result.hits.isEmpty());assertFalse(f.base.db.read("SELECT bodyTokens,tagTokens FROM local_search_fts").toString().contains("synthetic-private-person"))
    }

    @Test fun personAndDateTagFavoriteImageConditionsIntersectWithoutChangingSortOrOriginalQuotes(): Unit = ManualPeopleFixture().use { f ->
        val a=f.base.entry(text="合成 needle 原文🙂");val b=f.base.entry(text="合成 needle 另一条")
        f.people.replace(f.mutation(a,listOf("小林")));f.people.replace(f.mutation(b,listOf("小林#2")))
        for(id in listOf(a,b)){
            f.base.tags.replace(ManualTagMutation(id,0,listOf("生活"),uuid()))
            f.base.favorites.set(f.base.favorite(id,true));f.base.dates.set(f.base.intent(id,RecordEventDate("2000-01-01","UTC")))
            f.base.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,9,'READY')",uuid(),id,"a".repeat(64))
        }
        f.base.drain()
        val input=OriginalSearchInput("needle",from="2000-01-01",through="2000-01-01",tag="生活",tagSnapshot=f.base.tags.snapshots(),
            onlyFavorites=true,favoriteSnapshot=f.base.favorites.snapshots(),dateBasis=RecordDateBasis.EVENT,
            eventDateSnapshot=f.base.dates.snapshots(),onlyImages=true,person="小林",peopleSnapshot=f.people.snapshots())
        val result=runBlocking{LocalOriginalSearchMatcher.indexed(f.base.search.candidates(LocalSearchRequest("")).entries,input,f.search)}
        assertEquals(OriginalSearchStage.READY,result.stage);assertEquals(listOf(a),result.hits.map{it.entry.id})
        assertEquals("合成 needle 原文🙂",result.hits.single().quote)
        assertTrue(f.search.publishCurrent(checkNotNull(result.proof)){})
        val noName=runBlocking{LocalOriginalSearchMatcher.match(result.hits.map{it.entry},input.copy(person="不存在"))}
        assertTrue(noName.hits.isEmpty())
    }

    @Test fun changedPersonMembershipEmptyProofAndRetainedNavigationAreInvalidatedByEpoch(): Unit = ManualPeopleFixture().use { f ->
        val id=f.base.entry();f.base.drain()
        val empty=f.search.candidates(LocalSearchRequest("needle",person="小林"));assertTrue(empty.entries.isEmpty())
        f.people.replace(f.mutation(id,listOf("小林")));assertFalse(f.search.publishCurrent(empty){fail("Stale empty")})
        val selected=f.search.candidates(LocalSearchRequest("needle",person="小林"));assertEquals(listOf(id),selected.entries.map{it.id})
        f.people.replace(f.mutation(id,emptyList()))
        assertFalse(f.search.resolve(selected,id){fail("Old person source")});assertFalse(f.search.publishCurrent(selected){fail("Old filter")})
    }

    @Test fun missingSnapshotOrCorruptPeopleCannotMasqueradeAsNoMatches(): Unit = ManualPeopleFixture().use { f ->
        val id=f.base.entry();f.people.replace(f.mutation(id,listOf("小林")));f.base.drain()
        val entries=f.base.search.candidates(LocalSearchRequest("")).entries
        assertEquals(OriginalSearchStage.FAILED,runBlocking{LocalOriginalSearchMatcher.match(entries,OriginalSearchInput(person="小林"))}.stage)
        f.base.db.write("UPDATE entry_manual_people SET version=version+1,lastMutationId=?,ciphertext=? WHERE entryId=?",uuid(),ByteArray(40),id)
        assertThrows(ManualPeopleStorageFailure::class.java){f.people.read(id)}
        assertThrows(ManualPeopleStorageFailure::class.java){f.people.snapshots()}
        val result=runBlocking{LocalOriginalSearchMatcher.indexed(entries,OriginalSearchInput("needle",person="小林",peopleSnapshot=emptyMap()),f.search)}
        assertEquals(OriginalSearchStage.FAILED,result.stage);assertTrue(result.hits.isEmpty())
    }

    @Test fun aiNoneStaysManualAndRecycleRestorePreservesNamesButRejectsOldGeneration(): Unit = ManualPeopleFixture().use { f ->
        val id=f.base.entry();f.people.replace(f.mutation(id,listOf("小林")));val old=f.mutation(id,emptyList())
        f.base.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",id)
        f.base.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",id)
        assertThrows(ManualPeopleConflict::class.java){f.people.replace(old)}
        assertEquals(listOf("小林"),f.people.read(id).names)
        val beforeDelete=f.mutation(id,emptyList())
        f.base.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
        assertThrows(ManualPeopleUnavailable::class.java){f.people.read(id)};assertTrue(f.people.snapshots().isEmpty())
        assertThrows(ManualPeopleUnavailable::class.java){f.people.replace(beforeDelete)}
        f.base.db.write("UPDATE remote_source_access SET deleted=0,generation=generation+1 WHERE entryId=?",id)
        assertEquals(listOf("小林"),f.people.read(id).names)
        assertThrows(ManualPeopleConflict::class.java){f.people.replace(beforeDelete)}
        f.people.replace(f.mutation(id,listOf("手工仍可更正")));assertEquals(listOf("手工仍可更正"),f.people.read(id).names)
    }

    @Test fun sqlGuardsAndPermanentErasureRemoveCiphertextWithoutAffectingOtherRecords(): Unit = runBlocking {
        PermanentErasureFixture(ManualPeopleFixture.KEY).use { f ->
            f.db.transaction { sql -> ManualPeopleSqlSchema.migration11To12.forEach{sql.execute(it)} }
            val people=SqlManualPeopleRepository(f.gateway,ManualPeopleFixture.KEY)
            val id=f.entry();val other=f.entry()
            people.replace(ManualPeopleMutation(people.read(id),listOf("待删除人物"),uuid()))
            people.replace(ManualPeopleMutation(people.read(other),listOf("保留人物"),uuid()))
            assertThrows(SqliteBridgeFailure::class.java){f.db.write("UPDATE entry_manual_people SET version=version WHERE entryId=?",id)}
            assertTrue(f.erase.erase(f.intent(id)).complete)
            assertTrue(f.db.read("SELECT entryId FROM entry_manual_people WHERE entryId=?",id).isEmpty())
            assertEquals(listOf("保留人物"),people.read(other).names);people.wipeKey()
        }
    }

    @Test fun metadataBudgetAndImmutableExactNameRulesRejectAmbiguousOrInvalidInput() {
        val before=ManualPeopleSnapshot("synthetic",0,emptyList(),1,"a".repeat(64),1)
        assertEquals(listOf("小林", "小林#2"),ManualPeopleMutation(before,listOf(" 小林 ","小林#2"),uuid()).names)
        for(names in listOf(listOf(""),listOf("x","x"),listOf("\uD800"),listOf("\n"),listOf("a".repeat(41)),List(13){"p$it"}))
            assertThrows(IllegalArgumentException::class.java){ManualPeopleMutation(before,names,uuid())}
        val mutation=ManualPeopleMutation(before,listOf("小林"),uuid())
        assertThrows(UnsupportedOperationException::class.java){(mutation.names as MutableList).clear()}
        assertFalse(mutation.toString().contains("小林"));assertFalse(before.toString().contains("synthetic"))
    }
    companion object { private fun uuid()=UUID.randomUUID().toString() }
}

internal class ManualPeopleFixture(upgrade: Boolean=true):AutoCloseable {
    val base=RecordEventDateFixture()
    lateinit var people:SqlManualPeopleRepository;private set
    lateinit var search:SqlLocalSearchRepository;private set
    init{ if(upgrade)upgrade() else ports() }
    fun upgrade(){base.db.transaction{sql->ManualPeopleSqlSchema.migration11To12.forEach{sql.execute(it)};sql.execute("PRAGMA user_version=12")};ports()}
    private fun ports(){people=SqlManualPeopleRepository(base.gateway,KEY);search=SqlLocalSearchRepository(base.gateway,base.tags,people=people)}
    fun mutation(id:String,names:List<String>)=ManualPeopleMutation(people.read(id),names,UUID.randomUUID().toString())
    fun rows()=base.db.read("SELECT * FROM entry_manual_people ORDER BY entryId")
    fun reopen(){people.wipeKey();base.reopen();ports()}
    override fun close(){people.wipeKey();base.close()}
    companion object { val KEY=ByteArray(32){(it+59).toByte()} }
}
