package com.t1doo.aiyou.model

/** A stable import intent. The hash and size describe the original bytes, not a thumbnail. */
data class AttachmentIntent(
    val id: String,
    val entryId: String,
    val revision: Int,
    val mediaType: String,
    val sha256: String,
    val byteSize: Long,
)

enum class AttachmentState { WRITING, STAGED, READY, FAILED, CORRUPT }

data class LocalAttachment(val intent: AttachmentIntent, val state: AttachmentState)
