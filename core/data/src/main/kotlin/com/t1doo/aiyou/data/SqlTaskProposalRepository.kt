package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.CancellationException

/** Separate logical ledger in a reserved physical namespace; no Room migration or inferred task. */
internal class SqlTaskProposalRepository(private val database:RecordsSqlDatabase,key:ByteArray,
    private val tasks:SqlManualTaskRepository):TaskProposalRepository {
    private val cipher=TaskProposalCipher(key)
    private val source=SqlSourceTaskRepository(database,tasks)
    private data class Row(val proposal:TaskProposal,val hash:String)
    override fun list():List<TaskProposalItem> = safe {database.transaction {sql ->
        readAllWithin(sql).map {p ->
            val available=try {source.quoteWithin(sql,p.binding,true);true} catch(_:SourceTaskConflict) {false}
            TaskProposalItem(p,available)
        }.sortedWith(compareByDescending<TaskProposalItem> {it.proposal.updatedAtMillis}.thenBy {it.proposal.id})
    }}
    override fun write(request:TaskProposalWrite):TaskProposal = safe {database.transaction {sql ->
        val r=request;val hash=TaskProposals.writeHash(r);val old=row(sql,r.id)
        val expected=TaskProposal(r.id,r.expectedVersion+1,r.binding,r.titleInput,r.dateInput,r.createdAtMillis,r.creationTimeZone,r.atMillis,
            if(r.action==TaskProposalAction.SAVE) TaskProposalState.DRAFT else TaskProposalState.REJECTED)
        if(old?.hash==hash) {
            if(old.proposal!=expected) throw TaskProposalConflict()
            return@transaction old.proposal
        }
        if(r.expectedVersion==0L) {
            if(old!=null||r.action!=TaskProposalAction.SAVE) throw TaskProposalConflict()
            if(tasks.readWithin(sql,TaskProposals.taskId(r.id))!=null) throw TaskProposalConflict()
            if(readAllWithin(sql).count {it.state==TaskProposalState.DRAFT}>=TaskProposals.MAX_DRAFTS) throw TaskProposalLimit()
        } else {
            val p=old?.proposal ?: throw TaskProposalConflict()
            if(p.version!=r.expectedVersion||p.state!=TaskProposalState.DRAFT||p.binding!=r.binding||
                p.createdAtMillis!=r.createdAtMillis||p.creationTimeZone!=r.creationTimeZone||r.atMillis<p.updatedAtMillis) throw TaskProposalConflict()
            if(r.action==TaskProposalAction.REJECT&&(p.titleInput!=r.titleInput||p.dateInput!=r.dateInput)) throw TaskProposalConflict()
        }
        if(r.action==TaskProposalAction.SAVE) source.quoteWithin(sql,r.binding,true)
        val bytes=cipher.seal(expected,hash)
        if(old==null) sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",
            listOf(TaskProposals.storageId(r.id),1,hash,bytes))
        else if(sql.execute("UPDATE manual_tasks SET version=?,lastMutationId=?,ciphertext=? WHERE id=? AND version=?",
            listOf(expected.version,hash,bytes,TaskProposals.storageId(r.id),r.expectedVersion))!=1) throw TaskProposalConflict()
        checkNotNull(row(sql,r.id)).proposal.also {check(it==expected)}
    }}
    override fun confirm(request:TaskProposalConfirm):ManualTaskSnapshot = safe {database.transaction {sql ->
        val r=request;val receipt=TaskProposals.receipt(r)
        val committed=tasks.readWithin(sql,r.task.id)
        if(committed!=null) {
            if(committed.sourceReceipt!=receipt) throw TaskProposalConflict()
            return@transaction committed
        }
        val p=row(sql,r.proposal.id)?.proposal ?: throw TaskProposalConflict()
        if(p!=r.proposal||p.state!=TaskProposalState.DRAFT) throw TaskProposalConflict()
        source.quoteWithin(sql,p.binding,true)
        val saved=tasks.createWithin(sql,r.task,receipt)
        // The immutable receipt moves to the independent task in the same transaction.
        if(sql.execute("DELETE FROM manual_tasks WHERE id=? AND version=?",listOf(TaskProposals.storageId(p.id),p.version))!=1) throw TaskProposalConflict()
        saved
    }}
    internal fun readAllWithin(sql:RecordsSqlConnection):List<TaskProposal> {
        val rows=sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)='TASK_PROPOSAL:' ORDER BY id")
        return rows.map {decode(it).proposal}
    }
    internal fun exportWithin(sql:RecordsSqlConnection):List<TaskProposal> =
        sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)='TASK_PROPOSAL:' ORDER BY id LIMIT 4097")
            .also {if(it.size>4096) throw RecordPackageActivityLimit(RecordPackageActivityBound.PROPOSAL_LEDGER)}
            .map {decode(it).proposal}.filter {it.state==TaskProposalState.DRAFT}
            .also {if(it.size>RecordPackageActivities.MAX_ITEMS) throw RecordPackageActivityLimit(RecordPackageActivityBound.SAVED_DRAFTS)}
    internal fun importWithin(sql:RecordsSqlConnection,p:TaskProposal,hash:String) {
        require(p.version==1L && p.state==TaskProposalState.DRAFT && row(sql,p.id)==null)
        require(readAllWithin(sql).count {it.state==TaskProposalState.DRAFT}<TaskProposals.MAX_DRAFTS)
        val bytes=cipher.seal(p,hash)
        sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",listOf(TaskProposals.storageId(p.id),1,hash,bytes))
        check(row(sql,p.id)?.proposal==p)
    }
    private fun row(sql:RecordsSqlConnection,id:String)=sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE id=?",
        listOf(TaskProposals.storageId(id))).singleOrNull()?.let(::decode)
    private fun decode(r:List<Any?>):Row {
        val stored=r[0] as String;require(stored.startsWith(TaskProposals.PREFIX))
        val id=stored.removePrefix(TaskProposals.PREFIX);val hash=r[2] as String
        return Row(cipher.open(id,r[1] as Long,hash,r[3] as ByteArray),hash)
    }
    private inline fun <T> safe(block:()->T):T=try {block()}
        catch(c:CancellationException) {throw c}
        catch(c:TaskProposalConflict) {throw c}
        catch(c:TaskProposalLimit) {throw c}
        catch(_:SourceTaskConflict) {throw TaskProposalConflict()}
        catch(_:Exception) {throw TaskProposalStorageFailure()}
    fun wipeKey()=cipher.wipe()
}
