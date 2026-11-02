package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** A repository-owned local read proof. It contains no reusable remote permit or credential. */
interface ChatMessageView {
    val message:ChatMessage
    val reply:SelectedQuestionReply?
    /** Verified historical citation metadata, never a processing/send permission. */
    val citations:List<ChatHistoryCitation>
}
interface ChatMessageLocation {
    val entry:StoredEntry
    val reference:OriginalTextReference
    val generation:Long
}
/** Blocking IO on the existing records lane. Every answer/source use needs a fresh private-owner check. */
interface ChatHistoryRepository {
    fun list():List<ChatMessage>
    fun open(id:String,expectedVersion:Long):ChatMessageView?
    fun current(view:ChatMessageView):Boolean
    fun locate(view:ChatMessageView,reference:OriginalTextReference):ChatMessageLocation?
    fun locationCurrent(location:ChatMessageLocation):Boolean
    fun delete(request:DeleteChatMessage):ChatMessage
}
class ChatHistoryConflict:IllegalStateException("Conversation history changed")
class ChatHistoryLimit:IllegalStateException("Conversation history capacity reached")
class ChatHistoryUnavailable:IllegalStateException("Encrypted conversation history unavailable")
