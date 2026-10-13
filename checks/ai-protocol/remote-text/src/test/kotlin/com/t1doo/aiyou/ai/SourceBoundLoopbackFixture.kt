package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.RemoteTextFixture
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.*

/** Real SQL repository plus the actual OkHttp transport routed only in test application code. */
internal class SourceBoundLoopbackFixture(
    protocol: ConnectionProtocol,
    selectedB: Boolean = false,
    initiallyApproved: Boolean = true,
    fragmented: Boolean = false,
    responseCode: Int = 200,
    responseBytes: ByteArray = complete(protocol),
) : Closeable {
    val records = RemoteTextFixture(protocol, selectedB, initiallyApproved)
    val serverA = MockWebServer()
    val serverB = MockWebServer()
    val keyA = "synthetic-private-key-a"
    val keyB = "synthetic-private-key-b"
    val now = AtomicReference(records.now)
    var beforeConnect: (() -> Unit)? = null
    val observer = Observer()
    private val client: OkHttpClient
    val transport: OkHttpStreamingTransport

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
            .newInstance(HttpBounds(connectMillis = 500, totalMillis = 3_000, idleMillis = 1_000, writeMillis = 1_000), client)
        listOf(serverA, serverB).forEach { server ->
            val body = if (responseCode == 200) responseBytes else "synthetic-private-provider-error".toByteArray()
            val response = MockResponse.Builder().code(responseCode).addHeader("Content-Type", "text/event-stream")
            if (responseCode == 503) response.addHeader("Retry-After", "0")
            if (fragmented) response.chunkedBody(Buffer().write(body), 1) else response.body(Buffer().write(body))
            server.enqueue(response.build())
        }
    }

    fun exchange(secrets: SecretResolver = resolver(), clock: () -> Instant = { now.get() }): SourceBoundStreamingExchange =
        transport.newSourceBoundExchange(records.repository, records.intent, maxOutputTokens = 73, secrets = secrets, clock = clock)

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

    fun wiped(resolver: Resolver) {
        assertTrue(resolver.values.isNotEmpty()); assertTrue(resolver.values.all { it.isClosed })
        assertTrue(resolver.arrays.all { chars -> chars.all { it == '\u0000' } })
    }

    override fun close() {
        transport.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
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
        override fun connectionAcquired(call: Call, connection: Connection) { acquired.incrementAndGet() }
        override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
        override fun callEnd(call: Call) { settled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { settled.countDown() }
    }

    companion object {
        const val TEXT = "合成候选🙂e\u0301"
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
