package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal class SqlReminderRebuildRepository(private val database:RecordsSqlDatabase,key:ByteArray,
    private val tasks:SqlManualTaskRepository):ReminderRebuildRepository,AutoCloseable {
    private val cipher=ReminderRuntimeMetaCipher(key)
    override fun read():ReminderRuntimeMeta=safe{database.transaction(::load)}
    private fun load(sql:RecordsSqlConnection):ReminderRuntimeMeta {
        val rows=sql.query("SELECT slot,version,ciphertext FROM reminder_runtime_meta LIMIT 2")
        if(rows.isEmpty())return ReminderRuntimeMeta()
        require(rows.size==1 && rows[0][0]==1L)
        return cipher.open(rows[0][1] as Long,rows[0][2] as ByteArray)
    }
    private fun save(sql:RecordsSqlConnection,old:ReminderRuntimeMeta,next:ReminderRuntimeMeta):ReminderRuntimeMeta {
        require(next.version==Math.addExact(old.version,1));val cell=cipher.seal(next)
        if(old.version==0L)sql.execute("INSERT INTO reminder_runtime_meta(slot,version,ciphertext) VALUES(1,?,?)",listOf(next.version,cell))
        else if(sql.execute("UPDATE reminder_runtime_meta SET version=?,ciphertext=? WHERE slot=1 AND version=?",listOf(next.version,cell,old.version))!=1)throw ReminderRuntimeConflict()
        return load(sql).also{check(it==next)}
    }
    private fun upper(sql:RecordsSqlConnection):String?=sql.query("SELECT id FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' ORDER BY id COLLATE BINARY DESC LIMIT 1").singleOrNull()?.get(0) as String?
    private fun newDrain(sql:RecordsSqlConnection)=upper(sql).let{ReminderDrainPass(UUID.randomUUID().toString(),it,done=it==null)}
    private fun dirty(sql:RecordsSqlConnection,pass:ReminderDrainPass?)=if(pass==null||pass.done)newDrain(sql)else pass.copy(dirty=true)
    override fun request(request:ReminderRebuildRequest):ReminderRuntimeMeta=safe{database.transaction{sql->
        val old=load(sql)
        if(old.request?.id==request.id){if(old.request!=request)throw ReminderRuntimeConflict();return@transaction old}
        if(old.version!=request.expectedMetaVersion)throw ReminderRuntimeConflict()
        val top=upper(sql)
        save(sql,old,old.copy(version=old.version+1,lastWriteId=request.id,request=request,planUpperTaskId=top,planCursor=null,planDone=top==null))
    }}
    override fun requestDrain(expectedVersion:Long,mutationId:String):ReminderRuntimeMeta=safe{database.transaction{sql->
        ManualTags.identifier(mutationId);val old=load(sql)
        if(old.lastWriteId==mutationId && old.version==expectedVersion+1)return@transaction old
        if(old.version!=expectedVersion)throw ReminderRuntimeConflict()
        save(sql,old,old.copy(version=old.version+1,lastWriteId=mutationId,drain=dirty(sql,old.drain)))
    }}
    override fun planPage(expectedVersion:Long,nowMillis:Long,limit:Int):ReminderRuntimeMeta=safe{database.transaction{sql->
        require(nowMillis>=0 && limit in 1..16);val old=load(sql)
        if(old.planDone)return@transaction old
        if(old.version!=expectedVersion)throw ReminderRuntimeConflict()
        val rows=sql.query("SELECT id FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' AND id COLLATE BINARY > ? AND id COLLATE BINARY <= ? ORDER BY id COLLATE BINARY LIMIT ?",
            listOf(old.planCursor?:"",requireNotNull(old.planUpperTaskId),limit))
        var errors=0
        for(row in rows)try{tasks.rebuildFutureWithin(sql,row[0] as String,nowMillis)}catch(c:CancellationException){throw c}catch(_:Exception){errors++}
        val cursor=rows.lastOrNull()?.get(0) as String? ?: old.planCursor
        val more=cursor!=null && sql.query("SELECT 1 FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' AND id COLLATE BINARY > ? AND id COLLATE BINARY <= ? LIMIT 1",listOf(cursor,old.planUpperTaskId)).isNotEmpty()
        save(sql,old,old.copy(version=old.version+1,lastWriteId="plan:"+UUID.randomUUID(),planCursor=cursor,planDone=!more,
            drain=dirty(sql,old.drain),failedRows=addErrors(old.failedRows,errors)))
    }}
    override fun commandPage(nowMillis:Long,rowLimit:Int,commandLimit:Int):ReminderCommandPage?=safe{database.transaction{sql->
        require(nowMillis>=0 && rowLimit in 1..16 && commandLimit in 1..16)
        val meta=load(sql);val pass=meta.drain?:return@transaction null
        if(pass.done)return@transaction null
        val cursor=pass.next;val relation=if(cursor!=null&&!cursor.rowExhausted) ">=" else ">"
        val rows=sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' AND id COLLATE BINARY $relation ? AND id COLLATE BINARY <= ? ORDER BY id COLLATE BINARY LIMIT ?",
            listOf(cursor?.taskId?:"",requireNotNull(pass.upperTaskId),rowLimit))
        val commands=mutableListOf<TaskReminderCommand>();val unowned=mutableListOf<String>();var next=cursor;var scanned=0;var errors=0
        for(row in rows){
            scanned++;val id=row[0] as String
            val view=try{tasks.reminderRuntimeFromCells(row)}catch(c:CancellationException){throw c}catch(_:Exception){errors++;next=ReminderScanCursor(id);continue}
            val task=view.task
            if(view.receipts.isNotEmpty())unowned+=id
            val eligible=task.reminder?.commands.orEmpty().filter{c->
                (id!=cursor?.taskId || cursor.rowExhausted || c.token.scheduleVersion>requireNotNull(cursor.afterGeneration)) &&
                    (c.kind==TaskReminderCommandKind.CANCEL || c.kind==TaskReminderCommandKind.SCHEDULE && requireNotNull(c.schedule.instant()).toEpochMilli()>nowMillis)
            }.sortedBy{it.token.scheduleVersion}
            val take=eligible.take(commandLimit-commands.size);commands+=take
            next=if(take.size<eligible.size)ReminderScanCursor(id,take.last().token.scheduleVersion,false)else ReminderScanCursor(id)
            if(commands.size==commandLimit)break
        }
        val more=next?.let{if(!it.rowExhausted)true else sql.query("SELECT 1 FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' AND id COLLATE BINARY > ? AND id COLLATE BINARY <= ? LIMIT 1",listOf(it.taskId,pass.upperTaskId)).isNotEmpty()}?:false
        ReminderCommandPage(meta.version,pass.id,commands,next,!more,scanned,errors,unowned)
    }}
    override fun checkpoint(page:ReminderCommandPage):ReminderRuntimeMeta=safe{database.transaction{sql->
        val old=load(sql);val proof=MessageDigest.getInstance("SHA-256").digest(ReminderWire.bytes{with(ReminderWire){
            text(page.passId);writeLong(page.metaVersion);cursor(page.next);writeBoolean(page.done);writeInt(page.scannedRows);writeInt(page.failedRows)
        }}).joinToString(""){"%02x".format(it.toInt() and 255)}
        val writeId="drain:"+proof
        if(old.lastWriteId==writeId)return@transaction old
        if(old.version!=page.metaVersion || old.drain?.id!=page.passId)throw ReminderRuntimeConflict()
        val pass=requireNotNull(old.drain)
        val next=if(page.done && pass.dirty)newDrain(sql)else pass.copy(next=page.next,done=page.done)
        save(sql,old,old.copy(version=old.version+1,lastWriteId=writeId,drain=next,failedRows=addErrors(old.failedRows,page.failedRows)))
    }}
    private fun addErrors(a:Int,b:Int)=(a.toLong()+b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private inline fun <T> safe(block:()->T):T=try{block()}catch(c:CancellationException){throw c}catch(c:ReminderRuntimeConflict){throw c}catch(_:Exception){throw ReminderMetaStorageFailure()}
    override fun close(){cipher.close()}
}
