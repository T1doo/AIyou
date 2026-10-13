package com.t1doo.aiyou.ai

import com.t1doo.aiyou.domain.CurrentSendAuthority
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

/** Historical guard contracts exercise the private entry only through test-source reflection. */
private fun OkHttpStreamingTransport.newGuardedExchange(
    authority: CurrentSendAuthority,
    intent: RemoteSendIntent,
    request: TextRequest,
    secrets: SecretResolver,
    clock: () -> Instant,
    streamLimits: StreamLimits = StreamLimits(),
): GuardedStreamingExchange {
    val method = OkHttpStreamingTransport::class.java.declaredMethods.single {
        it.name == "newGuardedExchange" && it.parameterCount == 7 &&
            it.parameterTypes.first() == CurrentSendAuthority::class.java
    }.apply { isAccessible = true }
    return try {
        method.invoke(this, authority, intent, request, secrets, clock, streamLimits, null) as GuardedStreamingExchange
    } catch (failure: InvocationTargetException) {
        throw failure.cause ?: failure
    }
}

/** Owned dual-loopback fixture. All references, authentication values and records are synthetic. */
internal class GuardedLoopbackFixture(protocol: ConnectionProtocol, selectedB: Boolean = false,
                                     responseCode: Int = 200, fragmented: Boolean = false,
                                     holdResponseTail: Boolean = false) : Closeable {
    val serverA = MockWebServer()
    val serverB = MockWebServer()
    val now: Instant = Instant.parse("2026-10-13T12:00:00Z")
    val clock = AtomicReference(now)
    var currentTime: () -> Instant = { clock.get() }
    val source = SourceVersion("synthetic-private-source", 3)
    val keyA = "synthetic-private-key-a"
    val keyB = "synthetic-private-key-b"
    val connectionA = ProviderConnection("synthetic-provider-a", 7,
        "https://provider-a.example.test/custom/v1/", protocol, "synthetic-model-a", SecretReference("synthetic-private-ref-a"))
    val connectionB = ProviderConnection("synthetic-provider-b", 11,
        "https://provider-b.example.test/custom/v1/", protocol, "synthetic-model-b", SecretReference("synthetic-private-ref-b"))
    val selected = if (selectedB) connectionB else connectionA
    val grant = ConsentGrant("synthetic-private-grant", 5, source, selected, TextPurpose.ORGANIZATION,
        now.minusSeconds(60), now.plusSeconds(3_600))
    val approvedState = RemoteSendState(source, ProcessingPolicy.REMOTE_ALLOWED,
        connection = selected, consent = grant)
    val authority = CurrentSendAuthority(approvedState)
    val intent = RemoteSendIntent(source, TextPurpose.ORGANIZATION)
    var beforeConnect: (() -> Unit)? = null
    val observer = Observer()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val client: OkHttpClient
    private val heldBodies = mutableListOf<HeldBody>()
    val transport: OkHttpStreamingTransport

    init {
        serverA.start(loopback, 0)
        serverB.start(loopback, 0)
        client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { hostname ->
            check(hostname == "127.0.0.1") { "Fixture forbids external DNS" }
            listOf(loopback)
        }.eventListener(observer).addInterceptor { chain ->
            val original = chain.request()
            check(original.url.scheme == "https")
            val server = when (original.url.host) {
                "provider-a.example.test" -> serverA
                "provider-b.example.test" -> serverB
                else -> error("Fixture forbids other providers")
            }
            // Run before ConnectInterceptor; the production final admission barrier remains active.
            beforeConnect?.invoke()
            val route = server.url("/").newBuilder().host("127.0.0.1").encodedPath(original.url.encodedPath).build()
            chain.proceed(original.newBuilder().url(route).build())
        }.build()
        transport = OkHttpStreamingTransport::class.java
            .getDeclaredConstructor(HttpBounds::class.java, OkHttpClient::class.java)
            .apply { isAccessible = true }
            .newInstance(HttpBounds(connectMillis = 500, totalMillis = if (holdResponseTail) 5_000 else 3_000,
                idleMillis = if (holdResponseTail) 3_000 else 1_000, writeMillis = 1_000), client)
        if (holdResponseTail) {
            listOf(serverA to connectionA.protocol, serverB to connectionB.protocol).forEach { (server, wire) ->
                val body = HeldBody(HttpWireFixtures.preview(wireProtocol(wire)), HttpWireFixtures.terminal(wireProtocol(wire)))
                heldBodies.add(body)
                server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())
            }
        } else {
            enqueueResponse(serverA, connectionA.protocol, responseCode, fragmented)
            enqueueResponse(serverB, connectionB.protocol, responseCode, fragmented)
        }
    }

    fun exchange(secrets: SecretResolver, requestedIntent: RemoteSendIntent = intent,
                 request: TextRequest = requestFor(selected)): GuardedStreamingExchange =
        transport.newGuardedExchange(authority, requestedIntent, request, secrets, clock = { currentTime() })

    fun resolver(value: String? = null, onResolve: (SecretReference) -> Unit = {}): TrackingResolver =
        TrackingResolver { reference ->
            onResolve(reference)
            value ?: when (reference) {
                connectionA.secretRef -> keyA
                connectionB.secretRef -> keyB
                else -> error("synthetic-private-unknown-reference")
            }
        }

    fun assertNoRequests() {
        assertEquals(0, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
    }

    fun assertSelectedRequestAndReleased() {
        assertEquals(if (selected == connectionA) 1 else 0, serverA.requestCount)
        assertEquals(if (selected == connectionB) 1 else 0, serverB.requestCount)
        assertTrue("Owned call must settle", observer.settled.await(1, TimeUnit.SECONDS))
        assertEquals(observer.acquired.get(), observer.released.get())
    }

    fun assertSecretWiped(resolver: TrackingResolver) {
        assertTrue(resolver.values.isNotEmpty())
        assertTrue(resolver.values.all { it.isClosed })
        assertTrue(resolver.arrays.all { chars -> chars.all { it == '\u0000' } })
    }

    override fun close() {
        heldBodies.forEach { it.release.countDown() }
        transport.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        serverA.close()
        serverB.close()
    }

    class TrackingResolver(private val raw: (SecretReference) -> String) : SecretResolver {
        val references = mutableListOf<SecretReference>()
        val arrays = mutableListOf<CharArray>()
        val values = mutableListOf<ResolvedSecret>()
        override fun resolve(reference: SecretReference): ResolvedSecret {
            references.add(reference)
            val chars = raw(reference).toCharArray()
            arrays.add(chars)
            return ResolvedSecret.fromChars(chars).also { values.add(it) }
        }
    }

    class Observer : EventListener() {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val settled = CountDownLatch(1)
        override fun connectionAcquired(call: Call, connection: Connection) { acquired.incrementAndGet() }
        override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
        override fun callEnd(call: Call) { settled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { settled.countDown() }
    }

    private class HeldBody(private val prefix: ByteArray, private val tail: ByteArray) : MockResponseBody {
        override val contentLength = prefix.size.toLong() + tail.size
        val release = CountDownLatch(1)
        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix).flush()
            check(release.await(5, TimeUnit.SECONDS)) { "Owned response tail wait exceeded bound" }
            sink.write(tail).flush()
        }
    }

    companion object {
        fun wireProtocol(protocol: ConnectionProtocol) = when (protocol) {
            ConnectionProtocol.CHAT_COMPLETIONS -> Protocol.CHAT_COMPLETIONS
            ConnectionProtocol.ANTHROPIC_MESSAGES -> Protocol.ANTHROPIC_MESSAGES
        }

        fun requestFor(connection: ProviderConnection) = TextRequest(connection.modelId,
            listOf(TextMessage(MessageRole.USER, "synthetic-private-record🙂")), system = "synthetic-private-system",
            maxOutputTokens = 73,
            chatTokenBudget = if (connection.chatBudgetField == ChatBudgetField.MAX_TOKENS) ChatTokenBudget.MAX_TOKENS
                else ChatTokenBudget.MAX_COMPLETION_TOKENS,
            chatUsageSupported = connection.chatUsageSupported)

        private fun enqueueResponse(server: MockWebServer, protocol: ConnectionProtocol, code: Int, fragmented: Boolean) {
            val bytes = if (code == 200) HttpWireFixtures.complete(wireProtocol(protocol))
                else "synthetic-private-provider-error synthetic-private-key-a synthetic-private-key-b".toByteArray()
            val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "text/event-stream")
            if (fragmented) builder.chunkedBody(Buffer().write(bytes), 1) else builder.body(Buffer().write(bytes))
            server.enqueue(builder.build())
        }
    }
}
