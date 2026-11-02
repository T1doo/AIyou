package com.t1doo.aiyou.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Question text and deletion state are authenticated by SavedQuestionCipher, not an Entry FK. */
@Entity(tableName="saved_questions")
internal data class SavedQuestionEntity(@PrimaryKey val id:String,val version:Long,val lastMutationId:String,
    val createdAtMillis:Long,val updatedAtMillis:Long,val state:String,val payload:ByteArray)
