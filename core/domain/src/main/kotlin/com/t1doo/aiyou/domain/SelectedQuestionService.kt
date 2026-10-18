package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Product question flow port. Current implementations are explicitly local protocol fixtures.
 * No remote authority, secret, persistent source job or network capability can be inferred here.
 * A future real provider needs separate atomic multi-source persisted admission and invalidation.
 */
interface SelectedQuestionService {
    val mode: SelectedQuestionMode
    /** Immutable for this service's lifetime. Configuration changes replace the service instance. */
    val target: SelectedQuestionTarget
    /** IO worker, after the app has checked/committed the current reviewed local source proof. */
    fun newExchange(request: SelectedQuestionRequest): SelectedQuestionExchange
}

interface SelectedQuestionExchange {
    /** One execution, complete bounded output only. No preview is an accepted result. */
    fun execute(): SelectedQuestionReply
    /** Nonblocking, owned cancellation. A noncooperative late return still cannot publish. */
    fun cancel()
}
