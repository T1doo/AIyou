package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Actual checked-in Room13 predecessor and product14 SQL/AEAD cells; host SQLite, not native SQLCipher. */
class ModelCapabilitySqlTest {
    @Test fun actualThirteenMigrationColdProfilesKeepCompleteBindingAndOriginalConnectionCiphertextUnchanged() {
        CapabilitySqlFixture().use { f ->
            assertEquals(13L,f.db.read("PRAGMA user_version").single().single())
            val configs=ConnectionProtocol.entries.map {f.connection(it.name,it)}
            val before=configs.associate {it.id to f.configBytes(it.id)}
            f.migrate();assertTrue(f.profiles.profiles().isEmpty())
            val saved=configs.mapIndexed { i,c -> val t=f.profiles.begin(c,"probe-$i",100)
                assertTrue(f.profiles.admit(t));assertFalse(f.profiles.admit(t))
                f.profiles.finish(t,if(i==0)supported() else unsupported()) }
            assertEquals(configs.sortedBy {it.id},f.catalog.configurations());configs.forEach {assertArrayEquals(before.getValue(it.id),f.configBytes(it.id))}
            f.reopen();assertEquals(saved.sortedBy {it.connection.id},f.profiles.profiles())
            assertEquals(listOf("合成原文","LOCAL_ONLY"),f.db.read("SELECT r.originalText,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id").single())
            assertEquals(listOf("合成独立草稿",7L),f.db.read("SELECT text,version FROM capture_draft").single())
            for(table in listOf("remote_source_access","remote_text_jobs","text_annotations","local_jobs"))assertTrue(f.db.read("SELECT * FROM $table").isEmpty())
            assertTrue(f.db.read("PRAGMA foreign_key_check").isEmpty())
        }
    }
    @Test fun pendingAndAdmittedColdRecoveryCannotReuseAdmissionAndExactLostResultAckIsReadOnly() {
        CapabilitySqlFixture().use { f ->
            val c=f.connection();f.migrate();val pending=f.profiles.begin(c,"pending",100)
            f.reopen();assertEquals(CapabilityState.UNKNOWN,f.profiles.profiles().single().observation.state)
            assertFalse(f.profiles.current(pending)) // A closed process owner cannot revive a ticket.
            val recovered=f.profiles.begin(c,"pending",100);assertTrue(f.profiles.admit(recovered))
            f.reopen();val admitted=f.profiles.begin(c,"pending",100);assertFalse(f.profiles.admit(admitted))
            assertEquals(CapabilityState.UNKNOWN,f.profiles.profiles().single().observation.state)
            var written=false
            f.db.afterSql={sql ->if(sql.startsWith("UPDATE model_capability_profiles SET phase='FINISHED'"))written=true
                if(sql=="COMMIT"&&written){written=false;throw IOException("Synthetic finished ACK loss")} }
            assertThrows(ModelConnectionStorageFailure::class.java){f.profiles.finish(admitted,supported())}
            f.db.afterSql=null;val bytes=f.profileBytes();f.reopen()
            val completed=f.profiles.begin(c,"pending",100)
            f.db.write("CREATE TEMP TRIGGER synthetic_forbid_profile_write BEFORE UPDATE ON model_capability_profiles BEGIN SELECT RAISE(ABORT,'must read only'); END")
            assertEquals(supported(),f.profiles.confirm(completed,supported())!!.observation)
            assertEquals(supported(),f.profiles.finish(completed,supported()).observation)
            assertArrayEquals(bytes,f.profileBytes());assertFalse(f.profiles.admit(completed))
            assertThrows(ModelConnectionConflict::class.java){f.profiles.finish(completed,unsupported())}
        }
    }
    @Test fun newestGenerationCancelsOldWatchAndConcurrentDuplicateAdmissionHasExactlyOneWinner() {
        CapabilitySqlFixture().use { f ->
            val c=f.connection();f.migrate();val old=f.profiles.begin(c,"old",100);assertTrue(f.profiles.admit(old))
            val retired=CountDownLatch(1);val watch=f.profiles.watch(old){retired.countDown()}
            try {
                val next=f.profiles.begin(c,"new",100);assertEquals(2L,next.profile.generation)
                assertTrue(retired.await(3,TimeUnit.SECONDS));assertFalse(f.profiles.current(old))
                assertThrows(ModelConnectionConflict::class.java){f.profiles.finish(old,supported())}
                val winners=AtomicInteger();val start=CountDownLatch(1);val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
                val threads=List(2){thread(name="synthetic-profile-admit") {try {start.await();if(f.profiles.admit(next))winners.incrementAndGet()} catch(t:Throwable){errors.add(t)}}}
                start.countDown();threads.forEach {it.join(3000);assertFalse(it.isAlive)};assertTrue(errors.isEmpty());assertEquals(1,winners.get())
                assertEquals(CapabilityState.UNKNOWN,f.profiles.profiles().single().observation.state)
            } finally {watch.close()}
        }
    }
    @Test fun allConnectionEditsKeyReplacementRevokeAndDeleteInvalidateProfilesAtomicallyAndRejectLateResults() {
        for(stage in 0..7) CapabilitySqlFixture().use { f ->
            val c=f.connection();f.migrate();val t=f.profiles.begin(c,"old",100);assertTrue(f.profiles.admit(t));f.profiles.finish(t,supported())
            when(stage) {
                in 0..4 -> {
                    val d=c.details
                    val next=ModelConnectionDetails(if(stage==0)"合成改名" else d.name,
                        if(stage==1)"https://other.example.test/prefix/" else d.apiRoot,
                        if(stage==2)ConnectionProtocol.ANTHROPIC_MESSAGES else d.protocol,
                        if(stage==3)"synthetic-other-model" else d.modelId,
                        if(stage==4)setOf(ModelConfigurationPurpose.VISION) else d.purposes)
                    f.catalog.change(ModelConnectionChange(c.id,c.version,"edit",next))
                }
                5 -> f.catalog.apply(f.catalog.prepare(c,"replace",ConnectionSecretVaultTest.material()))
                6 -> f.catalog.revoke(c,"revoke")
                7 -> f.catalog.change(ModelConnectionChange(c.id,c.version,"delete",null))
            }
            assertTrue(f.profiles.profiles().isEmpty());assertTrue(f.db.read("SELECT * FROM model_capability_profiles").isEmpty())
            assertFalse(f.profiles.current(t));assertFalse(f.profiles.admit(t))
            assertThrows(ModelConnectionConflict::class.java){f.profiles.finish(t,supported())}
            assertThrows(ModelConnectionConflict::class.java){f.profiles.begin(c,"stale",100)}
        }
        CapabilitySqlFixture().use { f ->
            val c=f.connection();f.migrate();val t=f.profiles.begin(c,"stable",100);assertTrue(f.profiles.admit(t));val p=f.profiles.finish(t,supported())
            f.db.write("CREATE TEMP TRIGGER synthetic_connection_abort BEFORE UPDATE ON model_configurations BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
            assertThrows(ModelConnectionStorageFailure::class.java){f.catalog.revoke(c,"abort")}
            assertEquals(listOf(p),f.profiles.profiles());assertEquals(listOf(c),f.catalog.configurations())
        }
    }
    @Test fun profileWriteAbortAndCorruptArchivePreserveBytesAndCannotDisableTheSeparateCredentialRevoke() {
        CapabilitySqlFixture().use { f ->
            val c=f.connection();f.migrate();val t=f.profiles.begin(c,"probe",100);val bytes=f.profileBytes()
            assertThrows(ModelConnectionConflict::class.java){f.profiles.finish(t,supported())}
            f.db.write("CREATE TEMP TRIGGER synthetic_profile_abort BEFORE UPDATE ON model_capability_profiles BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
            assertThrows(ModelConnectionStorageFailure::class.java){f.profiles.admit(t)};assertArrayEquals(bytes,f.profileBytes())
            f.db.write("DROP TRIGGER synthetic_profile_abort");assertTrue(f.profiles.admit(t))
            val admitted=f.profileBytes()
            f.db.write("CREATE TEMP TRIGGER synthetic_finish_abort BEFORE UPDATE ON model_capability_profiles BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
            assertThrows(ModelConnectionStorageFailure::class.java){f.profiles.finish(t,supported())}
            assertNull(f.profiles.confirm(t,supported()));assertArrayEquals(admitted,f.profileBytes())
            f.db.write("DROP TRIGGER synthetic_finish_abort")
            f.db.write("UPDATE model_capability_profiles SET generation=2,probeId='corrupt',phase='PENDING',payload=?",byteArrayOf(0,0,0,99))
            val corrupted=f.profileBytes();assertThrows(ModelConnectionStorageFailure::class.java){f.profiles.profiles()}
            assertThrows(ModelConnectionStorageFailure::class.java){f.profiles.begin(c,"cannot-overwrite-corrupt",100)}
            assertArrayEquals(corrupted,f.profileBytes());assertEquals(listOf(c),f.catalog.configurations())
            f.catalog.revoke(c,"revoke-despite-derived-corruption");assertTrue(f.db.read("SELECT * FROM model_capability_profiles").isEmpty())
            assertNull(f.catalog.configurations().single().secretRef)
        }
    }
    @Test fun forgedOwnerSnapshotOrUnauthenticatedKeyCannotAdmitOrPersistSupport() {
        CapabilitySqlFixture().use { f ->
            val c=f.connection();f.migrate();val t=f.profiles.begin(c,"probe",100)
            val forged=object:ModelCapabilityProbeTicket {override val profile=t.profile}
            assertFalse(f.profiles.current(forged));assertThrows(ModelConnectionConflict::class.java){f.profiles.admit(forged)}
            assertThrows(ModelConnectionConflict::class.java){f.profiles.finish(forged,supported())}
            assertThrows(ModelConnectionConflict::class.java){f.profiles.begin(c.copy(secretRef=SecretReference("synthetic-other-ref")),"foreign",100)}
            assertThrows(ModelConnectionConflict::class.java){f.profiles.begin(c.copy(version=3),"foreign",100)}
            val wrong=SqlModelCapabilityRepository(f.db,SqlModelConnectionCatalog(f.db,ByteArray(32){7}))
            assertThrows(ModelConnectionStorageFailure::class.java){wrong.begin(c,"wrong-key",100)}
            assertEquals(CapabilityState.UNKNOWN,f.profiles.profiles().single().observation.state)
        }
    }
    companion object {
        fun supported()=TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,101)
        fun unsupported()=TextCapabilityObservation(CapabilityState.UNSUPPORTED,CapabilityProbeReason.TEXT_ENDPOINT_UNAVAILABLE,101)
    }
}

internal class CapabilitySqlFixture:AutoCloseable {
    private val root=Files.createTempDirectory("aiyou-capability-synthetic-")
    private val bridge=root.resolve("sqlite_bridge.py");private val file=root.resolve("records.sqlite")
    var db:PythonSqliteDatabase;private set
    var catalog:SqlModelConnectionCatalog;private set
    var profiles:SqlModelCapabilityRepository;private set
    init {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use {Files.copy(checkNotNull(it),bridge)}
        db=PythonSqliteDatabase(file,bridge,true,13)
        db.transaction {sql ->
            ModelConnectionSqlSchema.guards.forEach {sql.execute(it)}
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('original',1,10,'UTC','LOCAL_ONLY')")
            sql.execute("INSERT INTO entry_revisions VALUES('original',1,'合成原文',?,10)",listOf(OriginalExports.hash("合成原文")))
            sql.execute("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
        }
        catalog=SqlModelConnectionCatalog(db,ConnectionSecretVaultTest.key());profiles=SqlModelCapabilityRepository(db,catalog)
    }
    fun migrate(){db.transaction {sql->ModelCapabilitySqlSchema.migration13To14.forEach {sql.execute(it)};sql.execute("PRAGMA user_version=14")}}
    fun connection(id:String="config",protocol:ConnectionProtocol=ConnectionProtocol.CHAT_COMPLETIONS):ModelConnectionConfiguration {
        val c=catalog.change(ModelConnectionCatalogTest.change(id,0,"create-$id",ModelConnectionCatalogTest.details(protocol)))!!
        return catalog.apply(catalog.prepare(c,"key-$id",ConnectionSecretVaultTest.material()))
    }
    fun configBytes(id:String)=db.read("SELECT payload FROM model_configurations WHERE id=?",id).single().single() as ByteArray
    fun profileBytes()=db.read("SELECT payload FROM model_capability_profiles").single().single() as ByteArray
    fun reopen(){catalog.close();db.close();db=PythonSqliteDatabase(file,bridge,false,13)
        catalog=SqlModelConnectionCatalog(db,ConnectionSecretVaultTest.key());profiles=SqlModelCapabilityRepository(db,catalog)}
    override fun close(){catalog.close();db.close();root.toFile().deleteRecursively()}
}
