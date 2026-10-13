package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.RemoteTextFixture
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
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

/** Real SQL repository plus the actual OkHttp transport routed only in test application code. */
internal class SourceBoundLoopbackFixture(
    protocol: ConnectionProtocol,
    selectedB: Boolean = false,
    initiallyApproved: Boolean = true,
    fragmented: Boolean = false,
    responseCode: Int = 200,
    responseBytes: ByteArray = complete(protocol),
    heldTail: Boolean = false,
    readBufferBytes: Int = 8192,
    heldResponsePrefix: ByteArray? = null,
) : Closeable {
    val records = RemoteTextFixture(protocol, selectedB, initiallyApproved)
    val serverA = MockWebServer()
    val serverB = MockWebServer()
    val keyA = "synthetic-private-key-a"
    val keyB = "synthetic-private-key-b"
    val now = AtomicReference(records.now)
    @Volatile var beforeConnect: (() -> Unit)? = null
    val observer = Observer()
    private val client: OkHttpClient
    val transport: OkHttpStreamingTransport
    private val heldBodies = mutableListOf<HeldBody>()
    private val additionalTransports = mutableListOf<OkHttpStreamingTransport>()
    var selectedBody: HeldBody? = null
        private set

    init {
        val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        serverA.start(address, 0); serverB.start(address, 0)
        client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { host ->
            check(host == "127.0.0.1") { "Owned fixture forbids external DNS" }; listOf(address)
        }.eventListener(observer).addInterceptor { chain ->
            val original = chain.request(); check(original.url.scheme == "https")
            val target = when (original.url.host) {
                "provider-a.example.test" -> serverA
                "provider-b.example.test" -> serverB
                else -> error("Owned fixture forbids another provider")
            }
            beforeConnect?.invoke()
            val url = target.url("/").newBuilder().host("127.0.0.1").encodedPath(original.url.encodedPath).build()
            chain.proceed(original.newBuilder().url(url).build())
        }.build()
        transport = OkHttpStreamingTransport::class.java
            .getDeclaredConstructor(HttpBounds::class.java, OkHttpClient::class.java)
            .apply { isAccessible = true }
            .newInstance(HttpBounds(connectMillis = 500, totalMillis = 3_000, idleMillis = 1_000,
                writeMillis = 1_000, readBufferBytes = readBufferBytes), client)
        listOf(serverA, serverB).forEach { server ->
            val body = if (responseCode == 200) responseBytes else "synthetic-private-provider-error".toByteArray()
            val response = MockResponse.Builder().code(responseCode).addHeader("Content-Type", "text/event-stream")
            if (responseCode == 503) response.addHeader("Retry-After", "0")
            if (heldTail && responseCode == 200) {
                val held = HeldBody(heldResponsePrefix ?: openPreview(protocol),
                    if (heldResponsePrefix == null) continuation(protocol) else ": synthetic owned unread tail\n\n".toByteArray())
                heldBodies.add(held)
                if (server === (if (selectedB) serverB else serverA)) selectedBody = held
                response.body(held)
            } else if (fragmented) response.chunkedBody(Buffer().write(body), 1) else response.body(Buffer().write(body))
            server.enqueue(response.build())
        }
    }

    fun exchange(secrets: SecretResolver = resolver(), clock: () -> Instant = { now.get() },
                 intent: RemoteSendIntent = records.intent,
                 repository: SourceBoundTextRepository = records.repository): SourceBoundStreamingExchange =
        transport.newSourceBoundExchange(repository, intent, maxOutputTokens = 73, secrets = secrets, clock = clock)

    fun enqueueHeld(protocol: ConnectionProtocol, providerB: Boolean = records.selectedB): HeldBody =
        HeldBody(openPreview(protocol), continuation(protocol)).also { held ->
            heldBodies.add(held)
            (if (providerB) serverB else serverA).enqueue(MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream").body(held).build())
        }

    fun resolver(onResolve: (SecretReference) -> Unit = {}): Resolver = Resolver { ref ->
        onResolve(ref)
        when (ref) {
            records.connectionA.secretRef -> keyA
            records.connectionB.secretRef -> keyB
            else -> error("synthetic-private-unknown-reference")
        }
    }

    fun noRequests() { assertEquals(0, serverA.requestCount); assertEquals(0, serverB.requestCount) }
    fun selectedRequestReleased() {
        assertEquals(if (records.selectedB) 0 else 1, serverA.requestCount)
        assertEquals(if (records.selectedB) 1 else 0, serverB.requestCount)
        assertTrue("Owned HTTP call must settle", observer.settled.await(1, TimeUnit.SECONDS))
        assertEquals(observer.acquired.get(), observer.released.get())
    }

    fun requestsReleased(a: Int, b: Int) {
        assertEquals(a, serverA.requestCount); assertEquals(b, serverB.requestCount)
        assertEquals(a + b, observer.finished.get())
        assertEquals(observer.acquired.get(), observer.released.get())
    }

    fun cancelledSocketReleased() {
        selectedRequestReleased()
        assertEquals(1, observer.calls.size)
        assertTrue(observer.calls.single().isCanceled())
        assertTrue(observer.sockets.isNotEmpty())
        assertTrue(observer.sockets.all { it.isClosed })
    }

    fun releaseTails() { heldBodies.forEach { it.release.countDown() } }

    /** Same actual injected client; each production transport retains ownership isolation. */
    fun additionalTransport(): OkHttpStreamingTransport =
        OkHttpStreamingTransport::class.java
            .getDeclaredConstructor(HttpBounds::class.java, OkHttpClient::class.java)
            .apply { isAccessible = true }
            .newInstance(HttpBounds(connectMillis = 500, totalMillis = 3_000,
                idleMillis = 1_000, writeMillis = 1_000), client)
            .also { additionalTransports.add(it) }

    fun wiped(resolver: Resolver) {
        assertTrue(resolver.values.isNotEmpty()); assertTrue(resolver.values.all { it.isClosed })
        assertTrue(resolver.arrays.all { chars -> chars.all { it == '\u0000' } })
    }

    override fun close() {
        releaseTails()
        transport.close()
        additionalTransports.forEach { it.close() }
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        serverA.close(); serverB.close(); records.close()
    }

    class Resolver(private val material: (SecretReference) -> String) : SecretResolver {
        val references = mutableListOf<SecretReference>()
        val arrays = mutableListOf<CharArray>()
        val values = mutableListOf<ResolvedSecret>()
        override fun resolve(reference: SecretReference): ResolvedSecret {
            references.add(reference); val chars = material(reference).toCharArray(); arrays.add(chars)
            return ResolvedSecret.fromChars(chars).also { values.add(it) }
        }
    }

    class Observer : EventListener() {
        val acquired = AtomicInteger(); val released = AtomicInteger(); val settled = CountDownLatch(1)
        val finished = AtomicInteger()
        val calls = CopyOnWriteArrayList<Call>(); val sockets = CopyOnWriteArrayList<Socket>()
        override fun callStart(call: Call) { calls.add(call) }
        override fun connectionAcquired(call: Call, connection: Connection) {
            sockets.add(connection.socket()); acquired.incrementAndGet()
        }
        override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
        override fun callEnd(call: Call) { finished.incrementAndGet(); settled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { finished.incrementAndGet(); settled.countDown() }
    }

    class HeldBody(private val prefix: ByteArray, private val tail: ByteArray) : MockResponseBody {
        val release = CountDownLatch(1); val prefixWritten = CountDownLatch(1); val finished = CountDownLatch(1)
        override val contentLength: Long = (prefix.size + tail.size).toLong()
        override fun writeTo(sink: BufferedSink) {
            try {
                sink.write(prefix); sink.flush(); prefixWritten.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Owned server tail barrier exceeded deadline" }
                sink.write(tail); sink.flush()
            } catch (_: IOException) {
                // A cancelled owned call closes the real socket before the tail is released.
            } finally { finished.countDown() }
        }
    }

    companion object {
        const val TEXT = "合成候选🙂e\u0301"
        const val LATE_TEXT = "synthetic-late-preview"
        fun openPreview(protocol: ConnectionProtocol): ByteArray = when (protocol) {
            ConnectionProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$TEXT\"},\"finish_reason\":null}]}\n\n"
            ConnectionProtocol.ANTHROPIC_MESSAGES ->
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\n" +
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"$TEXT\"}}\n\n"
        }.toByteArray(Charsets.UTF_8)
        fun continuation(protocol: ConnectionProtocol): ByteArray = when (protocol) {
            ConnectionProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$LATE_TEXT\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            ConnectionProtocol.ANTHROPIC_MESSAGES ->
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$LATE_TEXT\"}}\n\n" +
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        }.toByteArray(Charsets.UTF_8)
        fun complete(protocol: ConnectionProtocol) = preview(protocol) + terminal(protocol)
        fun preview(protocol: ConnectionProtocol): ByteArray = when (protocol) {
            ConnectionProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$TEXT\"},\"finish_reason\":\"stop\"}]}\n\n"
            ConnectionProtocol.ANTHROPIC_MESSAGES ->
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\n" +
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"$TEXT\"}}\n\n" +
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n\n"
        }.toByteArray(Charsets.UTF_8)
        fun terminal(protocol: ConnectionProtocol): ByteArray = when (protocol) {
            ConnectionProtocol.CHAT_COMPLETIONS -> "data: [DONE]\n\n"
            ConnectionProtocol.ANTHROPIC_MESSAGES -> "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        }.toByteArray()
    }
}
