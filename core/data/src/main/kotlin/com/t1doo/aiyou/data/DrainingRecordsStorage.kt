package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.AwaitableRecordStorage

/** The production owner's lifetime, also compiled verbatim by host concurrency tests. */
internal class DrainingRecordsStorage(private val sql: DrainingRecordsSqlDatabase,
    private val attachments: EncryptedAttachmentStore, private val edits: EncryptedOriginalEditStore,
    private val closeDatabase: () -> Unit, private val wipeSharedKeys: () -> Unit) : AwaitableRecordStorage {
    private val monitor = Any()
    private var retired = false
    override fun close() { sql.seal(); attachments.close(); edits.close() }
    override fun awaitClosed() {
        close()
        // All self-wait checks precede every wait, including cross-store/SQL dependency waits.
        sql.checkExternalWait(); attachments.checkExternalWait(); edits.checkExternalWait()
        attachments.awaitIdle(); edits.awaitIdle(); sql.awaitIdle()
        synchronized(monitor) {
            if (!retired) {
                try { closeDatabase() }
                finally { retired = true; wipeSharedKeys() }
            }
        }
    }
}
