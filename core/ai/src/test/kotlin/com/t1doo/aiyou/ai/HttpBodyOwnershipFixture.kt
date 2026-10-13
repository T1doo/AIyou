package com.t1doo.aiyou.ai

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
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
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*

/** Observe the actual HTTP response source; only the fixture owns routing and instrumentation. */
internal class HttpBodyOwnershipFixture(protocol: Protocol, private val blockRead: Boolean,
                                       private val throwOnClose: Boolean) : Closeable {
    val serverA = MockWebServer()
    val serverB = MockWebServer()
    val probe = SourceProbe()
    val observer = Observer()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val client: OkHttpClient
    val transport: OkHttpStreamingTransport
    val exchange: StreamingHttpExchange

    init {
        serverA.start(loopback, 0)
        serverB.start(loopback, 0)
        val response = MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
            .body(Buffer().write(HttpWireFixtures.complete(protocol))).build()
        serverA.enqueue(response)
        serverB.enqueue(response)
        client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { host ->
            check(host == "127.0.0.1") { "Fixture forbids external DNS" }
            listOf(loopback)
        }.eventListener(observer).addInterceptor { chain ->
            val original = chain.request()
            check(original.url.scheme == "https")
            val server = when (original.url.host) {
                "provider-a.example.test" -> serverA
                "provider-b.example.test" -> serverB
                else -> error("Fixture forbids other providers")
            }
            val route = server.url("/").newBuilder().host("127.0.0.1").encodedPath(original.url.encodedPath).build()
            val actual = chain.proceed(original.newBuilder().url(route).build())
            val body = actual.body
            val monitored = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    probe.activeReads.incrementAndGet()
                    probe.ownerThread.compareAndSet(null, Thread.currentThread())
                    try {
                        if (blockRead) {
                            probe.readEntered.countDown()
                            check(probe.releaseRead.await(3, TimeUnit.SECONDS)) { "Owned source barrier exceeded bound" }
                        }
                        return super.read(sink, byteCount)
                    } finally { probe.activeReads.decrementAndGet() }
                }

                override fun close() {
                    probe.closeCount.incrementAndGet()
                    probe.closeThread.set(Thread.currentThread())
                    if (probe.activeReads.get() != 0) probe.closeOverlaps.incrementAndGet()
                    try { super.close() } finally {
                        probe.closeAttempted.countDown()
                        if (throwOnClose) throw AssertionError(SourceProbe.closeMarker)
                    }
                }
            }.buffer()
            val wrapped = object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = monitored
            }
            actual.newBuilder().body(wrapped).build()
        }.build()
        transport = OkHttpStreamingTransport::class.java
            .getDeclaredConstructor(HttpBounds::class.java, OkHttpClient::class.java)
            .apply { isAccessible = true }
            .newInstance(HttpBounds(connectMillis = 500, totalMillis = 5_000,
                idleMillis = 3_000, writeMillis = 1_000), client)
        val prepared = ProtocolRequests.prepare("https://provider-a.example.test/custom/v1/", protocol,
            TextRequest("synthetic-model", listOf(TextMessage(MessageRole.USER, "synthetic-input"))))
        exchange = transport.newExchange(protocol, prepared)
    }

    fun assertActualRequestAndReleased() {
        assertEquals(1, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
        assertTrue(observer.settled.await(1, TimeUnit.SECONDS))
        assertEquals(1, observer.acquired.get())
        assertEquals(1, observer.released.get())
    }

    override fun close() {
        probe.releaseRead.countDown()
        transport.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        serverA.close()
        serverB.close()
    }

    class SourceProbe {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val activeReads = AtomicInteger()
        val closeCount = AtomicInteger()
        val closeOverlaps = AtomicInteger()
        val ownerThread = AtomicReference<Thread>()
        val closeThread = AtomicReference<Thread>()
        companion object { const val closeMarker = "synthetic-private-close-marker" }
    }

    class Observer : EventListener() {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val settled = CountDownLatch(1)
        val socket = AtomicReference<Socket>()
        val call = AtomicReference<Call>()
        override fun callStart(call: Call) { this.call.set(call) }
        override fun connectionAcquired(call: Call, connection: Connection) {
            acquired.incrementAndGet()
            socket.set(connection.socket())
        }
        override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
        override fun callEnd(call: Call) { settled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { settled.countDown() }
    }
}
