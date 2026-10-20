package com.t1doo.aiyou.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.concurrent.Callable

/** Blocking IO adapter over the SAME encrypted Room database as original records. */
class RoomSourceBoundTextRepository internal constructor(private val sql: DrainingRecordsSqlDatabase,
    private val sourceGate:com.t1doo.aiyou.domain.CaptureDraftGate?=null) : SourceBoundTextRepository {
    internal constructor(database: RecordsDatabase) : this(DrainingRecordsSqlDatabase(RoomRecordsSqlDatabase(database)))
    private val storage = RemoteTextTransactions(sql,captureGate=sourceGate)
    /** The records owner calls this after its AI operations have finished, before Room close. */
    internal fun stopAndAwaitSqlIdle() = sql.stopAndAwaitIdle()
    override fun queue(intent:RemoteSendIntent,promptVersion:String,clock:()->Instant)=storage.queue(intent,promptVersion,clock)
    override fun check(job:SourceBoundTextJob,clock:()->Instant)=storage.check(job,clock)
    override fun subscribe(job:SourceBoundTextJob,clock:()->Instant,onInvalidated:(SendDenial)->Unit)=
        storage.subscribe(job,clock,onInvalidated)
    override fun admit(job:SourceBoundTextJob,clock:()->Instant)=storage.admit(job,clock)
    override fun commit(job:SourceBoundTextJob,candidate:DerivedTextCandidate,clock:()->Instant)=storage.commit(job,candidate,clock)
    override fun cancel(job:SourceBoundTextJob)=storage.cancel(job)
    fun readAccess(sourceId:String)=storage.readAccess(sourceId)
    fun updateAuthorization(source:SourceVersion,expectedGeneration:Long,policy:ProcessingPolicy,
                            connection:ProviderConnection?,consent:ConsentGrant?)=
        storage.updateAuthorization(source,expectedGeneration,policy,connection,consent)
    fun tombstone(source:SourceVersion,expectedGeneration:Long)=storage.tombstone(source,expectedGeneration)
    fun annotations(source:SourceVersion,clock:()->Instant)=storage.annotations(source,clock)
}

internal class RoomRecordsSqlDatabase(private val room:RecordsDatabase):RecordsSqlDatabase {
    override fun <T> transaction(block:(RecordsSqlConnection)->T):T = room.runInTransaction(Callable {
        block(SupportRecordsSqlConnection(room.openHelper.writableDatabase))
    })
}

internal class SupportRecordsSqlConnection(private val db:SupportSQLiteDatabase):RecordsSqlConnection {
    override fun query(sql:String,args:List<Any?>):List<List<Any?>> {
        validate(args)
        return db.query(SimpleSQLiteQuery(sql,args.toTypedArray())).use { cursor ->
            buildList {
                while(cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    when(cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                        else -> throw RemoteTextStorageFailure()
                    }
                })
            }
        }
    }
    override fun execute(sql:String,args:List<Any?>):Int {
        validate(args)
        return db.compileStatement(sql).use { statement ->
            args.forEachIndexed { offset,value -> when(value) {
                null->statement.bindNull(offset+1)
                is String->statement.bindString(offset+1,value)
                is Long->statement.bindLong(offset+1,value)
                is Int->statement.bindLong(offset+1,value.toLong())
                is ByteArray->statement.bindBlob(offset+1,value)
            } }
            statement.executeUpdateDelete()
        }
    }
    private fun validate(args:List<Any?>) {
        require(args.all { it==null || it is String || it is Long || it is Int || it is ByteArray }) { "Unsupported SQL binding" }
    }
}
