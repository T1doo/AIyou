package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ManualTaskCreate
import com.t1doo.aiyou.model.ManualTaskMutation
import com.t1doo.aiyou.model.ManualTaskSnapshot
import com.t1doo.aiyou.model.ManualTaskScheduleMutation
import com.t1doo.aiyou.model.ManualTaskRename
import com.t1doo.aiyou.model.ManualTaskReopen

/** Blocking IO borrowed from the encrypted records owner; it never schedules or sends. */
interface ManualTaskRepository {
    fun snapshots(): List<ManualTaskSnapshot>
    fun create(request: ManualTaskCreate): ManualTaskSnapshot
    fun change(request: ManualTaskMutation): ManualTaskSnapshot
    /** Optional adapters fail closed; encrypted production owner implements exact replay. */
    fun reopen(request:ManualTaskReopen):ManualTaskSnapshot = throw ManualTaskStorageFailure()
    fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot
    fun rename(request: ManualTaskRename): ManualTaskSnapshot
}
class ManualTaskConflict : IllegalStateException("Manual task version changed")
class ManualTaskStorageFailure : IllegalStateException("Encrypted manual tasks unavailable")
