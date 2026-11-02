package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.SaveQuestion
import com.t1doo.aiyou.model.SavedQuestion

/** Blocking IO on the admitted records owner's existing serial lane. No network or implicit Entry write. */
interface SavedQuestionRepository {
    fun read(id: String): SavedQuestion?
    fun list(): List<SavedQuestion>
    fun save(request: SaveQuestion): SavedQuestion
}
class SavedQuestionConflict : IllegalStateException("Saved question changed")
class SavedQuestionLimit : IllegalStateException("Saved question capacity reached")
class SavedQuestionStorageFailure : IllegalStateException("Encrypted saved questions unavailable")
