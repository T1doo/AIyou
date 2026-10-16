package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.RecordEventDateMutation
import com.t1doo.aiyou.model.RecordEventDateSnapshot

/** Borrowed IO inside the same encrypted records owner and counted lease. */
interface RecordEventDateRepository {
    fun read(entryId: String): RecordEventDateSnapshot
    fun snapshots(): Map<String, RecordEventDateSnapshot>
    fun set(mutation: RecordEventDateMutation): RecordEventDateSnapshot
}

class RecordEventDateConflict : IllegalStateException("Event date or original changed")
class RecordEventDateUnavailable : IllegalStateException("Event date source unavailable")
open class RecordEventDateStorageFailure : IllegalStateException("Local event dates unavailable")
class RecordEventDateTooBroad : RecordEventDateStorageFailure()
