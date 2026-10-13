package com.t1doo.aiyou.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.concurrent.Callable

/** Blocking IO adapter over the SAME encrypted Room database as original records. */
class RoomSourceBoundTextRepository internal constructor(database: RecordsDatabase) : SourceBoundTextRepository {
    private val storage = RemoteTextTransactions(RoomRecordsSqlDatabase(database))
    override fun queue(intent:RemoteSendIntent,promptVersion:String,clock:()->Instant)=storage.queue(intent,promptVersion,clock)
    override fun check(job:SourceBoundTextJob,clock:()->Instant)=storage.check(job,clock)
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

private class RoomRecordsSqlDatabase(private val room:RecordsDatabase):RecordsSqlDatabase {
    override fun <T> transaction(block:(RecordsSqlConnection)->T):T = room.runInTransaction(Callable {
        block(RoomRecordsSqlConnection(room.openHelper.writableDatabase))
    })
}

private class RoomRecordsSqlConnection(private val db:SupportSQLiteDatabase):RecordsSqlConnection {
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
