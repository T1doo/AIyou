package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual owner SQL + immutable ledger + AEAD. Synthetic signatures, not decoded pixels/OCR. */
class UnifiedReadyImageSqlTest {
    private suspend fun search(h:UnifiedKeywordFixture,s:UnifiedKeywordScope=UnifiedKeywordScope.ALL,guard:()->Unit={}):UnifiedKeywordSnapshot =
        h.repo.search("淡茶",s,UnifiedCaptureRange(),null,null,false,true,guard)
    private fun memory(h:UnifiedKeywordFixture,id:String)=AcceptedMemoryKeywordSqlTest.memory(h,id,"淡茶")
    private suspend fun populate(h:UnifiedKeywordFixture,id:String){h.audio(id);h.image(id);h.preference(id);memory(h,id)}
    @Test fun allFiveTypesIntersectCurrentReadyOriginalWithDatesTagsPeopleAndFavorite():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val yes=h.f.entry(text="我偏好淡茶🙂");populate(h,yes)
        val noImage=h.f.entry(text="我偏好淡茶🙂");h.audio(noImage);h.preference(noImage);memory(h,noImage)
        val noFavorite=h.f.entry(text="我偏好淡茶🙂");populate(h,noFavorite)
        for(id in listOf(yes,noImage,noFavorite)){
            h.f.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),FullBackupTest.uuid()))
            h.people.replace(ManualPeopleMutation(h.people.read(id),listOf("合成人物"),FullBackupTest.uuid()))
            if(id!=noFavorite)UnifiedFavoriteSqlTest.favorite(h,id,true)
        }
        val tags=h.repo.tagChoices(null,{});val tag=h.repo.selectTag(tags,tags.choices.single(),{})
        val persons=h.repo.personChoices(null,{});val person=h.repo.selectPerson(persons,persons.choices.single(),{})
        val p=h.repo.search("淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange.parse("1970-01-01","1970-01-01"),tag,person,true,true,{})
        assertTrue(p.onlyReadyImages);assertEquals(listOf(yes),p.groups.map{it.binding.source.sourceId});assertEquals(5,p.groups.single().items.size)
        for(item in p.groups.single().items){var called=false;assertTrue(h.repo.resolve(p,item){called=true});assertTrue(called)}
        assertTrue(h.lastReadyPlaintext!!.all{it==0.toByte()})
        assertEquals(2,search(h).groups.size) // noImage's audio cannot qualify.
    }}
    @Test fun readyQualificationDoesNotChangeLegacyAnyStateOrInventTextForImageOnly():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val ready=h.f.entry(text="合成淡茶");h.f.image(ready)
        val failed=h.f.entry(text="合成淡茶");val f=h.f.image(failed);h.f.db.write("UPDATE attachments SET state='FAILED' WHERE id=?",f.id)
        val old=h.f.entry(text="合成淡茶");h.f.image(old);h.f.revise(old,"合成修订淡茶")
        val empty=h.f.entry(text="");h.f.image(empty)
        val p=search(h,UnifiedKeywordScope.ORIGINAL);assertEquals(listOf(ready),p.groups.map{it.binding.source.sourceId})
        assertTrue(h.repo.resolve(p){}) // nested proof must retain READY mode, not old any-state
        val legacy=h.originals.candidates(LocalSearchRequest("淡茶",onlyImages=true));assertEquals(setOf(ready,failed,empty),legacy.entries.map{it.id}.toSet());assertEquals(setOf(ready,failed),legacy.entries.filter{it.originalText.contains("淡茶")}.map{it.id}.toSet()) // dirty-index candidates are a literal-check superset, including the empty media source
        val bytes=h.f.db.read("SELECT * FROM entry_revisions");assertFalse(p.groups.any{it.binding.source.sourceId==empty});assertEquals(bytes,h.f.db.read("SELECT * FROM entry_revisions"))
    }}
    @Test fun everyScopeEmptyBecomingReadyNonmatchingDeletionAndEmptyAbaInvalidate():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries)UnifiedKeywordFixture().use{h->
            val id=h.f.entry(text="我偏好淡茶🙂");h.audio(id);h.preference(id);memory(h,id)
            // IMAGE starts empty without a derived row too; later writes must invalidate that old proof.
            val empty=search(h,scope);assertTrue(empty.groups.isEmpty());h.image(id)
            assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(empty){fail("Old empty proof")}}}
            assertEquals(1,search(h,scope).groups.size)
            val hidden=h.f.entry(text="合成无关");val image=h.f.image(hidden);val proof=search(h,scope)
            h.f.db.write("DELETE FROM attachments WHERE id=?",image.id)
            assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(proof){fail("Nonmatching source changed")}}}
            val aba=search(h,scope);val i=AttachmentIntent(FullBackupTest.uuid(),hidden,1,"image/png","0".repeat(64),68)
            h.f.db.transaction{sql->sql.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,'READY')",listOf(i.id,hidden,1,i.mediaType,i.sha256,i.byteSize));sql.execute("DELETE FROM attachments WHERE id=?",listOf(i.id))}
            assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(aba){fail("Empty ABA")}}}
        }
    }
    @Test fun corruptionOfAnyReadyIncludingOutsideConditionsRejectsWholeWithoutMarkingOrReplacing():Unit=runBlocking{
        for(fault in listOf("tag","missing","mime"))UnifiedKeywordFixture().use{h->
            val id=h.f.entry(text="合成淡茶");h.f.image(id);val outside=h.f.entry(text="合成无关");val image=h.f.image(outside)
            val file=h.f.mediaRoot.resolve(image.id+".aead");val bytes=file.readBytes();val before=h.f.db.read("SELECT * FROM attachments")
            when(fault){"missing"->file.delete();"tag"->{bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)}
                else->{h.f.db.write("DROP TRIGGER immutable_attachment_intent");h.f.db.write("UPDATE attachments SET mediaType='image/jpeg' WHERE id=?",image.id);h.f.db.transaction{sql->AttachmentIntentSqlSchema.guards.forEach{sql.execute(it)}}}}
            val badRows=h.f.db.read("SELECT * FROM attachments")
            assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.ORIGINAL)}}
            assertEquals(badRows,h.f.db.read("SELECT * FROM attachments"));assertEquals(2,before.size)
            if(fault!="missing")assertArrayEquals(bytes,file.readBytes());assertEquals("READY",h.f.db.read("SELECT state FROM attachments WHERE id=?",image.id).single().single())
        }
    }
    @Test fun validNonReadyMetadataIsNotReadButUnknownMimeAndSixtyFifthImageReject():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");h.f.image(id)
        repeat(63){h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,68,'FAILED')",FullBackupTest.uuid(),id,"0".repeat(64))}
        h.reads=0;assertEquals(1,search(h,UnifiedKeywordScope.ORIGINAL).groups.size);assertEquals(1,h.reads)
        val extra=FullBackupTest.uuid();h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,68,'FAILED')",extra,id,"0".repeat(64))
        assertThrows(UnifiedReadyImageLimit::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO)}}
        h.f.db.write("DELETE FROM attachments WHERE id=?",extra)
        h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'application/unknown',?,68,'FAILED')",extra,id,"0".repeat(64))
        assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO)}}
    }}
    @Test fun initialIntentCannotBeSwappedWithSameIdBeforeActualOpen():Unit=runBlocking{UnifiedKeywordFixture().use{h->FullFixture().use{shadow->
        val id=h.f.entry(text="合成淡茶");val image=h.f.image(id);shadow.entry(id)
        val replacement=FullBackupTest.PNG.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
        val newer=image.copy(sha256=FullBackupTest.hash(replacement));shadow.media.save(newer){replacement.inputStream()};var changed=false
        h.beforeReadyRead={if(!changed){changed=true;h.f.db.write("DROP TRIGGER immutable_attachment_intent");h.f.db.write("UPDATE attachments SET sha256=? WHERE id=?",newer.sha256,image.id)
            h.f.mediaRoot.resolve(image.id+".aead").writeBytes(shadow.mediaRoot.resolve(image.id+".aead").readBytes());h.f.db.transaction{sql->AttachmentIntentSqlSchema.guards.forEach{sql.execute(it)}}}}
        h.reads=0;assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.ORIGINAL)}};assertTrue(changed);assertEquals(1,h.reads)
        assertArrayEquals(replacement,h.f.media.readReadOnly(image.id));assertTrue(h.lastReadyPlaintext!!.all{it==0.toByte()})
        assertEquals("READY",h.f.db.read("SELECT state FROM attachments WHERE id=?",image.id).single().single())
    }}}
    @Test fun oldAnyStateImageCollectionRecheckRemainsActiveIndependentOfEpoch():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");val old=h.originals.candidates(LocalSearchRequest("淡茶",onlyImages=true));assertTrue(old.entries.isEmpty());val before=h.originals.epoch()
        h.f.db.transaction{sql->listOf("insert","update","delete").forEach{sql.execute("DROP TRIGGER unified_ready_image_epoch_"+it)}}
        try{h.f.image(id)}finally{h.f.db.transaction{sql->ReadyImageSqlSchema.epochGuards.forEach{sql.execute(it)}}}
        assertEquals(before,h.originals.epoch());assertFalse(h.originals.publishCurrent(old){fail("Old any-state collection changed")})
        assertEquals(listOf(id),h.originals.candidates(LocalSearchRequest("淡茶",onlyImages=true)).entries.map{it.id})
    }}
    @Test fun finalCommitAbortOrLostAckCannotReturnProofAndReadCancellationReleasesOwner():Unit=runBlocking{
        for(lost in listOf(false,true))UnifiedKeywordFixture().use{h->
            val id=h.f.entry(text="合成淡茶");h.f.image(id);var joint=false;var fault=false
            h.f.db.beforeSql={if(it.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis"))joint=true}
            if(lost)h.f.db.afterSql={if(it=="COMMIT"&&joint){fault=true;error("Synthetic lost ACK")}}else h.f.db.beforeCommit={if(joint){fault=true;error("Synthetic abort")}}
            try{assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.ORIGINAL)}}}finally{h.f.db.beforeSql=null;h.f.db.afterSql=null;h.f.db.beforeCommit=null}
            assertTrue(fault);assertEquals(1,search(h,UnifiedKeywordScope.ORIGINAL).groups.size)
        }
        UnifiedKeywordFixture().use{h->val id=h.f.entry(text="合成淡茶");h.f.image(id);class Stop:RuntimeException();var done=false
            h.afterRead={done=true};assertThrows(Stop::class.java){runBlocking{search(h,UnifiedKeywordScope.ORIGINAL){if(done)throw Stop()}}}
            assertTrue(h.lastReadyPlaintext!!.all{it==0.toByte()});h.afterRead=null;assertEquals(1,search(h,UnifiedKeywordScope.ORIGINAL).groups.size)
        }
    }
    @Test fun legalMediaCollectionAboveBackupTotalStillStreamsAndSearchesWithoutNewSaveCap():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val bytes=ByteArray(14*1024*1024);FullBackupTest.PNG.copyInto(bytes)
        try{repeat(5){val id=h.f.entry(text="合成淡茶");val intent=AttachmentIntent(FullBackupTest.uuid(),id,1,"image/png",FullBackupTest.hash(bytes),bytes.size.toLong());h.f.media.save(intent){bytes.inputStream()}}
            h.reads=0;val p=search(h,UnifiedKeywordScope.ORIGINAL);assertEquals(5,p.groups.size);assertEquals(5,h.reads)
            assertEquals(70L*1024*1024,h.f.db.read("SELECT sum(byteSize) FROM attachments").single().single());assertTrue(h.lastReadyPlaintext!!.all{it==0.toByte()})
            assertThrows(Exception::class.java){runBlocking{val backup=h.f.backup.capture(FullBackupTest.uuid(),100);try{val payload=h.f.backup.seal(backup,FullBackupTest.password());try{payload.writeTo(java.io.ByteArrayOutputStream()){true}}finally{payload.release()}}finally{backup.release()}}} // unchanged existing full-backup 64MiB boundary
        }finally{bytes.fill(0)}
    }}
    @Test fun memoryBudgetIsAfterReadyIntersectionButNonmatchingRowsStillAuthenticate():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val sole=h.f.entry(text="合成淡茶");memory(h,sole);h.f.image(sole)
        val sources=mutableListOf<String>();h.f.gateway.transaction{var id="";repeat(1001){n->if(n%20==0){id=h.f.entry();sources+=id};memory(h,id)}}
        assertEquals(1,search(h,UnifiedKeywordScope.ACCEPTED_MEMORY).groups.sumOf{it.items.size})
        h.f.db.write("DELETE FROM attachments WHERE entryId=?",sole)
        for(id in sources.take(50))h.f.image(id)
        assertEquals(1000,search(h,UnifiedKeywordScope.ACCEPTED_MEMORY).groups.sumOf{it.items.size})
        h.f.image(sources.last());assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{search(h,UnifiedKeywordScope.ACCEPTED_MEMORY)}}
        assertEquals(1002L,h.f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=2").single().single())
    }}
    @Test fun emptyKeyCorruptionCannotHideBeforeFirstKeysetPageAndValidNonImageMetadataIsNotRead():Unit=runBlocking{
        for(sourceFault in listOf(true,false))UnifiedKeywordFixture().use{h->
            val id=h.f.entry(text="合成淡茶");h.f.image(id)
            h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'audio/ogg',?,68,'FAILED')",FullBackupTest.uuid(),id,"0".repeat(64))
            h.reads=0;assertEquals(1,search(h,UnifiedKeywordScope.ORIGINAL).groups.size);assertEquals(1,h.reads)
            if(sourceFault)h.f.entry("",text="合成无关")else h.f.db.write("INSERT INTO attachments VALUES('',?,1,'image/png',?,68,'FAILED')",id,"0".repeat(64))
            assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO)}}
        }
    }
    @Test fun disabledFilterDoesNotAddCorpusScanOrReadyMediaRead():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");h.f.image(id);val bad=h.f.entry(text="合成无关");h.f.db.write("UPDATE entries SET captureTimeZone='invalid/synthetic' WHERE id=?",bad)
        h.reads=0;assertTrue(h.repo.search("淡茶",UnifiedKeywordScope.AUDIO).groups.isEmpty());assertEquals(0,h.reads)
        assertThrows(Exception::class.java){runBlocking{search(h,UnifiedKeywordScope.AUDIO)}}
    }}
}
