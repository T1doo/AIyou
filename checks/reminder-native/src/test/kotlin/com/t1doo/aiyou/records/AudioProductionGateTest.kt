package com.t1doo.aiyou.records

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual production capability DI, Room on the controlled JVM SQLite, JCA files; not native SQLCipher/device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class AudioProductionGateTest {
    @Test fun oneProductionOwnerPinGuardsEverySourceWriterBeforeSqlFilesOrInputOpen():Unit=runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val repo=f.repo;val capture=repo.captureRecords
            val base=capture.current();val wave=LocalPcmAudio.encode(shortArrayOf(0x1234,-2,1000,-1000))
            val gate=checkNotNull(capture.mutationGate);val reservation=checkNotNull(gate.freeze(base.id));gate.reserve(reservation,base,base.text,0)
            gate.bindIntent(reservation,AttachmentIntent(uuid(),base.id,1,"audio/wav",hash(wave),wave.size.toLong()))
            val ready=capture.addAudio(reservation,wave);gate.finish(reservation,ready);capture.save(ready,100,"UTC")
            val source=SourceVersion(base.id,1);val policy=repo.localProcessing.read(source)
            val auth=repo.recordAuthorizations.readAuthorization(source)
            val draft=OriginalEditDraft(uuid(),base.id,1,1,1,"","合成待提交编辑")
            repo.editDrafts.write(null,draft)
            val rows=repo.attachments.list(base.id,1);assertEquals(1,rows.size)
            val pin=checkNotNull(gate.preparePlayback());gate.activatePlayback(pin)
            val before=f.sqlSnapshot();val beforeFiles=f.filesSnapshot();val opened=AtomicInteger()
            val neverRead=object:VerifiedRecordPackage {
                override val manifest:RecordPackageManifest get()=error("must not read unadmitted package")
                override val records:List<RecordPackageOriginal> get()=error("must not read unadmitted package")
                override val digest:String get()=error("must not read unadmitted package")
            }
            val neverFull=object:VerifiedFullBackup {
                override val summary:FullBackupSummary get()=error("must not read unadmitted backup")
                override val digest:String get()=error("must not read unadmitted backup")
                override fun release() {}
            }
            val attempts=listOf<suspend()->Unit>(
                { repo.updateDraft("冻结后输入");Unit }, { repo.revise(base.id,1,"冻结后原文",101);Unit },
                { repo.reviseFromDraft(draft,101);Unit },
                { repo.attachments.save(AttachmentIntent(uuid(),base.id,1,"audio/wav",hash(wave),wave.size.toLong())) { opened.incrementAndGet();wave.inputStream() };Unit },
                { repo.attachments.recover() }, { repo.prepare() }, { capture.recover() },
                { repo.recycleBin.apply(RecycleMutation(source,policy.generation,false,uuid()));Unit },
                { repo.permanentErasure.erase(PermanentErasureIntent(source,policy.generation,uuid()));Unit },
                { repo.localProcessing.change(LocalProcessingChange(policy,LocalProcessingChoice.AI_NONE,uuid()));Unit },
                { repo.recordAuthorizations.changeAuthorization(RecordAuthorizationChange.withdraw(auth,ProcessingPolicy.AI_NONE,uuid())) { Instant.ofEpochMilli(101) };Unit },
                { repo.sourceBoundText.updateAuthorization(source,policy.generation,ProcessingPolicy.AI_NONE,null,null);Unit },
                { repo.sourceBoundText.tombstone(source,policy.generation);Unit },
                { repo.recordPackages.restore(neverRead,listOf(uuid()),101);Unit },
                { repo.mediaPackages.restore(neverRead,listOf(uuid()),101);Unit },
                { repo.fullBackups.restore(neverFull) { true };Unit }, { repo.mediaPackages.recover() },
            )
            try {
                attempts.forEach { action ->
                    assertThrows(CaptureConflict::class.java) { runBlocking { action() } }
                    assertEquals(before,f.sqlSnapshot());assertEquals(beforeFiles,f.filesSnapshot())
                }
                assertEquals(0,opened.get())
                assertArrayEquals(wave,repo.attachments.read(rows.single().intent.id))
                assertEquals(rows,repo.attachments.list(base.id,1));assertEquals(before,f.sqlSnapshot())
            } finally { gate.finishPlayback(pin) }
            assertEquals(2,repo.reviseFromDraft(draft,101).currentRevision)
        }
    }
    @Test fun strictOwnerListDoesNotRecoverUnrelatedWritingAndCorruptReadDoesNotMarkUnderPin():Unit=runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val repo=f.repo;val draft=repo.updateDraft("合成原文");val entry=repo.saveDraft(draft,1,"UTC")
            val wave=LocalPcmAudio.encode(shortArrayOf(1,2));val intent=AttachmentIntent(uuid(),entry.id,1,"audio/wav",hash(wave),wave.size.toLong())
            repo.attachments.save(intent) { wave.inputStream() }
            val writing=AttachmentIntent(uuid(),entry.id,1,"audio/wav",hash(wave),wave.size.toLong())
            f.insertInterrupted(writing)
            val path=f.mediaDir.resolve(intent.id+".aead");val cipher=path.readBytes();cipher[cipher.lastIndex]=(cipher.last().toInt() xor 1).toByte();path.writeBytes(cipher)
            val gate=checkNotNull(repo.captureRecords.mutationGate);val pin=checkNotNull(gate.preparePlayback());gate.activatePlayback(pin)
            val before=f.sqlSnapshot()
            try {
                assertThrows(AttachmentIntegrityFailure::class.java) { runBlocking { repo.attachments.read(intent.id) } }
                val states=repo.attachments.list(entry.id,1).associate { it.intent.id to it.state }
                assertEquals(AttachmentState.READY,states[intent.id]);assertEquals(AttachmentState.WRITING,states[writing.id])
                assertEquals(before,f.sqlSnapshot())
            } finally { gate.finishPlayback(pin) }
            repo.attachments.recover()
            val recovered=repo.attachments.list(entry.id,1).associate { it.intent.id to it.state }
            assertEquals(AttachmentState.CORRUPT,recovered[intent.id]);assertEquals(AttachmentState.FAILED,recovered[writing.id])
        }
    }
    private class Fixture:AutoCloseable {
        val root=Files.createTempDirectory("aiyou-r1-synthetic-room-").toFile()
        val mediaDir=root.resolve("media")
        private val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),RecordsDatabase::class.java)
            .allowMainThreadQueries().addCallback(object:RoomDatabase.Callback() {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    installRevisionGuard(db);installAttachmentGuard(db);installRemoteTextGuards(db)
                    installManualTagGuard(db);installManualTaskGuard(db);installLocalSearchGuards(db);installPermanentErasureGuards(db)
                    installRecordFavoriteGuards(db);installLocalCandidateGuards(db);installRecordEventDateGuards(db)
                    installManualPeopleGuards(db);installModelConnectionGuards(db);installModelCapabilityGuards(db)
                    db.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
                }
            }).build()
        val repo:EncryptedEntryRepository
        init {
            val sql=DrainingRecordsSqlDatabase(RoomRecordsSqlDatabase(db));val hooks=SqlErasureFileHooks(sql)
            val media=EncryptedAttachmentStore(mediaDir,RoomAttachmentLedger(db),ByteArray(32){(it+7).toByte()}).also { it.installErasureHooks(hooks) }
            val edits=EncryptedOriginalEditStore(root.resolve("edits"),ByteArray(32){(it+9).toByte()}).also { it.installErasureHooks(hooks) }
            repo=EncryptedEntryRepository(db,ByteArray(32){(it+3).toByte()},media,edits,sql)
        }
        fun sqlSnapshot():List<List<String?>> {
            val sql=db.openHelper.writableDatabase
            val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
            return tables.flatMap { table -> sql.query("SELECT * FROM \"$table\"").use { c -> buildList { while(c.moveToNext())add(listOf(table)+(0 until c.columnCount).map { i -> if(c.isNull(i))null else if(c.getType(i)==android.database.Cursor.FIELD_TYPE_BLOB)hash(c.getBlob(i))else c.getString(i) }) } } }
        }
        fun insertInterrupted(intent:AttachmentIntent) {
            db.openHelper.writableDatabase.execSQL("INSERT INTO attachments VALUES(?,?,?,?,?,?,'WRITING')",
                arrayOf(intent.id,intent.entryId,intent.revision,intent.mediaType,intent.sha256,intent.byteSize))
        }
        fun filesSnapshot()=root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to hash(it.readBytes()) }
        override fun close() { repo.closeAndAwait();root.deleteRecursively() }
    }
    companion object {
        private fun uuid()=UUID.randomUUID().toString()
        private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
