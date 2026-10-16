package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64

/** Borrows existing key owners; final import and immutable replay receipt share the records transaction. */
internal class SqlRecordPackageActivities(private val database:RecordsSqlDatabase,
    private val tasks:SqlManualTaskRepository,private val proposals:SqlTaskProposalRepository) {
    fun capture(snapshot:OriginalExportSnapshot):RecordPackageActivities=database.transaction {sql ->
        val source=SqlSourceTaskRepository(database,tasks)
        fun index(b:TaskSourceBinding?):Int {
            if(b==null) return -1
            val index=snapshot.records.indexOfFirst {r -> r.entry.id==b.source.sourceId && r.entry.currentRevision.toLong()==b.source.revision &&
                r.contentHash==b.sourceHash && r.sourceGeneration==b.generation && b.end<=r.entry.originalText.codePointCount(0,r.entry.originalText.length)}
            if(index<0) return -1
            return try {source.quoteWithin(sql,b,true);index} catch(_:SourceTaskConflict) {-1}
        }
        RecordPackageActivities(tasks.exportWithin(sql).map {RecordPackageTask(it,index(it.sourceReceipt?.binding))},
            proposals.exportWithin(sql).map {RecordPackageDraft(it,index(it.binding))},snapshot.records.map {it.sourceGeneration})
    }
    fun current(snapshot:OriginalExportSnapshot,a:RecordPackageActivities):Boolean {
        val now=capture(snapshot)
        return now.tasks==a.tasks && now.drafts==a.drafts && now.sourceGenerations==a.sourceGenerations
    }
    fun importWithin(sql:RecordsSqlConnection,pack:AuthenticatedRecordPackage,ids:List<String>) {
        val a=pack.activities ?: return
        require(receipt(sql,pack,ids)==null)
        val mutation=hash("receipt",pack.manifest.backupId,pack.digest)
        a.tasks.forEach {item ->
            val old=item.task
            val receipt=old.sourceReceipt?.let {SourceTaskReceipt(mapped(pack,ids,it.binding,item.sourceRecord),hash("source",pack.digest,it.requestHash))}
            tasks.importWithin(sql,old.copy(id=taskId(pack,old.id),version=1,sourceReceipt=receipt),mutation)
        }
        a.drafts.forEach {item ->
            val old=item.proposal
            proposals.importWithin(sql,old.copy(id=draftId(pack,old.id),version=1,binding=mapped(pack,ids,old.binding,item.sourceRecord)),mutation)
        }
        sql.execute("INSERT INTO record_package_receipts(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'PACKAGE_TASK_RESTORE',?)",
            listOf(PREFIX+pack.manifest.backupId,ids.firstOrNull() ?: anchor(pack.manifest.backupId),expected(pack,ids)))
        replayWithin(sql,pack,ids)
    }
    fun replayWithin(sql:RecordsSqlConnection,pack:AuthenticatedRecordPackage,ids:List<String>):Pair<List<String>,List<String>> {
        val a=pack.activities ?: return emptyList<String>() to emptyList()
        require(receipt(sql,pack,ids)==expected(pack,ids))
        // A consumed/deleted draft stays consumed/deleted; mutable task state is never rewritten.
        return a.tasks.map {taskId(pack,it.task.id)} to a.drafts.map {draftId(pack,it.proposal.id)}
    }
    private fun expected(pack:AuthenticatedRecordPackage,ids:List<String>):String=PackageWriter(98304).use {w ->
        val a=checkNotNull(pack.activities);w.i32(3);w.string(pack.manifest.backupId,36);w.raw(packageUnhex(pack.digest))
        w.strings(ids,20,36)
        val raw=RecordPackageActivityCodec.encode(a)
        try {w.raw(packageHash(raw))} finally {raw.fill(0)}
        w.strings(a.tasks.map {taskId(pack,it.task.id)},256,96);w.strings(a.drafts.map {draftId(pack,it.proposal.id)},256,96)
        val bytes=w.bytes();try {Base64.getEncoder().encodeToString(bytes)} finally {bytes.fill(0)}
    }
    private fun receipt(sql:RecordsSqlConnection,pack:AuthenticatedRecordPackage,ids:List<String>):String? {
        val row=sql.query("SELECT entryId,sourceRevision,state FROM record_package_receipts WHERE id=? AND operation='PACKAGE_TASK_RESTORE'",listOf(PREFIX+pack.manifest.backupId)).singleOrNull() ?: return null
        require(row[0]==(ids.firstOrNull() ?: anchor(pack.manifest.backupId)) && row[1]==1L)
        return row[2] as String
    }
    private fun mapped(pack:AuthenticatedRecordPackage,ids:List<String>,b:TaskSourceBinding,index:Int):TaskSourceBinding =
        if(index>=0) b.copy(source=SourceVersion(ids[index],1),generation=1)
        else b.copy(source=SourceVersion(RecordPackageActivities.UNAVAILABLE_PREFIX+hash("missing",pack.manifest.backupId,b.source.sourceId),b.source.revision))
    private fun taskId(p:AuthenticatedRecordPackage,id:String)="PACKAGE_TASK:"+hash("task",p.manifest.backupId,id)
    private fun draftId(p:AuthenticatedRecordPackage,id:String)="PACKAGE_PROPOSAL:"+hash("draft",p.manifest.backupId,id)
    private fun hash(purpose:String,backup:String,id:String):String=PackageWriter(2048).use {w ->
        w.string(purpose,32);w.string(backup,128);w.string(id,512);val bytes=w.bytes()
        try {packageHex(packageHash(bytes))} finally {bytes.fill(0)}
    }
    companion object {
        private const val PREFIX="PACKAGE_TASKS:"
        fun anchor(backupId:String)="PACKAGE_ACTIVITY:"+backupId
    }
}
