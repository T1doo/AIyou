package com.t1doo.aiyou.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName="reminder_runtime_meta")
internal data class ReminderRuntimeMetaEntity(@PrimaryKey val slot:Int,val version:Long,val ciphertext:ByteArray)
