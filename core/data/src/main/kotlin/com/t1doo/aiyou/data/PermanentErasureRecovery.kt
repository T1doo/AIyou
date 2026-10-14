package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.PermanentErasureRepository
import com.t1doo.aiyou.domain.PermanentErasureConflict
import com.t1doo.aiyou.domain.PermanentErasureIncomplete

/** One bounded startup pass. A broken source stays fenced without blocking healthy records. */
internal suspend fun resumePermanentErasure(repo: PermanentErasureRepository) {
    for (receipt in repo.pending(limit=4).receipts) try { repo.erase(receipt.intent) }
        catch (_: PermanentErasureIncomplete) { /* explicit retry, never an automatic loop */ }
        catch (_: PermanentErasureConflict) { /* inconsistent source stays fenced and requires readback */ }
}
