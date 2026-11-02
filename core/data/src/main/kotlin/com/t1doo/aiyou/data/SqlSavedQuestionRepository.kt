package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

internal class SqlSavedQuestionRepository(private val database: RecordsSqlDatabase, databaseKey: ByteArray) : SavedQuestionRepository {
    private val cipher=SavedQuestionCipher(databaseKey)
    override fun read(id:String):SavedQuestion? = safely {
        SavedQuestions.id(id); database.transaction { sql -> row(sql,id)?.let(cipher::open) }
    }
    override fun list():List<SavedQuestion> = safely { database.transaction { sql ->
        val rows=all(sql); Collections.unmodifiableList(rows.map(cipher::open).filter { it.question != null }
            .sortedWith(compareByDescending<SavedQuestion> { it.updatedAtMillis }.thenBy { it.id }))
    } }
    override fun save(request:SaveQuestion):SavedQuestion = safely { database.transaction { sql ->
        // Authenticate the complete bounded collection before accepting any mutation, including tombstones.
        val rows=all(sql); rows.forEach(cipher::open)
        val old=rows.singleOrNull { it[0]==request.id }; val previous=old?.let(cipher::open)
        if(old?.get(2)==request.mutationId) {
            if(previous!!.version==request.expectedVersion+1 && previous.question==request.question && previous.updatedAtMillis==request.atMillis)
                return@transaction previous
            throw SavedQuestionConflict()
        }
        // Idempotence belongs to this question/version/content/time, not a global forever-retained UUID ledger.
        // Reject a UUID still attached to another row's latest intent as an additional caller-error check.
        if(rows.any { it[2]==request.mutationId } || (previous?.version ?: 0L)!=request.expectedVersion ||
            previous!=null && (previous.question==null || request.atMillis<previous.updatedAtMillis)) throw SavedQuestionConflict()
        if(old==null && rows.size>=SavedQuestions.MAX_RETAINED) throw SavedQuestionLimit()
        val next=request.expectedVersion+1
        val metadata=listOf(request.id,next,request.mutationId,previous?.createdAtMillis ?: request.atMillis,
            request.atMillis,if(request.question==null) "DELETED" else "ACTIVE")
        val bytes=cipher.seal(metadata,request.question)
        try {
            if(old==null) sql.execute("INSERT INTO saved_questions("+SavedQuestionSqlSchema.columns+") VALUES(?,?,?,?,?,?,?)",metadata+listOf(bytes))
            else if(sql.execute("UPDATE saved_questions SET version=?,lastMutationId=?,updatedAtMillis=?,state=?,payload=? WHERE id=? AND version=?",
                    listOf(next,request.mutationId,request.atMillis,metadata[5],bytes,request.id,request.expectedVersion))!=1) throw SavedQuestionConflict()
            cipher.open(checkNotNull(row(sql,request.id)))
        } finally { bytes.fill(0) }
    } }
    internal fun validateWithin(sql:RecordsSqlConnection) { all(sql).forEach(cipher::open) }
    private fun row(sql:RecordsSqlConnection,id:String):List<Any?>? {
        val size=sql.query("SELECT length(payload) FROM saved_questions WHERE id=?",listOf(id)).singleOrNull() ?: return null
        require(size.single() is Long && size.single() as Long in 29..4096)
        return sql.query("SELECT "+SavedQuestionSqlSchema.columns+" FROM saved_questions WHERE id=?",listOf(id)).single()
    }
    private fun all(sql:RecordsSqlConnection):List<List<Any?>> {
        val stats=sql.query("SELECT count(*),COALESCE(MAX(length(payload)),0),COALESCE(MIN(length(payload)),29),COALESCE(SUM(length(payload)),0) FROM saved_questions").single()
        require(stats[0] as Long <= SavedQuestions.MAX_RETAINED && stats[1] as Long <= 4096 &&
            stats[2] as Long >= 29 && stats[3] as Long <= SavedQuestions.MAX_RETAINED * 4096L)
        return sql.query("SELECT "+SavedQuestionSqlSchema.columns+" FROM saved_questions ORDER BY id")
    }
    private inline fun <T> safely(block:()->T):T = try { block() }
        catch(f:CancellationException) { throw f }
        catch(f:SavedQuestionConflict) { throw f }
        catch(f:SavedQuestionLimit) { throw f }
        catch(_:Exception) { throw SavedQuestionStorageFailure() }
    fun wipeKey() { cipher.wipe() }
}
