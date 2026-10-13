package com.t1doo.aiyou.ai

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Protocol { CHAT_COMPLETIONS, ANTHROPIC_MESSAGES }
enum class MessageRole { USER, ASSISTANT }
enum class AuthenticationKind { BEARER, ANTHROPIC_API_KEY }
enum class ChatTokenBudget { MAX_TOKENS, MAX_COMPLETION_TOKENS }

class TextMessage(val role: MessageRole, val text: String) {
    override fun toString() = "TextMessage(role=$role, text=<redacted>)"
}

class TextRequest(
    val modelId: String,
    val messages: List<TextMessage>,
    val system: String? = null,
    val maxOutputTokens: Int = 1024,
    val chatTokenBudget: ChatTokenBudget = ChatTokenBudget.MAX_TOKENS,
    val chatUsageSupported: Boolean = false,
) {
    override fun toString() = "TextRequest(messages=${messages.size}, content=<redacted>)"
}

/** A wire request description, never a network client or a credential container. */
class PreparedRequest(
    val endpoint: URI,
    val headers: Map<String, String>,
    val bodyJson: String,
    val authentication: AuthenticationKind,
) {
    override fun toString() = "PreparedRequest(authentication=$authentication, payload=<redacted>)"
}

object ProtocolRequests {
    fun endpoint(apiRoot: String, protocol: Protocol): URI {
        require(apiRoot.length <= 4096) { "API root exceeds supported limits" }
        val root = try { URI(apiRoot) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid API root")
        }
        require(root.scheme.equals("https", ignoreCase = true) && !root.isOpaque && !root.host.isNullOrBlank()) {
            "API root requires HTTPS and a host"
        }
        require(root.rawUserInfo == null && root.rawQuery == null && root.rawFragment == null) {
            "API root cannot contain credentials, query or fragment"
        }
        require(root.port == -1 || root.port in 1..65535) { "Invalid API port" }
        val rawPath = root.rawPath.orEmpty()
        require(!rawPath.lowercase().contains("%2f") && !rawPath.lowercase().contains("%5c")) {
            "Encoded path separators are unsupported"
        }
        require(root.path.orEmpty().split('/').none { it == "." || it == ".." }) {
            "API root cannot contain traversal segments"
        }
        val suffix = when (protocol) {
            Protocol.CHAT_COMPLETIONS -> "chat/completions"
            Protocol.ANTHROPIC_MESSAGES -> "messages"
        }
        val prefix = root.toASCIIString().trimEnd('/')
        require(!prefix.endsWith("/chat/completions") && !prefix.endsWith("/messages") &&
            !prefix.endsWith("/responses")) { "Provide an API root, not an operation endpoint" }
        return URI("$prefix/$suffix")
    }

    fun prepare(apiRoot: String, protocol: Protocol, request: TextRequest): PreparedRequest {
        require(request.modelId.isNotBlank() && request.modelId.length <= 256 &&
            request.modelId.none { it.isISOControl() }) { "Invalid model ID" }
        require(request.messages.size in 1..256) { "Message count exceeds supported limits" }
        require(request.messages.all { it.text.isNotEmpty() }) { "Empty messages are unsupported" }
        require(isWellFormedUtf16(request.modelId) && request.messages.all { isWellFormedUtf16(it.text) } &&
            (request.system == null || isWellFormedUtf16(request.system))) { "Input must contain valid Unicode" }
        val chars = request.messages.sumOf { it.text.length.toLong() } + (request.system?.length ?: 0)
        require(chars <= 262_144) { "Input exceeds supported limits" }
        require(request.maxOutputTokens in 1..100_000) { "Invalid output token budget" }
        val messages = request.messages.map {
            JsonObject(mapOf("role" to JsonPrimitive(it.role.name.lowercase()), "content" to JsonPrimitive(it.text)))
        }.toMutableList()
        val body = linkedMapOf(
            "model" to JsonPrimitive(request.modelId),
            "stream" to JsonPrimitive(true),
        )
        val budgetField = if (protocol == Protocol.ANTHROPIC_MESSAGES) "max_tokens"
            else request.chatTokenBudget.name.lowercase()
        body[budgetField] = JsonPrimitive(request.maxOutputTokens)
        if (protocol == Protocol.CHAT_COMPLETIONS && request.system != null) {
            messages.add(0, JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(request.system))))
        }
        val fields = body.toMutableMap<String, kotlinx.serialization.json.JsonElement>()
        fields["messages"] = JsonArray(messages)
        if (protocol == Protocol.CHAT_COMPLETIONS && request.chatUsageSupported) {
            fields["stream_options"] = JsonObject(mapOf("include_usage" to JsonPrimitive(true)))
        }
        if (protocol == Protocol.ANTHROPIC_MESSAGES && request.system != null) {
            fields["system"] = JsonPrimitive(request.system)
        }
        val headers = linkedMapOf("Content-Type" to "application/json", "Accept" to "text/event-stream")
        if (protocol == Protocol.ANTHROPIC_MESSAGES) headers["anthropic-version"] = "2023-06-01"
        return PreparedRequest(
            endpoint(apiRoot, protocol), headers.toMap(), JsonObject(fields).toString(),
            if (protocol == Protocol.CHAT_COMPLETIONS) AuthenticationKind.BEARER else AuthenticationKind.ANTHROPIC_API_KEY,
        )
    }
}
