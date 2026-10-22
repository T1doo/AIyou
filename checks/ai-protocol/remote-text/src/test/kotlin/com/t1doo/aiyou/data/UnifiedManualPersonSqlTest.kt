package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production streaming SQLite/AEAD/HMAC. Only synthetic records, never a device claim. */
class UnifiedManualPersonSqlTest {
    @Test fun fixedPurposeLengthFramingColdOwnerAndCanonicalValuesHaveStableDistinctIds():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val id=h.f.entry();set(h,id,listOf("淡茶","A","a","é","é","𠀀","\uE000"))
        val page=h.repo.personChoices(null,{})
        assertNotEquals(h.f.tags.valueId("淡茶"),h.people.valueId("淡茶"))
        assertEquals("a77a4e03c8795ec65fac51a312011214b93ca90f59c8c93155c4f3ad138749b7",page.choices.single{it.name=="淡茶"}.id)
        assertEquals(page.choices.size,page.choices.map{it.id}.distinct().size)
        assertEquals(listOf("A","a","é","é","淡茶","\uE000","𠀀"),page.choices.map{it.name})
        val cold=SqlUnifiedKeywordRepository(h.f.gateway,h.originals,h.audios,h.images,h.preferences,h.memories,h.f.tags,h.people)
        assertEquals(page.choices.map{it.id},cold.personChoices(null,{}).choices.map{it.id})
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{cold.selectPerson(page,page.choices.first(),{})}}
        val ids=ManualPersonValueIds(h.f.key);val other=ManualPersonValueIds(ByteArray(32){(it+2).toByte()})
        assertEquals(h.people.valueId("淡茶"),ids.id("淡茶"));assertNotEquals(ids.id("淡茶"),other.id("淡茶"))
        ids.wipe();other.wipe();assertThrows(IllegalStateException::class.java){ids.id("淡茶")}
    }}
    @Test fun streamingTopKDeduplicatesFullValuesAcrossOldPagesWithoutGlobalNameCap():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        repeat(51){n->set(h,h.f.entry(),listOf("标签"+n.toString().padStart(3,'0'),"共享"))}
        var metadata=0;var largest=0
        h.f.db.beforeSql={sql->if(sql.startsWith("SELECT e.id,length(CAST(r.originalText AS BLOB)),length(t.ciphertext)")){assertTrue(sql.endsWith("LIMIT 20"));metadata++}}
        val pages=ArrayList<UnifiedPersonPage>();var page=h.repo.personChoices(null,{})
        try{while(true){pages+=page;largest=maxOf(largest,page.choices.size);if(!page.hasNext)break;page=h.repo.personChoices(page,{})}}
        finally{h.f.db.beforeSql=null}
        assertEquals(listOf(20,20,12),pages.map{it.choices.size});assertEquals(20,largest)
        val values=pages.flatMap{it.choices};assertEquals(52,values.size);assertEquals(52,values.map{it.name}.distinct().size)
        assertEquals("Three full scans, each with three bounded metadata batches",9,metadata);assertEquals("标签050",values.last().name)
        val last=h.repo.selectPerson(pages.last(),values.last(),{})
        assertEquals("标签050",last.name)
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.selectPerson(pages.first(),values.last(),{})}}
        val fake=object:UnifiedPersonChoice{override val id=values.first().id;override val name=values.first().name}
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.selectPerson(pages.first(),fake,{})}}
    }}
    @Test fun allFiveTypesUseOriginalPersonAndDateIntersectionWithoutInventingPersonNameQuotes():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val a=h.f.entry(text="我偏好淡茶🙂");UnifiedCaptureDateSqlTest.all(h,a);set(h,a,listOf("饮食"))
        val b=h.f.entry(text="我偏好淡茶🙂");UnifiedCaptureDateSqlTest.all(h,b);set(h,b,listOf("其他"))
        val tag=select(h,"饮食");val day=UnifiedCaptureRange.parse("1970-01-01","1970-01-01")
        for(scope in UnifiedKeywordScope.entries){val p=search(h,"淡茶",scope,day,tag,{})
            assertSame(tag,p.person);assertEquals(a,p.groups.single().binding.source.sourceId)
            assertEquals(if(scope==UnifiedKeywordScope.ALL)5 else 1,p.groups.single().items.size)
            assertTrue(h.repo.resolve(p,p.groups.single().items.first()){})
        }
        assertTrue(search(h,"饮食",UnifiedKeywordScope.ALL,day,tag,{}).groups.isEmpty())
        assertTrue(search(h,"淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange.parse("2026-10-22","2026-10-22"),tag,{}).groups.isEmpty())
        val forged=object:UnifiedPersonSelection{override val id=tag.id;override val name=tag.name}
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ALL,day,forged,{})}}
    }}
    @Test fun mediaOnlyEmptyBodyAndAiNoneKeepExistingManualPersonAndOriginalPolicy():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val media=h.f.entry(text="");h.audio(media);h.image(media);set(h,media,listOf("合成空正文"))
        val none=h.f.entry(text="合成淡茶");h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",none);set(h,none,listOf("合成空正文"))
        val tag=select(h,"合成空正文");val p=search(h,"淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange(),tag,{})
        assertEquals(setOf(media,none),p.groups.map{it.binding.source.sourceId}.toSet())
        assertEquals(setOf("A:","I:"),p.groups.single{it.binding.source.sourceId==media}.items.map{it.key.take(2)}.toSet())
        assertEquals("O:",p.groups.single{it.binding.source.sourceId==none}.items.single().key.take(2))
        h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",none)
        assertThrows(Exception::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange(),tag,{})}}
    }}
    @Test fun personOutsideThousandAndFirstAcceptedMatchesNeverConsumeInsideBudget():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        h.f.gateway.transaction{var source="";repeat(1001){n->
            if(n%20==0){source=h.f.entry(text="合成依据");set(h,source,listOf("范围外"))}
            AcceptedMemoryKeywordSqlTest.memory(h,source,"标签预算专词",quote="合成依据",id="person-outside-$n")
        }}
        val inside=h.f.entry(text="合成依据");set(h,inside,listOf("范围内"));AcceptedMemoryKeywordSqlTest.memory(h,inside,"标签预算专词",quote="合成依据")
        val chosen=select(h,"范围内")
        assertEquals(inside,search(h,"标签预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,UnifiedCaptureRange(),chosen,{}).groups.single().binding.source.sourceId)
        val outside=select(h,"范围外")
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{search(h,"标签预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,UnifiedCaptureRange(),outside,{})}}
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{search(h,"标签预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY)}}
        val firstSource=h.f.db.read("SELECT entryId FROM local_candidate_batches WHERE id='person-outside-0'").single().single() as String
        val row=h.memories.memories(SourceVersion(firstSource,1)).single{it.id=="person-outside-0"}
        AcceptedMemoryKeywordSqlTest.change(h,row,SourceMemoryAction.REVOKE)
        val thousand=search(h,"标签预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,UnifiedCaptureRange(),select(h,"范围外"),{})
        assertEquals(1000,thousand.groups.sumOf{it.items.size})
    }}
    @Test fun originalPersonIntersectionPrecedesBodyAndThousandCandidateBudget():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        h.f.gateway.transaction{repeat(1001){set(h,h.f.entry(text="合成淡茶"),listOf("外"))}}
        val id=h.f.entry(text="合成淡茶");set(h,id,listOf("内"));val tag=select(h,"内")
        assertEquals(id,search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),tag,{}).groups.single().binding.source.sourceId)
        assertThrows(Exception::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ORIGINAL)}}
    }}
    @Test fun oldPageSelectionAndProofRejectRenameEmptyAbaAndNonmatchingSourceChanges():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val a=h.f.entry(text="合成淡茶");val b=h.f.entry();set(h,a,listOf("A"));set(h,b,listOf("B"))
        val page=h.repo.personChoices(null,{});val tag=h.repo.selectPerson(page,page.choices.first{it.name=="A"},{})
        val proof=search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),tag,{})
        set(h,b,listOf("C"));set(h,b,listOf("B"))
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.selectPerson(page,page.choices.first(),{})}}
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(proof){fail("ABA cannot revive query proof")}}}
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),tag,{})}}
        val fresh=h.repo.refreshPerson(tag,{});assertEquals(tag.id,fresh.id);assertNotSame(tag,fresh)
        assertEquals(a,search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),fresh,{}).groups.single().binding.source.sourceId)
        set(h,a,emptyList());assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.refreshPerson(fresh,{})}}
        set(h,a,listOf("A"));assertEquals(tag.id,select(h,"A").id)
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),fresh,{})}}
    }}
    @Test fun outsidePersonCorruptionAndOversizeCipherRejectWholeChooserAndQueryWithoutChangingBytes():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val a=h.f.entry(text="合成淡茶");set(h,a,listOf("内"));val tag=select(h,"内")
        val b=h.f.entry();set(h,b,listOf("外"))
        val before=h.people.read(b);val mutation=FullBackupTest.uuid();val cipher=ManualTagCipher(h.f.key,ManualLabelPurpose.PEOPLE)
        val bytes=try{cipher.sealPeople(b,before.version+1,mutation,before.names,before.aliases)}finally{cipher.wipe()}
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        h.f.db.write("UPDATE entry_manual_people SET version=version+1,lastMutationId=?,ciphertext=? WHERE entryId=?",mutation,bytes,b)
        assertThrows(Exception::class.java){runBlocking{h.repo.personChoices(null,{})}}
        assertThrows(Exception::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),tag,{})}}
        assertArrayEquals(bytes,h.f.db.read("SELECT ciphertext FROM entry_manual_people WHERE entryId=?",b).single().single() as ByteArray)
        h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",a)
        // Owned synthetic fixture only: inject impossible stored corruption after bypassing its value guard.
        // The product guard and schema remain unchanged and are covered separately.
        h.f.db.write("DROP TRIGGER manual_people_values_update")
        h.f.db.write("UPDATE entry_manual_people SET version=version+1,lastMutationId=?,ciphertext=? WHERE entryId=?",FullBackupTest.uuid(),ByteArray(8193),b)
        var blobReads=0;h.f.db.beforeSql={if(it.startsWith("SELECT version,lastMutationId,ciphertext FROM entry_manual_people WHERE entryId=?"))blobReads++}
        try{assertThrows(Exception::class.java){runBlocking{h.repo.personChoices(null,{})}}}finally{h.f.db.beforeSql=null}
        assertTrue("Preflight refuses the oversized row before loading its BLOB",blobReads==0)
    }}
    @Test fun emptyCorpusAndPageCommitFailureOrAckLossNeverProduceUsableChoicesAndGuardUnwinds():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        for(lost in listOf(false,true)){
            var tripped=false
            if(lost)h.f.db.afterSql={if(it=="COMMIT"&&!tripped){tripped=true;error("Synthetic tag ACK loss")}}
            else h.f.db.beforeCommit={tripped=true;error("Synthetic tag commit failure")}
            try{assertThrows(Exception::class.java){runBlocking{h.repo.personChoices(null,{})}}}finally{h.f.db.beforeCommit=null;h.f.db.afterSql=null}
            assertTrue(tripped)
        }
        assertTrue(h.repo.personChoices(null,{}).choices.isEmpty());set(h,h.f.entry(),listOf("新"))
        var guards=0
        assertThrows(IllegalStateException::class.java){runBlocking{h.repo.personChoices(null,{if(++guards==3)error("Synthetic retirement")})}}
        assertEquals("新",h.repo.personChoices(null,{}).choices.single().name)
        h.f.gateway.awaitIdle() // Actual transaction release, not coroutine cancellation.
    }}
    @Test fun independentSqlEmptyToNonemptyOrPersonOnlyChangeCannotPublishOldPage():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val id=h.f.entry();val empty=h.repo.personChoices(null,{})
        PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{peer->
            val t=SqlManualPeopleRepository(peer,h.f.key)
            try{t.replace(ManualPeopleMutation(t.read(id),listOf("新"),FullBackupTest.uuid()))}finally{t.wipeKey()}
        }
        val selected=select(h,"新");val proof=search(h,"不存在",UnifiedKeywordScope.AUDIO,UnifiedCaptureRange(),selected,{})
        assertTrue(empty.choices.isEmpty());assertTrue(proof.groups.isEmpty())
        set(h,id,listOf("新","非命中变化"))
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(proof){fail("Empty proof still needs tag corpus")}}}
        assertEquals(listOf("新","非命中变化"),h.repo.personChoices(null,{}).choices.map{it.name})
    }}
    @Test fun fiveScopesRejectPeerInsertedMembershipBetweenPersonAuthenticationAndJointCommit():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries.filter{it!=UnifiedKeywordScope.ALL})UnifiedKeywordFixture(withPeople=true).use{h->UnifiedKeywordFixture(withPeople=true).use{donor->
            set(h,h.f.entry(text="合成无匹配"),listOf("共同标签"));val tag=select(h,"共同标签")
            val id=donor.f.entry(text="我偏好淡茶🙂");UnifiedCaptureDateSqlTest.all(donor,id);set(donor,id,listOf("共同标签"))
            val tables=listOf("entries","entry_revisions","remote_source_access","entry_manual_people","attachments","local_candidate_batches")
            val rows=tables.associateWith{donor.f.db.read("SELECT * FROM $it")}
            donor.f.mediaRoot.listFiles()!!.filter{it.isFile}.forEach{it.copyTo(h.f.mediaRoot.resolve(it.name))}
            PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{peer->
                var inserted=false
                h.f.db.afterSql={sql->if(sql=="COMMIT"&&!inserted){inserted=true;peer.transaction{connection->
                    for(table in tables)for(row in rows.getValue(table))connection.execute("INSERT INTO $table VALUES("+row.joinToString(","){"?"}+")",row)
                }}}
                try{assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(h,"淡茶",scope,UnifiedCaptureRange(),tag,{})}}}
                finally{h.f.db.afterSql=null};assertTrue(inserted)
            }
            val fresh=select(h,"共同标签");assertEquals(id,search(h,"淡茶",scope,UnifiedCaptureRange(),fresh,{}).groups.single().binding.source.sourceId)
        }}
    }
    @Test fun jointCommitFailureAndLostAckAfterPersonValidationCannotReturnSnapshot():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val id=h.f.entry(text="合成淡茶");set(h,id,listOf("合成标签"));val tag=select(h,"合成标签")
        for(lost in listOf(false,true)){
            var joint=false;var tripped=false
            h.f.db.beforeSql={if(it.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis"))joint=true}
            if(lost)h.f.db.afterSql={if(it=="COMMIT"&&joint&&!tripped){tripped=true;error("Synthetic person-filtered joint ACK loss")}}
            else h.f.db.beforeCommit={if(joint){tripped=true;error("Synthetic person-filtered joint commit failure")}}
            try{assertThrows(Exception::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),tag,{})}}}
            finally{h.f.db.beforeSql=null;h.f.db.afterSql=null;h.f.db.beforeCommit=null};assertTrue(tripped)
        }
        assertEquals(id,search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange(),tag,{}).groups.single().binding.source.sourceId)
    }}
    @Test fun completeBackupKeepsNamesAndFormatButNewVaultRequiresFreshValueProof():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{source->UnifiedKeywordFixture(99,withPeople=true).use{target->
        val id=source.f.entry(text="我偏好淡茶🙂");UnifiedCaptureDateSqlTest.all(source,id);set(source,id,listOf("合成标签","é","é"))
        val old=select(source,"合成标签");val snapshot=source.f.backup.capture(FullBackupTest.uuid(),100)
        val prepared=source.f.backup.seal(snapshot,FullBackupTest.password());val output=java.io.ByteArrayOutputStream()
        try{prepared.writeTo(output){true}}finally{prepared.release();snapshot.release()}
        val opened=target.f.backup.open({output.toByteArray().inputStream()},FullBackupTest.password())
        try{assertFalse(target.f.backup.restore(opened){true}.replayed)}finally{opened.release()}
        assertEquals(source.people.read(id).names,target.people.read(id).names)
        val fresh=select(target,"合成标签");assertNotEquals(old.id,fresh.id)
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(target,"淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange(),old,{})}}
        assertEquals(5,search(target,"淡茶",UnifiedKeywordScope.ALL,UnifiedCaptureRange(),fresh,{}).groups.single().items.size)
        assertEquals(12L,target.f.db.read("PRAGMA user_version").single().single())
    }}}
    @Test fun fiveTypesIntersectKeywordDateTagAndManualNameWithoutInferringIdentity():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val a=h.f.entry(text="我偏好淡茶🙂");val b=h.f.entry(text="我偏好淡茶🙂");val c=h.f.entry(text="我偏好淡茶🙂");val d=h.f.entry(text="我偏好淡茶🙂")
        for(id in listOf(a,b,c,d))UnifiedCaptureDateSqlTest.all(h,id)
        for(id in listOf(a,c,d))UnifiedManualTagSqlTest.set(h,id,listOf("饮食"));UnifiedManualTagSqlTest.set(h,b,listOf("别的标签"))
        for(id in listOf(a,b,c))set(h,id,listOf("合成甲"));set(h,d,listOf("合成乙"))
        h.f.db.write("UPDATE entries SET capturedAtMillis=172800000 WHERE id=?",c)
        val person=select(h,"合成甲");val tag=UnifiedManualTagSqlTest.select(h,"饮食");val day=UnifiedCaptureRange.parse("1970-01-01","1970-01-01")
        for(scope in UnifiedKeywordScope.entries){val p=h.repo.search("淡茶",scope,day,tag,person,{})
            assertSame(tag,p.tag);assertSame(person,p.person);assertEquals(a,p.groups.single().binding.source.sourceId)
            assertEquals(if(scope==UnifiedKeywordScope.ALL)5 else 1,p.groups.single().items.size);assertTrue(h.repo.resolve(p,p.groups.single().items.first()){})
        }
        assertTrue(h.repo.search("合成甲",UnifiedKeywordScope.ALL,day,tag,person,{}).groups.isEmpty())
        assertTrue(h.repo.search("饮食",UnifiedKeywordScope.ALL,day,tag,person,{}).groups.isEmpty())
    }}
    @Test fun combinedBudgetAppliesBothConditionsBeforeCountingEveryAuthenticatedMemory():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        h.f.gateway.transaction{for(kind in 0..1){var id="";repeat(1001){n->
            if(n%20==0){id=h.f.entry(text="合成依据");set(h,id,listOf(if(kind==0)"合成甲"else"合成乙"));UnifiedManualTagSqlTest.set(h,id,listOf(if(kind==0)"外"else"内"))}
            AcceptedMemoryKeywordSqlTest.memory(h,id,"交集预算专词",quote="合成依据",id="intersection-$kind-$n")
        }}}
        val inside=h.f.entry(text="合成依据");set(h,inside,listOf("合成甲"));UnifiedManualTagSqlTest.set(h,inside,listOf("内"));AcceptedMemoryKeywordSqlTest.memory(h,inside,"交集预算专词",quote="合成依据")
        val person=select(h,"合成甲");val tag=UnifiedManualTagSqlTest.select(h,"内")
        val p=h.repo.search("交集预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,UnifiedCaptureRange(),tag,person,{})
        assertEquals(inside,p.groups.single().binding.source.sourceId)
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{h.repo.search("交集预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,UnifiedCaptureRange(),tag,null,{})}}
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{h.repo.search("交集预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,UnifiedCaptureRange(),null,person,{})}}
    }}
    @Test fun recordLocalAliasesNeverMergeDifferentNamesAndRenameDoesNotRenameAnotherOriginal():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val a=h.f.entry(text="合成淡茶");val b=h.f.entry(text="合成淡茶");val c=h.f.entry(text="合成淡茶")
        set(h,a,listOf("合成甲"),listOf(ManualPersonAlias("合成别称","合成甲")))
        set(h,b,listOf("合成乙"),listOf(ManualPersonAlias("合成别称","合成乙")));set(h,c,listOf("合成甲"))
        val page=h.repo.personChoices(null,{});assertEquals(setOf("合成甲","合成乙"),page.choices.map{it.name}.toSet())
        val person=select(h,"合成甲");val proof=search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,person=person)
        assertEquals(setOf(a,c),proof.groups.map{it.binding.source.sourceId}.toSet())
        assertTrue(search(h,"合成别称",UnifiedKeywordScope.ORIGINAL,person=person).groups.isEmpty())
        set(h,a,listOf("合成甲"),listOf(ManualPersonAlias("合成新别称","合成甲")))
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.resolve(proof){fail("Alias version change must retire whole proof")}}}
        set(h,a,listOf("合成丙"));val fresh=select(h,"合成甲")
        assertEquals(person.id,fresh.id);assertEquals(listOf(c),search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,person=fresh).groups.map{it.binding.source.sourceId})
        assertEquals(listOf("合成甲"),h.people.read(c).names)
        set(h,c,emptyList());assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.refreshPerson(fresh,{})}}
        set(h,c,listOf("合成甲"));assertEquals(person.id,select(h,"合成甲").id)
        assertThrows(UnifiedKeywordConflict::class.java){runBlocking{search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,person=fresh)}}
    }}
    @Test fun literalPersonScopeDoesNotSpendAliasBudgetOnUnrelatedOriginals():Unit=runBlocking{UnifiedKeywordFixture(withPeople=true).use{h->
        val body="合成无关";val hash=OriginalExports.hash(body);val cipher=ManualTagCipher(h.f.key,ManualLabelPurpose.PEOPLE)
        try{h.f.gateway.transaction{sql->
            // Bulk fixture insertion uses the production cipher, AAD and live SQL guards; no fake query port.
            (0 until 4097).chunked(100).forEach{batch->
                val ids=batch.map{"outside-person-"+it.toString().padStart(5,'0')}
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES "+ids.joinToString(","){"(?,1,5,'UTC','LOCAL_ONLY')"},ids)
                sql.execute("INSERT INTO entry_revisions VALUES "+ids.joinToString(","){"(?,1,?,?,5)"},ids.flatMap{listOf(it,body,hash)})
                sql.execute("INSERT INTO remote_source_access VALUES "+ids.joinToString(","){"(?,1,0,NULL,NULL)"},ids)
                val cells=ids.flatMap{id->val mutation="mutation-"+id
                    listOf(id,mutation,cipher.sealPeople(id,1,mutation,listOf("合成其他"),listOf(ManualPersonAlias("淡茶","合成其他"))))}
                sql.execute("INSERT INTO entry_manual_people VALUES "+ids.joinToString(","){"(?,1,?,?)"},cells)
            }
        }}finally{cipher.wipe()}
        val id=h.f.entry(text="合成淡茶");set(h,id,listOf("合成所选"));val person=select(h,"合成所选")
        val found=search(h,"淡茶",UnifiedKeywordScope.ORIGINAL,person=person)
        assertEquals(id,found.groups.single().binding.source.sourceId)
        assertTrue(h.repo.resolve(found){})
    }}
    private suspend fun search(h:UnifiedKeywordFixture,word:String,scope:UnifiedKeywordScope,range:UnifiedCaptureRange=UnifiedCaptureRange(),person:UnifiedPersonSelection?=null,guard:()->Unit={}):UnifiedKeywordSnapshot =
        h.repo.search(word,scope,range,null,person,guard)
    companion object {
        internal fun set(h:UnifiedKeywordFixture,id:String,names:List<String>,aliases:List<ManualPersonAlias> = emptyList()){val old=h.people.read(id);h.people.replace(ManualPeopleMutation(old,names,FullBackupTest.uuid(),aliases))}
        internal suspend fun select(h:UnifiedKeywordFixture,name:String):UnifiedPersonSelection {var page=h.repo.personChoices(null,{})
            while(page.choices.none{it.name==name}&&page.hasNext)page=h.repo.personChoices(page,{})
            return h.repo.selectPerson(page,page.choices.single{it.name==name},{})}
    }
}
