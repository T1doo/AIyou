package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftRef

interface OriginalEditDraftStore {
    suspend fun read(entryId: String): OriginalEditDraft?
    suspend fun pending(): List<OriginalEditDraftRef>
    /** Compare the full acknowledged snapshot; terminal identities cannot accept late updates. */
    suspend fun write(expected: OriginalEditDraft?, next: OriginalEditDraft): OriginalEditDraft
}

class OriginalEditDraftConflict : IllegalStateException("Local edit draft changed")
class OriginalEditDraftIntegrityFailure : IllegalStateException("Local edit draft integrity check failed")
class OriginalEditDraftBusy : IllegalStateException("Local edit draft storage busy")
