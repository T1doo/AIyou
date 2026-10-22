package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnifiedFavoriteSqlTest {
    @Test fun fiveTypesIntersectRealOriginalFavoriteWithoutChangingSources():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val selected=h.f.entry(text="我偏好淡茶🙂");val other=h.f.entry(text="我偏好淡茶🙂")
        for(id in listOf(selected,other)){h.audio(id);h.image(id);h.preference(id);memory(h,id)}
        favorite(h,selected,true)
        val before=h.f.db.read("SELECT * FROM entries ORDER BY id")
        val p=search(h)
        assertTrue(p.onlyFavorites);assertEquals(listOf(selected),p.groups.map{it.binding.source.sourceId})
        assertEquals(5,p.groups.single().items.size)
        for(item in p.groups.single().items){var admitted=false;assertTrue(h.repo.resolve(p,item){admitted=true});assertTrue(admitted)}
        assertEquals(before,h.f.db.read("SELECT * FROM entries ORDER BY id"))
        assertEquals(2,h.repo.search("淡茶",UnifiedKeywordScope.ALL).groups.size)
    }}
    @Test fun everyScopeEmptyToNonemptyOffPageUnfavoriteAndAbaInvalidateOldReceipt():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries)UnifiedKeywordFixture().use{h->
            val id=h.f.entry(text="我偏好淡茶🙂");h.audio(id);h.image(id);h.preference(id);memory(h,id)
            val empty=search(h,scope);assertTrue(empty.groups.isEmpty())
            favorite(h,id,true)
            assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(empty){fail("Empty favorite proof revived")}}}
            val p=search(h,scope);assertEquals(1,p.groups.size)
            val hidden=h.f.entry(text="合成不命中");favorite(h,hidden,true)
            val whole=search(h,scope);favorite(h,hidden,false)
            assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(whole){fail("Offscreen unfavorite")}}}
            val before=search(h,scope);favorite(h,id,false);favorite(h,id,true)
            assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(before){fail("Favorite ABA")}}}
            assertEquals(1,search(h,scope).groups.size)
        }
    }
    @Test fun conjunctionWithSavedDateTagAndManualPersonIsAppliedToAllFiveTypes():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val id=h.f.entry(text="我偏好淡茶🙂");val noFavorite=h.f.entry(text="我偏好淡茶🙂");val noPerson=h.f.entry(text="我偏好淡茶🙂");val noTag=h.f.entry(text="我偏好淡茶🙂")
        for(s in listOf(id,noFavorite,noPerson,noTag)){
            h.audio(s);h.image(s);h.preference(s);memory(h,s)
            if(s!=noTag)h.f.tags.replace(ManualTagMutation(s,0,listOf("合成标签"),FullBackupTest.uuid()))
            if(s!=noPerson)h.people.replace(ManualPeopleMutation(h.people.read(s),listOf("合成人物"),FullBackupTest.uuid()))
            if(s!=noFavorite)favorite(h,s,true)
        }
        val tags=h.repo.tagChoices(null,{});val tag=h.repo.selectTag(tags,tags.choices.single(),{})
        val names=h.repo.personChoices(null,{});val person=h.repo.selectPerson(names,names.choices.single(),{})
        val range=UnifiedCaptureRange.parse("1970-01-01","1970-01-01")
        val p=h.repo.search("淡茶",UnifiedKeywordScope.ALL,range,tag,person,true,{})
        assertEquals(listOf(id),p.groups.map{it.binding.source.sourceId});assertEquals(5,p.groups.single().items.size)
        assertTrue(h.repo.search("淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange.parse("1970-01-02",""),tag,person,true,{}).groups.isEmpty())
    }}
    @Test fun unselectedThousandFirstMemoryDoesNotSpendMatchBudgetAndSelectedThousandFirstRejectsWholeSet():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val sources=mutableListOf<String>();val sole=h.f.entry();AcceptedMemoryKeywordSqlTest.memory(h,sole,"淡茶")
        h.f.gateway.transaction{var id="";repeat(1001){n->if(n%20==0){id=h.f.entry();sources+=id};AcceptedMemoryKeywordSqlTest.memory(h,id,"淡茶")}}
        favorite(h,sole,true);assertEquals(1,search(h,UnifiedKeywordScope.ACCEPTED_MEMORY).groups.sumOf{it.items.size})
        favorite(h,sole,false);sources.take(50).forEach{favorite(h,it,true)}
        assertEquals(1000,search(h,UnifiedKeywordScope.ACCEPTED_MEMORY).groups.sumOf{it.items.size})
        favorite(h,sources.last(),true)
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{search(h,UnifiedKeywordScope.ACCEPTED_MEMORY)}}
        assertEquals(1002L,h.f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=2").single().single())
    }}
    @Test fun unrelatedUnfavoritedRowsStillValidateMutationAndLastFavoriteSourceRevision():Unit=runBlocking{
        for(fault in listOf("mutation","lastRevision","body","zone"))UnifiedKeywordFixture().use{h->
            val chosen=h.f.entry(text="合成淡茶");h.audio(chosen);favorite(h,chosen,true)
            val other=h.f.entry(text="合成不命中");favorite(h,other,true);favorite(h,other,false)
            assertEquals(1,search(h,UnifiedKeywordScope.AUDIO).groups.size)
            h.f.db.transaction{sql->
                // Owned synthetic fixture: model stored corruption beyond live production SQL guards.
                for(name in listOf("favorite_values_update","favorite_version_guard"))sql.execute("DROP TRIGGER "+name)
                when(fault){
                    "mutation"->sql.execute("UPDATE entries SET lastFavoriteMutationId=' ' WHERE id=?",listOf(other))
                    "lastRevision"->sql.execute("UPDATE entries SET lastFavoriteSourceRevision=0 WHERE id=?",listOf(other))
                    "body"->{sql.execute("DROP TRIGGER immutable_original_revision");sql.execute("UPDATE entry_revisions SET originalText='合成被替换' WHERE entryId=?",listOf(other));sql.execute(RemoteTextSqlSchema.originalRevisionGuard)}
                    else->sql.execute("UPDATE entries SET captureTimeZone='Invalid/Synthetic' WHERE id=?",listOf(other))
                }
            }
            assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO)}}
            // Explicit false does not add a global favorite scan for this otherwise unrelated source.
            assertEquals(1,h.repo.search("淡茶",UnifiedKeywordScope.AUDIO).groups.size)
        }
    }
    @Test fun excludedMediaCorruptionCannotBecomeSuccessEmptyAndBytesStayUnchanged():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val chosen=h.f.entry(text="合成淡茶");h.audio(chosen);favorite(h,chosen,true)
        val outside=h.audio(h.f.entry(),"合成无关");val file=h.f.mediaRoot.resolve(outside.anchor.intent.id+".aead")
        val bad=file.readBytes();bad[bad.lastIndex]=(bad.last().toInt() xor 1).toByte();file.writeBytes(bad)
        assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO)}};assertArrayEquals(bad,file.readBytes())
    }}
    @Test fun metadataVisitorKeepsFullReceiptFieldsTwentyRowPagesAndExistingReadLimit():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        repeat(41){h.f.entry()};var pages=0;var max=0
        val storage=SqlRecordFavoriteRepository(h.f.gateway);val ids=mutableSetOf<String>()
        h.f.db.afterSql={if(it.contains("e.lastFavoriteSourceRevision FROM entries e WHERE")&&it.contains("ORDER BY e.id LIMIT ?"))pages++}
        try{h.f.gateway.transaction{sql->storage.visitWithin(sql,{}){r->ids+=r.id;max=maxOf(max,r.currentRevision);assertNull(r.mutation);assertEquals(0,r.sourceRevision)}}}
        finally{h.f.db.afterSql=null}
        assertEquals(3,pages);assertEquals(41,ids.size);assertEquals(1,max)
        assertThrows(RecordFavoriteTooBroad::class.java){h.f.gateway.transaction{sql->UnifiedFavorites(SqlRecordFavoriteRepository(h.f.gateway,40)).scan(sql,{})}}
        assertEquals(41L,h.f.db.read("SELECT count(*) FROM entries").single().single())
    }}
    @Test fun favoriteGuardRollsBackActualTransactionBeforeNextQueryOnSameOwner():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        repeat(41){h.f.entry()};var pages=0;var rollback=false
        h.f.db.afterSql={if(it.contains("e.lastFavoriteSourceRevision FROM entries e WHERE")&&it.contains("ORDER BY e.id LIMIT ?"))pages++;if(it=="ROLLBACK")rollback=true}
        class Stop:RuntimeException()
        try{assertThrows(Stop::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO){if(pages==2)throw Stop()}}}}
        finally{h.f.db.afterSql=null}
        assertEquals(2,pages);assertTrue(rollback);assertTrue(search(h,UnifiedKeywordScope.AUDIO).groups.isEmpty())
    }}
    @Test fun finalCommonCommitAbortOrLostAckNeverReturnsProof():Unit=runBlocking{
        for(lostAck in listOf(false,true))UnifiedKeywordFixture().use{h->
            val id=h.f.entry(text="合成淡茶");h.audio(id);favorite(h,id,true);var final=false;var fault=false
            h.f.db.beforeSql={if(it.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis"))final=true}
            if(lostAck)h.f.db.afterSql={if(it=="COMMIT"&&final){fault=true;error("Synthetic lost final ACK")}}
            else h.f.db.beforeCommit={if(final){fault=true;error("Synthetic final SQL abort")}}
            try{assertThrows(Exception::class.java){runBlocking{search(h)}}}
            finally{h.f.db.beforeSql=null;h.f.db.afterSql=null;h.f.db.beforeCommit=null}
            assertTrue(fault);assertEquals(1,search(h).groups.size)
        }
    }
    @Test fun independentSqliteFavoriteChangeBetweenCollectionAndCommonCommitRejectsOldProof():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");h.audio(id);favorite(h,id,true);val before=SqlRecordFavoriteRepository(h.f.gateway).read(id)
        PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{peer->
            var changed=false
            h.f.db.afterSql={if(it=="COMMIT"&&!changed){changed=true;SqlRecordFavoriteRepository(peer).set(RecordFavoriteMutation(id,1,OriginalExports.hash("合成淡茶"),before.version,false,FullBackupTest.uuid(),SqlLocalProcessingRepository(peer).versions().getValue(id).generation))}}
            try{assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(h)}}}
            finally{h.f.db.afterSql=null}
            assertTrue(changed);assertTrue(search(h).groups.isEmpty())
        }
    }}
    @Test fun favoriteSurvivesRevisionButOldEvidenceForeignOwnerAndSourcePolicyDoNot():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");h.audio(id);favorite(h,id,true);val p=search(h,UnifiedKeywordScope.AUDIO)
        val cold=SqlUnifiedKeywordRepository(h.f.gateway,h.originals,h.audios,h.images,h.preferences,h.memories,h.f.tags,h.people)
        assertFalse(cold.resolve(p){fail("Foreign owner")})
        assertTrue(cold.search("淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange(),null,null,true,{}).onlyFavorites)
        h.f.revise(id,"合成修订淡茶")
        assertTrue(SqlRecordFavoriteRepository(h.f.gateway).read(id).isFavorite)
        assertTrue(search(h,UnifiedKeywordScope.AUDIO).groups.isEmpty())
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(p){fail("Old source")}}}
        val next=search(h,UnifiedKeywordScope.ORIGINAL);assertEquals(2L,next.groups.single().binding.source.revision)
        h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
        assertTrue(search(h).groups.isEmpty())
    }}
    @Test fun cancellationInsideOriginalFavoriteMetadataPassRollsBackBeforeAnyCommit():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");favorite(h,id,true);repeat(41){h.f.entry()}
        var initialCommitted=false;var stopping=false;var committedAfterStop=false;var rollback=false
        h.f.db.afterSql={sql->
            if(sql=="COMMIT"){if(stopping)committedAfterStop=true;initialCommitted=true}
            if(initialCommitted&&sql.contains("e.lastFavoriteSourceRevision FROM entries e WHERE")&&sql.contains("ORDER BY e.id LIMIT ?"))stopping=true
            if(sql=="ROLLBACK"&&stopping)rollback=true
        }
        class Stop:RuntimeException()
        try{assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.ORIGINAL){if(stopping)throw Stop()}}}}
        finally{h.f.db.afterSql=null}
        assertTrue(stopping);assertFalse("The active SQL read must unwind before cancellation is acknowledged",committedAfterStop);assertTrue(rollback)
        assertEquals(1,search(h,UnifiedKeywordScope.ORIGINAL).groups.size)
    }}
    companion object {
        internal suspend fun search(h:UnifiedKeywordFixture,scope:UnifiedKeywordScope=UnifiedKeywordScope.ALL,guard:()->Unit={}):UnifiedKeywordSnapshot=
            h.repo.search("淡茶",scope,UnifiedCaptureRange(),null,null,true,guard)
        internal fun favorite(h:UnifiedKeywordFixture,id:String,value:Boolean){
            val row=h.f.db.read("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",id).single()
            val f=SqlRecordFavoriteRepository(h.f.gateway);val old=f.read(id)
            f.set(RecordFavoriteMutation(id,(row[0] as Long).toInt(),row[1] as String,old.version,value,FullBackupTest.uuid(),row[2] as Long))
        }
        internal fun memory(h:UnifiedKeywordFixture,id:String){AcceptedMemoryKeywordSqlTest.memory(h,id,"合成人工备注")}
    }
}
