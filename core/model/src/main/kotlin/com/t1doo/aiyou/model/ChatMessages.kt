package com.t1doo.aiyou.model

/** Local conversation data, never an Entry, stable fact or sending authority. */
object ChatMessages {
    const val MAX_RETAINED = 128
    const val MAX_BYTES = 4L * 1024 * 1024
    const val MAX_QUESTION_CELL = 8192
    const val MAX_ANSWER_CELL = 65536
    fun id(value:String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
}
enum class ChatMessageState { PENDING, ANSWERED, CANCELLED, INVALIDATED, DELETED }
class ChatHistoryCitation(val reference:OriginalTextReference,val capturedAtMillis:Long,val captureTimeZone:String) {
    override fun toString()="ChatHistoryCitation(redacted)"
}

class ChatMessage(val id:String,val version:Long,val createdAtMillis:Long,val updatedAtMillis:Long,
    val state:ChatMessageState,val question:String?) {
    init {
        ChatMessages.id(id);require(version>0&&createdAtMillis>=0&&updatedAtMillis>=createdAtMillis)
        require((question==null)==(state==ChatMessageState.DELETED));question?.let(SavedQuestions::question)
    }
    override fun toString()="ChatMessage(redacted, state=$state, version=$version)"
}
class DeleteChatMessage(val id:String,val expectedVersion:Long,val mutationId:String,val atMillis:Long) {
    init { ChatMessages.id(id);SavedQuestions.id(mutationId);require(expectedVersion in 1 until Long.MAX_VALUE&&atMillis>=0) }
    override fun toString()="DeleteChatMessage(redacted)"
}
