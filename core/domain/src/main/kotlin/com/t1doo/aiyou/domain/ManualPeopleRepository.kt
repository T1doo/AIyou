package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ManualPeopleMutation
import com.t1doo.aiyou.model.ManualPeopleSnapshot

/** Manual metadata in the existing encrypted owner. AI_NONE remains manually usable. Blocking IO. */
interface ManualPeopleRepository {
    fun read(entryId: String): ManualPeopleSnapshot
    fun snapshots(): Map<String, ManualPeopleSnapshot>
    fun replace(mutation: ManualPeopleMutation): ManualPeopleSnapshot
}
class ManualPeopleConflict : IllegalStateException("Manual people source or version changed")
class ManualPeopleUnavailable : IllegalStateException("Manual people source unavailable")
class ManualPeopleStorageFailure : IllegalStateException("Encrypted manual people unavailable")
class ManualPeopleTooBroad : IllegalStateException("Manual people scope too broad")
