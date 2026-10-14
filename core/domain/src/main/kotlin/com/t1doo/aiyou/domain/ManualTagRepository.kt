package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ManualTagMutation
import com.t1doo.aiyou.model.ManualTagSnapshot

/** Blocking IO, borrowed from the encrypted records owner. No independent database/key lifecycle. */
interface ManualTagRepository {
    fun read(entryId: String): ManualTagSnapshot
    fun snapshots(): Map<String, ManualTagSnapshot>
    fun replace(mutation: ManualTagMutation): ManualTagSnapshot
}
class ManualTagConflict : IllegalStateException("Manual tag version changed")
class ManualTagUnavailable : IllegalStateException("Manual tag source unavailable")
class ManualTagStorageFailure : IllegalStateException("Encrypted manual tags unavailable")
