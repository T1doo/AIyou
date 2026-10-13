package com.t1doo.aiyou.ai

enum class FailureKind {
    INVALID_UTF8, LIMIT_EXCEEDED, MALFORMED_RESPONSE, TRUNCATED, REFUSED,
    UNSUPPORTED_CONTENT, PROVIDER_ERROR, AUTH_REQUIRED, CONFIGURATION,
    RATE_LIMITED, TEMPORARY, REDIRECT_BLOCKED, NETWORK,
}

internal fun isWellFormedUtf16(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val ch = value[i++]
        if (ch.isHighSurrogate()) {
            if (i >= value.length || !value[i++].isLowSurrogate()) return false
        } else if (ch.isLowSurrogate()) return false
    }
    return true
}

/** Preview text is not validated memory and must never be committed as a final result. */
sealed interface StreamEvent {
    data object Started : StreamEvent
    class TextDelta(val text: String) : StreamEvent {
        override fun toString() = "TextDelta(<redacted>)"
    }
    data class Usage(val inputTokens: Long?, val outputTokens: Long?) : StreamEvent
    data object Completed : StreamEvent
    data class Failed(val kind: FailureKind) : StreamEvent
    data object Cancelled : StreamEvent
}

data class StreamLimits(
    val maxChunkBytes: Int = 262_144,
    val maxLineChars: Int = 16_384,
    val maxEventChars: Int = 65_536,
    val maxFrames: Int = 4_096,
    val maxTotalBytes: Long = 8_388_608,
    val maxTextChars: Int = 1_048_576,
) {
    init {
        require(maxChunkBytes > 0 && maxLineChars > 0 && maxEventChars > 0 && maxFrames > 0 &&
            maxTotalBytes > 0 && maxTextChars > 0) { "Stream limits must be positive" }
    }
}
