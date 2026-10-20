package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*

/** Same-database assembly shared by foreground and minimal cold owners. Key bytes never leave data. */
class ReminderRuntimeStores(database:RecordsSqlDatabase,key:ByteArray,applicationId:String="com.t1doo.aiyou"):AutoCloseable {
    internal val tasks=SqlManualTaskRepository(database,key)
    private val meta=SqlReminderRebuildRepository(database,key,tasks)
    private val envelopes=ReminderEnvelopeCipher(key,applicationId)
    val manualTasks:ManualTaskRepository get()=tasks
    val resources=ReminderRuntimeResources(tasks,meta,envelopes)
    override fun close(){meta.close();envelopes.close();tasks.wipeKey()}
}
