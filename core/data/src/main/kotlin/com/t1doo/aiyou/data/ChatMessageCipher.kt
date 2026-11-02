package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ChatSourceBinding(val ordinal:Int,val source:SourceVersion,val hash:String,val generation:Long) {override fun toString()="ChatSourceBinding(redacted)"}
internal data class ChatQuestionPayload(val text:String?,val sources:List<ChatSourceBinding>,val deletion:DeleteChatMessage?=null) {override fun toString()="ChatQuestionPayload(redacted)"}
internal data class ChatAnswerPayload(val request:SelectedQuestionRequest,val reply:SelectedQuestionReply?) {override fun toString()="ChatAnswerPayload(redacted)"}
internal data class DecodedChatRow(val row:List<Any?>,val question:ChatQuestionPayload,val answer:ChatAnswerPayload?)

/** Independent purposes. SQL source retirement never changes the immutable question AAD. */
internal class ChatMessageCipher(databaseKey:ByteArray) {
    private fun derive(databaseKey:ByteArray,label:String)=Mac.getInstance("HmacSHA256").run {
        require(databaseKey.size==32);init(SecretKeySpec(databaseKey,"HmacSHA256"));doFinal(label.toByteArray(Charsets.US_ASCII)+byteArrayOf(1))
    }
    private val questionKey=derive(databaseKey,"com.t1doo.aiyou.chat-question.v1")
    private val answerKey=derive(databaseKey,"com.t1doo.aiyou.chat-answer.v1")
    private fun questionAad(row:List<Any?>)=PackageWriter(512).use { w ->
        w.string("AIyou/chat-question/1",32);w.string(row[0] as String,64);w.string(row[2] as String,64);w.i64(row[4] as Long);w.bytes()
    }
    private fun answerAad(row:List<Any?>)=PackageWriter(1024).use { w ->
        w.string("AIyou/chat-answer/1",32);for(i in listOf(0,2,3))w.string(row[i] as String,64)
        w.i64(row[1] as Long);w.i64(row[4] as Long);w.i64(row[5] as Long);w.string(row[6] as String,16);w.string(row[7] as String,64);w.bytes()
    }
    private fun echo(w:PackageWriter,aad:ByteArray) {w.i32(aad.size);w.raw(aad)}
    private fun echo(r:PackageReader,aad:ByteArray) {require(java.security.MessageDigest.isEqual(r.raw(r.i32(),1024),aad))}
    private fun reference(w:PackageWriter,v:OriginalTextReference) {w.string(v.source.sourceId,512);w.i64(v.source.revision);w.i32(v.start);w.i32(v.end);w.string(v.quote,1280)}
    private fun reference(r:PackageReader)=OriginalTextReference(SourceVersion(r.string(512),r.i64()),r.i32(),r.i32(),r.string(1280))
    fun sealQuestion(row:List<Any?>,p:ChatQuestionPayload):ByteArray {
        val aad=questionAad(row)
        val plain=PackageWriter(ChatMessages.MAX_QUESTION_CELL-29).use {w ->
            w.string("AIyou-chat-question",32);w.i32(1);echo(w,aad);w.i32(if(p.text==null)0 else 1)
            if(p.text!=null) {SavedQuestions.question(p.text);w.string(p.text,2048)}
            w.i32(p.sources.size);p.sources.forEach {s->w.i32(s.ordinal);w.string(s.source.sourceId,512);w.i64(s.source.revision);w.string(s.hash,64);w.i64(s.generation)}
            w.i32(if(p.deletion==null)0 else 1);p.deletion?.let {w.i64(it.expectedVersion);w.string(it.mutationId,36);w.i64(it.atMillis)};w.bytes()
        }
        return seal(questionKey,aad,plain)
    }
    fun openQuestion(row:List<Any?>):ChatQuestionPayload {
        val aad=questionAad(row);val plain=open(questionKey,aad,row[8] as ByteArray,ChatMessages.MAX_QUESTION_CELL)
        return try {
            val r=PackageReader(plain);require(r.string(32)=="AIyou-chat-question"&&r.i32()==1);echo(r,aad)
            val text=when(r.i32()){0->null;1->r.string(2048);else->error("Invalid question")}
            val bindings=List(r.count(5)){ChatSourceBinding(r.i32(),SourceVersion(r.string(512),r.i64()),r.string(64),r.i64())}
            val deletion=when(r.i32()){0->null;1->DeleteChatMessage(row[0] as String,r.i64(),r.string(36),r.i64());else->error("Invalid deletion")};r.end()
            require(bindings.map{it.ordinal}==bindings.indices.toList()&&bindings.map{it.source.sourceId}.toSet().size==bindings.size)
            bindings.forEach {ChatMessages.id(it.hash);require(it.generation>=0)}
            if(text==null)require(row[6]=="DELETED"&&bindings.isEmpty()&&deletion!=null&&row[1]==deletion.expectedVersion+1&&row[7]==deletion.mutationId&&row[5]==deletion.atMillis)
            else {SavedQuestions.question(text);require(row[6]!="DELETED"&&deletion==null&&bindings.isNotEmpty())}
            ChatQuestionPayload(text,bindings,deletion)
        } finally {plain.fill(0)}
    }
    fun sealAnswer(row:List<Any?>,p:ChatAnswerPayload):ByteArray {
        val aad=answerAad(row);val q=p.request
        val plain=PackageWriter(ChatMessages.MAX_ANSWER_CELL-29).use {w ->
            w.string("AIyou-chat-answer",32);w.i32(1);echo(w,aad)
            w.string(q.id,256);w.string(q.selectionId,256);w.string(q.question,2048);w.i64(q.askedAtMillis);w.string(q.questionTimeZone,1024)
            w.string(q.target.protocol.name,32);w.string(q.target.apiRoot,16384);w.string(q.target.modelId,1024);w.i64(q.target.version)
            w.i32(q.sources.size);q.sources.forEach {s->reference(w,s.reference);w.i64(s.capturedAtMillis);w.string(s.captureTimeZone,1024);w.string(s.policy.name,32)}
            w.i32(if(p.reply==null)0 else 1);p.reply?.let {reply ->
                require(SelectedQuestionSendProof.reply(q,reply)==row[3]);w.i32(if(reply.noEvidence)1 else 0);w.i32(reply.paragraphs.size)
                reply.paragraphs.forEach {paragraph->w.string(paragraph.text,4800);w.i32(paragraph.references.size);paragraph.references.forEach {reference(w,it)}}
            };w.bytes()
        };return seal(answerKey,aad,plain)
    }
    fun openAnswer(row:List<Any?>,question:ChatQuestionPayload):ChatAnswerPayload {
        require(row[6] in setOf("PENDING","ANSWERED"));val aad=answerAad(row)
        val plain=open(answerKey,aad,row[9] as ByteArray,ChatMessages.MAX_ANSWER_CELL)
        return try {
            val r=PackageReader(plain);require(r.string(32)=="AIyou-chat-answer"&&r.i32()==1);echo(r,aad)
            val id=r.string(256);val selection=r.string(256);val text=r.string(2048);val asked=r.i64();val zone=r.string(1024)
            val target=SelectedQuestionTarget(ConnectionProtocol.valueOf(r.string(32)),r.string(16384),r.string(1024),r.i64())
            val sources=List(r.count(5)){SelectedQuestionSource(reference(r),r.i64(),r.string(1024),ProcessingPolicy.valueOf(r.string(32)))}
            val request=SelectedQuestionRequest(id,selection,text,target,asked,zone,sources)
            val reply=when(r.i32()) {0->null;1->{val noEvidence=r.i32();require(noEvidence in 0..1)
                SelectedQuestionReply(id,selection,noEvidence==1,List(r.count(5)){SelectedQuestionParagraph(r.string(4800),List(r.count(5)){reference(r)})})};else->error("Invalid answer")};r.end()
            require(text==question.text&&sources.map{it.reference.source}==question.sources.map{it.source})
            require((reply==null)==(row[6]=="PENDING"));if(reply==null)require(row[3]=="")else require(SelectedQuestionSendProof.reply(request,reply)==row[3])
            ChatAnswerPayload(request,reply)
        } finally {plain.fill(0)}
    }
    fun decodeRow(row:List<Any?>,relations:List<List<Any?>>):DecodedChatRow {
        require(row.size==10);ChatMessages.id(row[0] as String);require(row[0]==row[2]&&(row[1] as Long)>0&&
            (row[4] as Long)>=0&&(row[5] as Long)>=(row[4] as Long));val state=ChatMessageState.valueOf(row[6] as String)
        val hash=row[3] as String;require(hash.isEmpty()||hash.matches(Regex("[0-9a-f]{64}")))
        val q=openQuestion(row);require(relations.size<=5)
        val bindings=relations.map {ChatSourceBinding(Math.toIntExact(it[0] as Long),SourceVersion(it[1] as String,it[2] as Long),it[3] as String,it[4] as Long)}
        require(bindings.all {it in q.sources}&&bindings.map{it.ordinal}.toSet().size==bindings.size)
        val body=if(state in setOf(ChatMessageState.PENDING,ChatMessageState.ANSWERED)) {
            require(bindings==q.sources);openAnswer(row,q)
        }else {require((row[9] as ByteArray).isEmpty());null}
        if(state==ChatMessageState.DELETED)require(bindings.isEmpty())
        return DecodedChatRow(row,q,body)
    }
    private fun seal(key:ByteArray,aad:ByteArray,plain:ByteArray):ByteArray = try {
        val nonce=ByteArray(12).also(SecureRandom()::nextBytes);val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad);byteArrayOf(1)+nonce+c.doFinal(plain)
    } finally {plain.fill(0)}
    private fun open(key:ByteArray,aad:ByteArray,bytes:ByteArray,maximum:Int):ByteArray {
        require(bytes.size in 29..maximum&&bytes[0]==1.toByte());val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(1,13)));c.updateAAD(aad);return c.doFinal(bytes,13,bytes.size-13)
    }
    fun portable(row:List<Any?>,index:Int,restoring:Boolean):ByteArray {
        require(index in 8..9);val bytes=row[index] as ByteArray
        if(index==9&&bytes.isEmpty()){require(row[6] !in setOf("PENDING","ANSWERED"));return ByteArray(0)}
        val key=if(index==8)questionKey else answerKey;val aad=if(index==8)questionAad(row)else answerAad(row)
        val maximum=if(index==8)ChatMessages.MAX_QUESTION_CELL else ChatMessages.MAX_ANSWER_CELL
        return if(restoring)PortableCipherCell.seal(key,aad,bytes,maximum) else PortableCipherCell.open(key,aad,bytes,maximum)
    }
    fun wipe(){questionKey.fill(0);answerKey.fill(0)}
}
