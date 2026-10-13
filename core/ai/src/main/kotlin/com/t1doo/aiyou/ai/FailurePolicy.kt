package com.t1doo.aiyou.ai

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class RetryDecision(val kind: FailureKind, val delay: Duration?)

/** A bounded recommendation only. It never retries, sends, reconnects or switches providers. */
class FailurePolicy(val maxAttempts: Int = 3, val maxDelay: Duration = Duration.ofSeconds(60)) {
    init {
        require(maxAttempts in 1..10 && !maxDelay.isNegative && !maxDelay.isZero &&
            maxDelay <= Duration.ofMinutes(10)) { "Invalid retry bounds" }
    }

    fun http(status: Int, attempt: Int, partialOutput: Boolean = false,
             retryAfter: String? = null, now: Instant = Instant.EPOCH): RetryDecision {
        require(status in 100..599 && attempt >= 1) { "Invalid HTTP failure metadata" }
        val kind = when (status) {
            in 300..399 -> FailureKind.REDIRECT_BLOCKED
            401, 403 -> FailureKind.AUTH_REQUIRED
            429 -> FailureKind.RATE_LIMITED
            408, in 500..599 -> FailureKind.TEMPORARY
            else -> FailureKind.CONFIGURATION
        }
        val retryable = kind == FailureKind.RATE_LIMITED || kind == FailureKind.TEMPORARY
        if (!retryable || partialOutput || attempt >= maxAttempts) return RetryDecision(kind, null)
        val requested = retryAfter?.let { parseWait(it, now) }
        if (retryAfter != null && requested == null) return RetryDecision(kind, null)
        val backoff = Duration.ofSeconds(1L shl (attempt - 1).coerceAtMost(9))
        val delay = maxOf(backoff, requested ?: Duration.ZERO)
        return RetryDecision(kind, delay.takeIf { it <= maxDelay })
    }

    fun network(attempt: Int, partialOutput: Boolean = false): RetryDecision {
        require(attempt >= 1) { "Invalid attempt" }
        return RetryDecision(FailureKind.NETWORK,
            if (partialOutput || attempt >= maxAttempts) null
            else Duration.ofSeconds(1L shl (attempt - 1).coerceAtMost(9)).takeIf { it <= maxDelay })
    }

    private fun parseWait(value: String, now: Instant): Duration? {
        if (value.length > 128) return null
        val seconds = value.trim().toLongOrNull()
        if (seconds != null) return if (seconds >= 0) Duration.ofSeconds(seconds) else null
        return try {
            val target = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(now, target).coerceAtLeast(Duration.ZERO)
        } catch (_: Exception) { null }
    }
}
