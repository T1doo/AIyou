package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** CAS, authenticated readback and stable latest-operation replay share one real transaction. */
internal class SqlManualTaskRepository(private val database: RecordsSqlDatabase, key: ByteArray) : ManualTaskRepository {
    private val cipher = ManualTaskCipher(key)
    override fun snapshots(): List<ManualTaskSnapshot> = safely {
        database.transaction { sql ->
            Collections.unmodifiableList(sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' ORDER BY id")
                .map { decode(it).payload.task }.sortedWith(compareByDescending<ManualTaskSnapshot> { it.createdAtMillis }.thenBy { it.id }))
        }
    }
    override fun create(request: ManualTaskCreate): ManualTaskSnapshot = safely {
        if(request.id.startsWith(TaskProposals.PREFIX)||request.id.startsWith("TASK_FROM_PROPOSAL:")) throw ManualTaskConflict()
        database.transaction { sql -> createWithin(sql,request) }
    }
    internal fun readWithin(sql: RecordsSqlConnection,id: String): ManualTaskSnapshot? = row(sql,id)?.payload?.task
    internal fun exportWithin(sql:RecordsSqlConnection):List<ManualTaskSnapshot> =
        sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' ORDER BY id LIMIT 257")
            .map {decode(it).payload.task}.also {require(it.size<=RecordPackageActivities.MAX_ITEMS)}
    internal fun importWithin(sql:RecordsSqlConnection,t:ManualTaskSnapshot,mutation:String) {
        require(t.version==1L && row(sql,t.id)==null)
        val bytes=cipher.seal(t,mutation,t.updatedAtMillis,"IMPORT")
        sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",listOf(t.id,1,mutation,bytes))
        check(readWithin(sql,t.id)==t)
    }
    internal fun createWithin(sql: RecordsSqlConnection,request: ManualTaskCreate,receipt: SourceTaskReceipt? = null): ManualTaskSnapshot {
        val expected = ManualTaskSnapshot(request.id, 1, request.title, ManualTaskStatus.OPEN,
            request.createdAtMillis, request.creationTimeZone, request.createdAtMillis, request.schedule, receipt)
        val old = row(sql, request.id)
        return if (old != null) {
            if (old.mutationId != request.mutationId || old.payload.task != expected ||
                old.payload.requestedAtMillis != request.createdAtMillis ||
                old.payload.operation != null && old.payload.operation != "CREATE") throw ManualTaskConflict()
            old.payload.task
        } else {
            val bytes = cipher.seal(expected, request.mutationId, request.createdAtMillis)
            sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",
                listOf(request.id, 1, request.mutationId, bytes))
            checkNotNull(row(sql, request.id)).payload.task.also { check(it == expected) }
        }
    }
    override fun change(request: ManualTaskMutation): ManualTaskSnapshot = safely {
        database.transaction { sql ->
            val old = row(sql, request.id) ?: throw ManualTaskConflict()
            val current = old.payload.task
            if (old.mutationId == request.mutationId) {
                if (current.version != request.expectedVersion + 1 || current.status != request.status ||
                    old.payload.requestedAtMillis != request.requestedAtMillis ||
                    old.payload.operation != null && old.payload.operation != "STATUS") throw ManualTaskConflict()
                current
            } else {
                if (current.version != request.expectedVersion || current.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
                val next = current.copy(version = Math.addExact(current.version, 1), status = request.status,
                    updatedAtMillis = maxOf(current.updatedAtMillis, request.requestedAtMillis))
                val bytes = cipher.seal(next, request.mutationId, request.requestedAtMillis)
                if (sql.execute("UPDATE manual_tasks SET version=?,lastMutationId=?,ciphertext=? WHERE id=? AND version=?",
                    listOf(next.version, request.mutationId, bytes, request.id, request.expectedVersion)) != 1) throw ManualTaskConflict()
                checkNotNull(row(sql, request.id)).payload.task.also { check(it == next) }
            }
        }
    }
    override fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot = safely {
        database.transaction { sql ->
            val old = row(sql, request.id) ?: throw ManualTaskConflict()
            val current = old.payload.task
            if (current.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            if (old.mutationId == request.mutationId) {
                if (current.version != request.expectedVersion + 1 || current.schedule != request.schedule ||
                    old.payload.operation != request.operation.name || old.payload.requestedAtMillis != request.requestedAtMillis)
                    throw ManualTaskConflict()
                current
            } else {
                if (current.version != request.expectedVersion) throw ManualTaskConflict()
                ManualTaskDates.validate(request.operation, current.schedule, request.schedule)
                val next = current.copy(version = Math.addExact(current.version, 1), schedule = request.schedule,
                    updatedAtMillis = maxOf(current.updatedAtMillis, request.requestedAtMillis))
                val bytes = cipher.seal(next, request.mutationId, request.requestedAtMillis, request.operation.name)
                if (sql.execute("UPDATE manual_tasks SET version=?,lastMutationId=?,ciphertext=? WHERE id=? AND version=?",
                    listOf(next.version, request.mutationId, bytes, request.id, request.expectedVersion)) != 1) throw ManualTaskConflict()
                checkNotNull(row(sql, request.id)).payload.task.also { check(it == next) }
            }
        }
    }
    private fun row(sql: RecordsSqlConnection, id: String): Row? {
        if(id.startsWith(TaskProposals.PREFIX)) throw ManualTaskConflict()
        return sql.query(
        "SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE id=?", listOf(id)).singleOrNull()?.let(::decode)
    }
    private fun decode(cells: List<Any?>): Row {
        val id = cells[0] as String; val version = cells[1] as Long; val mutation = cells[2] as String
        ManualTags.identifier(mutation)
        return Row(mutation, cipher.open(id, version, mutation, cells[3] as ByteArray))
    }
    private class Row(val mutationId: String, val payload: ManualTaskCipher.Payload)
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (conflict: ManualTaskConflict) { throw conflict }
        catch (invalid: ManualTaskDateInvalid) { throw invalid }
        catch (_: Exception) { throw ManualTaskStorageFailure() }
    fun wipeKey() = cipher.wipe()
}
