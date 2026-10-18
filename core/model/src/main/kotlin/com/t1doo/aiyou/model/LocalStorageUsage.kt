package com.t1doo.aiyou.model

/** File lengths in the app's records directory, including recovery files; not allocated disk blocks. */
data class LocalStorageUsage(
    val databaseBytes: Long = 0,
    val attachmentBytes: Long = 0,
    val editDraftBytes: Long = 0,
    val otherBytes: Long = 0,
) {
    init {
        require(listOf(databaseBytes, attachmentBytes, editDraftBytes, otherBytes).all { it >= 0 })
    }
    val totalBytes: Long = Math.addExact(Math.addExact(databaseBytes, attachmentBytes),
        Math.addExact(editDraftBytes, otherBytes))
}
