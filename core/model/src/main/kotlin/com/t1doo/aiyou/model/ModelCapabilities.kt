package com.t1doo.aiyou.model

enum class CapabilityState { UNKNOWN, SUPPORTED, UNSUPPORTED }

/** Fixed categories only: provider response text and credentials never enter an archive or UI error. */
enum class CapabilityProbeReason {
    IN_PROGRESS, TEXT_STREAM_VERIFIED, TEXT_ENDPOINT_UNAVAILABLE, AUTH_REQUIRED, RATE_LIMITED,
    TEMPORARY, NETWORK, TIMEOUT, REDIRECT_BLOCKED, INVALID_RESPONSE, REFUSED, LIMIT_EXCEEDED,
    CANCELLED, CONFIGURATION_CHANGED, SECRET_UNAVAILABLE, STORAGE_UNAVAILABLE,
    CONFIGURATION_REJECTED,
}

data class TextCapabilityObservation(val state: CapabilityState, val reason: CapabilityProbeReason,
    val checkedAtMillis: Long) {
    init {
        require(checkedAtMillis >= 0)
        require(when(state) {
            CapabilityState.SUPPORTED -> reason == CapabilityProbeReason.TEXT_STREAM_VERIFIED
            CapabilityState.UNSUPPORTED -> reason == CapabilityProbeReason.TEXT_ENDPOINT_UNAVAILABLE
            CapabilityState.UNKNOWN -> reason !in setOf(CapabilityProbeReason.TEXT_STREAM_VERIFIED, CapabilityProbeReason.TEXT_ENDPOINT_UNAVAILABLE)
        })
    }
}

/** Exact immutable configuration includes destination, protocol, model, planned uses and secret reference. */
data class ModelCapabilityProfile(val connection: ModelConnectionConfiguration, val probeId: String,
    val generation: Long, val startedAtMillis: Long, val observation: TextCapabilityObservation) {
    init {
        require(probeId.length in 1..128 && probeId.none { it.isISOControl() })
        require(generation > 0 && startedAtMillis >= 0 && observation.checkedAtMillis >= startedAtMillis)
    }
    // Text streaming verifies neither structured organization nor vision/audio/embedding semantics.
    override fun toString() = "ModelCapabilityProfile(generation=$generation, state=${observation.state}, redacted)"
}
