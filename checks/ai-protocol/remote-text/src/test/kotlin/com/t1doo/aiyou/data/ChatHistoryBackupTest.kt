package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production encrypted file codec and restore journal over independent real SQLite files/keys. No SAF/device. */
class ChatHistoryBackupTest {
    @Test fun crossKeyFilePendingDoneLostAckAndReexportKeepBodiesButNeverRestoreSending():Unit=runBlocking {
        FullFixture().use {source->FullFixture(90).use {target->FullFixture(120).use {third->
            val h=Sender(source);val readers=listOf(SqlChatHistoryRepository(target.gateway,target.key),SqlChatHistoryRepository(third.gateway,third.key))
            try {
                val (permit,active)=h.answer();h.pending();val (_,deleted)=h.answer()
                h.history.delete(DeleteChatMessage(deleted.id,deleted.version,uuid(),NOW.toEpochMilli()+1))
                val oldView=h.history.open(active.id,active.version)!!
                val snapshot=source.backup.capture(uuid(),100);assertEquals(16,(snapshot as FullBackupData).version);assertEquals(3,snapshot.summary.chatMessages)
                val file=source.root.resolve("synthetic-chat-full.aib")
                val prepared=source.backup.seal(snapshot,password())
                try {Files.newOutputStream(file).use {prepared.writeTo(it){true}}}finally{prepared.release();snapshot.release()}
                assertEquals("AIYOUF16",Files.readAllBytes(file).take(8).toByteArray().toString(Charsets.US_ASCII))
                val pack=target.backup.open({Files.newInputStream(file)},password())
                try {
                    target.checkpoint={if(it=="full-intent")error("Synthetic pending interruption")}
                    assertThrows(Exception::class.java){runBlocking {target.backup.restore(pack){true}}}
                    assertFalse(target.backup.completed(pack));assertTrue(readers[0].list().isEmpty())
                    target.checkpoint={if(it=="full-committed")error("Synthetic durable DONE acknowledgement loss")}
                    assertThrows(Exception::class.java){runBlocking {target.backup.restore(pack){true}}}
                    target.checkpoint={};assertTrue(target.backup.completed(pack));assertTrue(target.backup.restore(pack){true}.replayed)
                    val restored=readers[0].open(active.id,active.version)!!
                    assertEquals(oldView.reply!!.paragraphs.map{it.text},restored.reply!!.paragraphs.map{it.text});assertTrue(readers[0].current(restored))
                    assertFalse(readers[0].current(oldView));assertNotNull(readers[0].locate(restored,restored.reply!!.paragraphs.first().references.first()))
                    assertTrue(readers[0].list().any{it.state==ChatMessageState.PENDING})
                    assertTrue(target.db.read("SELECT connection,consent FROM remote_source_access").all{it==listOf(null,null)})
                    assertTrue(target.db.read("SELECT * FROM remote_text_jobs").isEmpty())
                    assertTrue(target.db.read("SELECT * FROM local_jobs WHERE operation='SELECTED_QUESTION_SEND_V1'").isEmpty())
                    val newAuthority=SqlSelectedQuestionAuthorizationRepository(target.gateway,readers[0])
                    try {assertNotNull(newAuthority.check(permit){NOW});assertThrows(SelectedQuestionSendDenied::class.java){newAuthority.authorizeReviewedSend(permit.review){NOW}}}
                    finally {newAuthority.close()}
                    val before=source.db.read("SELECT question,answer FROM chat_messages WHERE id=?",active.id).single()
                    val after=target.db.read("SELECT question,answer FROM chat_messages WHERE id=?",active.id).single()
                    assertFalse((before[0] as ByteArray).contentEquals(after[0] as ByteArray));assertFalse((before[1] as ByteArray).contentEquals(after[1] as ByteArray))
                    readers[0].delete(DeleteChatMessage(active.id,active.version,uuid(),NOW.toEpochMilli()+1))
                    assertTrue(target.backup.restore(pack){true}.replayed);assertTrue(readers[0].list().none{it.id==active.id})
                    val next=target.backup.capture(uuid(),200);assertEquals(16,(next as FullBackupData).version)
                    val bytes=export(target,next);next.release();val again=third.backup.open({bytes.inputStream()},password())
                    try {third.backup.restore(again){true};assertTrue(readers[1].list().none{it.id==active.id||it.id==deleted.id})
                        assertTrue(readers[1].list().any{it.state==ChatMessageState.PENDING})}
                    finally {again.release();bytes.fill(0)}
                }finally{pack.release()}
            }finally{h.close();readers.forEach{it.wipeKey()}}
        }}}
    }
    @Test fun sourceRetirementMakesPreparedSnapshotStaleAndInactiveHistoryCannotDowngrade():Unit=runBlocking {
        FullFixture().use {source->FullFixture(90).use {target->
            Sender(source).use {h->
                val (_,message)=h.answer();val snap=source.backup.capture(uuid(),100)
                source.revise(h.ids[1],"真实生产修订路径的合成新原文")
                assertThrows(Exception::class.java){runBlocking {source.backup.seal(snap,password())}};snap.release()
                val retired=h.history.list().single();assertEquals(message.question,retired.question);assertEquals(ChatMessageState.INVALIDATED,retired.state)
                val next=source.backup.capture(uuid(),101);assertEquals(16,(next as FullBackupData).version)
                val bytes=export(source,next);next.release();val pack=target.backup.open({bytes.inputStream()},password())
                val history=SqlChatHistoryRepository(target.gateway,target.key)
                try {target.backup.restore(pack){true};val row=history.list().single();assertEquals(message.question,row.question)
                    assertEquals(ChatMessageState.INVALIDATED,row.state);assertNull(history.open(row.id,row.version)!!.reply)}
                finally{history.wipeKey();pack.release();bytes.fill(0)}
            }
        }}
    }
    @Test fun missingRelationAndOldHeaderFailBeforeAnyRestoreIntent():Unit=runBlocking {
        FullFixture().use {source->FullFixture(90).use {target->
            Sender(source).use {h->
                h.answer();val snap=source.backup.capture(uuid(),100) as FullBackupData
                val logical=FullBackupCodec.encode(snap)
                try {
                    val old=logical.copyOf();val nameSize=ByteBuffer.wrap(old).int
                    val name="AIyou-logical-full-v15".toByteArray();assertEquals(nameSize,name.size);name.copyInto(old,4)
                    ByteBuffer.wrap(old,4+nameSize,4).putInt(15)
                    assertThrows(Exception::class.java){FullBackupCodec.decode(old)};old.fill(0)
                    val tables=snap.tables.map{rows->rows.map{row->row.map{if(it is ByteArray)it.copyOf()else it}}}.toMutableList()
                    tables[15]=tables[15].dropLast(1)
                    val bad=FullBackupData(uuid(),101,tables,emptyList(),emptyList(),null)
                    val prepared=RecordMediaPackageCipher().sealFull(bad,password(),{error("No synthetic media")},{true})
                    val out=ByteArrayOutputStream()
                    try {prepared.writeTo(out){true}}finally{prepared.release();bad.release()}
                    val bytes=out.toByteArray()
                    try {assertThrows(Exception::class.java){runBlocking {target.backup.open({bytes.inputStream()},password())}}}
                    finally{bytes.fill(0)}
                    assertTrue(target.db.read("SELECT * FROM record_package_receipts WHERE operation LIKE 'FULL_BACKUP_IMPORT%'").isEmpty())
                    assertTrue(target.db.read("SELECT * FROM chat_messages").isEmpty())
                }finally{logical.fill(0);snap.release()}
            }
        }}
    }
    private class Sender(val f:FullFixture):AutoCloseable {
        val history=SqlChatHistoryRepository(f.gateway,f.key)
        val authority=SqlSelectedQuestionAuthorizationRepository(f.gateway,history)
        val ids=listOf(f.entry(text="合成甲🙂引用原文"),f.entry(text="合成乙🙂引用原文"))
        val connection=ProviderConnection("synthetic-history",1,"https://history.invalid/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",SecretReference("never-resolved-synthetic-reference"))
        init {
            val raw=RemoteTextTransactions(f.gateway);val records=SqlRecordAuthorizationRepository(f.gateway)
            for(id in ids) {
                val source=SourceVersion(id,1);val before=raw.readAccess(id)!!
                check(raw.updateAuthorization(source,before.generation,ProcessingPolicy.LOCAL_ONLY,connection,null)==null)
                val preview=records.previewAuthorization(records.readAuthorization(source).binding,TextPurpose.CONVERSATION){NOW}
                records.changeAuthorization(RecordAuthorizationChange.confirm(preview,NOW.minusSeconds(1),NOW.plusSeconds(600),uuid())){NOW}
            }
        }
        fun pending():SelectedQuestionSendPermit {
            val request=SelectedQuestionRequest(uuid(),uuid(),"这两条合成记录的内容是什么🙂？",SelectedQuestionTarget(connection.protocol,connection.apiRoot,connection.modelId),NOW.toEpochMilli(),"UTC",
                ids.mapIndexed {i,id->val quote=if(i==0)"合成甲🙂"else"合成乙🙂"
                    SelectedQuestionSource(OriginalTextReference(SourceVersion(id,1),0,quote.codePointCount(0,quote.length),quote),5,"UTC",ProcessingPolicy.REMOTE_ALLOWED)})
            return authority.authorizeReviewedSend(authority.review(request,connection,"selected-question-protocol-v1"){NOW}){NOW}
        }
        fun answer():Pair<SelectedQuestionSendPermit,ChatMessage> {
            val p=pending();authority.admit(p){NOW}
            val reply=SelectedQuestionReply(p.review.request.id,p.review.request.selectionId,false,p.review.request.sources.map{SelectedQuestionParagraph("合成历史回答🙂："+it.reference.quote,listOf(it.reference))})
            authority.accept(p,reply){NOW};return p to history.list().single{it.id==SelectedQuestionSendProof.review(p.review)}
        }
        override fun close(){authority.close();history.wipeKey()}
    }
    private suspend fun export(f:FullFixture,snapshot:FullBackupSnapshot):ByteArray {
        val prepared=f.backup.seal(snapshot,password());val out=ByteArrayOutputStream()
        return try{prepared.writeTo(out){true};out.toByteArray()}finally{prepared.release()}
    }
    companion object {
        private val NOW=Instant.parse("2026-11-02T01:00:00Z")
        private fun uuid()=UUID.randomUUID().toString()
        private fun password()="synthetic-chat-backup-passphrase".toCharArray()
    }
}
