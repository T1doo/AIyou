package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ManualTaskCreate
import com.t1doo.aiyou.model.ManualTaskMutation
import com.t1doo.aiyou.model.ManualTaskSnapshot

/** Blocking IO borrowed from the encrypted records owner; it never schedules or sends. */
interface ManualTaskRepository {
    fun snapshots(): List<ManualTaskSnapshot>
    fun create(request: ManualTaskCreate): ManualTaskSnapshot
    fun change(request: ManualTaskMutation): ManualTaskSnapshot
}
class ManualTaskConflict : IllegalStateException("Manual task version changed")
class ManualTaskStorageFailure : IllegalStateException("Encrypted manual tasks unavailable")
