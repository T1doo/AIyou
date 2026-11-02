package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Production SQL/AES-GCM on real isolated SQLite; no Android device or external service. */
class SavedQuestionSqlTest {
    @Test fun offlineQuestionColdReopensAndSourceAuthorizationNeverErasesIt():Unit=Fixture().use { f ->
        val before=f.base.legacy(); val id=uuid(); val text="我是不是不喜欢咖啡？🙂e\u0301"
        val first=f.store.save(write(id,0,text)); assertEquals(text,first.question)
        assertEquals(before,f.base.legacy()); assertFalse(f.blob(id).toString(Charsets.UTF_8).contains(text))
        f.base.db.write("UPDATE remote_source_access SET generation=generation+1,connection=NULL,consent=NULL WHERE entryId=?",ManualTagSqlTest.ID)
        f.reopen(); assertEquals(text,f.store.read(id)!!.question)
        assertEquals(1L,f.store.read(id)!!.version); assertTrue(f.base.db.read("PRAGMA foreign_key_list(saved_questions)").isEmpty())
        val edited=f.store.save(write(id,1,"下一次问的问题",at=20)); assertEquals(2L,edited.version)
        val deleted=f.store.save(write(id,2,null,at=30)); assertNull(deleted.question)
        f.reopen(); assertTrue(f.store.list().isEmpty()); assertEquals(3L,f.store.read(id)!!.version)
        assertThrows(SavedQuestionConflict::class.java) { f.store.save(write(id,3,"不能复活",at=40)) }
        assertThrows(Exception::class.java) { f.base.db.write("DELETE FROM saved_questions WHERE id=?",id) }
        assertEquals(3L,f.store.read(id)!!.version)
    }
    @Test fun committedLostAcknowledgementRetriesOnlyExactIntentAndOldReplayCannotResurrect():Unit=Fixture().use { f ->
        val id=uuid(); val request=write(id,0,"合成问题"); var wrote=false; var lost=false
        f.base.db.beforeSql={ if(it.startsWith("INSERT INTO saved_questions")) wrote=true }
        f.base.db.afterSql={ if(it=="COMMIT" && wrote && !lost) { lost=true; error("Synthetic committed ACK loss") } }
        assertThrows(SavedQuestionStorageFailure::class.java) { f.store.save(request) }
        f.base.db.beforeSql=null; f.base.db.afterSql=null; assertTrue(lost)
        val bytes=f.blob(id); assertEquals(1L,f.store.save(request).version); assertArrayEquals(bytes,f.blob(id))
        for(changed in listOf(SaveQuestion(id,0,request.mutationId,"另一问题",10),
                SaveQuestion(id,0,request.mutationId,request.question,11),
                SaveQuestion(uuid(),0,request.mutationId,request.question,10)))
            assertThrows(SavedQuestionConflict::class.java) { f.store.save(changed) }
        f.store.save(write(id,1,null,at=20))
        assertThrows(SavedQuestionConflict::class.java) { f.store.save(request) }; assertNull(f.store.read(id)!!.question)
    }
    @Test fun separateConnectionsCompeteByCasWithoutOverwritingWinner():Unit=Fixture().use { f ->
        f.base.second().use { db ->
            val other=SqlSavedQuestionRepository(db,ManualTagSqlTest.KEY); val pool=Executors.newFixedThreadPool(2)
            val start=CountDownLatch(1); val id=uuid()
            try {
                val outcomes=listOf(f.store,other).mapIndexed { n,store -> pool.submit<String> {
                    start.await(); try { store.save(write(id,0,"合成问题$n")); "saved" } catch(_:SavedQuestionConflict) { "conflict" }
                } }; start.countDown()
                assertEquals(listOf("conflict","saved"),outcomes.map { it.get(3,TimeUnit.SECONDS) }.sorted())
                assertEquals(1L,f.store.read(id)!!.version); assertEquals(1,f.store.list().size)
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)); other.wipeKey() }
        }
    }
    @Test fun tamperedMetadataOrCiphertextFailsClosedWithoutReplacingQuestion():Unit=Fixture().use { f ->
        val id=uuid(); f.store.save(write(id,0,"合成私密问题")); val row=f.row(id)
        val wrong=SavedQuestionCipher(ByteArray(32) { 99 }); val right=SavedQuestionCipher(ManualTagSqlTest.KEY)
        try {
            assertThrows(Exception::class.java) { wrong.open(row) }
            for(index in 0..5) {
                val changed=row.toMutableList(); changed[index]=when(index) {
                    0,2 -> uuid(); 1 -> 2L; 3 -> 9L; 4 -> 11L; else -> "DELETED"
                }
                assertThrows(Exception::class.java) { right.open(changed) }
            }
            val corrupt=f.blob(id).also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
            f.base.db.write("UPDATE saved_questions SET version=version+1,lastMutationId=?,payload=? WHERE id=?",uuid(),corrupt,id)
            assertThrows(SavedQuestionStorageFailure::class.java) { f.store.list() }
            assertThrows(SavedQuestionStorageFailure::class.java) { f.store.save(write(uuid(),0,"不应写入")) }
            assertEquals(1L,f.base.db.read("SELECT count(*) FROM saved_questions").single().single())
            assertArrayEquals(corrupt,f.blob(id))
        } finally { wrong.wipe(); right.wipe() }
    }
    @Test fun retainedTombstonesCountTowardsCapacityAndUnicodeBoundaryIsExact():Unit=Fixture().use { f ->
        val max="🙂".repeat(512); val first=uuid(); f.store.save(write(first,0,max))
        repeat(SavedQuestions.MAX_RETAINED-1) { f.store.save(write(uuid(),0,"问题$it")) }
        f.store.save(write(first,1,null,at=20))
        assertThrows(SavedQuestionLimit::class.java) { f.store.save(write(uuid(),0,"不能借删除绕容量")) }
        for(text in listOf(" ","\uD83D",max+"a","x".repeat(513)))
            assertThrows(IllegalArgumentException::class.java) { write(uuid(),0,text) }
        assertEquals(127,f.store.list().size)
    }
    @Test fun portableCrossKeyReencryptsAndDeletedRowsStayDeleted():Unit=Fixture().use { f ->
        val first=uuid(); f.store.save(write(first,0,"跨密钥问题🙂"))
        val deleted=uuid(); f.store.save(write(deleted,0,"待删除")); f.store.save(write(deleted,1,null,at=20))
        val source=SavedQuestionCipher(ManualTagSqlTest.KEY); val target=SavedQuestionCipher(ByteArray(32) { 33 })
        try { for(id in listOf(first,deleted)) {
            val row=f.row(id); val plain=source.portable(row,false)
            try {
                val sealed=target.portable(row.take(6)+listOf(plain),true)
                try {
                    assertFalse((row[6] as ByteArray).contentEquals(sealed))
                    assertEquals(f.store.read(id)!!.question,target.open(row.take(6)+listOf(sealed)).question)
                    val again=target.portable(row.take(6)+listOf(sealed),false)
                    try { assertArrayEquals(plain,again) } finally { again.fill(0) }
                } finally { sealed.fill(0) }
                val altered=row.toMutableList().also { it[0]=uuid(); it[6]=plain }
                assertThrows(Exception::class.java) { target.portable(altered,true) }
            } finally { plain.fill(0) }
        } } finally { source.wipe(); target.wipe() }
    }
    private class Fixture:AutoCloseable {
        val base=ManualTagSqlTest.Fixture()
        var store=SqlSavedQuestionRepository(base.db,ManualTagSqlTest.KEY)
        init { base.db.transaction { sql -> sql.execute(SavedQuestionSqlSchema.create); SavedQuestionSqlSchema.guards.forEach { sql.execute(it) } } }
        fun row(id:String)=base.db.read("SELECT "+SavedQuestionSqlSchema.columns+" FROM saved_questions WHERE id=?",id).single()
        fun blob(id:String)=(row(id)[6] as ByteArray).copyOf()
        fun reopen() { store.wipeKey(); base.reopen(); store=SqlSavedQuestionRepository(base.db,ManualTagSqlTest.KEY) }
        override fun close() { store.wipeKey(); base.close() }
    }
    companion object {
        private fun uuid()=UUID.randomUUID().toString()
        private fun write(id:String,version:Long,text:String?,at:Long=10)=SaveQuestion(id,version,uuid(),text,at)
    }
}
