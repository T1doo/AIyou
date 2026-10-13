package com.t1doo.aiyou.ai

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test

/** Actual loopback HTTP requests through the production transport, without TLS/key setup. */
class HttpTransportContractTest {
    private val defaultBounds = HttpBounds(connectMillis = 500, totalMillis = 3_000,
        idleMillis = 1_000, writeMillis = 1_000, errorBodyBytes = 64, readBufferBytes = 8_192)

    @Test(timeout = 10_000) fun bothProtocolsSendActualPostWithPreservedPathBodyAndSafeHeaders() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                val prepared = HttpWireFixtures.prepared(protocol)
                val events = mutableListOf<StreamEvent>()
                val result = fixture.transport.newExchange(protocol, prepared).execute(events::add)
                assertCompleted(result, events)
                assertEquals(HttpWireFixtures.text, text(events))
                val request = checkNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals(prepared.endpoint.rawPath, request.url.encodedPath)
                assertEquals(prepared.bodyJson, request.body?.utf8())
                assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';'))
                assertEquals("text/event-stream", request.headers["Accept"])
                assertNull(request.headers["Authorization"])
                assertNull(request.headers["x-api-key"])
                assertNull(request.headers["Proxy-Authorization"])
                if (protocol == Protocol.ANTHROPIC_MESSAGES) assertEquals("2023-06-01", request.headers["anthropic-version"])
                else assertNull(request.headers["anthropic-version"])
                fixture.assertOneRequestAndReleased()
            }
        }
    }

    @Test(timeout = 10_000) fun coalescedAndOneByteHttpChunksProduceIdenticalUtf8Events() {
        Protocol.entries.forEach { protocol ->
            val wire = HttpWireFixtures.complete(protocol)
            val coalesced = perform(protocol, sse(wire))
            val fragmented = perform(protocol, MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream; charset=utf-8")
                .chunkedBody(Buffer().write(wire), 1).build())
            assertEquals(StreamEvent.Completed, coalesced.first.terminal)
            assertEquals(coalesced.first.terminal, fragmented.first.terminal)
            assertEquals(HttpWireFixtures.summary(coalesced.second), HttpWireFixtures.summary(fragmented.second))
            assertEquals(HttpWireFixtures.text, text(fragmented.second))
        }
    }

    @Test(timeout = 10_000) fun completedPrefixIgnoresBadUtf8AndLongTailAcrossActualHttpWrites() {
        Protocol.entries.forEach { protocol ->
            val prefix = HttpWireFixtures.complete(protocol)
            val tail = byteArrayOf(0xFF.toByte()) + "x".repeat(513).toByteArray()
            val limits = StreamLimits(maxLineChars = 512)
            val expected = perform(protocol, sse(prefix), limits = limits)
            val coalesced = perform(protocol, sse(prefix + tail), limits = limits)
            val fragmented = perform(protocol, MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .chunkedBody(Buffer().write(prefix + tail), 1).build(), limits = limits)
            listOf(coalesced, fragmented).forEach { result ->
                assertEquals(StreamEvent.Completed, result.first.terminal)
                assertEquals(HttpWireFixtures.summary(expected.second), HttpWireFixtures.summary(result.second))
            }
        }
    }

    @Test(timeout = 10_000) fun completedPrefixReturnsAndReleasesConnectionBeforeServerTailOrEof() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                val body = fixture.heldBody(HttpWireFixtures.complete(protocol))
                fixture.server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())
                val events = mutableListOf<StreamEvent>()
                val outcome = fixture.exchange(protocol).execute(events::add)
                assertCompleted(outcome, events)
                assertEquals("server tail must still be held", 1L, body.release.count)
                assertTrue(body.prefixWritten.await(1, TimeUnit.SECONDS))
                fixture.assertOneRequestAndReleased()
            }
        }
    }

    @Test(timeout = 10_000) fun malformedUtf8BeforeTerminalFailsAndPreservesActualPreview() {
        Protocol.entries.forEach { protocol ->
            val wire = HttpWireFixtures.preview(protocol) + byteArrayOf(0xFF.toByte()) + HttpWireFixtures.terminal(protocol)
            val result = perform(protocol, sse(wire))
            assertFailed(result.first, result.second, FailureKind.INVALID_UTF8)
            assertEquals(HttpWireFixtures.text, text(result.second))
            assertTrue(result.second.any { it is StreamEvent.Usage })
        }
    }

    @Test(timeout = 10_000) fun eofWithoutFormalTerminalRemainsTruncatedForBothProtocols() {
        Protocol.entries.forEach { protocol ->
            listOf(HttpWireFixtures.preview(protocol), HttpWireFixtures.complete(protocol).dropLast(1).toByteArray()).forEach { wire ->
                val result = perform(protocol, sse(wire))
                assertFailed(result.first, result.second, FailureKind.TRUNCATED)
                assertEquals(HttpWireFixtures.text, text(result.second))
            }
        }
    }

    @Test(timeout = 10_000) fun providerTruncationCannotBePromotedToHttpSuccess() {
        Protocol.entries.forEach { protocol ->
            val result = perform(protocol, sse(HttpWireFixtures.providerTruncation(protocol)))
            assertFailed(result.first, result.second, FailureKind.TRUNCATED)
            assertEquals(200, result.first.httpStatus)
        }
    }

    @Test(timeout = 10_000) fun streamedTextAndTotalByteQuotasRemainStrictBeforeTerminal() {
        Protocol.entries.forEach { protocol ->
            val wire = HttpWireFixtures.complete(protocol)
            val textQuota = perform(protocol, sse(wire), limits = StreamLimits(maxTextChars = 1))
            assertFailed(textQuota.first, textQuota.second, FailureKind.LIMIT_EXCEEDED)
            val preview = HttpWireFixtures.preview(protocol)
            val byteQuota = perform(protocol, sse(wire), limits = StreamLimits(maxTotalBytes = preview.size.toLong()))
            assertFailed(byteQuota.first, byteQuota.second, FailureKind.LIMIT_EXCEEDED)
            assertEquals(HttpWireFixtures.text, text(byteQuota.second))
        }
    }

    @Test(timeout = 10_000) fun postTerminalTailDoesNotConsumeActualStreamByteQuota() {
        Protocol.entries.forEach { protocol ->
            val prefix = HttpWireFixtures.complete(protocol)
            val result = perform(protocol, sse(prefix + ByteArray(2_048) { 0xFF.toByte() }),
                limits = StreamLimits(maxChunkBytes = 7, maxTotalBytes = prefix.size.toLong()))
            assertCompleted(result.first, result.second)
        }
    }

    @Test(timeout = 10_000) fun cancelBeforeExecuteMakesZeroRequestsAndOneCancelledEvent() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                val exchange = fixture.exchange(protocol)
                exchange.cancel()
                val events = mutableListOf<StreamEvent>()
                val outcome = exchange.execute(events::add)
                assertEquals(StreamEvent.Cancelled, outcome.terminal)
                assertEquals(listOf(StreamEvent.Cancelled), events)
                assertEquals(0, fixture.server.requestCount)
                assertEquals(0, fixture.observer.acquired.get())
                assertThrows(IllegalStateException::class.java) { exchange.execute() }
            }
        }
    }

    @Test(timeout = 10_000) fun midReadCancelAndTransportCloseReleaseOwnedRequests() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { closeTransport ->
                Harness(defaultBounds).use { fixture ->
                    val body = fixture.heldBody(HttpWireFixtures.preview(protocol))
                    fixture.server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())
                    val exchange = fixture.exchange(protocol)
                    val previewSeen = CountDownLatch(1)
                    val events = Collections.synchronizedList(mutableListOf<StreamEvent>())
                    val worker = Executors.newSingleThreadExecutor()
                    try {
                        val result = worker.submit<HttpOutcome> {
                            exchange.execute { event -> events.add(event); if (event is StreamEvent.TextDelta) previewSeen.countDown() }
                        }
                        assertTrue("actual preview must arrive before cancellation", previewSeen.await(2, TimeUnit.SECONDS))
                        if (closeTransport) fixture.transport.close() else exchange.cancel()
                        assertEquals(StreamEvent.Cancelled, result.get(2, TimeUnit.SECONDS).terminal)
                        assertEquals(StreamEvent.Cancelled, events.last())
                        assertEquals(1, events.count { it == StreamEvent.Cancelled })
                        assertFalse(events.contains(StreamEvent.Completed))
                        fixture.assertOneRequestAndReleased()
                    } finally { worker.shutdownNow() }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun totalDeadlineStopsServerWithDelayedHeadersWithoutRetry() {
        Protocol.entries.forEach { protocol ->
            val bounds = defaultBounds.copy(totalMillis = 200, idleMillis = 1_500)
            val response = sse(HttpWireFixtures.complete(protocol)).newBuilder().headersDelay(1, TimeUnit.SECONDS).build()
            val result = perform(protocol, response, bounds)
            assertFailed(result.first, result.second, FailureKind.TIMEOUT)
            assertNull(result.first.retryDecision?.delay)
        }
    }

    @Test(timeout = 10_000) fun idleDeadlineStopsHeldBodyAndPreservesPreviewWithoutRetry() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds.copy(idleMillis = 150, totalMillis = 2_000)).use { fixture ->
                val body = fixture.heldBody(HttpWireFixtures.preview(protocol))
                fixture.server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())
                val events = mutableListOf<StreamEvent>()
                val outcome = fixture.exchange(protocol).execute(events::add)
                assertFailed(outcome, events, FailureKind.TIMEOUT)
                assertEquals(HttpWireFixtures.text, text(events))
                assertEquals(1L, body.release.count)
                assertNull(outcome.retryDecision?.delay)
                fixture.assertOneRequestAndReleased()
            }
        }
    }

    @Test(timeout = 10_000) fun redirectNeverFollowsLocationOrChangesProvider() {
        Protocol.entries.forEach { protocol ->
            listOf(301, 302, 307, 308).forEach { status ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(MockResponse.Builder().code(status).addHeader("Location", fixture.server.url("/should-not-follow"))
                        .body("synthetic redirect").build())
                    fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                    val events = mutableListOf<StreamEvent>()
                    val outcome = fixture.exchange(protocol).execute(events::add)
                    assertFailed(outcome, events, FailureKind.REDIRECT_BLOCKED)
                    assertEquals(status, outcome.httpStatus)
                    assertNull(outcome.retryDecision?.delay)
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun authAndProxyChallengesNeverRetryOrSendCredentials() {
        Protocol.entries.forEach { protocol ->
            listOf(401, 407).forEach { status ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(MockResponse.Builder().code(status)
                        .addHeader(if (status == 401) "WWW-Authenticate" else "Proxy-Authenticate", "Basic realm=synthetic")
                        .body("synthetic auth failure").build())
                    fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                    val events = mutableListOf<StreamEvent>()
                    val outcome = fixture.exchange(protocol).execute(events::add)
                    assertTrue(outcome.terminal is StreamEvent.Failed)
                    assertEquals(status, outcome.httpStatus)
                    assertNull(outcome.retryDecision?.delay)
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun retryableHttpStatusesProduceAdviceAndExactlyOneActualRequest() {
        Protocol.entries.forEach { protocol ->
            listOf(408, 421, 429, 503).forEach { status ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(MockResponse.Builder().code(status).addHeader("Retry-After", "0")
                        .body("synthetic transient failure").build())
                    fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                    val events = mutableListOf<StreamEvent>()
                    val outcome = fixture.exchange(protocol).execute(events::add)
                    assertTrue(outcome.terminal is StreamEvent.Failed)
                    assertEquals(status, outcome.httpStatus)
                    if (status == 408 || status == 429 || status == 503) assertEquals(Duration.ofSeconds(1), outcome.retryDecision?.delay)
                    else assertNull(outcome.retryDecision?.delay)
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun errorBodyReadsOnlyCapPlusProbeAndDoesNotWaitForHugeServerTail() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                val marker = "synthetic-private-marker"
                val prefix = marker.repeat(4).take(defaultBounds.errorBodyBytes + 1).toByteArray()
                assertEquals(defaultBounds.errorBodyBytes + 1, prefix.size)
                val body = fixture.heldBody(prefix, declaredLength = 1_048_576)
                fixture.server.enqueue(MockResponse.Builder().code(503).addHeader("X-Synthetic-Diagnostic", marker).body(body).build())
                val events = mutableListOf<StreamEvent>()
                val exchange = fixture.exchange(protocol)
                val outcome = exchange.execute(events::add)
                assertFailed(outcome, events, FailureKind.TEMPORARY)
                assertEquals(defaultBounds.errorBodyBytes, outcome.errorBodyBytes)
                assertTrue(outcome.errorBodyTruncated)
                assertEquals("only cap+1 bytes have been sent before result", 1L, body.release.count)
                assertFalse(outcome.toString().contains(marker))
                assertFalse(exchange.toString().contains(marker))
                assertFalse(events.toString().contains(marker))
                fixture.assertOneRequestAndReleased()
            }
        }
    }

    @Test(timeout = 10_000) fun shortAndExactCapErrorBodiesHaveHonestByteCountsAndNoTruncation() {
        Protocol.entries.forEach { protocol ->
            listOf(0, 3, defaultBounds.errorBodyBytes).forEach { size ->
                val result = perform(protocol, MockResponse.Builder().code(400).body(Buffer().write(ByteArray(size) { 'x'.code.toByte() })).build())
                assertEquals(size, result.first.errorBodyBytes)
                assertFalse(result.first.errorBodyTruncated)
                assertFailed(result.first, result.second, FailureKind.CONFIGURATION)
            }
        }
    }

    @Test(timeout = 10_000) fun absentOrNonSseMimeCannotBecomeCompleted() {
        Protocol.entries.forEach { protocol ->
            listOf<String?>(null, "text/plain", "application/json", "application/octet-stream").forEach { mime ->
                val builder = MockResponse.Builder().code(200).body(Buffer().write(HttpWireFixtures.complete(protocol)))
                if (mime != null) builder.addHeader("Content-Type", mime)
                val result = perform(protocol, builder.build())
                assertTrue(result.first.terminal is StreamEvent.Failed)
                assertFalse(result.second.contains(StreamEvent.Completed))
                assertEquals(result.first.terminal, result.second.last())
            }
        }
    }

    @Test(timeout = 10_000) fun callbackExceptionReturnsSafeConsumerFailureAndClosesActualConnection() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                val calls = AtomicInteger()
                val marker = "synthetic-private-consumer-marker"
                val outcome = fixture.exchange(protocol).execute { calls.incrementAndGet(); throw IllegalStateException(marker) }
                assertEquals(StreamEvent.Failed(FailureKind.CONSUMER_ERROR), outcome.terminal)
                assertEquals(1, calls.get())
                assertFalse(outcome.toString().contains(marker))
                fixture.assertOneRequestAndReleased()
            }
        }
    }

    @Test(timeout = 10_000) fun onTextCancelPreventsLaterWireCompletionForCoalescedAndFragmentedBodies() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { fragmented ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(wireResponse(protocol, fragmented))
                    val exchange = fixture.exchange(protocol)
                    val events = mutableListOf<StreamEvent>()
                    val outcome = exchange.execute { event ->
                        events.add(event)
                        if (event is StreamEvent.TextDelta) exchange.cancel()
                    }
                    assertEquals(StreamEvent.Cancelled, outcome.terminal)
                    assertEquals(StreamEvent.Cancelled, events.last())
                    assertEquals(1, events.count { it is StreamEvent.TextDelta })
                    assertEquals(1, events.count { it == StreamEvent.Cancelled })
                    assertFalse(events.contains(StreamEvent.Completed))
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun onStartedCancelOrThrowStopsAllLaterEventsAcrossHttpFragmentation() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { fragmented ->
                listOf(false, true).forEach { throwConsumer ->
                    Harness(defaultBounds).use { fixture ->
                        fixture.server.enqueue(wireResponse(protocol, fragmented))
                        val exchange = fixture.exchange(protocol)
                        val events = mutableListOf<StreamEvent>()
                        val outcome = exchange.execute { event ->
                            events.add(event)
                            if (event == StreamEvent.Started) {
                                if (throwConsumer) throw IllegalStateException("synthetic-started-callback")
                                exchange.cancel()
                            }
                        }
                        if (throwConsumer) {
                            assertEquals(StreamEvent.Failed(FailureKind.CONSUMER_ERROR), outcome.terminal)
                            assertEquals(listOf(StreamEvent.Started), events)
                        } else {
                            assertEquals(StreamEvent.Cancelled, outcome.terminal)
                            assertEquals(listOf(StreamEvent.Started, StreamEvent.Cancelled), events)
                        }
                        fixture.assertOneRequestAndReleased()
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun onTextThrowPreventsLaterWireCompletionForCoalescedAndFragmentedBodies() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { fragmented ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(wireResponse(protocol, fragmented))
                    val events = mutableListOf<StreamEvent>()
                    val marker = "synthetic-private-callback-marker"
                    val outcome = fixture.exchange(protocol).execute { event ->
                        events.add(event)
                        if (event is StreamEvent.TextDelta) throw IllegalStateException(marker)
                    }
                    assertEquals(StreamEvent.Failed(FailureKind.CONSUMER_ERROR), outcome.terminal)
                    assertEquals(1, events.count { it is StreamEvent.TextDelta })
                    assertFalse(events.contains(StreamEvent.Completed))
                    assertFalse(outcome.toString().contains(marker))
                    assertTrue(events.last() is StreamEvent.TextDelta)
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun ordinaryAssertionErrorOnTextIsSanitizedAcrossActualHttpFragmentation() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { fragmented ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(wireResponse(protocol, fragmented))
                    val marker = "synthetic-private-assertion-marker"
                    val events = mutableListOf<StreamEvent>()
                    val outcome = fixture.exchange(protocol).execute { event ->
                        events.add(event)
                        if (event is StreamEvent.TextDelta) throw AssertionError(marker)
                    }
                    assertEquals(StreamEvent.Failed(FailureKind.CONSUMER_ERROR), outcome.terminal)
                    assertEquals(1, events.count { it is StreamEvent.TextDelta })
                    assertFalse(events.contains(StreamEvent.Completed))
                    assertTrue(events.last() is StreamEvent.TextDelta)
                    assertFalse(outcome.toString().contains(marker))
                    assertFalse(events.toString().contains(marker))
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun ordinaryAssertionErrorOnCompletedKeepsValidatedCompletionAndReleasesConnection() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { fragmented ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(wireResponse(protocol, fragmented))
                    val marker = "synthetic-private-completed-assertion-marker"
                    val events = mutableListOf<StreamEvent>()
                    val outcome = fixture.exchange(protocol).execute { event ->
                        events.add(event)
                        if (event == StreamEvent.Completed) throw AssertionError(marker)
                    }
                    assertCompleted(outcome, events)
                    assertFalse(outcome.toString().contains(marker))
                    assertFalse(events.toString().contains(marker))
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun completedCallbackThrowOrLateCancelKeepsValidatedCompletion() {
        Protocol.entries.forEach { protocol ->
            listOf(false, true).forEach { throwConsumer ->
                Harness(defaultBounds).use { fixture ->
                    fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                    val exchange = fixture.exchange(protocol)
                    val events = mutableListOf<StreamEvent>()
                    val outcome = exchange.execute { event ->
                        events.add(event)
                        if (event == StreamEvent.Completed) {
                            exchange.cancel()
                            fixture.transport.close()
                            if (throwConsumer) throw IllegalStateException("synthetic-private-final-callback")
                        }
                    }
                    assertCompleted(outcome, events)
                    assertFalse(outcome.toString().contains("synthetic-private-final-callback"))
                    fixture.assertOneRequestAndReleased()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun cancelAndCloseAfterCompletedCannotChangeOutcomeOrAddEvents() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                fixture.server.enqueue(sse(HttpWireFixtures.complete(protocol)))
                val exchange = fixture.exchange(protocol)
                val events = mutableListOf<StreamEvent>()
                val outcome = exchange.execute(events::add)
                val before = HttpWireFixtures.summary(events)
                exchange.cancel()
                fixture.transport.close()
                assertEquals(StreamEvent.Completed, outcome.terminal)
                assertEquals(before, HttpWireFixtures.summary(events))
                assertEquals(1, events.count { it == StreamEvent.Completed })
                assertThrows(IllegalStateException::class.java) { exchange.execute() }
                assertThrows(IllegalStateException::class.java) { fixture.exchange(protocol) }
                fixture.assertOneRequestAndReleased()
            }
        }
    }

    @Test(timeout = 10_000) fun productionEntryRejectsDirectHttpAndUnsafeHeadersBeforeAnyRequest() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds).use { fixture ->
                val baseline = HttpWireFixtures.prepared(protocol)
                val unsafeUrl = PreparedRequest(URI("http://127.0.0.1:${fixture.server.port}${baseline.endpoint.rawPath}"),
                    baseline.headers, baseline.bodyJson, baseline.authentication)
                assertThrows(IllegalArgumentException::class.java) { fixture.transport.newExchange(protocol, unsafeUrl) }
                listOf("Host", "Content-Length", "Authorization", "x-api-key", "Proxy-Authorization", "Connection").forEach { header ->
                    val unsafe = PreparedRequest(baseline.endpoint, baseline.headers + (header to "synthetic-value"),
                        baseline.bodyJson, baseline.authentication)
                    assertThrows(IllegalArgumentException::class.java) { fixture.transport.newExchange(protocol, unsafe) }
                }
                assertEquals(0, fixture.server.requestCount)
                assertEquals(0, fixture.observer.acquired.get())
            }
        }
    }

    @Test(timeout = 10_000) fun oversizedPreparedRequestIsRejectedBeforeNetworkOrCallback() {
        Protocol.entries.forEach { protocol ->
            Harness(defaultBounds.copy(requestBytes = 16)).use { fixture ->
                assertThrows(IllegalArgumentException::class.java) { fixture.exchange(protocol) }
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    private fun perform(protocol: Protocol, response: MockResponse, bounds: HttpBounds = defaultBounds,
                        limits: StreamLimits = StreamLimits()): Pair<HttpOutcome, List<StreamEvent>> = Harness(bounds).use { fixture ->
        fixture.server.enqueue(response)
        val events = mutableListOf<StreamEvent>()
        val outcome = fixture.exchange(protocol, limits).execute(events::add)
        assertEquals(1, events.count { it is StreamEvent.Failed || it == StreamEvent.Completed || it == StreamEvent.Cancelled })
        assertEquals(outcome.terminal, events.last())
        fixture.assertOneRequestAndReleased()
        outcome to events
    }

    private fun sse(bytes: ByteArray) = MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
        .body(Buffer().write(bytes)).build()

    private fun wireResponse(protocol: Protocol, fragmented: Boolean): MockResponse = if (!fragmented) sse(HttpWireFixtures.complete(protocol))
        else MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
            .chunkedBody(Buffer().write(HttpWireFixtures.complete(protocol)), 1).build()

    private fun text(events: List<StreamEvent>) = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

    private fun assertCompleted(outcome: HttpOutcome, events: List<StreamEvent>) {
        assertEquals(StreamEvent.Completed, outcome.terminal)
        assertEquals(200, outcome.httpStatus)
        assertEquals(StreamEvent.Completed, events.last())
        assertEquals(1, events.count { it == StreamEvent.Completed })
        assertFalse(events.any { it is StreamEvent.Failed || it == StreamEvent.Cancelled })
    }

    private fun assertFailed(outcome: HttpOutcome, events: List<StreamEvent>, kind: FailureKind) {
        assertEquals(StreamEvent.Failed(kind), outcome.terminal)
        assertEquals(outcome.terminal, events.last())
        assertEquals(1, events.count { it is StreamEvent.Failed })
        assertFalse(events.contains(StreamEvent.Completed))
    }

    private class ExchangeObserver : EventListener() {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val settled = CountDownLatch(1)
        override fun connectionAcquired(call: Call, connection: Connection) { acquired.incrementAndGet() }
        override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
        override fun callEnd(call: Call) { settled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { settled.countDown() }
    }

    private class HeldBody(private val prefix: ByteArray, override val contentLength: Long) : MockResponseBody {
        val release = CountDownLatch(1)
        val prefixWritten = CountDownLatch(1)
        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix).flush()
            prefixWritten.countDown()
            check(release.await(3, TimeUnit.SECONDS)) { "Owned fixture body wait exceeded bound" }
            // A delayed trailing byte distinguishes protocol completion from HTTP EOF.
            sink.writeByte('x'.code).flush()
        }
    }

    private class Harness(bounds: HttpBounds) : Closeable {
        val server = MockWebServer()
        val observer = ExchangeObserver()
        private val bodies = mutableListOf<HeldBody>()
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val client: OkHttpClient
        val transport: OkHttpStreamingTransport
        init {
            server.start(loopback, 0)
            client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { host ->
                check(host == "127.0.0.1") { "Test fixture forbids external DNS" }
                listOf(loopback)
            }.eventListener(observer).addInterceptor { chain ->
                val original = chain.request()
                check(original.url.scheme == "https" && original.url.host == "example.test")
                // APPLICATION interceptor: replace the route before DNS/TLS/ConnectInterceptor.
                val route = server.url("/").newBuilder().host("127.0.0.1").encodedPath(original.url.encodedPath).build()
                chain.proceed(original.newBuilder().url(route).build())
            }.build()
            // Fixture-only reflection keeps the production client-injection constructor private.
            transport = OkHttpStreamingTransport::class.java
                .getDeclaredConstructor(HttpBounds::class.java, OkHttpClient::class.java)
                .apply { isAccessible = true }
                .newInstance(bounds, client)
        }

        fun exchange(protocol: Protocol, limits: StreamLimits = StreamLimits()) =
            transport.newExchange(protocol, HttpWireFixtures.prepared(protocol), limits)

        fun heldBody(prefix: ByteArray, declaredLength: Long = prefix.size.toLong() + 1): HeldBody =
            HeldBody(prefix, declaredLength).also { bodies.add(it) }

        fun assertOneRequestAndReleased() {
            assertEquals("No retries, redirects, auth resends or provider switches", 1, server.requestCount)
            assertTrue("Owned call must finish or fail", observer.settled.await(1, TimeUnit.SECONDS))
            assertEquals(observer.acquired.get(), observer.released.get())
        }

        override fun close() {
            bodies.forEach { it.release.countDown() }
            transport.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }
}
