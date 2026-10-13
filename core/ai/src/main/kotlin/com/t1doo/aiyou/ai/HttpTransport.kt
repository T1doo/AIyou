package com.t1doo.aiyou.ai

import java.io.Closeable

data class HttpBounds(
    val connectMillis: Long = 5_000,
    val totalMillis: Long = 30_000,
    val idleMillis: Long = 10_000,
    val writeMillis: Long = 10_000,
    val errorBodyBytes: Int = 4_096,
    val readBufferBytes: Int = 8_192,
    val requestBytes: Int = 1_048_576,
) {
    init {
        require(listOf(connectMillis, totalMillis, idleMillis, writeMillis).all { it in 1..300_000 }) {
            "HTTP deadlines must be finite and positive"
        }
        require(errorBodyBytes in 1..65_536 && readBufferBytes in 1..65_536 && requestBytes in 1..4_194_304) {
            "HTTP size bounds are outside supported limits"
        }
    }
}

data class HttpOutcome(
    val terminal: StreamEvent,
    val httpStatus: Int? = null,
    val retryDecision: RetryDecision? = null,
    val errorBodyBytes: Int = 0,
    val errorBodyTruncated: Boolean = false,
)

interface StreamingHttpExchange {
    /** One synchronous execution; events are preview only until a successful protocol terminal. */
    fun execute(onEvent: (StreamEvent) -> Unit = {}): HttpOutcome
    fun cancel()
}

interface StreamingHttpTransport : Closeable {
    fun newExchange(protocol: Protocol, request: PreparedRequest,
                    streamLimits: StreamLimits = StreamLimits()): StreamingHttpExchange
}
