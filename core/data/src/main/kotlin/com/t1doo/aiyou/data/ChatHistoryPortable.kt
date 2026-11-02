package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*

/** Full restore validates these logical cells before an intent; no old grant/session is imported. */
internal class ChatHistoryPortable(key:ByteArray):AutoCloseable {
    private val cipher=ChatMessageCipher(key)
    fun transform(row:List<Any?>,restoring:Boolean):List<Any?> {
        val question=cipher.portable(row,8,restoring)
        return try {row.toMutableList().also {it[8]=question;it[9]=cipher.portable(row,9,restoring)}}
        catch(f:Throwable){question.fill(0);throw f}
    }
    fun validate(data:FullBackupData) {
        val messages=data.tables[14];val relations=data.tables[15]
        require(messages.size<=ChatMessages.MAX_RETAINED&&relations.size<=ChatMessages.MAX_RETAINED*5)
        val entries=data.tables[0].associateBy {it[0]};val revisions=data.tables[1].associateBy {it.take(2)}
        val accesses=data.tables[2].associateBy {it[0]};var reserved=0L
        for(row in messages) {
            val sealed=transform(row,true)
            try {
                val sources=relations.filter {it[0]==row[0]}.sortedBy {it[1] as Long}.map {it.drop(1)}
                val decoded=cipher.decodeRow(sealed,sources)
                reserved+=(sealed[8] as ByteArray).size+if(row[6]=="PENDING")ChatMessages.MAX_ANSWER_CELL else (sealed[9] as ByteArray).size
                require(reserved<=ChatMessages.MAX_BYTES)
                val answer=decoded.answer?:continue
                for((index,binding) in decoded.question.sources.withIndex()) {
                    val entry=entries[binding.source.sourceId]?:error("Missing chat source")
                    val original=revisions[listOf(binding.source.sourceId,binding.source.revision)]?:error("Missing chat revision")
                    val access=accesses[binding.source.sourceId];val selected=answer.request.sources[index];val ref=selected.reference
                    val text=original[2] as String
                    require(entry[1]==binding.source.revision&&entry[4]!=ProcessingPolicy.AI_NONE.name&&access?.get(2)!=1L&&
                        (access?.get(1)?:0L)==binding.generation&&original[3]==binding.hash&&RemoteTextCodec.textHash(text)==binding.hash&&
                        entry[2]==selected.capturedAtMillis&&entry[3]==selected.captureTimeZone&&ref.end<=text.codePointCount(0,text.length)&&
                        text.substring(text.offsetByCodePoints(0,ref.start),text.offsetByCodePoints(0,ref.end))==ref.quote)
                }
            } finally { (sealed[8] as ByteArray).fill(0);(sealed[9] as ByteArray).fill(0) }
        }
        require(relations.all {source->messages.any {it[0]==source[0]}&&entries.containsKey(source[2])})
    }
    override fun close(){cipher.wipe()}
}
