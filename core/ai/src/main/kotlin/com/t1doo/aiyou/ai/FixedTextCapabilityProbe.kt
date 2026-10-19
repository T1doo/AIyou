package com.t1doo.aiyou.ai

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock

/** No record repository or caller-supplied sample. Installation is explicit; production leaves it absent. */
class FixedTextCapabilityProbe(private val transport: OkHttpStreamingTransport,
    private val profiles: ModelCapabilityRepository, private val secrets: SecretResolver,
    private val clock: Clock) : ModelCapabilityProbe {
    override fun newExchange(ticket: ModelCapabilityProbeTicket): ModelCapabilityProbeExchange =
        transport.newCapabilityProbeExchange(ticket, profiles, secrets, clock)
}

internal object FixedTextCapabilitySample {
    const val ANSWER = "AIYOU_CAPABILITY_OK"
    const val PROMPT = "Reply with exactly AIYOU_CAPABILITY_OK. This is a synthetic text capability check."
    fun prepare(connection: ModelConnectionConfiguration): PreparedRequest = ProtocolRequests.prepare(
        connection.details.apiRoot, Protocol.valueOf(connection.details.protocol.name),
        TextRequest(connection.details.modelId, listOf(TextMessage(MessageRole.USER, PROMPT)), maxOutputTokens=32))

    fun normalize(outcome: HttpOutcome, exactReply: Boolean, time: Long): TextCapabilityObservation {
        val state: CapabilityState
        val reason = when {
            outcome.terminal == StreamEvent.Completed && exactReply -> CapabilityProbeReason.TEXT_STREAM_VERIFIED
            outcome.httpStatus in setOf(404,405,501) -> CapabilityProbeReason.TEXT_ENDPOINT_UNAVAILABLE
            outcome.terminal == StreamEvent.Cancelled -> CapabilityProbeReason.CANCELLED
            else -> when((outcome.terminal as? StreamEvent.Failed)?.kind) {
                FailureKind.AUTH_REQUIRED -> CapabilityProbeReason.AUTH_REQUIRED
                FailureKind.RATE_LIMITED -> CapabilityProbeReason.RATE_LIMITED
                FailureKind.TEMPORARY -> CapabilityProbeReason.TEMPORARY
                FailureKind.NETWORK -> CapabilityProbeReason.NETWORK
                FailureKind.TIMEOUT -> CapabilityProbeReason.TIMEOUT
                FailureKind.REDIRECT_BLOCKED -> CapabilityProbeReason.REDIRECT_BLOCKED
                FailureKind.LIMIT_EXCEEDED -> CapabilityProbeReason.LIMIT_EXCEEDED
                FailureKind.REFUSED -> CapabilityProbeReason.REFUSED
                FailureKind.CONFIGURATION -> CapabilityProbeReason.CONFIGURATION_REJECTED
                else -> CapabilityProbeReason.INVALID_RESPONSE
            }
        }
        state = when(reason) {
            CapabilityProbeReason.TEXT_STREAM_VERIFIED -> CapabilityState.SUPPORTED
            CapabilityProbeReason.TEXT_ENDPOINT_UNAVAILABLE -> CapabilityState.UNSUPPORTED
            else -> CapabilityState.UNKNOWN
        }
        return TextCapabilityObservation(state,reason,time)
    }
}
