package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CurrentSendAuthorityTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val source = SourceVersion("synthetic-source", 1)
    private val config = ProviderConnection("synthetic-provider", 1, "https://provider.test/custom/v1/",
        ConnectionProtocol.CHAT_COMPLETIONS, "synthetic-model", SecretReference("fake-reference"))
    private val intent = RemoteSendIntent(source, TextPurpose.CONVERSATION)
    private val grant = ConsentGrant("synthetic-consent", 1, source, config, intent.purpose,
        now.minusSeconds(1), now.plusSeconds(60))
    private val allowed = RemoteSendState(source, ProcessingPolicy.REMOTE_ALLOWED, connection = config, consent = grant)

    @Test fun defaultStateAndBothRestrictivePoliciesDeny() {
        listOf(RemoteSendState(source), allowed.copy(policy = ProcessingPolicy.LOCAL_ONLY),
            allowed.copy(policy = ProcessingPolicy.AI_NONE)).forEach { state ->
            val authority = CurrentSendAuthority(state)
            assertEquals(SendDenial.POLICY_BLOCKED, authority.check(authority.queue(intent), now))
        }
    }

    @Test fun grantMustMatchSourcePurposeAndCompleteConfiguration() {
        listOf(grant.copy(source = source.copy(revision = 2)), grant.copy(purpose = TextPurpose.ORGANIZATION),
            grant.copy(connection = config.copy(secretRef = SecretReference("other-fake-reference")))).forEach { bad ->
            val authority = CurrentSendAuthority(allowed.copy(consent = bad))
            assertEquals(SendDenial.SCOPE_MISMATCH, authority.check(authority.queue(intent), now))
        }
    }

    @Test fun consentExpiresExactlyAtBoundaryAndCannotStartInFuture() {
        val authority = CurrentSendAuthority(allowed)
        val ticket = authority.queue(intent)
        assertNull(authority.check(ticket, grant.expiresAt.minusNanos(1)))
        assertEquals(SendDenial.CONSENT_EXPIRED, authority.check(ticket, grant.expiresAt))
        assertEquals(SendDenial.CONSENT_NOT_CURRENT, authority.check(ticket, grant.issuedAt.minusNanos(1)))
    }

    @Test fun claimIsOneUseAndForeignAuthorityCannotAdmitTicket() {
        val authority = CurrentSendAuthority(allowed)
        val ticket = authority.queue(intent)
        assertTrue(authority.claim(ticket, now) {} is SendAdmission.Granted)
        assertEquals(SendDenial.ALREADY_SENT, (authority.claim(ticket, now) {} as SendAdmission.Denied).reason)
        assertEquals(SendDenial.QUEUE_INVALIDATED,
            (CurrentSendAuthority(allowed).claim(ticket, now) {} as SendAdmission.Denied).reason)
    }

    @Test fun switchingAwayAndBackCannotReviveQueuedRequest() {
        val authority = CurrentSendAuthority(allowed)
        val ticket = authority.queue(intent)
        val other = config.copy(providerId = "other-provider")
        authority.replaceState(allowed.copy(connection = other, consent = grant.copy(connection = other)))
        authority.replaceState(allowed)
        assertEquals(SendDenial.QUEUE_INVALIDATED, authority.check(ticket, now))
    }

    @Test(timeout = 5_000) fun invalidationPublishesBeforeCancellationCallbackAndDoesNotHoldStateLock() {
        val authority = CurrentSendAuthority(allowed)
        val ticket = authority.queue(intent)
        lateinit var permit: SendPermit
        var cancelled = false
        var published = false
        var callbackRead: SendDenial? = null
        var otherThreadRead: SendDenial? = null
        permit = (authority.claim(ticket, now) {
            cancelled = true
            published = permit.invalidated
            callbackRead = authority.check(ticket, now)
            // Reading from a separate thread proves the callback is outside the state lock.
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try { otherThreadRead = worker.submit<SendDenial?> {
                authority.check(ticket, now)
            }.get(1, java.util.concurrent.TimeUnit.SECONDS) } finally { worker.shutdownNow() }
        } as SendAdmission.Granted).permit
        authority.replaceState(allowed.copy(policy = ProcessingPolicy.LOCAL_ONLY))
        assertTrue(cancelled)
        assertTrue(published)
        assertEquals(SendDenial.POLICY_BLOCKED, callbackRead)
        assertEquals(SendDenial.POLICY_BLOCKED, otherThreadRead)
        assertEquals(SendDenial.QUEUE_INVALIDATED, (authority.finish(permit, now) as ResultAdmission.Denied).reason)
    }

    @Test fun completedReceiptRequiresCurrentVersionsAndCannotSurviveRevocation() {
        val authority = CurrentSendAuthority(allowed)
        val permit = (authority.claim(authority.queue(intent), now) {} as SendAdmission.Granted).permit
        val receipt = (authority.finish(permit, now) as ResultAdmission.Granted).receipt
        assertNull(authority.checkReceipt(receipt, now))
        authority.replaceState(allowed.copy(consent = grant.copy(revoked = true)))
        assertEquals(SendDenial.CONSENT_REVOKED, authority.checkReceipt(receipt, now))
    }

    @Test fun metadataDiagnosticsExcludeSyntheticPrivateIdentifiers() {
        val marker = "synthetic-private-marker"
        val privateConfig = config.copy(providerId = marker, apiRoot = "https://$marker.test/",
            modelId = marker, secretRef = SecretReference(marker))
        val privateSource = source.copy(sourceId = marker)
        val privateGrant = grant.copy(consentId = marker, source = privateSource, connection = privateConfig)
        listOf(privateConfig, privateSource, privateGrant, privateConfig.secretRef,
            RemoteSendIntent(privateSource, intent.purpose), allowed.copy(source = privateSource, connection = privateConfig,
                consent = privateGrant)).forEach { assertFalse(it.toString().contains(marker)) }
    }

    @Test fun publicJvmMetadataConstructorsCannotForgeIssuedTicketOrReceipt() {
        val authority = CurrentSendAuthority(allowed)
        val original = authority.queue(intent)
        val copied = QueuedSendStamp::class.java.getConstructor(Any::class.java, RemoteSendIntent::class.java,
            Long::class.javaPrimitiveType, ProviderConnection::class.java, ConsentGrant::class.java)
            .newInstance(original.owner, original.intent, original.generation, original.connection, original.consent)
        assertEquals(SendDenial.QUEUE_INVALIDATED, (authority.claim(copied, now) {} as SendAdmission.Denied).reason)
        val forgedReceipt = AuthorizedResultReceipt::class.java.getConstructor(QueuedSendStamp::class.java).newInstance(original)
        assertEquals(SendDenial.QUEUE_INVALIDATED, authority.checkReceipt(forgedReceipt, now))
        assertFalse(QueuedSendStamp::class.java.methods.any { it.name.startsWith("setConsumed") })
        val permit = (authority.claim(original, now) {} as SendAdmission.Granted).permit
        val validReceipt = (authority.finish(permit, now) as ResultAdmission.Granted).receipt
        val copiedReceipt = AuthorizedResultReceipt::class.java.getConstructor(QueuedSendStamp::class.java).newInstance(validReceipt.stamp)
        assertNull(authority.checkReceipt(validReceipt, now))
        assertEquals(SendDenial.QUEUE_INVALIDATED, authority.checkReceipt(copiedReceipt, now))
    }

    @Test(timeout = 5_000) fun concurrentClaimsConsumeOnlyOnceAndFinishedPermitCannotFinishTwice() {
        val authority = CurrentSendAuthority(allowed)
        val ticket = authority.queue(intent)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val tasks = (1..2).map { workers.submit<SendAdmission> {
                check(start.await(1, java.util.concurrent.TimeUnit.SECONDS))
                authority.claim(ticket, { now }) {}
            } }
            start.countDown()
            val outcomes = tasks.map { it.get(1, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it is SendAdmission.Granted })
            assertEquals(1, outcomes.count { it == SendAdmission.Denied(SendDenial.ALREADY_SENT) })
            val permit = (outcomes.single { it is SendAdmission.Granted } as SendAdmission.Granted).permit
            assertTrue(authority.finish(permit, now) is ResultAdmission.Granted)
            assertEquals(SendDenial.QUEUE_INVALIDATED, (authority.finish(permit, now) as ResultAdmission.Denied).reason)
            assertEquals(SendDenial.ALREADY_SENT, (authority.claim(ticket, now) {} as SendAdmission.Denied).reason)
        } finally { workers.shutdownNow() }
    }
}
