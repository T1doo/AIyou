package com.t1doo.aiyou.ai

import org.junit.Assert.*
import org.junit.Test

/** A protocol's formal terminal delimiter ends the response prefix, regardless of read boundaries. */
class PartitionInvariantTest {
    private val chatPrefix = (
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"合成🙂\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\n" +
            "data: [DONE]\n\n"
        ).toByteArray(Charsets.UTF_8)
    private val anthropicPrefix = (
        "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"合成🙂\"}}\n\n" +
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        ).toByteArray(Charsets.UTF_8)
    private val limits = StreamLimits(maxLineChars = 512)
    private val invalidUtf8 = byteArrayOf(0xFF.toByte())
    private val oversizedLine = "x".repeat(513).toByteArray()

    @Test fun chatTerminalThenInvalidUtf8HasSameCompleteEventsForSingleAndSplitRead() {
        assertTerminalSuffixIgnored(Protocol.CHAT_COMPLETIONS, invalidUtf8)
    }

    @Test fun anthropicTerminalThenInvalidUtf8HasSameCompleteEventsForSingleAndSplitRead() {
        assertTerminalSuffixIgnored(Protocol.ANTHROPIC_MESSAGES, invalidUtf8)
    }

    @Test fun chatTerminalThenLongLineHasSameCompleteEventsForSingleAndSplitRead() {
        assertTerminalSuffixIgnored(Protocol.CHAT_COMPLETIONS, oversizedLine)
    }

    @Test fun anthropicTerminalThenLongLineHasSameCompleteEventsForSingleAndSplitRead() {
        assertTerminalSuffixIgnored(Protocol.ANTHROPIC_MESSAGES, oversizedLine)
    }

    @Test fun terminalThenLaterValidOrErrorEventsNeverAddsOrReclassifiesEvents() {
        Protocol.entries.forEach { protocol ->
            assertTerminalSuffixIgnored(protocol, prefix(protocol))
            assertTerminalSuffixIgnored(protocol, providerError(protocol))
            assertTerminalSuffixIgnored(protocol, "event: ping\ndata: {}\n\n".toByteArray())
        }
    }

    @Test fun terminalThenIncompleteUtf8RemainsCompletedEvenAtEof() {
        Protocol.entries.forEach { protocol ->
            assertTerminalSuffixIgnored(protocol, byteArrayOf(0xE4.toByte(), 0xB8.toByte()))
        }
    }

    @Test fun terminalSuffixDoesNotSpendTotalChunkOrFrameBudgets() {
        Protocol.entries.forEach { protocol ->
            val prefix = prefix(protocol)
            val bounds = StreamLimits(maxChunkBytes = 7, maxLineChars = 512,
                maxFrames = if (protocol == Protocol.CHAT_COMPLETIONS) 2 else 5,
                maxTotalBytes = prefix.size.toLong())
            val tail = ByteArray(2_048) { 0xFF.toByte() }
            assertTerminalSuffixIgnored(protocol, tail, bounds)
        }
    }

    @Test fun largeReadIsWindowedAndMatchesSmallReadsBeforeCompletion() {
        Protocol.entries.forEach { protocol ->
            val bytes = prefix(protocol)
            val bounds = StreamLimits(maxChunkBytes = 7, maxLineChars = 512)
            val expected = observe(protocol, byteChunks(bytes, 7), bounds)
            assertEquals("Completed", expected.events.last())
            assertEquals(expected, observe(protocol, listOf(bytes), bounds))
            assertEquals(expected, observe(protocol, byteChunks(bytes, 1), bounds))
        }
    }

    @Test fun malformedUtf8BeforeTerminalPreservesDispatchedPrefixAndFailsForEveryPartition() {
        Protocol.entries.forEach { protocol -> assertFailureAfterPreview(protocol, invalidUtf8, FailureKind.INVALID_UTF8) }
    }

    @Test fun longLineBeforeTerminalPreservesDispatchedPrefixAndFailsForEveryPartition() {
        Protocol.entries.forEach { protocol -> assertFailureAfterPreview(protocol, oversizedLine, FailureKind.LIMIT_EXCEEDED) }
    }

    @Test fun consumedTotalBytesRemainStrictBeforeTerminalForLargeAndSmallReads() {
        Protocol.entries.forEach { protocol ->
            val preview = previewPrefix(protocol)
            val bounds = StreamLimits(maxChunkBytes = 7, maxLineChars = 512, maxTotalBytes = preview.size.toLong())
            val expected = expectedFailureAfterPreview(protocol, FailureKind.LIMIT_EXCEEDED)
            val wire = preview + terminalFrame(protocol)
            assertEquals(expected, observe(protocol, listOf(wire), bounds))
            assertEquals(expected, observe(protocol, listOf(preview, terminalFrame(protocol)), bounds))
            assertEquals(expected, observe(protocol, byteChunks(wire, 1), bounds))
        }
    }

    @Test fun unterminatedCompletionFrameAtEofRemainsTruncatedForEveryPartition() {
        Protocol.entries.forEach { protocol ->
            val preview = previewPrefix(protocol)
            val missingBlankLine = terminalFrame(protocol).dropLast(1).toByteArray()
            val expected = expectedFailureAfterPreview(protocol, FailureKind.TRUNCATED)
            val wire = preview + missingBlankLine
            assertEquals(expected, observe(protocol, listOf(wire)))
            assertEquals(expected, observe(protocol, listOf(preview, missingBlankLine)))
            assertEquals(expected, observe(protocol, byteChunks(wire, 1)))
        }
    }

    @Test fun incompleteUtf8BeforeCompletionFailsAtEofAndPreservesPreview() {
        Protocol.entries.forEach { protocol ->
            val preview = previewPrefix(protocol)
            val partial = byteArrayOf(0xE4.toByte(), 0xB8.toByte())
            val expected = expectedFailureAfterPreview(protocol, FailureKind.INVALID_UTF8)
            assertEquals(expected, observe(protocol, listOf(preview + partial)))
            assertEquals(expected, observe(protocol, listOf(preview, partial)))
            assertEquals(expected, observe(protocol, byteChunks(preview + partial, 1)))
        }
    }

    @Test fun bareCrTerminationEndsConsumptionBeforeMalformedSuffix() {
        Protocol.entries.forEach { protocol ->
            val crPrefix = prefix(protocol).toString(Charsets.UTF_8).replace("\n", "\r").toByteArray()
            val expected = observe(protocol, listOf(prefix(protocol)))
            val bounds = StreamLimits(maxChunkBytes = 7, maxLineChars = 512, maxTotalBytes = crPrefix.size.toLong())
            val wire = crPrefix + byteArrayOf(10, 0xFF.toByte())
            assertEquals(expected, observe(protocol, listOf(wire), bounds))
            assertEquals(expected, observe(protocol, listOf(crPrefix, byteArrayOf(10, 0xFF.toByte())), bounds))
            assertEquals(expected, observe(protocol, byteChunks(wire, 1), bounds))
        }
    }

    @Test fun crLfTerminationDoesNotChargeTheTrailingLfAfterTerminalCr() {
        Protocol.entries.forEach { protocol ->
            val crLfPrefix = prefix(protocol).toString(Charsets.UTF_8).replace("\n", "\r\n").toByteArray()
            val expected = observe(protocol, listOf(prefix(protocol)))
            val bounds = StreamLimits(maxChunkBytes = 7, maxLineChars = 512, maxTotalBytes = crLfPrefix.size.toLong() - 1)
            val wire = crLfPrefix + invalidUtf8
            assertEquals(expected, observe(protocol, listOf(wire), bounds))
            assertEquals(expected, observe(protocol, byteChunks(wire, 1), bounds))
        }
    }

    @Test fun cancelAfterPreviewIgnoresAllLaterBytesAndLimits() {
        Protocol.entries.forEach { protocol ->
            val preview = previewPrefix(protocol)
            val stream = ProtocolStream(protocol, StreamLimits(maxChunkBytes = 7, maxLineChars = 512,
                maxTotalBytes = preview.size.toLong()))
            val events = stream.push(preview).map(::summarize) + stream.cancel().map(::summarize)
            assertEquals(expectedFailureAfterPreview(protocol, FailureKind.TRUNCATED).events.dropLast(1) + "Cancelled", events)
            assertTrue(stream.isTerminal)
            assertTrue(stream.push(ByteArray(2_048) { 0xFF.toByte() }).isEmpty())
            assertTrue(stream.push(prefix(protocol)).isEmpty())
            assertTrue(stream.finish().isEmpty())
            assertTrue(stream.cancel().isEmpty())
        }
    }

    @Test fun cancelBeforeInputSkipsDecodeAndBudgetValidation() {
        Protocol.entries.forEach { protocol ->
            val stream = ProtocolStream(protocol, StreamLimits(maxChunkBytes = 1, maxTotalBytes = 1))
            assertEquals(listOf(StreamEvent.Cancelled), stream.cancel())
            assertTrue(stream.push(ByteArray(2_048) { 0xFF.toByte() }).isEmpty())
            assertTrue(stream.finish().isEmpty())
            assertTrue(stream.isTerminal)
        }
    }

    @Test fun explicitProviderFailureIsTerminalBeforeLaterDecodeFaults() {
        Protocol.entries.forEach { protocol ->
            val preview = previewPrefix(protocol)
            val error = providerError(protocol)
            val expected = expectedFailureAfterPreview(protocol, FailureKind.AUTH_REQUIRED)
            val wire = preview + error + invalidUtf8 + oversizedLine
            assertEquals(expected, observe(protocol, listOf(wire)))
            assertEquals(expected, observe(protocol, listOf(preview, error, invalidUtf8 + oversizedLine)))
            assertEquals(expected, observe(protocol, byteChunks(wire, 1)))
        }
    }

    private fun assertTerminalSuffixIgnored(protocol: Protocol, suffix: ByteArray, bounds: StreamLimits = limits) {
        val prefix = prefix(protocol)
        val expected = observe(protocol, listOf(prefix), bounds)
        assertEquals("complete prefix terminal category", "Completed", expected.events.last())
        assertTrue(expected.terminal)
        val split = observe(protocol, listOf(prefix, suffix), bounds)
        assertEquals("terminal prefix then separate suffix", expected, split)
        val single = observe(protocol, listOf(prefix + suffix), bounds)
        assertEquals("same bytes in one read must produce the same complete typed events", expected, single)
        assertEquals(expected, observe(protocol, byteChunks(prefix + suffix, 1), bounds))
        val wire = prefix + suffix
        for (splitPoint in listOf(prefix.size - 1, prefix.size + 1).filter { it in 0..wire.size }) {
            assertEquals("boundary=$splitPoint", expected,
                observe(protocol, listOf(wire.copyOfRange(0, splitPoint), wire.copyOfRange(splitPoint, wire.size)), bounds))
        }
    }

    private fun assertFailureAfterPreview(protocol: Protocol, fault: ByteArray, kind: FailureKind) {
        val preview = previewPrefix(protocol)
        val rest = fault + terminalFrame(protocol)
        val expected = expectedFailureAfterPreview(protocol, kind)
        val wire = preview + rest
        assertEquals(expected, observe(protocol, listOf(wire)))
        assertEquals(expected, observe(protocol, listOf(preview, rest)))
        assertEquals(expected, observe(protocol, byteChunks(wire, 1)))
    }

    private fun expectedFailureAfterPreview(protocol: Protocol, kind: FailureKind): Observed {
        val preview = observe(protocol, listOf(previewPrefix(protocol)))
        assertEquals("Failed(TRUNCATED)", preview.events.last())
        assertTrue(preview.events.any { it == "TextDelta(合成🙂)" })
        assertTrue(preview.events.any { it == "Usage(2,1)" })
        return Observed(preview.events.dropLast(1) + "Failed($kind)", terminal = true)
    }

    private fun byteChunks(bytes: ByteArray, size: Int) = bytes.asList().chunked(size).map { it.toByteArray() }

    private fun terminalFrame(protocol: Protocol) = when (protocol) {
        Protocol.CHAT_COMPLETIONS -> "data: [DONE]\n\n"
        Protocol.ANTHROPIC_MESSAGES -> "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
    }.toByteArray()

    private fun previewPrefix(protocol: Protocol) = prefix(protocol).dropLast(terminalFrame(protocol).size).toByteArray()

    private fun providerError(protocol: Protocol) = when (protocol) {
        Protocol.CHAT_COMPLETIONS -> "event: error\ndata: {\"error\":{\"type\":\"authentication_error\",\"message\":\"synthetic\"}}\n\n"
        Protocol.ANTHROPIC_MESSAGES -> "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"synthetic\"}}\n\n"
    }.toByteArray()

    private fun prefix(protocol: Protocol) = when (protocol) {
        Protocol.CHAT_COMPLETIONS -> chatPrefix
        Protocol.ANTHROPIC_MESSAGES -> anthropicPrefix
    }

    private data class Observed(val events: List<String>, val terminal: Boolean)

    private fun observe(protocol: Protocol, chunks: List<ByteArray>, bounds: StreamLimits = limits): Observed {
        val stream = ProtocolStream(protocol, bounds)
        val events = chunks.flatMap { stream.push(it) } + stream.finish()
        return Observed(events.map(::summarize), stream.isTerminal)
    }

    private fun summarize(event: StreamEvent): String = when (event) {
        StreamEvent.Started -> "Started"
        is StreamEvent.TextDelta -> "TextDelta(${event.text})"
        is StreamEvent.Usage -> "Usage(${event.inputTokens},${event.outputTokens})"
        StreamEvent.Completed -> "Completed"
        is StreamEvent.Failed -> "Failed(${event.kind})"
        StreamEvent.Cancelled -> "Cancelled"
    }
}
