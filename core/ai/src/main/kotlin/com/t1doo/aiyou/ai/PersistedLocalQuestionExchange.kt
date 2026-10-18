package com.t1doo.aiyou.ai

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exercises persisted multi-source authority using ONLY the final memory-fixture service.
 * Not a SelectedQuestionService; UI can inject it only through the explicit memory rehearsal
 * capability. Production has no such capability and this cannot enable remote requests.
 * Real transport will need its own immediately-before-wire admission and protected secret wiring.
 */
class PersistedLocalQuestionExchange(private val repository: SelectedQuestionAuthorizationRepository,
    private val permit: SelectedQuestionSendPermit, private val service: LocalProtocolQuestionService,
    private val clock: () -> Instant) {
    private val used = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val invalid = AtomicReference<SendDenial?>()
    private val delegate = AtomicReference<SelectedQuestionExchange?>()
    private val watch = AtomicReference<SourceBoundTextSubscription?>()
    init { require(service.target == permit.review.request.target) }

    /** Owned nonblocking cancellation only; the IO execution owner cleans persisted unfinished work. */
    fun cancel() { stopped.set(true); closeOwned() }
    private fun closeOwned() {
        try { delegate.getAndSet(null)?.cancel() } finally { watch.getAndSet(null)?.close() }
    }
    private fun requireActive() {
        if (stopped.get()) throw CancellationException()
        invalid.get()?.let { throw SelectedQuestionSendDenied(it) }
    }
    private fun currentTime(): Instant { requireActive(); return clock().also { requireActive() } }
    private fun checkPersisted() {
        requireActive()
        repository.check(permit, ::currentTime)?.let { throw SelectedQuestionSendDenied(it) }
        requireActive()
    }
    fun execute(): SelectedQuestionSendReceipt {
        check(used.compareAndSet(false, true)) { "One execution only" }
        try {
            requireActive()
            val subscription = repository.subscribe(permit, ::currentTime) { reason ->
                invalid.compareAndSet(null, reason)
                closeOwned() // Observer has already left SQL. Never do persistent cleanup here.
            }
            watch.set(subscription)
            if (stopped.get() || invalid.get() != null) closeOwned()
            checkPersisted()
            repository.admit(permit, ::currentTime)
            checkPersisted()
            val owned = service.newExchange(permit.review.request)
            delegate.set(owned)
            if (stopped.get() || invalid.get() != null) closeOwned()
            checkPersisted() // Includes cancel/withdraw during exchange creation and registration.
            val reply = owned.execute()
            checkPersisted()
            // Pending observation ends before this owner's own COMMITTED transition. Reads
            // finishing after close cannot reserve an invalidation; accept/receipt still recheck.
            watch.getAndSet(null)?.close()
            val receipt = repository.accept(permit, reply, ::currentTime)
            requireActive()
            repository.checkReceipt(receipt, ::currentTime)?.let { throw SelectedQuestionSendDenied(it) }
            requireActive()
            return receipt
        } finally {
            closeOwned()
            repository.cancelPending(permit)
        }
    }
}
