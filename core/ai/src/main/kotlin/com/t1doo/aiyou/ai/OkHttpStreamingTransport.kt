package com.t1doo.aiyou.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Credential-free, one-request HTTP transport. Production callers can configure bounds only.
 * HTTPS and the platform's trust/hostname checks are mandatory; no replay or provider fallback
 * occurs here. An exchange owns its call and its body, never an injected client's other calls.
 */
class OkHttpStreamingTransport private constructor(
    private val bounds: HttpBounds,
    injectedClient: OkHttpClient,
) : StreamingHttpTransport {
    constructor(bounds: HttpBounds = HttpBounds()) : this(bounds, OkHttpClient())

    private val ownerLock = Any()
    private val exchanges = mutableSetOf<Exchange>()
    private var closed = false

    // Only the private constructor retains application interceptors. A source-test fixture
    // reflects into it for loopback routing; no Java/Kotlin production client injection exists.
    // TLS and transport policy are reset and this transport owns fresh connection pools.
    private val client: OkHttpClient = run {
        val platform = OkHttpClient()
        injectedClient.newBuilder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool())
            .sslSocketFactory(platform.sslSocketFactory, checkNotNull(platform.x509TrustManager))
            .socketFactory(platform.socketFactory)
            .hostnameVerifier(platform.hostnameVerifier)
            .certificatePinner(platform.certificatePinner)
            .connectionSpecs(platform.connectionSpecs)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            .connectTimeout(bounds.connectMillis, TimeUnit.MILLISECONDS)
            .callTimeout(bounds.totalMillis, TimeUnit.MILLISECONDS)
            .readTimeout(bounds.idleMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(bounds.writeMillis, TimeUnit.MILLISECONDS)
            .apply {
                networkInterceptors().clear()
                addNetworkInterceptor(RejectHttpFollowUps())
            }
            .build()
    }

    override fun newExchange(
        protocol: Protocol,
        request: PreparedRequest,
        streamLimits: StreamLimits,
    ): StreamingHttpExchange {
        // PreparedRequest is public, so do not assume it came from ProtocolRequests.prepare().
        val wireRequest = validateRequest(protocol, request)
        return synchronized(ownerLock) {
            check(!closed) { "HTTP transport is closed" }
            Exchange(protocol, streamLimits, wireRequest).also { exchanges.add(it) }
        }
    }

    override fun close() {
        val owned = synchronized(ownerLock) {
            if (closed) return
            closed = true
            exchanges.toList().also { exchanges.clear() }
        }
        owned.forEach { it.cancel() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun validateRequest(protocol: Protocol, description: PreparedRequest): Request {
        val endpoint = description.endpoint
        require(endpoint.toASCIIString().length <= 4096 && !endpoint.isOpaque &&
            endpoint.scheme.equals("https", ignoreCase = true) && !endpoint.host.isNullOrBlank() &&
            endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null &&
            (endpoint.port == -1 || endpoint.port in 1..65535)) { "Invalid HTTPS operation endpoint" }
        val path = endpoint.rawPath.orEmpty()
        val suffix = if (protocol == Protocol.CHAT_COMPLETIONS) "/chat/completions" else "/messages"
        require(path.endsWith(suffix) && !path.lowercase().contains("%2f") &&
            !path.lowercase().contains("%5c") &&
            endpoint.path.orEmpty().split('/').none { it == "." || it == ".." }) {
            "Invalid operation path"
        }
        val url = endpoint.toASCIIString().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.encodedPath == path) { "Invalid operation endpoint" }
        val authentication = if (protocol == Protocol.CHAT_COMPLETIONS) AuthenticationKind.BEARER
            else AuthenticationKind.ANTHROPIC_API_KEY
        require(description.authentication == authentication) { "Protocol authentication kind mismatch" }
        val expected = linkedMapOf("content-type" to "application/json", "accept" to "text/event-stream")
        if (protocol == Protocol.ANTHROPIC_MESSAGES) expected["anthropic-version"] = "2023-06-01"
        require(description.headers.size == expected.size && description.headers.entries.all {
            expected[it.key.lowercase()] == it.value
        } && description.headers.keys.map { it.lowercase() }.toSet().size == expected.size) {
            "Unsupported protocol headers"
        }
        require(description.bodyJson.length <= bounds.requestBytes &&
            isWellFormedUtf16(description.bodyJson)) { "Invalid request body bounds or Unicode" }
        val bytes = description.bodyJson.toByteArray(Charsets.UTF_8)
        require(bytes.size <= bounds.requestBytes) { "Request body exceeds supported limits" }
        // Reconstruct the allowed headers rather than forwarding a caller-controlled map.
        return Request.Builder().url(url).apply {
            expected.forEach { (name, value) -> header(name, value) }
        }.post(bytes.toRequestBody("application/json".toMediaType())).build()
    }

    /** Reject before OkHttp's RetryAndFollowUpInterceptor can replay 503/421 responses. */
    private inner class RejectHttpFollowUps : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (response.code in 200..299) return response
            val values = response.headers("Retry-After")
            val retryAfter = when {
                values.isEmpty() -> null
                values.size == 1 && values.single().length <= 128 -> values.single()
                else -> "" // Invalid metadata disables a recommendation rather than becoming absent.
            }
            var count = 0
            var readFailure: FailureKind? = null
            try {
                val scratch = Buffer()
                val limit = bounds.errorBodyBytes + 1
                while (count < limit) {
                    val read = response.body.source().read(scratch, (limit - count).toLong())
                    if (read < 0) break
                    count += read.toInt()
                    scratch.clear() // Never decode, log, expose or retain provider error content.
                }
            } catch (failure: IOException) {
                readFailure = ioKind(failure)
            } finally {
                chain.call().cancel() // Close the socket before body.close() can try to drain it.
                quietClose(response)
            }
            throw HttpRejected(
                response.code, retryAfter, minOf(count, bounds.errorBodyBytes),
                count > bounds.errorBodyBytes, readFailure,
            )
        }
    }

    private inner class Exchange(
        protocol: Protocol,
        limits: StreamLimits,
        private val request: Request,
    ) : StreamingHttpExchange {
        private val stateLock = Any()
        private val stream = ProtocolStream(protocol, limits)
        private var executed = false
        private var userCancelled = false
        private var terminal: StreamEvent? = null
        private var call: Call? = null
        private var response: Response? = null
        private var status: Int? = null
        private var retry: RetryDecision? = null
        private var errorBytes = 0
        private var errorTruncated = false
        private var hadPreview = false
        private var terminalDelivered = false
        private var consumerFailed = false

        override fun cancel() {
            val owned = synchronized(stateLock) {
                if (terminal == null) terminal = stream.terminalEvent
                if (terminal != null) return
                userCancelled = true
                terminal = StreamEvent.Cancelled
                call to response
            }
            owned.first?.cancel()
            owned.second?.let(::quietClose)
            synchronized(ownerLock) { exchanges.remove(this) }
            // The parser owner observes cancellation at its next frame boundary. Do not wait
            // for its monitor here: a consumer callback may still be running on that thread.
        }

        override fun execute(onEvent: (StreamEvent) -> Unit): HttpOutcome {
            synchronized(stateLock) {
                check(!executed) { "HTTP exchange can execute only once" }
                executed = true
            }
            try {
                if (selectedTerminal() != null) return finishOutcome(onEvent)
                val ownedCall = client.newCall(request)
                val shouldExecute = synchronized(stateLock) {
                    call = ownedCall
                    terminal == null
                }
                if (!shouldExecute) {
                    ownedCall.cancel()
                    return finishOutcome(onEvent)
                }
                val ownedResponse = ownedCall.execute()
                val shouldRead = synchronized(stateLock) {
                    response = ownedResponse
                    status = ownedResponse.code
                    terminal == null
                }
                if (!shouldRead) return finishOutcome(onEvent)
                val type = ownedResponse.body.contentType()
                if (type?.type != "text" || type.subtype != "event-stream") {
                    dispatch(acceptFailure(FailureKind.MALFORMED_RESPONSE), onEvent)
                    return finishOutcome(onEvent)
                }
                val buffer = ByteArray(bounds.readBufferBytes)
                while (selectedTerminal() == null) {
                    val read = ownedResponse.body.source().read(buffer)
                    if (selectedTerminal() != null) break
                    if (read < 0) {
                        val events = stream.finish()
                        synchronizeWireTerminal()
                        dispatch(events, onEvent)
                    } else {
                        // Deliver one validated frame at a time, including cancellation/consumer
                        // failure, before parsing a following terminal in this same network read.
                        stream.pushTo(buffer.copyOf(read)) { event ->
                            synchronizeWireTerminal()
                            if (selectedTerminal() == StreamEvent.Cancelled) stream.cancel()
                            dispatch(listOf(event), onEvent)
                            if (selectedTerminal() == StreamEvent.Cancelled) stream.cancel()
                        }
                    }
                }
            } catch (failure: HttpRejected) {
                val recommendation = if (failure.status in 100..599)
                    FailurePolicy().http(failure.status, 1, hadPreview, failure.retryAfter, Instant.now())
                else RetryDecision(FailureKind.MALFORMED_RESPONSE, null)
                synchronized(stateLock) {
                    status = failure.status
                    errorBytes = failure.bytes
                    errorTruncated = failure.truncated
                    if (terminal == null && failure.readFailure == null) retry = recommendation
                }
                dispatch(acceptFailure(failure.readFailure ?: recommendation.kind), onEvent)
            } catch (failure: IOException) {
                val kind = ioKind(failure)
                synchronized(stateLock) {
                    if (terminal == null && kind == FailureKind.NETWORK) {
                        retry = FailurePolicy().network(1, hadPreview)
                    }
                }
                dispatch(acceptFailure(kind), onEvent)
            } catch (_: Exception) {
                // Injected/test plumbing and library exceptions must not expose request/provider text.
                dispatch(acceptFailure(FailureKind.NETWORK), onEvent)
            } finally {
                releaseOwned()
                synchronized(ownerLock) { exchanges.remove(this) }
            }
            return finishOutcome(onEvent)
        }

        private fun acceptFailure(kind: FailureKind): List<StreamEvent> = synchronized(stateLock) {
            if (terminal == null) terminal = stream.terminalEvent
            if (terminal != null) emptyList() else {
                // Only explicit user cancellation selects Cancelled; cleanup and call timeouts do not.
                val events = if (userCancelled) stream.cancel() else stream.transportFailure(kind)
                events.also { terminal = it.lastOrNull() }
            }
        }

        private fun dispatch(events: List<StreamEvent>, onEvent: (StreamEvent) -> Unit) {
            if (selectedTerminal() != null) releaseOwned()
            for (event in events) {
                if (consumerFailed) return
                val selected = selectedTerminal()
                if (selected == StreamEvent.Cancelled && event != StreamEvent.Cancelled) return
                if (event is StreamEvent.TextDelta) hadPreview = true
                if (isTerminal(event)) {
                    if (terminalDelivered) continue
                    terminalDelivered = true
                }
                try {
                    onEvent(event)
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError) {
                        throw failure
                    }
                    consumerFailed = true
                    synchronized(stateLock) {
                        if (terminal == null) {
                            stream.transportFailure(FailureKind.CONSUMER_ERROR)
                            terminal = StreamEvent.Failed(FailureKind.CONSUMER_ERROR)
                        }
                    }
                    releaseOwned()
                    return
                }
            }
        }

        private fun finishOutcome(onEvent: (StreamEvent) -> Unit): HttpOutcome {
            releaseOwned()
            if (selectedTerminal() == StreamEvent.Cancelled) stream.cancel()
            val final = checkNotNull(selectedTerminal()) { "HTTP exchange requires a terminal outcome" }
            if (!consumerFailed && !terminalDelivered) dispatch(listOf(final), onEvent)
            return HttpOutcome(final, status, retry, errorBytes, errorTruncated)
        }

        private fun selectedTerminal(): StreamEvent? = synchronized(stateLock) { terminal }

        private fun synchronizeWireTerminal() {
            synchronized(stateLock) {
                if (terminal == null) terminal = stream.terminalEvent
            }
        }

        private fun releaseOwned() {
            val owned = synchronized(stateLock) {
                (call to response).also { response = null }
            }
            owned.first?.cancel()
            owned.second?.let(::quietClose)
        }
    }

    // This sanitized exception is private and holds no raw cause, error body or request address.
    private class HttpRejected(
        val status: Int,
        val retryAfter: String?,
        val bytes: Int,
        val truncated: Boolean,
        val readFailure: FailureKind?,
    ) : IOException("HTTP response rejected")

    private companion object {
        fun ioKind(failure: IOException): FailureKind =
            if (failure is InterruptedIOException) FailureKind.TIMEOUT else FailureKind.NETWORK

        fun isTerminal(event: StreamEvent): Boolean = event == StreamEvent.Completed ||
            event == StreamEvent.Cancelled || event is StreamEvent.Failed

        fun quietClose(response: Response) {
            try { response.close() } catch (_: Exception) { /* Owned cleanup cannot change a terminal. */ }
        }
    }
}
