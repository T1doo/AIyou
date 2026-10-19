package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ModelConnectionChange
import com.t1doo.aiyou.model.ModelConnectionConfiguration

/** Local metadata over the existing encrypted records owner. No credentials, authorization or network. */
interface ModelConnectionCatalog {
    fun configurations(): List<ModelConnectionConfiguration>
    /** Retry confirms only the identical last mutation; any later change conflicts, never replays an old snapshot. */
    fun change(request: ModelConnectionChange): ModelConnectionConfiguration?
}
class ModelConnectionConflict : IllegalStateException("Connection configuration changed")
class ModelConnectionStorageFailure : IllegalStateException("Connection configuration storage unavailable")
class ModelConnectionLimit : IllegalStateException("Connection configuration limit reached")
