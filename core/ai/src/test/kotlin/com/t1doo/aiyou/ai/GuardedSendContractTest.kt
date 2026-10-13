package com.t1doo.aiyou.ai

import com.t1doo.aiyou.domain.CurrentSendAuthority
import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Public guarded entry point plus two independent actual loopback providers. */
class GuardedSendContractTest {
    @Test(timeout = 10_000) fun localOnlyAndAiNoneDenyBeforeSecretResolutionOrEitherProviderRequest() {
        protocols { fixture ->
            listOf(ProcessingPolicy.LOCAL_ONLY, ProcessingPolicy.AI_NONE).forEach { policy ->
                fixture.authority.replaceState(fixture.approvedState.copy(policy = policy))
                val resolver = fixture.resolver()
                val outcome = fixture.exchange(resolver).execute()
                assertAdmissionDenied(outcome, SendDenial.POLICY_BLOCKED)
                assertTrue(resolver.references.isEmpty())
                fixture.assertNoRequests()
            }
        }
    }

    @Test(timeout = 10_000) fun absentConnectionOrConsentDeniesWithoutResolvingSecrets() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(SendDenial.CONNECTION_MISSING, SendDenial.CONSENT_MISSING).forEach { denial ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val state = if (denial == SendDenial.CONNECTION_MISSING) fixture.approvedState.copy(connection = null)
                        else fixture.approvedState.copy(consent = null)
                    fixture.authority.replaceState(state)
                    val resolver = fixture.resolver()
                    assertAdmissionDenied(fixture.exchange(resolver).execute(), denial)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun initiallyRevokedExpiredOrFutureGrantDeniesBeforeSecretResolution() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(SendDenial.CONSENT_REVOKED, SendDenial.CONSENT_EXPIRED, SendDenial.CONSENT_NOT_CURRENT).forEach { denial ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val grant = when (denial) {
                        SendDenial.CONSENT_REVOKED -> fixture.grant.copy(revoked = true)
                        SendDenial.CONSENT_EXPIRED -> fixture.grant.copy(expiresAt = fixture.now)
                        else -> fixture.grant.copy(issuedAt = fixture.now.plusSeconds(1))
                    }
                    fixture.authority.replaceState(fixture.approvedState.copy(consent = grant))
                    val resolver = fixture.resolver()
                    assertAdmissionDenied(fixture.exchange(resolver).execute(), denial)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun sourceDeletedOrChangedDeniesBeforeSecretsAndNetwork() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { deleted ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val state = if (deleted) fixture.approvedState.copy(deleted = true)
                        else fixture.approvedState.copy(source = fixture.source.copy(revision = 4))
                    fixture.authority.replaceState(state)
                    val resolver = fixture.resolver()
                    assertAdmissionDenied(fixture.exchange(resolver).execute(),
                        if (deleted) SendDenial.SOURCE_DELETED else SendDenial.SOURCE_CHANGED)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun sourcePurposeAndFullConfigurationMustMatchGrantScope() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..2).forEach { scope ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val grant = when (scope) {
                        0 -> fixture.grant.copy(source = fixture.source.copy(revision = 4))
                        1 -> fixture.grant.copy(purpose = TextPurpose.CONVERSATION)
                        else -> fixture.grant.copy(connection = fixture.selected.copy(modelId = "synthetic-other-model"))
                    }
                    fixture.authority.replaceState(fixture.approvedState.copy(consent = grant))
                    val resolver = fixture.resolver()
                    assertAdmissionDenied(fixture.exchange(resolver).execute(), SendDenial.SCOPE_MISMATCH)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun callerModelBudgetOrUsageOverrideCannotBypassApprovedConfiguration() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..2).forEach { field ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val base = GuardedLoopbackFixture.requestFor(fixture.selected)
                    val request = TextRequest(
                        if (field == 0) "synthetic-unapproved-model" else base.modelId,
                        base.messages, base.system, base.maxOutputTokens,
                        if (field == 1) ChatTokenBudget.MAX_COMPLETION_TOKENS else base.chatTokenBudget,
                        if (field == 2) true else base.chatUsageSupported,
                    )
                    val resolver = fixture.resolver()
                    assertAdmissionDenied(fixture.exchange(resolver, request = request).execute(), SendDenial.CONFIGURATION_INVALID)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun queuedProviderOrConfigurationVersionSwitchCannotResolveOrFallback() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { providerSwitch ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    val queued = fixture.exchange(resolver)
                    val replacement = if (providerSwitch) fixture.connectionB else fixture.selected.copy(version = 8)
                    fixture.authority.replaceState(fixture.approvedState.copy(connection = replacement))
                    assertAdmissionDenied(queued.execute(), if (providerSwitch) SendDenial.PROVIDER_CHANGED else SendDenial.CONFIGURATION_CHANGED)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun sameVersionQueuedRootProtocolModelReferenceAndCapabilitiesDriftIsRejected() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..5).forEach { field ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    val queued = fixture.exchange(resolver)
                    val replacement = drift(fixture.selected, field)
                    assertEquals(fixture.selected.version, replacement.version)
                    fixture.authority.replaceState(fixture.approvedState.copy(connection = replacement))
                    assertAdmissionDenied(queued.execute(), SendDenial.CONFIGURATION_CHANGED)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun queuedGrantIdentityVersionAndScopeChangeIsRejectedWithoutResolver() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..3).forEach { field ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    val queued = fixture.exchange(resolver)
                    val replacement = when (field) {
                        0 -> fixture.grant.copy(consentId = "synthetic-other-grant")
                        1 -> fixture.grant.copy(version = 6)
                        2 -> fixture.grant.copy(purpose = TextPurpose.CONVERSATION)
                        else -> fixture.grant.copy(expiresAt = fixture.grant.expiresAt.plusSeconds(1))
                    }
                    fixture.authority.replaceState(fixture.approvedState.copy(consent = replacement))
                    assertAdmissionDenied(queued.execute(), if (field == 2) SendDenial.SCOPE_MISMATCH else SendDenial.CONSENT_CHANGED)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun queuedSourceRevisionDeletionPolicyOrRevokeChangeInvalidatesSend() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..3).forEach { mutation ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    val queued = fixture.exchange(resolver)
                    val (state, denial) = when (mutation) {
                        0 -> fixture.approvedState.copy(source = fixture.source.copy(revision = 4)) to SendDenial.SOURCE_CHANGED
                        1 -> fixture.approvedState.copy(deleted = true) to SendDenial.SOURCE_DELETED
                        2 -> fixture.approvedState.copy(policy = ProcessingPolicy.LOCAL_ONLY) to SendDenial.POLICY_BLOCKED
                        else -> fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true)) to SendDenial.CONSENT_REVOKED
                    }
                    fixture.authority.replaceState(state)
                    assertAdmissionDenied(queued.execute(), denial)
                    assertTrue(resolver.references.isEmpty())
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun queuedExpiryBoundaryUsesCurrentClockWithoutResolvingSecrets() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val queued = fixture.exchange(resolver)
            fixture.clock.set(fixture.grant.expiresAt)
            assertAdmissionDenied(queued.execute(), SendDenial.CONSENT_EXPIRED)
            assertTrue(resolver.references.isEmpty())
            fixture.assertNoRequests()
        }
    }

    @Test(timeout = 10_000) fun queuedAbaCannotReuseOldPermissionEvenWhenFullStateIsRestored() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val queued = fixture.exchange(resolver)
            fixture.authority.replaceState(fixture.approvedState.copy(policy = ProcessingPolicy.AI_NONE))
            fixture.authority.replaceState(fixture.approvedState)
            assertAdmissionDenied(queued.execute(), SendDenial.QUEUE_INVALIDATED)
            assertTrue(resolver.references.isEmpty())
            fixture.assertNoRequests()
        }
    }

    @Test(timeout = 10_000) fun resolverRevocationPolicyRevisionAndProviderChangesAreCheckedAgainAndWiped() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..3).forEach { mutation ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val (state, denial) = when (mutation) {
                        0 -> fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true)) to SendDenial.CONSENT_REVOKED
                        1 -> fixture.approvedState.copy(policy = ProcessingPolicy.LOCAL_ONLY) to SendDenial.POLICY_BLOCKED
                        2 -> fixture.approvedState.copy(source = fixture.source.copy(revision = 4)) to SendDenial.SOURCE_CHANGED
                        else -> fixture.approvedState.copy(connection = fixture.connectionB) to SendDenial.PROVIDER_CHANGED
                    }
                    val resolver = fixture.resolver { fixture.authority.replaceState(state) }
                    assertAdmissionDenied(fixture.exchange(resolver).execute(), denial)
                    assertEquals(listOf(fixture.selected.secretRef), resolver.references)
                    fixture.assertSecretWiped(resolver)
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun resolverSameVersionDriftAndAbaCannotReachEitherProvider() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..6).forEach { mutation ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver {
                        if (mutation == 6) {
                            fixture.authority.replaceState(fixture.approvedState.copy(deleted = true))
                            fixture.authority.replaceState(fixture.approvedState)
                        } else fixture.authority.replaceState(fixture.approvedState.copy(connection = drift(fixture.selected, mutation)))
                    }
                    assertAdmissionDenied(fixture.exchange(resolver).execute(),
                        if (mutation == 6) SendDenial.QUEUE_INVALIDATED else SendDenial.CONFIGURATION_CHANGED)
                    assertEquals(1, resolver.references.size)
                    fixture.assertSecretWiped(resolver)
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun expiryDuringResolutionRejectsAndWipesResolvedValue() {
        protocols { fixture ->
            val resolver = fixture.resolver { fixture.clock.set(fixture.grant.expiresAt) }
            assertAdmissionDenied(fixture.exchange(resolver).execute(), SendDenial.CONSENT_EXPIRED)
            assertEquals(1, resolver.references.size)
            fixture.assertSecretWiped(resolver)
            fixture.assertNoRequests()
        }
    }

    @Test(timeout = 10_000) fun missingOrThrowingResolverMakesZeroRequestsAndNeverLeaksExceptionText() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..2).forEach { resolverMode ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    var resolves = 0
                    val marker = "synthetic-private-resolver-error"
                    val resolver = SecretResolver {
                        resolves++
                        when (resolverMode) {
                            0 -> null
                            1 -> throw IllegalStateException(marker)
                            else -> throw AssertionError(marker)
                        }
                    }
                    val outcome = fixture.exchange(resolver).execute()
                    assertAdmissionDenied(outcome, SendDenial.SECRET_UNAVAILABLE)
                    assertEquals(1, resolves)
                    assertFalse(outcome.toString().contains(marker))
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun invalidEmptyWhitespaceControlNonAsciiOrOversizedSecretIsWipedAndNeverSent() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf("", " ", "synthetic\r\nInjected: marker", "synthetic\tmarker", "synthetic🙂", "x".repeat(8_193)).forEach { value ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver(value)
                    val outcome = fixture.exchange(resolver).execute()
                    assertAdmissionDenied(outcome, SendDenial.SECRET_UNAVAILABLE)
                    assertEquals(1, resolver.references.size)
                    fixture.assertSecretWiped(resolver)
                    assertFalse(outcome.toString().contains("Injected: marker"))
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun finalPreflightRejectsApplicationInterceptorRevocationBeforeHttpRequest() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            fixture.beforeConnect = { fixture.authority.replaceState(fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true))) }
            assertAdmissionDenied(fixture.exchange(resolver).execute(), SendDenial.CONSENT_REVOKED)
            assertEquals(1, resolver.references.size)
            fixture.assertSecretWiped(resolver)
            fixture.assertNoRequests()
        }
    }

    @Test(timeout = 10_000) fun finalPreflightRejectsSameVersionConfigurationDriftWithoutFallback() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            fixture.beforeConnect = { fixture.authority.replaceState(fixture.approvedState.copy(connection = drift(fixture.selected, 0))) }
            assertAdmissionDenied(fixture.exchange(resolver).execute(), SendDenial.CONFIGURATION_CHANGED)
            assertEquals(1, resolver.references.size)
            fixture.assertSecretWiped(resolver)
            fixture.assertNoRequests()
        }
    }

    @Test(timeout = 10_000) fun finalPreflightUsesCurrentExpiryClockAndEpochBeforeAnyHttpBytes() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { aba ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    fixture.beforeConnect = {
                        if (aba) {
                            fixture.authority.replaceState(fixture.approvedState.copy(policy = ProcessingPolicy.LOCAL_ONLY))
                            fixture.authority.replaceState(fixture.approvedState)
                        } else fixture.clock.set(fixture.grant.expiresAt)
                    }
                    assertAdmissionDenied(fixture.exchange(resolver).execute(),
                        if (aba) SendDenial.QUEUE_INVALIDATED else SendDenial.CONSENT_EXPIRED)
                    fixture.assertSecretWiped(resolver)
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun permittedProviderAndProtocolReceiveOnlyTheirResolvedAuthenticationAndCurrentReceipt() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { selectedB ->
                GuardedLoopbackFixture(protocol, selectedB).use { fixture ->
                    val resolver = fixture.resolver()
                    val events = mutableListOf<StreamEvent>()
                    val outcome = fixture.exchange(resolver).execute(events::add)
                    assertEquals(StreamEvent.Completed, outcome.terminal)
                    assertEquals(StreamEvent.Completed, outcome.wireOutcome?.terminal)
                    assertNull(outcome.denial)
                    assertNull(outcome.resultDenial)
                    val receipt = checkNotNull(outcome.receipt)
                    assertNull(fixture.authority.checkReceipt(receipt, fixture.clock.get()))
                    assertEquals(listOf(fixture.selected.secretRef), resolver.references)
                    val server = if (selectedB) fixture.serverB else fixture.serverA
                    val recorded = checkNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals("POST", recorded.method)
                    assertEquals("/custom/v1/" + if (protocol == ConnectionProtocol.CHAT_COMPLETIONS) "chat/completions" else "messages", recorded.url.encodedPath)
                    val expectedKey = if (selectedB) fixture.keyB else fixture.keyA
                    if (protocol == ConnectionProtocol.CHAT_COMPLETIONS) {
                        assertEquals("Bearer $expectedKey", recorded.headers["Authorization"])
                        assertNull(recorded.headers["x-api-key"])
                    } else {
                        assertEquals(expectedKey, recorded.headers["x-api-key"])
                        assertNull(recorded.headers["Authorization"])
                        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
                    }
                    val body = Json.parseToJsonElement(checkNotNull(recorded.body).utf8()).jsonObject
                    assertEquals(fixture.selected.modelId, body.getValue("model").jsonPrimitive.content)
                    assertFalse(body.toString().contains(expectedKey))
                    assertFalse(body.toString().contains(fixture.selected.secretRef.value))
                    fixture.assertSelectedRequestAndReleased()
                    fixture.assertSecretWiped(resolver)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun successfulReceiptBecomesInvalidAfterPolicySourceConsentOrAbaChanges() {
        ConnectionProtocol.entries.forEach { protocol ->
            (0..3).forEach { mutation ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    val outcome = fixture.exchange(resolver).execute()
                    val receipt = checkNotNull(outcome.receipt)
                    val denied = when (mutation) {
                        0 -> { fixture.authority.replaceState(fixture.approvedState.copy(policy = ProcessingPolicy.LOCAL_ONLY)); SendDenial.POLICY_BLOCKED }
                        1 -> { fixture.authority.replaceState(fixture.approvedState.copy(source = fixture.source.copy(revision = 4))); SendDenial.SOURCE_CHANGED }
                        2 -> { fixture.authority.replaceState(fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true))); SendDenial.CONSENT_REVOKED }
                        else -> {
                            fixture.authority.replaceState(fixture.approvedState.copy(deleted = true))
                            fixture.authority.replaceState(fixture.approvedState)
                            SendDenial.QUEUE_INVALIDATED
                        }
                    }
                    assertEquals(denied, fixture.authority.checkReceipt(receipt, fixture.clock.get()))
                    fixture.assertSelectedRequestAndReleased()
                    fixture.assertSecretWiped(resolver)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun receiptCannotAuthorizeAnotherAuthorityEvenWithIdenticalState() {
        protocols { fixture ->
            val outcome = fixture.exchange(fixture.resolver()).execute()
            val foreign = CurrentSendAuthority(fixture.approvedState)
            assertEquals(SendDenial.QUEUE_INVALIDATED, foreign.checkReceipt(checkNotNull(outcome.receipt), fixture.now))
            fixture.assertSelectedRequestAndReleased()
        }
    }

    @Test(timeout = 10_000) fun revocationAfterClaimCancelsWireAndSeparatelyDeniesResultWithoutClaimingZeroRequests() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { revokeOnText ->
                var baselineEvents: List<String>? = null
                listOf(false, true).forEach { fragmented ->
                    GuardedLoopbackFixture(protocol, fragmented = fragmented).use { fixture ->
                        val resolver = fixture.resolver()
                        val events = mutableListOf<StreamEvent>()
                        val outcome = fixture.exchange(resolver).execute { event ->
                            events.add(event)
                            if (if (revokeOnText) event is StreamEvent.TextDelta else event == StreamEvent.Started) {
                                fixture.authority.replaceState(fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true)))
                            }
                        }
                        assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
                        assertNull(outcome.denial)
                        assertEquals(SendDenial.QUEUE_INVALIDATED, outcome.resultDenial)
                        assertNull(outcome.receipt)
                        assertEquals(StreamEvent.Cancelled, outcome.wireOutcome?.terminal)
                        assertEquals(revokeOnText, events.any { it is StreamEvent.TextDelta })
                        assertFalse(events.contains(StreamEvent.Completed))
                        assertEquals(1, events.count { it == StreamEvent.Cancelled })
                        val summary = HttpWireFixtures.summary(events)
                        if (baselineEvents == null) baselineEvents = summary else assertEquals(baselineEvents, summary)
                        fixture.assertSelectedRequestAndReleased()
                        fixture.assertSecretWiped(resolver)
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun wireCompletedCallbackRevocationCannotBecomeSubmittableCompletion() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val events = mutableListOf<StreamEvent>()
            val outcome = fixture.exchange(resolver).execute { event ->
                events.add(event)
                if (event == StreamEvent.Completed) fixture.authority.replaceState(fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true)))
            }
            assertTrue(events.contains(StreamEvent.Completed))
            assertEquals(StreamEvent.Completed, outcome.wireOutcome?.terminal)
            assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
            assertNull(outcome.denial)
            assertEquals(SendDenial.QUEUE_INVALIDATED, outcome.resultDenial)
            assertNull(outcome.receipt)
            fixture.assertSelectedRequestAndReleased()
            fixture.assertSecretWiped(resolver)
        }
    }

    @Test(timeout = 10_000) fun wireCompletionExpiryFailsResultGateWhilePreservingWireFact() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val outcome = fixture.exchange(resolver).execute { event ->
                if (event == StreamEvent.Completed) fixture.clock.set(fixture.grant.expiresAt)
            }
            assertEquals(StreamEvent.Completed, outcome.wireOutcome?.terminal)
            assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
            assertNull(outcome.denial)
            assertEquals(SendDenial.CONSENT_EXPIRED, outcome.resultDenial)
            assertNull(outcome.receipt)
            fixture.assertSelectedRequestAndReleased()
            fixture.assertSecretWiped(resolver)
        }
    }

    @Test(timeout = 10_000) fun cancelQueuedGuardedExchangeMakesNoSecretResolutionOrNetworkRequest() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val exchange = fixture.exchange(resolver)
            exchange.cancel()
            val outcome = exchange.execute()
            assertEquals(StreamEvent.Cancelled, outcome.terminal)
            assertNull(outcome.receipt)
            assertTrue(resolver.references.isEmpty())
            fixture.assertNoRequests()
            assertThrows(IllegalStateException::class.java) { exchange.execute() }
        }
    }

    @Test(timeout = 10_000) fun cancelWhileResolverIsBlockedWipesEventuallyReturnedSecretAndMakesZeroRequests() {
        protocols { fixture ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val chars = fixture.keyA.toCharArray()
            val returned = java.util.concurrent.atomic.AtomicReference<ResolvedSecret>()
            var calls = 0
            val resolver = SecretResolver {
                calls++
                entered.countDown()
                check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                ResolvedSecret.fromChars(chars).also { value -> returned.set(value) }
            }
            val exchange = fixture.exchange(resolver)
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<GuardedSendOutcome> { exchange.execute() }
                assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
                exchange.cancel()
                release.countDown()
                val outcome = result.get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(StreamEvent.Cancelled, outcome.terminal)
                assertNull(outcome.denial)
                assertNull(outcome.resultDenial)
                assertNull(outcome.wireOutcome)
                assertNull(outcome.receipt)
                assertEquals(1, calls)
                assertTrue(checkNotNull(returned.get()).isClosed)
                assertTrue(chars.all { it == '\u0000' })
                fixture.assertNoRequests()
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }

    @Test(timeout = 10_000) fun finalFreshClockFailureOrCancellationDoesNotSendAuthentication() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { cancellation ->
                GuardedLoopbackFixture(protocol).use { fixture ->
                    val resolver = fixture.resolver()
                    val marker = "synthetic-private-clock-error"
                    fixture.beforeConnect = {
                        fixture.currentTime = {
                            if (cancellation) throw java.util.concurrent.CancellationException(marker)
                            else throw AssertionError(marker)
                        }
                    }
                    val outcome = fixture.exchange(resolver).execute()
                    if (cancellation) {
                        assertEquals(StreamEvent.Cancelled, outcome.terminal)
                        assertNull(outcome.denial)
                        assertNull(outcome.resultDenial)
                        assertNull(outcome.wireOutcome)
                        assertNull(outcome.receipt)
                    } else assertAdmissionDenied(outcome, SendDenial.STATE_UNAVAILABLE)
                    assertFalse(outcome.toString().contains(marker))
                    assertEquals(1, resolver.references.size)
                    fixture.assertSecretWiped(resolver)
                    fixture.assertNoRequests()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun successfulGuardedExchangeCannotExecuteAgainOrResolveAnotherSecret() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val exchange = fixture.exchange(resolver)
            val outcome = exchange.execute()
            assertEquals(StreamEvent.Completed, outcome.terminal)
            assertNotNull(outcome.receipt)
            assertThrows(IllegalStateException::class.java) { exchange.execute() }
            assertEquals(1, resolver.references.size)
            fixture.assertSelectedRequestAndReleased()
            fixture.assertSecretWiped(resolver)
        }
    }

    @Test(timeout = 10_000) fun providerAuthOrTemporaryFailureHasNoReceiptAndNeverFallsBack() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(401, 503).forEach { status ->
                GuardedLoopbackFixture(protocol, responseCode = status).use { fixture ->
                    val resolver = fixture.resolver()
                    val outcome = fixture.exchange(resolver).execute()
                    val expected = StreamEvent.Failed(if (status == 401) FailureKind.AUTH_REQUIRED else FailureKind.TEMPORARY)
                    assertEquals(expected, outcome.terminal)
                    assertEquals(expected, outcome.wireOutcome?.terminal)
                    assertEquals(status, outcome.wireOutcome?.httpStatus)
                    assertNull(outcome.denial)
                    assertNull(outcome.resultDenial)
                    assertNull(outcome.receipt)
                    assertEquals(1, resolver.references.size)
                    assertFalse(outcome.toString().contains(fixture.keyA))
                    assertFalse(outcome.toString().contains(fixture.keyB))
                    fixture.assertSelectedRequestAndReleased()
                    fixture.assertSecretWiped(resolver)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun queuedTextPayloadIsFrozenBeforeCallerMutatesOriginalMessageList() {
        protocols { fixture ->
            val original = "synthetic-private-original-payload"
            val changed = "synthetic-private-mutated-payload"
            val mutable = mutableListOf(TextMessage(MessageRole.USER, original))
            val resolver = fixture.resolver()
            val request = TextRequest(fixture.selected.modelId, mutable)
            val queued = fixture.exchange(resolver, request = request)
            mutable.clear()
            mutable.add(TextMessage(MessageRole.USER, changed))
            val outcome = queued.execute()
            assertEquals(StreamEvent.Completed, outcome.terminal)
            assertNotNull(outcome.receipt)
            val recorded = checkNotNull(fixture.serverA.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
            val body = checkNotNull(recorded.body).utf8()
            assertTrue(body.contains(original))
            assertFalse(body.contains(changed))
            fixture.assertSelectedRequestAndReleased()
            fixture.assertSecretWiped(resolver)
        }
    }

    @Test(timeout = 10_000) fun resolvedMutableValueIsAlreadyWipedAtApplicationInterceptorBeforeConnect() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            var witnessed = false
            fixture.beforeConnect = {
                fixture.assertSecretWiped(resolver)
                witnessed = true
            }
            val outcome = fixture.exchange(resolver).execute()
            assertEquals(StreamEvent.Completed, outcome.terminal)
            assertTrue(witnessed)
            fixture.assertSelectedRequestAndReleased()
        }
    }

    @Test(timeout = 10_000) fun externalThreadRevocationDuringOwnedTailReadSelectsCancelledWireAndDeniesResult() {
        ConnectionProtocol.entries.forEach { protocol ->
            GuardedLoopbackFixture(protocol, holdResponseTail = true).use { fixture ->
                val resolver = fixture.resolver()
                val previewSeen = java.util.concurrent.CountDownLatch(1)
                val events = java.util.Collections.synchronizedList(mutableListOf<StreamEvent>())
                val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val result = worker.submit<GuardedSendOutcome> {
                        fixture.exchange(resolver).execute { event ->
                            events.add(event)
                            if (event is StreamEvent.TextDelta) previewSeen.countDown()
                        }
                    }
                    assertNotNull(fixture.serverA.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue(previewSeen.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    // This is the test thread, independently of the HTTP worker's callbacks.
                    fixture.authority.replaceState(fixture.approvedState.copy(consent = fixture.grant.copy(revoked = true)))
                    val outcome = result.get(2, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals(StreamEvent.Cancelled, outcome.wireOutcome?.terminal)
                    assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
                    assertNull(outcome.denial)
                    assertEquals(SendDenial.QUEUE_INVALIDATED, outcome.resultDenial)
                    assertNull(outcome.receipt)
                    assertEquals(StreamEvent.Cancelled, events.last())
                    assertFalse(events.contains(StreamEvent.Completed))
                    fixture.assertSelectedRequestAndReleased()
                    fixture.assertSecretWiped(resolver)
                } finally { worker.shutdownNow() }
            }
        }
    }

    @Test(timeout = 10_000) fun configurationSecretReferenceGrantSourceAndOutcomesKeepSyntheticPrivateValuesOutOfDiagnostics() {
        protocols { fixture ->
            val resolver = fixture.resolver()
            val exchange = fixture.exchange(resolver)
            val outcome = exchange.execute()
            val diagnostics = listOf(fixture.connectionA.toString(), fixture.connectionB.toString(), fixture.grant.toString(),
                fixture.source.toString(), fixture.intent.toString(), fixture.approvedState.toString(),
                fixture.selected.secretRef.toString(), resolver.values.single().toString(), exchange.toString(), outcome.toString())
            listOf(fixture.keyA, fixture.keyB, fixture.selected.secretRef.value, fixture.source.sourceId,
                fixture.grant.consentId, "synthetic-private-record", "synthetic-private-system").forEach { marker ->
                diagnostics.forEach { diagnostic -> assertFalse(diagnostic.contains(marker)) }
            }
            fixture.assertSelectedRequestAndReleased()
            fixture.assertSecretWiped(resolver)
        }
    }

    private fun protocols(test: (GuardedLoopbackFixture) -> Unit) {
        ConnectionProtocol.entries.forEach { protocol -> GuardedLoopbackFixture(protocol).use(test) }
    }

    private fun assertAdmissionDenied(outcome: GuardedSendOutcome, reason: SendDenial) {
        assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
        assertEquals(reason, outcome.denial)
        assertNull(outcome.resultDenial)
        assertNull(outcome.wireOutcome)
        assertNull(outcome.receipt)
    }

    private fun drift(connection: ProviderConnection, field: Int): ProviderConnection = when (field) {
        0 -> connection.copy(apiRoot = connection.apiRoot.replace("/custom/", "/changed/"))
        1 -> connection.copy(protocol = if (connection.protocol == ConnectionProtocol.CHAT_COMPLETIONS)
            ConnectionProtocol.ANTHROPIC_MESSAGES else ConnectionProtocol.CHAT_COMPLETIONS)
        2 -> connection.copy(modelId = "synthetic-different-model")
        3 -> connection.copy(secretRef = SecretReference("synthetic-different-reference"))
        4 -> connection.copy(chatBudgetField = ChatBudgetField.MAX_COMPLETION_TOKENS)
        else -> connection.copy(chatUsageSupported = true)
    }
}
