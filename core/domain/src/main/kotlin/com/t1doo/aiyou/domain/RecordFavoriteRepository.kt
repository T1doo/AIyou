package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.RecordFavoriteMutation
import com.t1doo.aiyou.model.RecordFavoriteSnapshot

/** Blocking IO borrowed from the encrypted records owner; no independent storage or key lifetime. */
interface RecordFavoriteRepository {
    fun read(entryId: String): RecordFavoriteSnapshot
    fun snapshots(): Map<String, RecordFavoriteSnapshot>
    fun set(mutation: RecordFavoriteMutation): RecordFavoriteSnapshot
}

class RecordFavoriteConflict : IllegalStateException("Favorite version changed")
class RecordFavoriteUnavailable : IllegalStateException("Favorite source unavailable")
open class RecordFavoriteStorageFailure : IllegalStateException("Local favorites unavailable")
class RecordFavoriteTooBroad : RecordFavoriteStorageFailure()
