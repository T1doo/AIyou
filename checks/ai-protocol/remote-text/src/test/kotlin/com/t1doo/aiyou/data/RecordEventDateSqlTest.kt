package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Actual checked-in v10 export, production migration/SQL and bounded host SQLite. No native-DB claim. */
class RecordEventDateSqlTest {
    @Test fun actualTenToElevenMigrationPreservesOriginalsCaptureDraftAndUnknownDates(): Unit = RecordEventDateFixture(false).use { f ->
        val id=f.entry();f.db.write("INSERT INTO capture_draft VALUES(1,'synthetic-draft',7,'合成草稿')")
        val before=f.originalState();val draft=f.db.read("SELECT * FROM capture_draft")
        assertEquals(10L,f.db.read("PRAGMA user_version").single().single())
        assertEquals(0L,f.dates.read(id).version);assertNull(f.dates.read(id).date)
        f.upgrade();assertEquals(11L,f.db.read("PRAGMA user_version").single().single())
        assertEquals(before,f.originalState());assertEquals(draft,f.db.read("SELECT * FROM capture_draft"))
        assertNull(f.dates.read(id).date);assertTrue(f.db.read("PRAGMA foreign_key_check").isEmpty())
        assertEquals(listOf(listOf("entries", "entryId", "id", "NO ACTION", "CASCADE")),
            f.db.read("PRAGMA foreign_key_list(entry_event_dates)").map { it.drop(2).take(5) })
    }

    @Test fun setCorrectClearColdReopenKeepsReceiptAndEveryOriginalByte(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val original=f.originalState()
        val first=f.intent(id,DATE);assertEquals(DATE,f.dates.set(first).date)
        f.reopen();assertEquals(DATE,f.dates.read(id).date);assertEquals(1L,f.dates.read(id).version)
        val corrected=RecordEventDate("2024-02-29","Pacific/Kiritimati")
        assertEquals(corrected,f.dates.set(f.intent(id,corrected)).date)
        val clear=f.intent(id,null);assertNull(f.dates.set(clear).date)
        f.reopen();assertNull(f.dates.read(id).date);assertEquals(3L,f.dates.read(id).version)
        assertEquals(3L,f.dates.set(clear).version)
        assertEquals(original,f.originalState());assertEquals(1,f.db.read("SELECT entryId FROM entry_event_dates").size)
    }

    @Test fun originalAndMetadataConflictsNeverOverwriteWinningChoice(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val stale=f.intent(id,DATE);f.append(id)
        assertThrows(RecordEventDateConflict::class.java){f.dates.set(stale)}
        val first=f.intent(id,DATE);f.dates.set(first)
        val outdated=f.intent(id,null);val winning=f.intent(id,RecordEventDate("2001-01-01","Europe/Paris"))
        f.dates.set(winning);val before=f.metadata()
        assertThrows(RecordEventDateConflict::class.java){f.dates.set(outdated)}
        assertThrows(RecordEventDateConflict::class.java){f.dates.set(first)}
        assertEquals(before,f.metadata())
    }

    @Test fun lostCommitAckColdRetryAcknowledgesExactlyOnceAndNeverClobbersNewerDate(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val intent=f.intent(id,DATE);var writing=false;var lost=false
        f.db.afterSql={sql ->
            if(sql.startsWith("INSERT INTO entry_event_dates")) writing=true
            if(sql=="COMMIT" && writing && !lost){lost=true;error("Synthetic event date commit ACK lost")}
        }
        assertThrows(RecordEventDateStorageFailure::class.java){f.dates.set(intent)};assertTrue(lost)
        f.db.afterSql=null;f.reopen();f.append(id)
        assertEquals(1L,f.dates.set(intent).version)
        val clear=f.intent(id,null);f.dates.set(clear);val before=f.metadata()
        assertThrows(RecordEventDateConflict::class.java){f.dates.set(intent)};assertEquals(before,f.metadata())
    }

    @Test fun abortedWriteAndSourceHashMismatchPreserveEntireTransaction(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val before=f.originalState();val epoch=f.search.epoch()
        f.db.write("CREATE TEMP TRIGGER synthetic_date_abort BEFORE INSERT ON entry_event_dates BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(RecordEventDateStorageFailure::class.java){f.dates.set(f.intent(id,DATE))}
        assertTrue(f.metadata().isEmpty());assertEquals(before,f.originalState());assertEquals(epoch,f.search.epoch())
        f.db.write("DROP TRIGGER synthetic_date_abort")
        assertThrows(RecordEventDateConflict::class.java){f.dates.set(RecordEventDateMutation(id,1,"0".repeat(64),0,DATE,uuid()))}
        assertTrue(f.metadata().isEmpty())
    }

    @Test fun deletedAndPreparedErasureSourcesRemainUnavailableWithoutRemovingMetadata(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();f.dates.set(f.intent(id,DATE));val stale=f.intent(id,null);val before=f.metadata()
        f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
        assertThrows(RecordEventDateUnavailable::class.java){f.dates.read(id)}
        assertThrows(RecordEventDateUnavailable::class.java){f.dates.set(stale)};assertFalse(f.dates.snapshots().containsKey(id))
        f.db.write("INSERT INTO permanent_erasure_receipts VALUES(?,1,2,?,'PREPARED','','synthetic-proof')",id,uuid())
        assertThrows(RecordEventDateUnavailable::class.java){f.dates.set(stale)};assertEquals(before,f.metadata())
    }

    @Test fun legacyMissingTableReadsUnknownButVersionElevenOrUnknownSchemaFailsClosed(): Unit = RecordEventDateFixture(false).use { f ->
        val id=f.entry();assertNull(f.dates.read(id).date);assertEquals(0L,f.dates.snapshots().getValue(id).version)
        assertThrows(RecordEventDateStorageFailure::class.java){f.dates.set(f.intent(id,DATE))}
        for(version in listOf(0,11,12)) {
            f.db.write("PRAGMA user_version="+version)
            assertThrows(RecordEventDateStorageFailure::class.java){f.dates.read(id)}
            assertThrows(RecordEventDateStorageFailure::class.java){f.dates.snapshots()}
        }
    }

    @Test fun malformedDateZoneOrReceiptIsFailureRatherThanUnknownOrSearchMiss(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();f.dates.set(f.intent(id,DATE))
        f.db.write("DROP TRIGGER event_date_values_update");f.db.write("DROP TRIGGER event_date_version_guard")
        val clean=f.metadata().single()
        for(assignment in listOf("localDate='2023-02-29'","timeZone='+08:00'","timeZone=NULL","version=-1","lastSourceRevision=2","lastMutationId=''")) {
            f.db.write("UPDATE entry_event_dates SET "+assignment+" WHERE entryId=?",id)
            assertThrows(RecordEventDateStorageFailure::class.java){f.dates.read(id)}
            assertThrows(LocalSearchFailure::class.java){f.search.candidates(LocalSearchRequest("",dateBasis=RecordDateBasis.EVENT))}
            f.db.write("UPDATE entry_event_dates SET version=?,lastMutationId=?,lastSourceRevision=?,localDate=?,timeZone=? WHERE entryId=?",
                clean[1],clean[2],clean[3],clean[4],clean[5],id)
        }
    }

    @Test fun metadataLimitRefusesWholeResultAndValidatesBoundaryRows(): Unit = RecordEventDateFixture().use { f ->
        repeat(4){f.entry("synthetic-page-"+it)}
        val small=SqlRecordEventDateRepository(f.gateway,3)
        assertThrows(RecordEventDateTooBroad::class.java){small.snapshots()}
        f.dates.set(f.intent("synthetic-page-3",DATE))
        assertEquals(DATE,f.dates.snapshots().getValue("synthetic-page-3").date)
    }

    @Test fun eventDateChangesInvalidateSearchProofWithoutReindexingOrChangingAuthority(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();f.drain();val proof=f.search.candidates(LocalSearchRequest("needle"));val index=f.indexState()
        val original=f.originalState();val epoch=f.search.epoch()
        assertTrue(f.search.publishCurrent(proof){})
        assertTrue(f.search.resolve(proof,id){assertEquals(id,it.id)})
        f.dates.set(f.intent(id,DATE))
        assertEquals(epoch+1,f.search.epoch());assertEquals(index,f.indexState());assertEquals(original,f.originalState())
        assertFalse(f.search.publishCurrent(proof){fail("Stale search published")})
        assertFalse(f.search.resolve(proof,id){fail("Stale search opened")})
        val event=f.search.candidates(LocalSearchRequest("needle",dateBasis=RecordDateBasis.EVENT))
        assertEquals(DATE,event.eventDates!!.getValue(id).date)
        assertTrue(f.search.resolve(event,id){assertEquals(id,it.id)})
        f.dates.set(f.intent(id,null));assertFalse(f.search.resolve(event,id){fail("Cleared event opened")})
    }

    @Test fun explicitEventCalendarDayCombinesKeywordTagFavoriteAndNeverUsesCaptureOrZoneConversion(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val unknown=f.entry(text="合成 needle 未补记")
        f.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),uuid()));f.favorites.set(f.favorite(id,true))
        f.dates.set(f.intent(id,RecordEventDate("2000-01-01","Pacific/Kiritimati")))
        f.drain()
        val combined=LocalSearchRequest("needle",from="2000-01-01",through="2000-01-01",tag="合成标签",onlyFavorites=true,dateBasis=RecordDateBasis.EVENT)
        assertEquals(listOf(id),f.search.candidates(combined).entries.map{it.id})
        assertTrue(f.search.candidates(LocalSearchRequest("needle",from="1999-12-31",through="1999-12-31",dateBasis=RecordDateBasis.EVENT)).entries.isEmpty())
        assertEquals(setOf(id,unknown),f.search.candidates(LocalSearchRequest("needle",from="1970-01-01",through="1970-01-01")).entries.map{it.id}.toSet())
        assertEquals(listOf(id),f.search.candidates(LocalSearchRequest("needle",dateBasis=RecordDateBasis.EVENT)).entries.map{it.id})
        assertTrue(f.search.candidates(LocalSearchRequest("absent",dateBasis=RecordDateBasis.EVENT)).entries.isEmpty())
        f.favorites.set(f.favorite(id,false));assertTrue(f.search.candidates(combined).entries.isEmpty())
    }

    @Test fun setCorrectClearLeavesCapturedDayRecapMembershipAndExistingProofCurrent(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val recap=SqlFactRecapRepository(f.gateway,f.tags)
        val captured=FactRecapRange(LocalDate.of(1970,1,1),FactRecapPeriod.DAY,"UTC")
        val other=FactRecapRange(LocalDate.of(2000,1,1),FactRecapPeriod.DAY,"UTC")
        val before=f.originalState();val proof=recap.capture(captured)
        for(date in listOf(DATE,RecordEventDate("2000-01-01","UTC"),null)) {
            f.dates.set(f.intent(id,date))
            assertEquals(listOf(id),recap.capture(captured).cards.map{it.source.sourceId})
            assertTrue(recap.capture(other).cards.isEmpty())
            assertTrue(recap.publishCurrent(proof){})
            assertTrue(recap.resolve(proof,SourceVersion(id,1)){})
            assertEquals(before,f.originalState())
        }
    }

    @Test fun independentOwnerStaleIntentAndErasureNeverRecreateMetadata(): Unit = RecordEventDateFixture().use { f ->
        val id=f.entry();val stale=f.intent(id,null);val winner=f.intent(id,DATE)
        f.second().use { second ->
            val other=SqlRecordEventDateRepository(second)
            f.dates.set(winner)
            assertEquals(1L,other.set(winner).version)
            assertThrows(RecordEventDateConflict::class.java){other.set(stale)}
            f.db.transaction { sql ->
                sql.execute("DELETE FROM local_jobs WHERE entryId=?",listOf(id))
                sql.execute("DELETE FROM remote_source_access WHERE entryId=?",listOf(id))
                sql.execute("DELETE FROM entry_revisions WHERE entryId=?",listOf(id))
                sql.execute("DELETE FROM entries WHERE id=?",listOf(id))
            }
            assertTrue(f.metadata().isEmpty())
            assertThrows(RecordEventDateUnavailable::class.java){other.set(winner)}
            assertTrue(f.metadata().isEmpty())
        }
    }

    companion object {
        val DATE=RecordEventDate("2020-12-31","Asia/Shanghai")
        fun uuid()=UUID.randomUUID().toString()
    }
}

internal class RecordEventDateFixture(upgrade: Boolean = true):AutoCloseable {
    private val root=Files.createTempDirectory("aiyou-event-dates-")
    private val file=root.resolve("records.sqlite")
    private val bridge=root.resolve("sqlite_bridge.py")
    private val key=ByteArray(32){(it+59).toByte()}
    lateinit var db:PythonSqliteDatabase;private set
    lateinit var gateway:DrainingRecordsSqlDatabase;private set
    lateinit var tags:SqlManualTagRepository;private set
    lateinit var dates:SqlRecordEventDateRepository;private set
    lateinit var favorites:SqlRecordFavoriteRepository;private set
    lateinit var search:SqlLocalSearchRepository;private set
    init {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use{Files.copy(checkNotNull(it),bridge)}
        db=PythonSqliteDatabase(file,bridge,true,10)
        db.transaction { sql ->
            sql.execute("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            (RemoteTextSqlSchema.guards+LocalSearchSqlSchema.guards+PermanentErasureSqlSchema.guards+
                RecordFavoriteSqlSchema.guards+LocalCandidateSqlSchema.guards).forEach {sql.execute(it)}
            sql.execute("DROP TRIGGER IF EXISTS local_search_entries_update");sql.execute(RecordFavoriteSqlSchema.sourceIndexUpdate)
            sql.execute(ManualTagSqlSchema.guard)
        }
        adapters();if(upgrade) upgrade()
    }
    fun upgrade()=db.transaction { sql ->
        RecordEventDateSqlSchema.migration10To11.forEach{sql.execute(it)};sql.execute("PRAGMA user_version=11")
    }
    private fun adapters(){
        gateway=DrainingRecordsSqlDatabase(db);tags=SqlManualTagRepository(gateway,key)
        dates=SqlRecordEventDateRepository(gateway);favorites=SqlRecordFavoriteRepository(gateway)
        search=SqlLocalSearchRepository(gateway,tags)
    }
    fun reopen(){retire();db=PythonSqliteDatabase(file,bridge,false,10);adapters()}
    fun second()=PythonSqliteDatabase(file,bridge,false,10)
    fun entry(id:String=RecordEventDateSqlTest.uuid(),text:String="合成 needle 原文🙂"):String {
        db.transaction {sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(id))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(id,text,OriginalExports.hash(text)))
            sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
            sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')",listOf("INDEX:"+id,id))
        };return id
    }
    fun intent(id:String,date:RecordEventDate?):RecordEventDateMutation {
        val row=db.read("SELECT e.currentRevision,r.contentHash FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision WHERE e.id=?",id).single()
        return RecordEventDateMutation(id,(row[0] as Long).toInt(),row[1] as String,dates.read(id).version,date,RecordEventDateSqlTest.uuid())
    }
    fun favorite(id:String,value:Boolean):RecordFavoriteMutation {
        val i=intent(id,null);return RecordFavoriteMutation(id,i.sourceRevision,i.sourceHash,favorites.read(id).version,value,RecordEventDateSqlTest.uuid())
    }
    fun append(id:String){
        val next=(db.read("SELECT currentRevision FROM entries WHERE id=?",id).single().single() as Long)+1
        db.transaction {sql ->
            val text="合成 needle 改写";sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,6)",listOf(id,next,text,OriginalExports.hash(text)))
            sql.execute("UPDATE entries SET currentRevision=? WHERE id=?",listOf(next,id))
        }
    }
    fun drain(){repeat(10){if(!search.batch().remaining)return};error("Synthetic search did not finish")}
    fun metadata()=db.read("SELECT * FROM entry_event_dates ORDER BY entryId")
    fun originalState()=listOf("entries","entry_revisions","remote_source_access","capture_draft","local_jobs").map{db.read("SELECT * FROM "+it+" ORDER BY 1")}
    fun indexState()=listOf("local_search_fts","local_search_documents","local_jobs").map{db.read("SELECT * FROM "+it+" ORDER BY 1")}
    private fun retire(){db.afterSql=null;db.beforeSql=null;db.beforeCommit=null;gateway.seal();gateway.awaitIdle();tags.wipeKey();db.close()}
    override fun close(){try{retire()}finally{key.fill(0);Files.walk(root).use{it.sorted(Comparator.reverseOrder()).forEach(Files::delete)}}}
}
