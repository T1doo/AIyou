package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Production plain/streaming codec, AEAD and new-copy SQL restore; all material is synthetic. */
class ManualPeoplePortabilityTest {
    @Test(timeout=30_000) fun plainJsonMarkdownActivityAndZipDeclareAndRoundTripLiteralNamesAndHashScope(): Unit = runBlocking {
        PeopleMediaFixture().use { f ->
            val id=f.seed();val name="小林\"```<b>";f.people!!.replace(ManualPeopleMutation(f.people!!.read(id),listOf(name),uuid()))
            val snapshot=f.originals.capture(listOf(SourceVersion(id,1)),uuid(),50)
            for(format in listOf(OriginalExportFormat.JSONL,OriginalExportFormat.MARKDOWN)) {
                val payload=OriginalExportCodec.render(snapshot,format)
                try {
                    val text=payload.copyBytes().toString(Charsets.UTF_8)
                    assertTrue(text.contains("manualPeople"));assertTrue(text.contains("manualPeopleVersion"))
                    if(format==OriginalExportFormat.JSONL){
                        val rows=text.lines().filter{it.isNotBlank()}.map{Json.parseToJsonElement(it).jsonObject}
                        assertEquals(3,rows[0]["formatVersion"]!!.jsonPrimitive.int)
                        assertEquals(name,rows[1]["manualPeople"]!!.jsonArray.single().jsonPrimitive.content)
                        assertEquals(OriginalExports.hash(text.lines()[1]+"\n"),rows.last()["recordsSha256"]!!.jsonPrimitive.content)
                    } else assertTrue(text.contains("````json"))
                } finally {payload.release()}
            }
            val activities=RecordPackageActivities(emptyList(),emptyList(),listOf(snapshot.records.single().sourceGeneration))
            val activity=OriginalActivityExportCodec.render(snapshot,activities,OriginalExportFormat.JSONL)
            try{ val text=activity.copyBytes().toString(Charsets.UTF_8);assertTrue(text.contains("manualPeople"));assertEquals(2,
                Json.parseToJsonElement(text.lineSequence().first()).jsonObject["formatVersion"]!!.jsonPrimitive.int) }finally{activity.release()}
            val preview=f.media.preview(snapshot)
            val prepared=OpenRecordZipCodec.prepare(preview,{error("No images")},{f.media.isCurrent(preview)})
            val output=ByteArrayOutputStream();try{prepared.writeTo(output){true}}finally{prepared.release()}
            val files=mutableMapOf<String,ByteArray>();ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}
            }
            val record=Json.parseToJsonElement(files.getValue("records.jsonl").toString(Charsets.UTF_8).trim()).jsonObject
            assertEquals(name,record["manualPeople"]!!.jsonArray.single().jsonPrimitive.content)
            val manifest=Json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
            assertEquals(2,manifest["formatVersion"]!!.jsonPrimitive.int)
            assertTrue(manifest["scope"]!!.jsonArray.any{it.jsonPrimitive.content=="manualPeople"})
        }
    }

    @Test(timeout=40_000) fun v6CurrentAndHistoryWithAndWithoutDatesRestoreOnlyExplicitNamesIntoIndependentCopies(): Unit = runBlocking {
        for(history in listOf(false,true)) for(withDate in listOf(false,true)) PeopleMediaFixture().use { source -> PeopleMediaFixture().use { target ->
            val id=source.seed();source.revise(id)
            source.people!!.replace(ManualPeopleMutation(source.people!!.read(id),listOf("小林"),uuid()))
            source.people!!.replace(ManualPeopleMutation(source.people!!.read(id),listOf("小林#2🙂"),uuid()))
            if(withDate)source.dates.set(RecordEventDateMutation(id,2,OriginalExports.hash(TEXT2),0,DATE,uuid()))
            val snapshot=source.originals.capture(listOf(SourceVersion(id,2)),uuid(),50)
            val bytes=source.export(if(history)source.media.previewHistory(snapshot)else source.media.preview(snapshot))
            assertEquals("AIYOUPK6",bytes.copyOfRange(0,8).toString(Charsets.US_ASCII))
            val verified=target.media.open({bytes.inputStream()},password())
            try {
                assertEquals(2L,verified.records.single().sourcePeopleVersion)
                assertEquals(listOf("小林#2🙂"),verified.records.single().people)
                val newId=uuid();target.media.restore(verified,listOf(newId),100)
                assertEquals(listOf("小林#2🙂"),target.people!!.read(newId).names);assertEquals(1L,target.people!!.read(newId).version)
                assertEquals(if(withDate)DATE else null,target.dates.read(newId).date)
                assertEquals(listOf(5L,"UTC","AI_NONE"),target.base.db.read("SELECT capturedAtMillis,captureTimeZone,privacyPolicy FROM entries WHERE id=?",newId).single())
                assertEquals(listOf(null,null),target.base.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?",newId).single())
                assertEquals(if(history)listOf(5L,17L)else listOf(100L),target.base.db.read("SELECT createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision",newId).map{it.single()})
                target.reopen();assertEquals(listOf("小林#2🙂"),target.people!!.read(newId).names)
            } finally {verified.release()}
        }}
    }

    @Test(timeout=30_000) fun uncertainRestoreAckReplayNeverOverwritesLaterNamesClearTrashOrPermanentErasure(): Unit = runBlocking {
        PeopleMediaFixture().use { source -> PeopleMediaFixture().use { target ->
            val id=source.seed();source.people!!.replace(ManualPeopleMutation(source.people!!.read(id),listOf("源名称"),uuid()))
            val snapshot=source.originals.capture(listOf(SourceVersion(id,1)),uuid(),50)
            val bytes=source.export(source.media.preview(snapshot));val newId=uuid();var group=false;var lost=false
            target.base.db.afterSql={ sql ->
                if(sql.startsWith("INSERT INTO record_package_receipts")&&sql.contains("'PACKAGE_RESTORE'"))group=true
                if(sql=="COMMIT"&&group&&!lost){lost=true;error("Synthetic people restore ACK lost")}
            }
            try{target.restore(bytes,newId);fail("Expected lost ACK")}catch(_:RecordPackageStorageFailure){assertTrue(lost)}
            target.base.db.afterSql=null;target.reopen()
            target.people!!.replace(ManualPeopleMutation(target.people!!.read(newId),listOf("明确更正"),uuid()))
            assertTrue(target.restore(bytes,uuid()).replayed);assertEquals(listOf("明确更正"),target.people!!.read(newId).names)
            target.people!!.replace(ManualPeopleMutation(target.people!!.read(newId),emptyList(),uuid()))
            assertTrue(target.restore(bytes,uuid()).replayed);assertTrue(target.people!!.read(newId).names.isEmpty())
            val intent=target.base.intent(newId);val trashedReplay=target.restore(bytes,uuid())
            assertTrue(trashedReplay.replayed);assertEquals(listOf(newId),trashedReplay.ids);assertEquals(0,trashedReplay.deletedCount)
            assertTrue(target.base.bin.read(newId).deleted)
            assertTrue(target.people!!.snapshots().isEmpty())
            assertTrue(target.base.erase.erase(intent).complete);assertEquals(1,target.restore(bytes,uuid()).deletedCount)
            assertTrue(target.base.db.read("SELECT entryId FROM entry_manual_people").isEmpty())
            assertTrue(target.base.db.read("SELECT id FROM entries").isEmpty())
        }}
    }

    @Test(timeout=30_000) fun refusedPeopleRowRollsBackWholeRestoreAndLegacyOwnerCannotSilentlyDropAssociations(): Unit = runBlocking {
        PeopleMediaFixture().use { source ->
            val id=source.seed();source.people!!.replace(ManualPeopleMutation(source.people!!.read(id),listOf("不许丢失"),uuid()))
            val snapshot=source.originals.capture(listOf(SourceVersion(id,1)),uuid(),50);val bytes=source.export(source.media.preview(snapshot))
            PeopleMediaFixture().use { target ->
                target.base.db.write("CREATE TEMP TRIGGER refuse_people BEFORE INSERT ON entry_manual_people BEGIN SELECT RAISE(ABORT,'synthetic refusal'); END")
                try{target.restore(bytes,uuid());fail("People insertion must be atomic")}catch(_:RecordPackageStorageFailure){}
                assertTrue(target.base.db.read("SELECT id FROM entries").isEmpty());assertTrue(target.base.db.read("SELECT * FROM entry_revisions").isEmpty())
                assertTrue(target.base.db.read("SELECT * FROM entry_manual_tags").isEmpty());assertTrue(target.base.db.read("SELECT * FROM entry_manual_people").isEmpty())
                assertTrue(target.base.db.read("SELECT id FROM record_package_receipts WHERE operation IN ('PACKAGE_RESTORE','PACKAGE_ORIGIN')").isEmpty())
                target.media.recover()
            }
            PeopleMediaFixture(false).use { legacy ->
                try{legacy.restore(bytes,uuid());fail("Legacy owner must reject v6 scope")}catch(_:RecordPackageStorageFailure){}
                assertTrue(legacy.base.db.read("SELECT id FROM entries").isEmpty())
                assertTrue(legacy.base.db.read("SELECT id FROM record_package_receipts WHERE operation='PACKAGE_RESTORE'").isEmpty())
            }
        }
    }

    @Test(timeout=30_000) fun changedNamesInvalidatePreparedExportAndAuthenticatedTamperCannotReachRestore(): Unit = runBlocking {
        PeopleMediaFixture().use { source ->
            val id=source.seed();source.people!!.replace(ManualPeopleMutation(source.people!!.read(id),listOf("首次选择"),uuid()))
            val snapshot=source.originals.capture(listOf(SourceVersion(id,1)),uuid(),50);val preview=source.media.preview(snapshot)
            val prepared=source.media.seal(preview,password())
            source.people!!.replace(ManualPeopleMutation(source.people!!.read(id),listOf("更新选择"),uuid()))
            assertFalse(source.originals.isCurrent(snapshot));assertFalse(source.media.isCurrent(preview))
            val output=ByteArrayOutputStream()
            try{prepared.writeTo(output){true};fail("Stale export")}catch(_:IllegalStateException){}finally{prepared.release()}
            assertEquals(0,output.size())
            val current=source.originals.capture(listOf(SourceVersion(id,1)),uuid(),60);val bytes=source.export(source.media.preview(current))
            val altered=bytes.copyOf().also{it[48]=(it[48].toInt() xor 1).toByte()}
            for(raw in listOf(altered,bytes.copyOf(bytes.size-1),bytes.copyOf().also{it[7]='7'.code.toByte()}))
                try{source.media.open({raw.inputStream()},password());fail("Invalid authenticated package")}catch(_:RecordPackageInvalid){}
        }
    }

    @Test fun versionSixParserRejectsWrongSourceDuplicateNamesAndAmbiguousLegacyEmptyEncoding(): Unit = PeopleMediaFixture().use { source ->
        val id=source.seed();val snapshot=source.originals.capture(listOf(SourceVersion(id,1)),uuid(),50)
        val legacy=JcaRecordPackageCipher();val bytes=legacy.encode(snapshot)
        val base=try{legacy.parse(bytes)}finally{bytes.fill(0)}
        fun raw(name:String,version:Long,names:List<String>,baseVersion:Int=2)=PackageWriter(8192).use { out ->
            out.string("AIyou-manual-people-v6",32);out.i32(6);out.i32(1);out.i32(baseVersion)
            out.i32(1);out.raw(byteArrayOf(42));out.i32(1);out.i32(0);out.string(name,512);out.i64(version);out.strings(names,12,160);out.bytes()
        }
        for(value in listOf(raw("wrong",1,listOf("小林")),raw(id,1,listOf("小林","小林")),raw(id,0,emptyList()),raw(id,1,listOf("小林"),6)))
            assertThrows(IllegalArgumentException::class.java){RecordPeoplePackageCodec.parse(value){block,version->
                assertArrayEquals(byteArrayOf(42),block);assertEquals(2,version);base
            }}
    }

    private class PeopleMediaFixture(withPeople:Boolean=true):AutoCloseable {
        val base=PermanentErasureFixture(ManualPeopleFixture.KEY)
        var people:SqlManualPeopleRepository?=null;private set
        lateinit var originals:SqlOriginalExportRepository;private set
        lateinit var packages:SqlRecordPackageRepository;private set
        lateinit var media:SqlRecordMediaPackageRepository;private set
        val dates get()=SqlRecordEventDateRepository(base.gateway)
        init {base.db.transaction{sql->
            (RecordFavoriteSqlSchema.migration8To9+LocalCandidateSqlSchema.migration9To10+RecordEventDateSqlSchema.migration10To11).forEach{sql.execute(it)}
            if(withPeople)ManualPeopleSqlSchema.migration11To12.forEach{sql.execute(it)}
            sql.execute("PRAGMA user_version="+if(withPeople)"12"else"11")
        };ports(withPeople)}
        private fun ports(withPeople:Boolean){
            people=if(withPeople)SqlManualPeopleRepository(base.gateway,ManualPeopleFixture.KEY)else null
            originals=SqlOriginalExportRepository(base.gateway,base.tags,people=people)
            packages=SqlRecordPackageRepository(base.gateway,base.tags,people=people)
            media=SqlRecordMediaPackageRepository(base.gateway,originals,base.media,packages)
        }
        fun seed():String {val id=uuid();base.db.transaction{sql->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(id))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(id,TEXT,OriginalExports.hash(TEXT)))
            sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
            sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')",listOf("INDEX:"+id,id))
        };return id}
        fun revise(id:String)=base.db.transaction{sql->
            sql.execute("INSERT INTO entry_revisions VALUES(?,2,?,?,17)",listOf(id,TEXT2,OriginalExports.hash(TEXT2)))
            sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?",listOf(id));Unit
        }
        suspend fun export(preview:RecordMediaPreview):ByteArray {val p=media.seal(preview,password());return try{ByteArrayOutputStream().use{out->p.writeTo(out){true};out.toByteArray()}}finally{p.release()}}
        suspend fun restore(bytes:ByteArray,id:String):RecordPackageRestoreResult {val p=media.open({bytes.inputStream()},password());return try{media.restore(p,listOf(id),100)}finally{p.release()}}
        fun reopen(){val withPeople=people!=null;people?.wipeKey();base.reopen();ports(withPeople)}
        override fun close(){people?.wipeKey();base.close()}
    }
    companion object {
        private const val TEXT="合成初版原文🙂"
        private const val TEXT2="合成第二版 needle 原文"
        private val DATE=RecordEventDate("2024-02-29","America/New_York")
        private fun uuid()=UUID.randomUUID().toString()
        private fun password()="synthetic manual people package 42".toCharArray()
    }
}
