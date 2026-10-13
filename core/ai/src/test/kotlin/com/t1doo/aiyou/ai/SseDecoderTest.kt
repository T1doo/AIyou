package com.t1doo.aiyou.ai

import org.junit.Assert.*
import org.junit.Test

class SseDecoderTest {
    @Test fun decodesEveryUtf8ByteSplitIncludingSupplementaryCharacters() {
        val bytes = "\uFEFFdata: 中文🙂é\r\n\r\ndata: 尾\n\n".toByteArray(Charsets.UTF_8)
        for (split in 0..bytes.size) {
            val decoder = SseDecoder()
            val frames = decoder.push(bytes.copyOfRange(0, split)) + decoder.push(bytes.copyOfRange(split, bytes.size))
            assertEquals("split=$split", listOf("message" to "中文🙂é", "message" to "尾"), values(frames))
            decoder.finish()
        }
    }

    @Test fun supportsOneByteChunksAndCrLfCrLfLineEndings() {
        val decoder = SseDecoder()
        val frames = "event: text\rdata: 一\r\ndata: 二\n\r\n".toByteArray().flatMap { decoder.push(byteArrayOf(it)) }
        assertEquals(listOf("text" to "一\n二"), values(frames))
        decoder.finish()
    }

    @Test fun combinesMultilineDataAndIgnoresCommentsAndUnknownFields() {
        val frames = SseDecoder().push(": keepalive\nid: ignored\nretry: 12\nevent: text\ndata: first\ndata: second\nunknown: value\n\n".toByteArray())
        assertEquals(listOf("text" to "first\nsecond"), values(frames))
    }

    @Test fun stripsOneOptionalSpaceAndPreservesOtherWhitespace() {
        val frames = SseDecoder().push("data:  leading \ndata:\tindented\n\n".toByteArray())
        assertEquals(listOf("message" to " leading \n\tindented"), values(frames))
    }

    @Test fun dispatchesEmptyDataButNotEmptyOrEventOnlyFrames() {
        val frames = SseDecoder().push("\n\nevent: unused\n\ndata\n\nevent:\ndata:\n\n".toByteArray())
        assertEquals(listOf("message" to "", "message" to ""), values(frames))
    }

    @Test fun lastEventFieldWinsAndEventTypeResetsAfterDispatch() {
        val frames = SseDecoder().push("event: old\nevent: current\ndata: a\n\ndata: b\n\n".toByteArray())
        assertEquals(listOf("current" to "a", "message" to "b"), values(frames))
    }

    @Test fun onlyInitialBomIsIgnored() {
        val frames = SseDecoder().push("\uFEFFdata: a\n\ndata: \uFEFFb\n\n".toByteArray())
        assertEquals(listOf("message" to "a", "message" to "\uFEFFb"), values(frames))
    }

    @Test fun eofDoesNotDispatchUnterminatedOrNonBlankTerminatedEvent() {
        listOf("data: unfinished", "data: unfinished\n", "event: message\ndata: unfinished\r").forEach { input ->
            val decoder = SseDecoder()
            assertTrue(decoder.push(input.toByteArray()).isEmpty())
            decoder.finish()
        }
    }

    @Test fun rejectsMalformedUtf8WithoutReplacementCharacters() {
        listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0x80.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
            .forEach { bytes -> assertWireFailure(FailureKind.INVALID_UTF8) { SseDecoder().push(bytes) } }
    }

    @Test fun incompleteUtf8IsAllowedBetweenReadsAndRejectedAtEof() {
        val decoder = SseDecoder()
        assertTrue(decoder.push(byteArrayOf(0xF0.toByte(), 0x9F.toByte())).isEmpty())
        assertWireFailure(FailureKind.INVALID_UTF8) { decoder.finish() }
        assertThrows(IllegalStateException::class.java) { decoder.push(byteArrayOf()) }
    }

    @Test fun enforcesChunkLimitAtBoundary() {
        val bytes = "data:x\n\n".toByteArray()
        assertEquals(1, SseDecoder(StreamLimits(maxChunkBytes = bytes.size)).push(bytes).size)
        assertWireFailure(FailureKind.LIMIT_EXCEEDED) { SseDecoder(StreamLimits(maxChunkBytes = bytes.size - 1)).push(bytes) }
    }

    @Test fun enforcesCumulativeByteLimitAcrossReads() {
        val decoder = SseDecoder(StreamLimits(maxTotalBytes = 8))
        assertTrue(decoder.push("data".toByteArray()).isEmpty())
        assertEquals(1, decoder.push(":x\n\n".toByteArray()).size)
        assertWireFailure(FailureKind.LIMIT_EXCEEDED) { decoder.push(byteArrayOf(10)) }
        assertThrows(IllegalStateException::class.java) { decoder.push(byteArrayOf()) }
    }

    @Test fun enforcesLineLimitAcrossChunkBoundaries() {
        val decoder = SseDecoder(StreamLimits(maxLineChars = 6))
        decoder.push("dat".toByteArray())
        assertEquals(1, decoder.push("a:x\n\n".toByteArray()).size)
        val limited = SseDecoder(StreamLimits(maxLineChars = 5))
        limited.push("dat".toByteArray())
        assertWireFailure(FailureKind.LIMIT_EXCEEDED) { limited.push("a:x".toByteArray()) }
    }

    @Test fun boundsWholeEventAndResetsLimitAfterDispatch() {
        assertEquals(2, SseDecoder(StreamLimits(maxEventChars = 4)).push("data:x\ndata:y\n\ndata:z\n\n".toByteArray()).size)
        assertWireFailure(FailureKind.LIMIT_EXCEEDED) {
            SseDecoder(StreamLimits(maxEventChars = 3)).push("data:x\ndata:y\n\n".toByteArray())
        }
    }

    @Test fun includesEventFieldsInEventLimit() {
        assertWireFailure(FailureKind.LIMIT_EXCEEDED) { SseDecoder(StreamLimits(maxEventChars = 3)).push("event: xx\ndata:y\n\n".toByteArray()) }
    }

    @Test fun enforcesFrameLimitEvenForEmptyDataEvents() {
        val decoder = SseDecoder(StreamLimits(maxFrames = 2))
        assertEquals(2, decoder.push("data:\n\ndata:\n\n".toByteArray()).size)
        assertWireFailure(FailureKind.LIMIT_EXCEEDED) { decoder.push("data:\n\n".toByteArray()) }
    }

    @Test fun closeDiscardsPartialStateAndRejectsFurtherInput() {
        val decoder = SseDecoder()
        decoder.push("data: partial".toByteArray())
        decoder.close()
        decoder.close()
        assertThrows(IllegalStateException::class.java) { decoder.push("\n\n".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { decoder.finish() }
    }

    @Test fun callbackCloseStopsConsumptionEvenWhenCallbackReturnsContinue() {
        listOf("\n", "\r", "\r\n").forEach { ending ->
            val decoder = SseDecoder()
            val bytes = ("data: first$ending$ending" + "data: second$ending$ending").toByteArray() + byteArrayOf(0xFF.toByte())
            val frames = mutableListOf<SseFrame>()
            decoder.consume(bytes, 0, bytes.size) { frame ->
                frames.add(frame)
                decoder.close()
                true
            }
            assertEquals(listOf("message" to "first"), values(frames))
            assertThrows(IllegalStateException::class.java) { decoder.push("data: later\n\n".toByteArray()) }
            assertThrows(IllegalStateException::class.java) { decoder.finish() }
        }
    }

    @Test fun diagnosticsDoNotExposeFrameContent() {
        val marker = "synthetic-private-marker"
        val frame = SseFrame(marker, marker)
        assertFalse(frame.toString().contains(marker))
        val error = assertThrows(WireFailure::class.java) { SseDecoder(StreamLimits(maxLineChars = 1)).push(marker.toByteArray()) }
        assertFalse(error.toString().contains(marker))
    }

    @Test fun rejectsNonPositiveLimits() {
        listOf({ StreamLimits(maxChunkBytes = 0) }, { StreamLimits(maxLineChars = 0) }, { StreamLimits(maxEventChars = -1) },
            { StreamLimits(maxFrames = 0) }, { StreamLimits(maxTotalBytes = 0) }, { StreamLimits(maxTextChars = 0) })
            .forEach { constructor -> assertThrows(IllegalArgumentException::class.java) { constructor() } }
    }

    private fun values(frames: List<SseFrame>) = frames.map { it.event to it.data }
    private fun assertWireFailure(kind: FailureKind, block: () -> Unit) {
        assertEquals(kind, assertThrows(WireFailure::class.java) { block() }.kind)
    }
}
