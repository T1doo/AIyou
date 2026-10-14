package com.t1doo.aiyou.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/** All three managed tables, including FTS shadow tables, live inside records.db SQLCipher. */
@Entity(tableName = "local_search_fts")
@Fts4(tokenizer = FtsOptions.TOKENIZER_SIMPLE, notIndexed = ["entryId"])
internal data class LocalSearchFtsEntity(val entryId: String, val bodyTokens: String, val tagTokens: String)

@Entity(tableName = "local_search_documents")
internal data class LocalSearchDocumentEntity(@PrimaryKey val entryId: String,
    val sourceRevision: Int, val contentHash: String, val tagVersion: Long,
    val visibilityGeneration: Long, val tokenizerVersion: Int, val tokenizerProfile: String, val indexedEpoch: Long)

@Entity(tableName = "local_search_epoch")
internal data class LocalSearchEpochEntity(@PrimaryKey val slot: Int, val epoch: Long,
    val tokenizerVersion: Int, val tokenizerProfile: String)
