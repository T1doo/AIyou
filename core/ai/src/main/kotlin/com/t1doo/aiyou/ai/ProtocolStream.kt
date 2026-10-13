package com.t1doo.aiyou.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Text-only wire adapter. Serialized calls; no transport, storage, logging or automatic replay. */
class ProtocolStream(val protocol: Protocol, private val limits: StreamLimits = StreamLimits()) {
    private val decoder = SseDecoder(limits)
    @Volatile var isTerminal = false
        private set
    @Volatile internal var terminalEvent: StreamEvent? = null
        private set
    private var started = false
    private var textChars = 0L
    private var inputTokens: Long? = null
    private var outputTokens: Long? = null
    private var chatStop = false
    private var anthropicStop = false
    private var activeBlock: Int? = null
    private var nextBlock = 0

    @Synchronized fun push(bytes: ByteArray): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        pushTo(bytes, events::add)
        return events
    }

    /** Deliver a validated frame before consuming the next frame in the same network read. */
    @Synchronized internal fun pushTo(bytes: ByteArray, emit: (StreamEvent) -> Unit) {
        if (isTerminal) return
        val events = mutableListOf<StreamEvent>()
        fun dispatchFrame() {
            val terminalBeforeDelivery = terminalEvent
            for (event in events) {
                // A callback can cancel/fail the stream; do not deliver the rest of that frame.
                if (terminalBeforeDelivery == null && isTerminal) break
                emit(event)
            }
            events.clear()
        }
        try {
            var offset = 0
            while (offset < bytes.size && !isTerminal) {
                val length = minOf(limits.maxChunkBytes, bytes.size - offset)
                decoder.consume(bytes, offset, length) { frame ->
                    if (!(frame.data.isEmpty() && frame.event == "message")) {
                        when (protocol) {
                            Protocol.CHAT_COMPLETIONS -> chat(frame, events)
                            Protocol.ANTHROPIC_MESSAGES -> anthropic(frame, events)
                        }
                    }
                    dispatchFrame()
                    !isTerminal
                }
                offset += length
            }
        } catch (failure: WireFailure) {
            fail(failure.kind, events)
        } catch (_: Exception) {
            // Never retain parser exceptions: their messages can contain provider/user text.
            fail(FailureKind.MALFORMED_RESPONSE, events)
        }
        dispatchFrame()
    }

    @Synchronized fun finish(): List<StreamEvent> {
        if (isTerminal) return emptyList()
        val events = mutableListOf<StreamEvent>()
        try {
            decoder.finish()
            fail(FailureKind.TRUNCATED, events)
        } catch (failure: WireFailure) {
            fail(failure.kind, events)
        } catch (_: Exception) {
            fail(FailureKind.MALFORMED_RESPONSE, events)
        }
        return events
    }

    @Synchronized fun cancel(): List<StreamEvent> {
        if (isTerminal) return emptyList()
        terminalEvent = StreamEvent.Cancelled
        isTerminal = true
        decoder.close()
        return listOf(StreamEvent.Cancelled)
    }

    @Synchronized internal fun transportFailure(kind: FailureKind): List<StreamEvent> {
        if (isTerminal) return emptyList()
        val events = mutableListOf<StreamEvent>()
        fail(kind, events)
        return events
    }

    private fun chat(frame: SseFrame, events: MutableList<StreamEvent>) {
        if (frame.event == "ping") return
        if (frame.event != "message" && frame.event != "error") return
        if (frame.event == "message" && frame.data.trim() == "[DONE]") {
            demand(started && chatStop)
            complete(events)
            return
        }
        val root = jsonObject(frame.data)
        if (frame.event == "error" || root.present("error")) {
            fail(providerFailure(root.obj("error")), events)
            return
        }
        val choices = root["choices"] as? JsonArray ?: malformed()
        demand(choices.size <= 1)
        if (choices.isEmpty()) {
            demand(started && root.present("usage"))
        } else {
            demand(!chatStop)
            val choice = choices.single() as? JsonObject ?: malformed()
            demand(choice.number("index") == 0L)
            val delta = choice.obj("delta")
            delta.string("role")?.let { demand(it == "assistant") }
            if (delta.present("tool_calls") || delta.present("function_call") || delta.present("audio")) {
                throw WireFailure(FailureKind.UNSUPPORTED_CONTENT)
            }
            if (!delta.string("refusal").isNullOrEmpty()) throw WireFailure(FailureKind.REFUSED)
            start(events)
            delta.string("content")?.let { text(it, events) }
            choice.string("finish_reason")?.let {
                when (it) {
                    "stop" -> chatStop = true
                    "length" -> throw WireFailure(FailureKind.TRUNCATED)
                    "content_filter" -> throw WireFailure(FailureKind.REFUSED)
                    "tool_calls", "function_call" -> throw WireFailure(FailureKind.UNSUPPORTED_CONTENT)
                    else -> malformed()
                }
            }
        }
        if (root.present("usage")) usage(root.obj("usage"), false, events)
    }

    private fun anthropic(frame: SseFrame, events: MutableList<StreamEvent>) {
        if (frame.event == "ping") return
        val known = setOf("message_start", "content_block_start", "content_block_delta",
            "content_block_stop", "message_delta", "message_stop", "error")
        if (frame.event !in known) return
        val root = jsonObject(frame.data)
        demand(root.string("type") == frame.event)
        when (frame.event) {
            "error" -> fail(providerFailure(root.obj("error")), events)
            "message_start" -> {
                demand(!started)
                val message = root.obj("message")
                demand(message.string("role") == "assistant")
                demand((message["content"] as? JsonArray)?.isEmpty() == true)
                demand(!message.present("stop_reason"))
                start(events)
                if (message.present("usage")) usage(message.obj("usage"), true, events)
            }
            "content_block_start" -> {
                demand(started && !anthropicStop && activeBlock == null)
                val index = blockIndex(root)
                demand(index == nextBlock)
                val block = root.obj("content_block")
                if (block.string("type") != "text") throw WireFailure(FailureKind.UNSUPPORTED_CONTENT)
                activeBlock = index
                text(block.string("text") ?: malformed(), events)
            }
            "content_block_delta" -> {
                demand(started && !anthropicStop && activeBlock == blockIndex(root))
                val delta = root.obj("delta")
                if (delta.string("type") != "text_delta") throw WireFailure(FailureKind.UNSUPPORTED_CONTENT)
                text(delta.string("text") ?: malformed(), events)
            }
            "content_block_stop" -> {
                demand(started && !anthropicStop && activeBlock == blockIndex(root))
                activeBlock = null
                nextBlock++
            }
            "message_delta" -> {
                demand(started && activeBlock == null)
                val delta = root.obj("delta")
                val reason = delta.string("stop_reason")
                if (anthropicStop) demand(reason == null && root.present("usage"))
                reason?.let {
                    when (it) {
                        "end_turn", "stop_sequence" -> anthropicStop = true
                        "max_tokens", "model_context_window_exceeded", "pause_turn" ->
                            throw WireFailure(FailureKind.TRUNCATED)
                        "refusal" -> throw WireFailure(FailureKind.REFUSED)
                        "tool_use" -> throw WireFailure(FailureKind.UNSUPPORTED_CONTENT)
                        else -> malformed()
                    }
                }
                if (root.present("usage")) usage(root.obj("usage"), true, events)
            }
            "message_stop" -> {
                demand(started && activeBlock == null && anthropicStop)
                complete(events)
            }
        }
    }

    private fun start(events: MutableList<StreamEvent>) {
        if (!started) { started = true; events.add(StreamEvent.Started) }
    }

    private fun text(value: String, events: MutableList<StreamEvent>) {
        if (value.isEmpty()) return
        demand(isWellFormedUtf16(value))
        if (value.length.toLong() > limits.maxTextChars - textChars) {
            throw WireFailure(FailureKind.LIMIT_EXCEEDED)
        }
        textChars += value.length
        events.add(StreamEvent.TextDelta(value))
    }

    private fun usage(root: JsonObject, anthropic: Boolean, events: MutableList<StreamEvent>) {
        val incoming = root.number(if (anthropic) "input_tokens" else "prompt_tokens")
        val outgoing = root.number(if (anthropic) "output_tokens" else "completion_tokens")
        demand(incoming != null || outgoing != null)
        incoming?.let { demand(it >= 0 && (inputTokens == null || it >= inputTokens!!)); inputTokens = it }
        outgoing?.let { demand(it >= 0 && (outputTokens == null || it >= outputTokens!!)); outputTokens = it }
        events.add(StreamEvent.Usage(inputTokens, outputTokens))
    }

    private fun complete(events: MutableList<StreamEvent>) {
        terminalEvent = StreamEvent.Completed
        isTerminal = true
        decoder.close()
        events.add(StreamEvent.Completed)
    }

    private fun fail(kind: FailureKind, events: MutableList<StreamEvent>) {
        if (!isTerminal) {
            terminalEvent = StreamEvent.Failed(kind)
            isTerminal = true
            decoder.close()
            events.add(StreamEvent.Failed(kind))
        }
    }

    private fun providerFailure(error: JsonObject): FailureKind = when (error.string("type")) {
        "authentication_error", "permission_error" -> FailureKind.AUTH_REQUIRED
        "rate_limit_error" -> FailureKind.RATE_LIMITED
        "overloaded_error", "api_error", "server_error" -> FailureKind.TEMPORARY
        "invalid_request_error", "not_found_error" -> FailureKind.CONFIGURATION
        else -> FailureKind.PROVIDER_ERROR
    }

    private fun blockIndex(root: JsonObject): Int {
        val index = root.number("index") ?: malformed()
        demand(index in 0..4095)
        return index.toInt()
    }

    private fun jsonObject(value: String): JsonObject {
        // Bound nesting before the library parser so adversarial JSON cannot overflow its stack.
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in value) {
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
                '}', ']' -> depth--
            }
        }
        return Json.parseToJsonElement(value) as? JsonObject ?: malformed()
    }

    private fun JsonObject.present(key: String): Boolean = this[key]?.let { it != JsonNull } == true
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: malformed()
    private fun JsonObject.string(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: malformed()
        demand(primitive.isString)
        return primitive.content
    }
    private fun JsonObject.number(key: String): Long? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: malformed()
        demand(!primitive.isString)
        return primitive.longOrNull ?: malformed()
    }
    private fun demand(valid: Boolean) { if (!valid) malformed() }
    private fun malformed(): Nothing = throw WireFailure(FailureKind.MALFORMED_RESPONSE)
}
