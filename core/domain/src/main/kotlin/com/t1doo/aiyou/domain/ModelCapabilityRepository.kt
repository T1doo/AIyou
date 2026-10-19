package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** A durable single-probe generation, independent of record consent and source jobs. */
interface ModelCapabilityProbeTicket {
    val profile: ModelCapabilityProfile
}

interface ModelCapabilityRepository {
    /** Returns only profiles whose entire connection snapshot is still current. */
    fun profiles(): List<ModelCapabilityProfile>
    fun begin(expected: ModelConnectionConfiguration, probeId: String, startedAtMillis: Long): ModelCapabilityProbeTicket
    fun current(ticket: ModelCapabilityProbeTicket): Boolean
    /** Durable once-only socket admission. A second exchange for this ticket cannot send again. */
    fun admit(ticket: ModelCapabilityProbeTicket): Boolean
    /** A wake-up only. The actual admission and completion still recheck SQL. */
    fun watch(ticket: ModelCapabilityProbeTicket, onRetired: () -> Unit): AutoCloseable
    /** A newer probe, edit, key replacement or revocation rejects the old result transactionally. */
    fun finish(ticket: ModelCapabilityProbeTicket, observation: TextCapabilityObservation): ModelCapabilityProfile
}

/** Explicitly invoked fixed-sample operation; never accepts records, prompts or caller output budgets. */
interface ModelCapabilityProbe {
    fun newExchange(ticket: ModelCapabilityProbeTicket): ModelCapabilityProbeExchange
}
interface ModelCapabilityProbeExchange {
    fun execute(): TextCapabilityObservation
    fun cancel()
}
