package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Actual shared SQL and real SQLite owners; synthetic media only, not Room/native-device execution. */
class ReadyImageEpochSqlTest {
    @Test fun sameIdReplaceAndUpsertCannotChangeIntentOrOriginalCiphertext():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成图片依据");val image=h.f.image(id);val row=h.f.db.read("SELECT * FROM attachments WHERE id=?",image.id)
        val file=h.f.mediaRoot.resolve(image.id+".aead");val bytes=file.readBytes();val stamp=h.originals.epoch()
        assertEquals(0L,h.f.db.read("PRAGMA recursive_triggers").single().single())
        for(statement in listOf(
            "INSERT OR REPLACE INTO attachments VALUES(?,?,1,'audio/wav',?,80,'READY')",
            "INSERT INTO attachments VALUES(?,?,1,'audio/wav',?,80,'READY') ON CONFLICT(id) DO UPDATE SET mediaType=excluded.mediaType,sha256=excluded.sha256,byteSize=excluded.byteSize",
            "INSERT OR REPLACE INTO attachments VALUES(?,?,1,'image/png',?,68,'READY')")){
            assertThrows(SqliteBridgeFailure::class.java){h.f.db.write(statement,image.id,id,"1".repeat(64))}
            assertEquals(row,h.f.db.read("SELECT * FROM attachments WHERE id=?",image.id));assertEquals(stamp,h.originals.epoch());assertArrayEquals(bytes,file.readBytes())
        }
        assertArrayEquals(FullBackupTest.PNG,h.f.media.readReadOnly(image.id))
    }}
    @Test fun independentImageAbaAdvancesStampWhileSourceFtsDocumentsAndJobsStayUnchanged():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成图片依据");while(h.originals.batch().remaining){}
        val original=h.f.db.read("SELECT * FROM entries");val revisions=h.f.db.read("SELECT * FROM entry_revisions")
        val fts=h.f.db.read("SELECT * FROM local_search_fts");val docs=h.f.db.read("SELECT * FROM local_search_documents");val jobs=h.f.db.read("SELECT * FROM local_jobs");val stamp=h.originals.epoch()
        PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{peer->peer.transaction{sql->
            sql.execute("INSERT INTO attachments VALUES('aba',?,1,'image/png',?,68,'WRITING')",listOf(id,"0".repeat(64)))
            sql.execute("UPDATE attachments SET state='STAGED' WHERE id='aba'");sql.execute("UPDATE attachments SET state='READY' WHERE id='aba'");sql.execute("DELETE FROM attachments WHERE id='aba'")
        }}
        assertEquals(stamp+4,h.originals.epoch());assertTrue(h.f.db.read("SELECT * FROM attachments").isEmpty())
        assertEquals(original,h.f.db.read("SELECT * FROM entries"));assertEquals(revisions,h.f.db.read("SELECT * FROM entry_revisions"));assertEquals(fts,h.f.db.read("SELECT * FROM local_search_fts"));assertEquals(docs,h.f.db.read("SELECT * FROM local_search_documents"));assertEquals(jobs,h.f.db.read("SELECT * FROM local_jobs"));assertFalse(h.originals.batch().remaining)
    }}
    @Test fun imageEpochRollsBackAndAudioOnlyOrNoopDoesNotAdvanceIt():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry();val image=h.f.image(id);val before=h.originals.epoch();val row=h.f.db.read("SELECT * FROM attachments")
        assertThrows(IllegalStateException::class.java){h.f.gateway.transaction{sql->sql.execute("DELETE FROM attachments WHERE id=?",listOf(image.id));error("Synthetic rollback")}}
        assertEquals(before,h.originals.epoch());assertEquals(row,h.f.db.read("SELECT * FROM attachments"))
        h.f.db.write("UPDATE attachments SET state=state WHERE id=?",image.id);assertEquals(before,h.originals.epoch())
        h.f.gateway.transaction{sql->sql.execute("INSERT INTO attachments VALUES('audio-only',?,1,'audio/wav',?,68,'WRITING')",listOf(id,"0".repeat(64)));sql.execute("UPDATE attachments SET state='STAGED' WHERE id='audio-only'");sql.execute("UPDATE attachments SET state='READY' WHERE id='audio-only'");sql.execute("DELETE FROM attachments WHERE id='audio-only'")}
        assertEquals(before,h.originals.epoch())
    }}
    @Test fun overflowRejectsNewImageBeforeOpeningInputAndMissingIndexIsNotASavePrerequisite():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry();val old=h.f.image(id);val file=h.f.mediaRoot.resolve(old.id+".aead");val bytes=file.readBytes()
        h.f.db.write("DROP TRIGGER local_search_epoch_guard");h.f.db.write("UPDATE local_search_epoch SET epoch=?",Long.MAX_VALUE)
        h.f.gateway.transaction{sql->LocalSearchSqlSchema.guards.forEach{sql.execute(it)}}
        var calls=0;val data=FullBackupTest.PNG;val intent=AttachmentIntent(FullBackupTest.uuid(),id,1,"image/png",FullBackupTest.hash(data),data.size.toLong())
        assertThrows(Exception::class.java){runBlocking{h.f.media.save(intent){calls++;data.inputStream()}}}
        assertEquals(0,calls);assertTrue(h.f.db.read("SELECT id FROM attachments WHERE id=?",intent.id).isEmpty());assertArrayEquals(bytes,file.readBytes())
        h.f.db.write("DELETE FROM local_search_epoch")
        assertEquals(AttachmentState.READY,h.f.media.save(intent){data.inputStream()}.state)
        assertArrayEquals(data,h.f.media.readReadOnly(intent.id));assertTrue(h.f.db.read("SELECT * FROM local_search_epoch").isEmpty())
    }}
    @Test fun recoveryNotifiesBeforeActualImageMutationButNotForStableReadOrAudio():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry();val image=h.f.image(id);var calls=0
        h.f.media.recover{calls++};assertEquals(0,calls)
        val file=h.f.mediaRoot.resolve(image.id+".aead");val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        h.f.media.recover{assertEquals("READY",h.f.db.read("SELECT state FROM attachments WHERE id=?",image.id).single().single());calls++}
        assertEquals(1,calls);assertEquals("CORRUPT",h.f.db.read("SELECT state FROM attachments WHERE id=?",image.id).single().single());assertArrayEquals(bytes,file.readBytes())
        val audioId=FullBackupTest.uuid();h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'audio/wav',?,68,'WRITING')",audioId,id,"0".repeat(64))
        h.f.media.recover{calls++};assertEquals(1,calls);assertEquals("FAILED",h.f.db.read("SELECT state FROM attachments WHERE id=?",audioId).single().single())
    }}
    @Test fun existingV15ColdOwnerInstallsSameGuardsWithoutChangingTablesOrOriginals(){
        val root=Files.createTempDirectory("aiyou-image-old15-");val bridge=root.resolve("sqlite_bridge.py");javaClass.getResourceAsStream("/sqlite_bridge.py").use{Files.copy(checkNotNull(it),bridge)}
        val asset=PythonSqliteDatabase.repositoryRoot().resolve("core/data/src/main/assets/reminder-schema-v15.json")
        val db=root.resolve("old.sqlite");var original:List<List<Any?>>;var tables:List<List<Any?>>
        PythonSqliteDatabase(db,bridge,true,15,asset).use{owner->
            assertEquals(3,owner.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'unified_ready_image_epoch_%'").size)
            owner.write("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            owner.transaction{sql->sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('synthetic-source',1,5,'UTC','LOCAL_ONLY')");sql.execute("INSERT INTO entry_revisions VALUES('synthetic-source',1,?,?,5)",listOf("合成旧库",OriginalExports.hash("合成旧库")))}
            original=owner.read("SELECT * FROM entries");tables=owner.read("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name")
            owner.transaction{sql->listOf("unified_ready_image_epoch_insert","unified_ready_image_epoch_update","unified_ready_image_epoch_delete","immutable_attachment_identity").forEach{sql.execute("DROP TRIGGER "+it)}}
        }
        PythonSqliteDatabase(db,bridge,false,15,asset).use{owner->
            assertEquals(original,owner.read("SELECT * FROM entries"));assertEquals(tables,owner.read("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name"))
            assertEquals(15L,owner.read("PRAGMA user_version").single().single())
            assertEquals(3,owner.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'unified_ready_image_epoch_%'").size)
            assertEquals(1,owner.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name='immutable_attachment_identity'").size)
            owner.write("INSERT INTO attachments VALUES('cold-image','synthetic-source',1,'image/jpeg',?,68,'READY')","0".repeat(64));assertEquals(1L,owner.read("SELECT epoch FROM local_search_epoch").single().single())
        }
    }
    @Test fun sixToSevenSharedMigrationSqlInstallsImageEpochAndRetainsLegacyBodies(){
        val root=Files.createTempDirectory("aiyou-image-v6-");val bridge=root.resolve("sqlite_bridge.py");javaClass.getResourceAsStream("/sqlite_bridge.py").use{Files.copy(checkNotNull(it),bridge)}
        try{PythonSqliteDatabase(root.resolve("v6.sqlite"),bridge,true,6).use{db->
            db.transaction{sql->sql.execute("INSERT INTO entries VALUES('legacy',1,5,'UTC','LOCAL_ONLY')");sql.execute("INSERT INTO entry_revisions VALUES('legacy',1,?,?,5)",listOf("合成旧正文",OriginalExports.hash("合成旧正文")))}
            val before=db.read("SELECT * FROM entry_revisions")
            db.transaction{sql->LocalSearchSqlSchema.migration6To7.forEach{sql.execute(it)};AttachmentIntentSqlSchema.guards.forEach{sql.execute(it)};LocalSearchSqlSchema.guards.forEach{sql.execute(it)};sql.execute("PRAGMA user_version=7")}
            db.write("INSERT INTO attachments VALUES('migrated-image','legacy',1,'image/png',?,68,'READY')","0".repeat(64))
            assertEquals(before,db.read("SELECT * FROM entry_revisions"));assertEquals(7L,db.read("PRAGMA user_version").single().single())
            assertEquals(1L,db.read("SELECT epoch FROM local_search_epoch").single().single())
            assertEquals(3,db.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'unified_ready_image_epoch_%'").size)
        }}finally{Files.walk(root).use{it.sorted(Comparator.reverseOrder()).forEach(Files::delete)}}
    }
}
