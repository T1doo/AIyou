package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.CancellationException

/** Same encrypted records owner/transaction. Immutable creation receipt lives with the manual task. */
internal class SqlSourceTaskRepository(private val database: RecordsSqlDatabase,
    private val tasks: SqlManualTaskRepository) : SourceTaskRepository {
    override fun preview(binding: TaskSourceBinding): SourceTaskPreview = safely { database.transaction { sql ->
        val quote=quoteWithin(sql,binding,true)
        val old=tasks.readWithin(sql,SourceTasks.id(binding))
        if(old!=null && old.sourceReceipt?.binding!=binding) throw SourceTaskConflict()
        SourceTaskPreview(binding,quote,old)
    } }
    override fun create(request: SourceTaskCreate): ManualTaskSnapshot = safely { database.transaction { sql ->
        val receipt=SourceTasks.receipt(request)
        val old=tasks.readWithin(sql,request.task.id)
        if(old!=null) {
            // No write, even after task completion/date changes or source erasure.
            if(old.sourceReceipt!=receipt) throw SourceTaskConflict()
            return@transaction old
        }
        if(quoteWithin(sql,request.preview.binding,true)!=request.preview.quote) throw SourceTaskConflict()
        tasks.createWithin(sql,request.task,receipt)
    } }
    override fun locate(taskId: String,version: Long,publish:(TaskSourceBinding)->Unit): Boolean = safely { database.transaction { sql ->
        val task=tasks.readWithin(sql,taskId) ?: throw SourceTaskConflict()
        val binding=task.sourceReceipt?.binding ?: throw SourceTaskConflict()
        if(task.version!=version) throw SourceTaskConflict()
        quoteWithin(sql,binding,false)
        publish(binding);true
    } }
    internal fun quoteWithin(sql:RecordsSqlConnection,b:TaskSourceBinding,creating:Boolean):String {
        if(b.source.sourceId.startsWith(RecordPackageActivities.UNAVAILABLE_PREFIX)) throw SourceTaskConflict()
        if(SqlErasureFileHooks.fenced(sql,b.source.sourceId)) throw SourceTaskConflict()
        val row=sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
            listOf(b.source.sourceId)).singleOrNull() ?: throw SourceTaskConflict()
        if(row[0]!=b.source.revision || row[2]!=b.sourceHash || row[5]!=0L ||
            creating && (row[4]!=b.generation || row[3] !in listOf("AI_NONE","LOCAL_ONLY"))) throw SourceTaskConflict()
        val text=row[1] as String
        if(text.length>100_000 || OriginalExports.hash(text)!=b.sourceHash || b.end>text.codePointCount(0,text.length)) throw SourceTaskConflict()
        return text.substring(text.offsetByCodePoints(0,b.start),text.offsetByCodePoints(0,b.end))
    }
    private inline fun <T> safely(block:()->T):T=try { block() }
        catch(c:CancellationException) { throw c }
        catch(c:SourceTaskConflict) { throw c }
        catch(_:Exception) { throw SourceTaskStorageFailure() }
}
