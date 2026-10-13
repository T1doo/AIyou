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
    private var pending = byteArrayOf()
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
        if (bytes.size > limits.maxChunkBytes || bytes.size.toLong() > limits.maxTotalBytes - totalBytes) {
            close()
            throw WireFailure(FailureKind.LIMIT_EXCEEDED)
        }
        totalBytes += bytes.size
        return try { decode(bytes, false) } catch (failure: WireFailure) {
            close()
            throw failure
        }
    }

    fun finish() {
        check(!ended) { "SSE decoder is closed" }
        try { decode(byteArrayOf(), true) } finally { close() }
    }

    fun close() {
        ended = true
        pending = byteArrayOf()
        line.setLength(0)
        data.setLength(0)
        event = ""
    }

    private fun decode(bytes: ByteArray, final: Boolean): List<SseFrame> {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(4096)
        val result = mutableListOf<SseFrame>()
        do {
            val status = decoder.decode(input, output, final)
            if (status.isError) throw WireFailure(FailureKind.INVALID_UTF8)
            output.flip()
            while (output.hasRemaining()) accept(output.get(), result)
            output.clear()
            if (!status.isOverflow) break
        } while (true)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (final) {
            if (pending.isNotEmpty()) throw WireFailure(FailureKind.INVALID_UTF8)
            val status = decoder.flush(output)
            if (status.isError) throw WireFailure(FailureKind.INVALID_UTF8)
        }
        return result
    }

    private fun accept(char: Char, output: MutableList<SseFrame>) {
        if (first) {
            first = false
            if (char == '\uFEFF') return
        }
        if (afterCr) {
            afterCr = false
            if (char == '\n') return
        }
        when (char) {
            '\r' -> { dispatchLine(output); afterCr = true }
            '\n' -> dispatchLine(output)
            else -> {
                if (line.length >= limits.maxLineChars) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
                line.append(char)
            }
        }
    }

    private fun dispatchLine(output: MutableList<SseFrame>) {
        val value = line.toString()
        line.setLength(0)
        if (value.isEmpty()) {
            if (hasData) {
                if (++frames > limits.maxFrames) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
                output.add(SseFrame(event.ifEmpty { "message" }, data.toString()))
            }
            data.setLength(0)
            event = ""
            hasData = false
            eventChars = 0
            return
        }
        if (value.startsWith(':')) return
        val split = value.indexOf(':')
        val field = if (split == -1) value else value.substring(0, split)
        val content = if (split == -1) "" else value.substring(split + 1).removePrefix(" ")
        if (field != "data" && field != "event") return
        eventChars += content.length.toLong() + 1
        if (eventChars > limits.maxEventChars) throw WireFailure(FailureKind.LIMIT_EXCEEDED)
        if (field == "event") event = content else {
            if (hasData) data.append('\n')
            data.append(content)
            hasData = true
        }
    }
}
