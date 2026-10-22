package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Existing full/selected formats and real recovery journals, with the new shared SQL guards. */
class ReadyImageRestoreSqlTest {
    private suspend fun full(f:FullFixture):ByteArray {
        val snapshot=f.backup.capture(FullBackupTest.uuid(),100)
        try{val payload=f.backup.seal(snapshot,FullBackupTest.password());try{val out=ByteArrayOutputStream();payload.writeTo(out){true};return out.toByteArray()}finally{payload.release()}}finally{snapshot.release()}
    }
    @Test fun fullRestoreRollbackAndJournalReplayAdvanceEpochOnlyForCommittedOriginalImages():Unit=runBlocking{
        for(point in listOf("full-before-commit","full-committed"))FullFixture().use{source->FullFixture(99).use{target->
            val id=source.entry(text="合成淡茶");val image=source.image(id);val bytes=full(source);val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
            val search=SqlLocalSearchRepository(target.gateway,target.tags);val before=search.epoch();var fired=false
            target.checkpoint={if(it==point&&!fired){fired=true;error("Synthetic restore boundary")}}
            try{
                assertThrows(Exception::class.java){runBlocking{target.backup.restore(pack){true}}};assertTrue(fired)
                if(point=="full-before-commit"){assertEquals(before,search.epoch());assertTrue(target.db.read("SELECT * FROM attachments").isEmpty())}
                else{assertTrue(search.epoch()>before);assertEquals(1,target.db.read("SELECT * FROM attachments").size)}
                target.checkpoint={};val resumed=target.backup.restore(pack){true};assertEquals(point=="full-committed",resumed.replayed)
                val committed=search.epoch();assertTrue(committed>before);assertArrayEquals(FullBackupTest.PNG,target.media.readReadOnly(image.id))
                assertTrue(target.backup.restore(pack){true}.replayed);assertEquals(committed,search.epoch())
                assertEquals(3,target.db.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'unified_ready_image_epoch_%'").size)
                assertTrue(target.db.read("PRAGMA foreign_key_check").isEmpty())
            }finally{target.checkpoint={};pack.release()}
        }}
    }
    @Test fun selectedRestoreRecoveryUsesNewIdsPlainInsertsAndKeepsEpochOnDoneReplay():Unit=runBlocking{FullFixture().use{source->FullFixture(99).use{target->
        val id=source.entry(text="合成淡茶");source.image(id)
        val originals=SqlOriginalExportRepository(source.gateway,source.tags)
        val sourceMedia=SqlRecordMediaPackageRepository(source.gateway,originals,source.media,source.packages,edits=source.edits)
        val preview=sourceMedia.preview(originals.capture(listOf(com.t1doo.aiyou.model.SourceVersion(id,1)),FullBackupTest.uuid(),100));val payload=sourceMedia.seal(preview,FullBackupTest.password());val out=ByteArrayOutputStream()
        try{payload.writeTo(out){true}}finally{payload.release()}
        val targetMedia=SqlRecordMediaPackageRepository(target.gateway,SqlOriginalExportRepository(target.gateway,target.tags),target.media,target.packages,edits=target.edits)
        val pack=targetMedia.open({out.toByteArray().inputStream()},FullBackupTest.password());val next=FullBackupTest.uuid();val before=SqlLocalSearchRepository(target.gateway,target.tags).epoch()
        try{
            var failed=false
            target.db.afterSql={sql->if(sql.startsWith("INSERT INTO attachments(")&&!failed){failed=true;error("Synthetic selected final transaction rollback")}}
            try{assertThrows(Exception::class.java){runBlocking{targetMedia.restore(pack,listOf(next),100)}}}finally{target.db.afterSql=null}
            assertTrue(failed);assertEquals(before,SqlLocalSearchRepository(target.gateway,target.tags).epoch());assertTrue(target.db.read("SELECT * FROM attachments").isEmpty())
            targetMedia.recover();targetMedia.restore(pack,listOf(next),100);targetMedia.recover()
            val rows=target.db.read("SELECT id,entryId,state FROM attachments");assertEquals(1,rows.size);assertEquals(next,rows.single()[1]);assertEquals("READY",rows.single()[2])
            val image=rows.single()[0] as String;assertArrayEquals(FullBackupTest.PNG,target.media.readReadOnly(image))
            val stamp=SqlLocalSearchRepository(target.gateway,target.tags).epoch();assertTrue(stamp>before)
            targetMedia.restore(pack,listOf(next),100);targetMedia.recover();assertEquals(stamp,SqlLocalSearchRepository(target.gateway,target.tags).epoch());assertEquals(rows,target.db.read("SELECT id,entryId,state FROM attachments"))
        }finally{pack.release()}
    }}}
}
