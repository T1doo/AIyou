package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Same SQL transaction owner as the send ledger. Local read proofs never recreate a send permit. */
internal class SqlChatHistoryRepository(private val database:RecordsSqlDatabase,key:ByteArray,
    private val gate:CaptureDraftGate?=null):ChatHistoryRepository {
    internal val cipher=ChatMessageCipher(key)
    private val closed=AtomicBoolean()
    private class View(val owner:SqlChatHistoryRepository,override val message:ChatMessage,
        override val reply:SelectedQuestionReply?,override val citations:List<ChatHistoryCitation>,val result:String):ChatMessageView
    private class Location(val owner:SqlChatHistoryRepository,val view:View,
        override val reference:OriginalTextReference,override val entry:StoredEntry,override val generation:Long):ChatMessageLocation

    override fun list():List<ChatMessage> = safely {database.transaction {sql ->
        Collections.unmodifiableList(all(sql).map {materialize(sql,it).message}.filter {it.state!=ChatMessageState.DELETED}
            .sortedWith(compareByDescending<ChatMessage>{it.createdAtMillis}.thenBy{it.id}))
    }}
    override fun open(id:String,expectedVersion:Long):ChatMessageView? = safely {
        ChatMessages.id(id);require(expectedVersion>0);database.transaction {sql->
            read(sql,id)?.takeIf {it.row[1]==expectedVersion&&it.row[6]!="DELETED"}?.let {materialize(sql,it)}
        }
    }
    override fun current(view:ChatMessageView):Boolean = observed {
        val value=view as? View ?: return@observed false
        if(value.owner!==this)return@observed false
        database.transaction {sql->currentWithin(sql,value)!=null}
    }
    override fun locate(view:ChatMessageView,reference:OriginalTextReference):ChatMessageLocation? = safely {
        val value=view as? View ?: return@safely null
        if(value.owner!==this||value.reply?.paragraphs?.any {reference in it.references}!=true)return@safely null
        database.transaction {sql->
            val live=currentWithin(sql,value)?:return@transaction null
            if(live.reply==null)return@transaction null
            val row=sql.query("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision WHERE e.id=? AND e.currentRevision=?",listOf(reference.source.sourceId,reference.source.revision)).singleOrNull()?:return@transaction null
            val generation=sql.query("SELECT generation FROM chat_message_sources WHERE messageId=? AND entryId=?",listOf(value.message.id,reference.source.sourceId)).single().single() as Long
            Location(this,value,reference,StoredEntry(row[0] as String,Math.toIntExact(row[1] as Long),row[2] as String,row[3] as Long,row[4] as String,ProcessingPolicy.valueOf(row[5] as String)),generation)
        }
    }
    override fun locationCurrent(location:ChatMessageLocation):Boolean = observed {
        val value=location as? Location ?: return@observed false
        val fresh=if(value.owner===this)locate(value.view,value.reference)else null
        fresh!=null&&fresh.entry==value.entry&&fresh.generation==value.generation
    }
    override fun delete(request:DeleteChatMessage):ChatMessage =
        if(gate==null)deleteOwned(request) else gate.syncSourceMutation {deleteOwned(request)}
    private fun deleteOwned(request:DeleteChatMessage):ChatMessage = safely {database.transaction {sql->
        all(sql) // Authenticate the bounded collection before accepting mutation.
        val old=read(sql,request.id)?:throw ChatHistoryConflict();val row=old.row
        if(row[7]==request.mutationId) {
            if(old.question.deletion==null||row[1]!=request.expectedVersion+1||row[5]!=request.atMillis||old.question.deletion.expectedVersion!=request.expectedVersion)throw ChatHistoryConflict()
            return@transaction summary(old)
        }
        if(row[6]=="DELETED"||row[1]!=request.expectedVersion||request.atMillis<(row[5] as Long))throw ChatHistoryConflict()
        val next=row.toMutableList();next[1]=request.expectedVersion+1;next[5]=request.atMillis;next[6]="DELETED";next[7]=request.mutationId
        val sealed=cipher.sealQuestion(next,ChatQuestionPayload(null,emptyList(),request))
        try {
            check(sql.execute("UPDATE chat_messages SET version=?,updatedAtMillis=?,state='DELETED',lastMutationId=?,question=?,answer=X'' WHERE id=? AND version=?",listOf(next[1],request.atMillis,request.mutationId,sealed,request.id,request.expectedVersion))==1)
            sql.execute("DELETE FROM chat_message_sources WHERE messageId=?",listOf(request.id))
            summary(checkNotNull(read(sql,request.id)))
        } finally {sealed.fill(0)}
    }}

    /** Called before any service opens. Charge every pending answer its maximum envelope size. */
    internal fun reserveWithin(sql:RecordsSqlConnection,review:SelectedQuestionSendReview,at:Long) {
        live();require(at>=0);val rows=all(sql)
        if(rows.size>=ChatMessages.MAX_RETAINED)throw ChatHistoryLimit()
        val proof=SelectedQuestionSendProof.review(review);if(rows.any {it.row[0]==proof})throw ChatHistoryConflict()
        val sources=review.sources.mapIndexed {index,s->ChatSourceBinding(index,s.binding.source,s.binding.sourceHash,s.binding.generation)}
        val row:List<Any?> = listOf(proof,1L,proof,"",at,at,"PENDING","QUEUED",ByteArray(0),ByteArray(0))
        val q=cipher.sealQuestion(row,ChatQuestionPayload(review.request.question,sources))
        val a=try {cipher.sealAnswer(row,ChatAnswerPayload(review.request,null))}catch(f:Throwable){q.fill(0);throw f}
        try {
            // Pending already contains the full request. A reply adds two ints, at most five
            // paragraph string/count pairs, 4800 UTF-8 bytes and five encoded reviewed refs.
            val largestReference=review.request.sources.maxOf {24+it.reference.source.sourceId.toByteArray(Charsets.UTF_8).size+it.reference.quote.toByteArray(Charsets.UTF_8).size}
            if(a.size.toLong()+8+5*8+4800+5L*largestReference>ChatMessages.MAX_ANSWER_CELL)throw ChatHistoryLimit()
            val reserved=rows.sumOf {(it.row[8] as ByteArray).size.toLong()+if(it.row[6]=="PENDING")ChatMessages.MAX_ANSWER_CELL else (it.row[9] as ByteArray).size}
            if(reserved+q.size+ChatMessages.MAX_ANSWER_CELL>ChatMessages.MAX_BYTES)throw ChatHistoryLimit()
            check(sql.execute("INSERT INTO chat_messages("+ChatMessageSqlSchema.columns+") VALUES(?,?,?,?,?,?,?,?,?,?)",row.take(8)+listOf(q,a))==1)
            sources.forEach {s->check(sql.execute("INSERT INTO chat_message_sources("+ChatMessageSqlSchema.sourceColumns+") VALUES(?,?,?,?,?,?)",listOf(proof,s.ordinal,s.source.sourceId,s.source.revision,s.hash,s.generation))==1)}
            checkNotNull(read(sql,proof))
        } finally {q.fill(0);a.fill(0)}
    }
    internal fun acceptWithin(sql:RecordsSqlConnection,review:SelectedQuestionSendReview,reply:SelectedQuestionReply,result:String,at:Long) {
        live();val old=guardWithin(sql,review,setOf("PENDING"));val row=old.row
        require(at>=(row[5] as Long));val next=row.toMutableList()
        next[1]=Math.addExact(row[1] as Long,1L);next[3]=result;next[5]=at;next[6]="ANSWERED";next[7]="ANSWERED"
        val bytes=cipher.sealAnswer(next,ChatAnswerPayload(review.request,reply))
        try {
            check(sql.execute("UPDATE chat_messages SET version=?,resultHash=?,updatedAtMillis=?,state='ANSWERED',lastMutationId='ANSWERED',answer=? WHERE id=? AND version=? AND state='PENDING'",listOf(next[1],result,at,bytes,row[0],row[1]))==1)
            guardWithin(sql,review,setOf("ANSWERED"),result)
        } finally {bytes.fill(0)}
    }
    internal fun verifyWithin(sql:RecordsSqlConnection,review:SelectedQuestionSendReview,committed:Boolean,result:String?=null) {
        guardWithin(sql,review,if(committed)setOf("ANSWERED")else setOf("PENDING"),result)
    }
    /** Automatic finally cleanup is intentionally unable to remove a committed answer. */
    internal fun cancelWithin(sql:RecordsSqlConnection,review:SelectedQuestionSendReview,includeCommitted:Boolean) {
        live();val id=SelectedQuestionSendProof.review(review);val old=read(sql,id)?:throw ChatHistoryConflict()
        if(old.row[6]!="PENDING"&&!(includeCommitted&&old.row[6]=="ANSWERED"))return
        sql.execute("UPDATE chat_messages SET version=?,state='CANCELLED',lastMutationId='SEND_CANCELLED',answer=X'' WHERE id=? AND version=?",listOf(Math.addExact(old.row[1] as Long,1L),id,old.row[1]))
    }
    private fun guardWithin(sql:RecordsSqlConnection,review:SelectedQuestionSendReview,states:Set<String>,result:String?=null):DecodedChatRow {
        live();val proof=SelectedQuestionSendProof.review(review);val value=read(sql,proof)?:throw ChatHistoryConflict()
        require(value.row[2]==proof&&value.row[6] in states&&value.question.text==review.request.question)
        if(result!=null)require(value.row[3]==result)
        require(value.question.sources==review.sources.mapIndexed {i,s->ChatSourceBinding(i,s.binding.source,s.binding.sourceHash,s.binding.generation)})
        val body=checkNotNull(value.answer)
        require(body.request.id==review.request.id&&body.request.selectionId==review.request.selectionId&&
            body.request.target==review.request.target&&body.request.askedAtMillis==review.request.askedAtMillis&&
            body.request.questionTimeZone==review.request.questionTimeZone&&body.request.sources==review.request.sources)
        return value
    }
    private fun currentWithin(sql:RecordsSqlConnection,view:View):View? {
        if(view.owner!==this)return null
        val value=read(sql,view.message.id)?:return null
        if(value.row[1]!=view.message.version||value.row[3]!=view.result||value.row[6]=="DELETED")return null
        val now=materialize(sql,value)
        return now.takeIf {it.message.state==view.message.state&&(it.reply==null)==(view.reply==null)}
    }
    private fun materialize(sql:RecordsSqlConnection,value:DecodedChatRow):View {
        val ready=value.row[6]=="ANSWERED"
        val valid=ready&&localSourcesCurrent(sql,value)
        val message=summary(value,if(ready&&!valid)ChatMessageState.INVALIDATED else null)
        val citations=if(valid)checkNotNull(value.answer).request.sources.map {ChatHistoryCitation(it.reference,it.capturedAtMillis,it.captureTimeZone)}else emptyList()
        return View(this,message,if(valid)value.answer?.reply else null,Collections.unmodifiableList(citations),value.row[3] as String)
    }
    private fun localSourcesCurrent(sql:RecordsSqlConnection,value:DecodedChatRow):Boolean {
        val body=value.answer?:return false
        for((index,binding) in value.question.sources.withIndex()) {
            if(SqlErasureFileHooks.fenced(sql,binding.source.sourceId))return false
            val row=sql.query("SELECT e.currentRevision,e.privacyPolicy,e.capturedAtMillis,e.captureTimeZone,r.originalText,r.contentHash,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(binding.source.sourceId)).singleOrNull()?:return false
            val source=body.request.sources[index];val text=row[4] as String;val ref=source.reference
            if(row[0]!=binding.source.revision||row[1]==ProcessingPolicy.AI_NONE.name||row[2]!=source.capturedAtMillis||
                row[3]!=source.captureTimeZone||row[5]!=binding.hash||RemoteTextCodec.textHash(text)!=binding.hash||
                row[6]!=binding.generation||row[7]!=0L||ref.end>text.codePointCount(0,text.length)||
                text.substring(text.offsetByCodePoints(0,ref.start),text.offsetByCodePoints(0,ref.end))!=ref.quote)return false
        }
        return true
    }
    private fun summary(value:DecodedChatRow,state:ChatMessageState?=null)=ChatMessage(value.row[0] as String,value.row[1] as Long,
        value.row[4] as Long,value.row[5] as Long,state?:ChatMessageState.valueOf(value.row[6] as String),value.question.text)
    private fun all(sql:RecordsSqlConnection):List<DecodedChatRow> {
        live();val stats=sql.query("SELECT count(*),COALESCE(MAX(length(question)),0),COALESCE(MAX(length(answer)),0),COALESCE(SUM(length(question)+length(answer)),0) FROM chat_messages").single()
        require(stats[0] as Long<=ChatMessages.MAX_RETAINED&&stats[1] as Long<=ChatMessages.MAX_QUESTION_CELL&&
            stats[2] as Long<=ChatMessages.MAX_ANSWER_CELL&&stats[3] as Long<=ChatMessages.MAX_BYTES)
        return sql.query("SELECT "+ChatMessageSqlSchema.columns+" FROM chat_messages ORDER BY id").map {decode(sql,it)}
    }
    private fun read(sql:RecordsSqlConnection,id:String):DecodedChatRow? {
        val sizes=sql.query("SELECT length(question),length(answer) FROM chat_messages WHERE id=?",listOf(id)).singleOrNull()?:return null
        require(sizes[0] as Long in 29..ChatMessages.MAX_QUESTION_CELL&&sizes[1] as Long in 0..ChatMessages.MAX_ANSWER_CELL)
        return decode(sql,sql.query("SELECT "+ChatMessageSqlSchema.columns+" FROM chat_messages WHERE id=?",listOf(id)).single())
    }
    private fun decode(sql:RecordsSqlConnection,row:List<Any?>):DecodedChatRow {
        val relations=sql.query("SELECT ordinal,entryId,sourceRevision,sourceHash,generation FROM chat_message_sources WHERE messageId=? ORDER BY ordinal",listOf(row[0]))
        return cipher.decodeRow(row,relations)
    }
    internal fun validateWithin(sql:RecordsSqlConnection) {all(sql).forEach {if(it.answer!=null)require(localSourcesCurrent(sql,it))}}
    private fun live(){if(closed.get())throw ChatHistoryUnavailable()}
    private inline fun <T> safely(block:()->T):T=try {live();block()}catch(c:CancellationException){throw c}
        catch(f:ChatHistoryConflict){throw f}catch(f:ChatHistoryLimit){throw f}catch(_:Exception){throw ChatHistoryUnavailable()}
    private inline fun observed(block:()->Boolean):Boolean=try {live();block()}catch(c:CancellationException){throw c}catch(_:Exception){false}
    internal fun close(){closed.set(true)}
    internal fun wipeKey(){close();cipher.wipe()}
}
