package com.t1doo.aiyou.ai

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

class FailurePolicyTest {
    private val policy = FailurePolicy()

    @Test fun authFailuresRequireActionAndNeverRetry() {
        listOf(401, 403).forEach { status -> assertEquals(RetryDecision(FailureKind.AUTH_REQUIRED, null), policy.http(status, 1)) }
    }

    @Test fun allRedirectsAreBlockedWithoutRetry() {
        (300..399).forEach { status -> assertEquals(RetryDecision(FailureKind.REDIRECT_BLOCKED, null), policy.http(status, 1)) }
    }

    @Test fun nonRetryableStatusesAreConfigurationFailures() {
        listOf(400, 404, 405, 409, 422).forEach { status -> assertEquals(RetryDecision(FailureKind.CONFIGURATION, null), policy.http(status, 1)) }
    }

    @Test fun retryableStatusesUseBoundedExponentialDelay() {
        listOf(408, 500, 503, 529, 599).forEach { status ->
            assertEquals(RetryDecision(FailureKind.TEMPORARY, Duration.ofSeconds(1)), policy.http(status, 1))
            assertEquals(Duration.ofSeconds(2), policy.http(status, 2).delay)
            assertNull(policy.http(status, 3).delay)
            assertNull(policy.http(status, 100).delay)
        }
    }

    @Test fun rateLimitHonorsRetryAfterWithoutShorteningServerWait() {
        assertEquals(RetryDecision(FailureKind.RATE_LIMITED, Duration.ofSeconds(15)), policy.http(429, 1, retryAfter = "15"))
        assertEquals(Duration.ofSeconds(2), policy.http(429, 2, retryAfter = "0").delay)
        assertEquals(Duration.ofSeconds(60), policy.http(429, 1, retryAfter = "60").delay)
    }

    @Test fun excessiveServerWaitStopsInsteadOfRetryingEarly() {
        listOf("61", Long.MAX_VALUE.toString()).forEach { wait -> assertNull(policy.http(429, 1, retryAfter = wait).delay) }
    }

    @Test fun invalidRetryAfterDoesNotInventAnEarlierRetry() {
        listOf("-1", "1.5", "not-a-date", "", "x".repeat(129)).forEach { wait -> assertNull(policy.http(429, 1, retryAfter = wait).delay) }
    }

    @Test fun supportsHttpDateRetryAfterWithInjectedClock() {
        val now = Instant.parse("2026-10-13T10:00:00Z")
        val later = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(45).atZone(ZoneOffset.UTC))
        assertEquals(Duration.ofSeconds(45), policy.http(429, 1, retryAfter = later, now = now).delay)
        val tooLate = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(61).atZone(ZoneOffset.UTC))
        assertNull(policy.http(429, 1, retryAfter = tooLate, now = now).delay)
    }

    @Test fun pastHttpDateStillRespectsMinimumBackoff() {
        val now = Instant.parse("2026-10-13T10:00:00Z")
        val earlier = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(10).atZone(ZoneOffset.UTC))
        assertEquals(Duration.ofSeconds(2), policy.http(429, 2, retryAfter = earlier, now = now).delay)
    }

    @Test fun partialOutputDisablesAutomaticReplay() {
        listOf(408, 429, 500, 503).forEach { status -> assertNull(policy.http(status, 1, partialOutput = true).delay) }
        assertEquals(RetryDecision(FailureKind.NETWORK, null), policy.network(1, partialOutput = true))
    }

    @Test fun networkAttemptsAreBounded() {
        assertEquals(RetryDecision(FailureKind.NETWORK, Duration.ofSeconds(1)), policy.network(1))
        assertEquals(Duration.ofSeconds(2), policy.network(2).delay)
        assertNull(policy.network(3).delay)
        assertNull(policy.network(Int.MAX_VALUE).delay)
    }

    @Test fun callerCanTightenAttemptAndDelayBounds() {
        assertNull(FailurePolicy(maxAttempts = 1).network(1).delay)
        val short = FailurePolicy(maxAttempts = 10, maxDelay = Duration.ofSeconds(3))
        assertEquals(Duration.ofSeconds(2), short.network(2).delay)
        assertNull(short.network(3).delay)
        assertNull(short.http(503, 3).delay)
    }

    @Test fun rejectsInvalidPolicyAndFailureMetadata() {
        listOf(0, 11).forEach { attempts -> assertThrows(IllegalArgumentException::class.java) { FailurePolicy(maxAttempts = attempts) } }
        listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(11)).forEach { delay ->
            assertThrows(IllegalArgumentException::class.java) { FailurePolicy(maxDelay = delay) }
        }
        listOf(99, 600).forEach { status -> assertThrows(IllegalArgumentException::class.java) { policy.http(status, 1) } }
        assertThrows(IllegalArgumentException::class.java) { policy.http(429, 0) }
        assertThrows(IllegalArgumentException::class.java) { policy.network(0) }
    }

    @Test fun failureDecisionDoesNotRetainRetryAfterContent() {
        val marker = "synthetic-private-marker"
        val decision = policy.http(429, 1, retryAfter = marker)
        assertFalse(decision.toString().contains(marker))
        assertNull(decision.delay)
    }
}
