package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.AwaitableRecordStorage

/** The production owner's lifetime, also compiled verbatim by host concurrency tests. */
internal class DrainingRecordsStorage(private val sql: DrainingRecordsSqlDatabase,
    private val attachments: EncryptedAttachmentStore, private val edits: EncryptedOriginalEditStore,
    private val closeDatabase: () -> Unit, private val wipeSharedKeys: () -> Unit) : AwaitableRecordStorage {
    private val monitor = Any()
    private var retired = false
    private var closureFailure:com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed?=null
    override fun close() { sql.seal(); attachments.close(); edits.close() }
    override fun awaitClosed() {
        close()
        // All self-wait checks precede every wait, including cross-store/SQL dependency waits.
        sql.checkExternalWait(); attachments.checkExternalWait(); edits.checkExternalWait()
        attachments.awaitIdle(); edits.awaitIdle(); sql.awaitIdle()
        synchronized(monitor) {
            closureFailure?.let{throw it}
            if (!retired) {
                try { closeDatabase();wipeSharedKeys();retired=true }
                catch(failure:Throwable){
                    throw com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed(arrayOf(this,failure)).also{closureFailure=it}
                }
            }
        }
    }
}
