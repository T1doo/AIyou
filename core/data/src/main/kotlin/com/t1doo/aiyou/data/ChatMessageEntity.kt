package com.t1doo.aiyou.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Questions have no Entry FK; derived answers are retired through the separate source index. */
@Entity(tableName="chat_messages")
internal data class ChatMessageEntity(@PrimaryKey val id:String,val version:Long,val originProof:String,
    val resultHash:String,val createdAtMillis:Long,val updatedAtMillis:Long,val state:String,
    val lastMutationId:String,val question:ByteArray,val answer:ByteArray)

@Entity(tableName="chat_message_sources",primaryKeys=["messageId","ordinal"],
    foreignKeys=[ForeignKey(entity=ChatMessageEntity::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=EntryEntity::class,parentColumns=["id"],childColumns=["entryId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["entryId"])])
internal data class ChatMessageSourceEntity(val messageId:String,val ordinal:Int,val entryId:String,
    val sourceRevision:Long,val sourceHash:String,val generation:Long)
