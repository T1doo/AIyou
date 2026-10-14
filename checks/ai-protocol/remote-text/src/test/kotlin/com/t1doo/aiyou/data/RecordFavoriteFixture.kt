package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.domain.*

/** The actual v8 fixture is upgraded with the same SQL used by production Room. */
internal class RecordFavoriteFixture(upgrade: Boolean = true) : AutoCloseable {
    val base = PermanentErasureFixture()
    val db get() = base.db
    val favorites get() = SqlRecordFavoriteRepository(base.gateway)
    val search get() = SqlLocalSearchRepository(base.gateway, base.tags)
    init { try { if (upgrade) upgrade() } catch (failure: Throwable) { base.close(); throw failure } }
    fun upgrade() = db.transaction { sql ->
        RecordFavoriteSqlSchema.migration8To9.forEach { sql.execute(it) }
        sql.execute("PRAGMA user_version=9")
    }
    fun entry(id: String = PermanentErasureFixture.uuid(), text: String = "合成 needle 原文🙂",
        captured: Long = 5, zone: String = "UTC"): String {
        db.transaction { sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,'LOCAL_ONLY')", listOf(id,captured,zone))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)", listOf(id,text,OriginalExports.hash(text)))
            sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)", listOf(id))
            sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')", listOf("INDEX:"+id,id))
        }
        return id
    }
    fun current(id: String) = base.bin.read(id).entry
    fun intent(id: String, value: Boolean, mutation: String = PermanentErasureFixture.uuid()): RecordFavoriteMutation {
        val entry = current(id); val state = favorites.read(id)
        return RecordFavoriteMutation(id,entry.currentRevision,OriginalExports.hash(entry.originalText),state.version,value,mutation)
    }
    fun revise(id: String, text: String = "合成改写 needle 原文") {
        val old = current(id)
        db.transaction { sql ->
            sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,6)",listOf(id,old.currentRevision+1,text,OriginalExports.hash(text)))
            sql.execute("UPDATE entries SET currentRevision=? WHERE id=?",listOf(old.currentRevision+1,id))
        }
    }
    fun readyIndex() { repeat(8) { if (!search.batch().remaining) return }; error("Synthetic index did not finish") }
    fun epoch() = search.epoch()
    fun favoriteColumns(id: String) = db.read("SELECT isFavorite,favoriteVersion,lastFavoriteMutationId,lastFavoriteSourceRevision FROM entries WHERE id=?",id)
    fun indexRows() = listOf(db.read("SELECT * FROM local_search_fts ORDER BY entryId"),db.read("SELECT * FROM local_search_documents ORDER BY entryId"),db.read("SELECT * FROM local_jobs ORDER BY id"))
    override fun close() = base.close()
}
