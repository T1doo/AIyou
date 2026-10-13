package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.WeakHashMap

enum class SendDenial {
    POLICY_BLOCKED, SOURCE_CHANGED, SOURCE_DELETED, CONNECTION_MISSING, PROVIDER_CHANGED,
    CONFIGURATION_CHANGED, CONSENT_MISSING, CONSENT_REVOKED, CONSENT_EXPIRED,
    CONSENT_NOT_CURRENT, CONSENT_CHANGED, SCOPE_MISMATCH, QUEUE_INVALIDATED,
    ALREADY_SENT, SECRET_UNAVAILABLE, CONFIGURATION_INVALID, STATE_UNAVAILABLE,
    /** Observer control signal: a trusted clock cancelled execution, not an authorization grant/denial. */
    EXECUTION_CANCELLED,
}

/** Metadata-only queue ticket. The HTTP guard creates it when the exchange is queued. */
class QueuedSendStamp internal constructor(
    internal val owner: Any,
    val intent: RemoteSendIntent,
    val generation: Long,
    val connection: ProviderConnection?,
    val consent: ConsentGrant?,
) {
    override fun toString() = "QueuedSendStamp(<redacted>, generation=$generation)"
}

class SendPermit internal constructor(internal val stamp: QueuedSendStamp) {
    @Volatile private var invalid = false
    val invalidated: Boolean get() = invalid
    internal fun invalidate() { invalid = true }
    override fun toString() = "SendPermit(<redacted>, invalidated=$invalidated)"
}

sealed interface SendAdmission {
    data class Denied(val reason: SendDenial) : SendAdmission
    class Granted(val permit: SendPermit) : SendAdmission
}

class AuthorizedResultReceipt internal constructor(internal val stamp: QueuedSendStamp) {
    override fun toString() = "AuthorizedResultReceipt(<redacted>)"
}

sealed interface ResultAdmission {
    data class Denied(val reason: SendDenial) : ResultAdmission
    class Granted(val receipt: AuthorizedResultReceipt) : ResultAdmission
}

/**
 * One lock owns current state, queue generation and one-use admission registration.
 * Only a trusted, nonblocking clock read runs under this lock; no resolver, network
 * or event callback does. In-memory state only;
 * future persistent/App wiring must provide the corresponding authoritative transaction.
 */
class CurrentSendAuthority(initial: RemoteSendState) {
    private val lock = Any()
    private val owner = Any()
    private var current = initial
    private var generation = 0L
    private enum class TicketState { QUEUED, CLAIMED, FINISHED }
    // Weak identity keys preserve one-use/issuance checks while clients hold metadata,
    // without retaining every abandoned ticket or receipt for the lifetime of this authority.
    private val tickets = WeakHashMap<QueuedSendStamp, TicketState>()
    private val receipts = WeakHashMap<AuthorizedResultReceipt, QueuedSendStamp>()
    private val admitted = mutableMapOf<SendPermit, () -> Unit>()

    fun queue(intent: RemoteSendIntent): QueuedSendStamp = synchronized(lock) {
        QueuedSendStamp(owner, intent, generation, current.connection, current.consent).also {
            tickets[it] = TicketState.QUEUED
        }
    }

    fun replaceState(next: RemoteSendState) {
        val cancellations = synchronized(lock) {
            check(generation < Long.MAX_VALUE) { "Send generation exhausted" }
            current = next
            generation++
            admitted.keys.forEach { it.invalidate(); tickets[it.stamp] = TicketState.FINISHED }
            admitted.values.toList().also { admitted.clear() }
        }
        cancellations.forEach { cancel ->
            try { cancel() } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError) throw failure
                // An owned cancellation failure cannot undo invalidation or expose its cause.
            }
        }
    }

    fun check(stamp: QueuedSendStamp, now: Instant): SendDenial? = synchronized(lock) {
        rejection(stamp, now)
    }

    /** Final pre-wire check and registration are the atomic send admission point. */
    fun claim(stamp: QueuedSendStamp, now: Instant, onInvalidated: () -> Unit): SendAdmission =
        claim(stamp, { now }, onInvalidated)

    fun claim(stamp: QueuedSendStamp, currentTime: () -> Instant, onInvalidated: () -> Unit): SendAdmission = synchronized(lock) {
        rejection(stamp, currentTime())?.let { return SendAdmission.Denied(it) }
        if (tickets[stamp] != TicketState.QUEUED) return SendAdmission.Denied(SendDenial.ALREADY_SENT)
        tickets[stamp] = TicketState.CLAIMED
        val permit = SendPermit(stamp)
        admitted[permit] = onInvalidated
        SendAdmission.Granted(permit)
    }

    /** Result validation is separate from the low-level protocol completion fact. */
    fun checkResult(permit: SendPermit, now: Instant): SendDenial? = synchronized(lock) {
        if (permit.invalidated || !admitted.containsKey(permit)) SendDenial.QUEUE_INVALIDATED
        else rejection(permit.stamp, now)
    }

    fun release(permit: SendPermit) { synchronized(lock) {
        if (admitted.remove(permit) != null) tickets[permit.stamp] = TicketState.FINISHED
    } }

    /** Atomically check the finished result and release its registered network cancellation. */
    fun finish(permit: SendPermit, now: Instant): ResultAdmission = finish(permit) { now }

    fun finish(permit: SendPermit, currentTime: () -> Instant): ResultAdmission = synchronized(lock) {
        val denied = if (permit.invalidated || !admitted.containsKey(permit)) SendDenial.QUEUE_INVALIDATED
            else rejection(permit.stamp, currentTime())
        if (admitted.remove(permit) != null) tickets[permit.stamp] = TicketState.FINISHED
        if (denied != null) ResultAdmission.Denied(denied)
        else ResultAdmission.Granted(AuthorizedResultReceipt(permit.stamp).also { receipts[it] = permit.stamp })
    }

    /** A receipt is version-bound evidence, not permanent permission to commit later. */
    fun checkReceipt(receipt: AuthorizedResultReceipt, now: Instant): SendDenial? = synchronized(lock) {
        if (receipts[receipt] !== receipt.stamp) SendDenial.QUEUE_INVALIDATED else rejection(receipt.stamp, now)
    }

    private fun rejection(stamp: QueuedSendStamp, now: Instant): SendDenial? {
        if (stamp.owner !== owner || !tickets.containsKey(stamp)) return SendDenial.QUEUE_INVALIDATED
        if (current.policy != ProcessingPolicy.REMOTE_ALLOWED) return SendDenial.POLICY_BLOCKED
        if (current.deleted) return SendDenial.SOURCE_DELETED
        if (current.source != stamp.intent.source) return SendDenial.SOURCE_CHANGED
        val connection = current.connection ?: return SendDenial.CONNECTION_MISSING
        val queued = stamp.connection ?: return SendDenial.CONNECTION_MISSING
        if (connection.providerId != queued.providerId) return SendDenial.PROVIDER_CHANGED
        if (connection != queued) return SendDenial.CONFIGURATION_CHANGED
        val consent = current.consent ?: return SendDenial.CONSENT_MISSING
        if (consent.revoked) return SendDenial.CONSENT_REVOKED
        if (now >= consent.expiresAt) return SendDenial.CONSENT_EXPIRED
        if (now < consent.issuedAt) return SendDenial.CONSENT_NOT_CURRENT
        if (consent.source != stamp.intent.source || consent.purpose != stamp.intent.purpose ||
            consent.connection != connection) return SendDenial.SCOPE_MISMATCH
        if (consent != stamp.consent) return SendDenial.CONSENT_CHANGED
        if (generation != stamp.generation) return SendDenial.QUEUE_INVALIDATED
        return null
    }
}
