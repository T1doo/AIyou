package com.t1doo.aiyou.ai

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class SseFrame(val event: String, val data: String) {
    override fun toString() = "SseFrame(event=<redacted>, data=<redacted>)"
}

internal class WireFailure(val kind: FailureKind) : RuntimeException(kind.name)

/** Strict incremental UTF-8 plus SSE framing. EOF discards undispatched data. */
class SseDecoder(private val limits: StreamLimits = StreamLimits()) {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    // At most one unfinished UTF-8 code point. Never decode ahead of dispatch.
    private val input = ByteBuffer.allocate(4)
    private val output = CharBuffer.allocate(2)
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var event = ""
    private var hasData = false
    private var eventChars = 0L
    private var frames = 0
    private var totalBytes = 0L
    private var first = true
    private var afterCr = false
    private var ended = false

    fun push(bytes: ByteArray): List<SseFrame> {
        check(!ended) { "SSE decoder is closed" }
        if (bytes.size > limits.maxChunkBytes) {
            close()
            throw WireFailure(FailureKind.LIMIT_EXCEEDED)
        }
        val frames = mutableListOf<SseFrame>()
        consume(bytes, 0, bytes.size) { frames.add(it); true }
        return frames
    }

    fun finish() {
        check(!ended) { "SSE decoder is closed" }
        try {
            input.flip()
            output.clear()
            val status = decoder.decode(input, output, true)
            if (status.isError) throw WireFailure(FailureKind.INVALID_UTF8)
            check(status.isUnderflow && !input.hasRemaining() && output.position() == 0) {
                "Unexpected UTF-8 finalization state"
            }
            val flushed = decoder.flush(output)
            check(flushed.isUnderflow && output.position() == 0) { "Unexpected UTF-8 flush state" }
        } finally { close() }
    }

    fun close() {
        ended = true
        input.clear()
        output.clear()
        line.setLength(0)
        data.setLength(0)
        event = ""
    }

    /** Visit frames in wire order; false stops exactly at the dispatched frame's delimiter. */
    internal fun consume(bytes: ByteArray, offset: Int, length: Int, emit: (SseFrame) -> Boolean) {
        check(!ended) { "SSE decoder is closed" }
        require(offset >= 0 && length in 0..limits.maxChunkBytes && offset <= bytes.size - length) {
            "Invalid SSE input window"
        }
        try {
            for (index in offset until offset + length) {
                if (totalBytes >= limits.maxTotalBytes) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
                totalBytes++
                input.put(bytes[index])
                input.flip()
                output.clear()
                val status = decoder.decode(input, output, false)
                if (status.isError) throw WireFailure(FailureKind.INVALID_UTF8)
                check(status.isUnderflow) { "Unexpected UTF-8 decoding state" }
                input.compact()
                output.flip()
                while (output.hasRemaining()) {
                    if (!accept(output.get(), emit) || ended) {
                        close()
                        return
                    }
                }
            }
        } catch (failure: WireFailure) {
            close()
            throw failure
        }
    }

    private fun accept(char: Char, emit: (SseFrame) -> Boolean): Boolean {
        if (first) {
            first = false
            if (char == '\uFEFF') return true
        }
        if (afterCr) {
            afterCr = false
            if (char == '\n') return true
        }
        when (char) {
            '\r' -> {
                if (!dispatchLine(emit)) return false
                afterCr = true
            }
            '\n' -> if (!dispatchLine(emit)) return false
            else -> {
                if (line.length >= limits.maxLineChars) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
                line.append(char)
            }
        }
        return true
    }

    private fun dispatchLine(emit: (SseFrame) -> Boolean): Boolean {
        val value = line.toString()
        line.setLength(0)
        if (value.isEmpty()) {
            var frame: SseFrame? = null
            if (hasData) {
                if (frames >= limits.maxFrames) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
                frames++
                frame = SseFrame(event.ifEmpty { "message" }, data.toString())
            }
            data.setLength(0)
            event = ""
            hasData = false
            eventChars = 0
            return frame?.let(emit) ?: true
        }
        if (value.startsWith(':')) return true
        val split = value.indexOf(':')
        val field = if (split == -1) value else value.substring(0, split)
        val content = if (split == -1) "" else value.substring(split + 1).removePrefix(" ")
        if (field != "data" && field != "event") return true
        eventChars += content.length.toLong() + 1
        if (eventChars > limits.maxEventChars) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
        if (field == "event") event = content else {
            if (hasData) data.append('\n')
            data.append(content)
            hasData = true
        }
        return true
    }
}
