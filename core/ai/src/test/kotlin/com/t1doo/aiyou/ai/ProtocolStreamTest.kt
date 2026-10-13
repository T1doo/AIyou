package com.t1doo.aiyou.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire contracts: no HTTP client, endpoint call, credential or device. */
class ProtocolStreamTest {
    private fun chat(delta: String, reason: String = "null", index: Int = 0) =
        "data: {\"id\":\"synthetic\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":$index,\"delta\":$delta,\"finish_reason\":$reason}]}\n\n"

    private fun anthropic(event: String, fields: String = "") =
        "event: $event\ndata: {\"type\":\"$event\"$fields}\n\n"

    private val chatStart = chat("{\"role\":\"assistant\",\"content\":\"\"}")
    private val chatText = chat("{\"content\":\"中文🙂é\"}")
    private val chatStop = chat("{}", "\"stop\"")
    private val chatUsage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n"
    private val done = "data: [DONE]\n\n"
    private val messageStart = anthropic("message_start", ",\"message\":{\"role\":\"assistant\",\"content\":[],\"stop_reason\":null,\"usage\":{\"input_tokens\":7,\"output_tokens\":0}}")
    private val blockStart = anthropic("content_block_start", ",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")
    private val blockText = anthropic("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"中文🙂é\"}")
    private val blockStop = anthropic("content_block_stop", ",\"index\":0")
    private val messageDelta = anthropic("message_delta", ",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}")
    private val messageStop = anthropic("message_stop")
    private val chatSuccess = chatStart + chatText + chatStop + chatUsage + done
    private val anthropicSuccess = messageStart + blockStart + blockText + blockStop + messageDelta + messageStop

    @Test fun chatSuccessMapsTextUsageAndExplicitCompletion() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS)
        val events = stream.push(chatSuccess.toByteArray())
        assertSuccess(events, "中文🙂é")
        assertEquals(listOf(StreamEvent.Usage(7, 3)), events.filterIsInstance<StreamEvent.Usage>())
        assertTrue(stream.isTerminal)
        assertTrue(stream.finish().isEmpty())
    }

    @Test fun anthropicSuccessMapsTextAndCumulativeUsage() {
        val stream = ProtocolStream(Protocol.ANTHROPIC_MESSAGES)
        val events = stream.push(anthropicSuccess.toByteArray())
        assertSuccess(events, "中文🙂é")
        assertEquals(listOf(StreamEvent.Usage(7, 0), StreamEvent.Usage(7, 3)), events.filterIsInstance<StreamEvent.Usage>())
        assertTrue(stream.isTerminal)
    }

    @Test fun bothProtocolsWorkAtEveryUtf8ByteSplit() {
        listOf(Protocol.CHAT_COMPLETIONS to chatSuccess, Protocol.ANTHROPIC_MESSAGES to anthropicSuccess).forEach { (protocol, fixture) ->
            val bytes = fixture.toByteArray()
            for (split in 0..bytes.size) {
                val stream = ProtocolStream(protocol)
                val events = stream.push(bytes.copyOfRange(0, split)) + stream.push(bytes.copyOfRange(split, bytes.size))
                assertSuccess(events, "中文🙂é", "$protocol split=$split")
                assertTrue(stream.isTerminal)
            }
        }
    }

    @Test fun bothProtocolsWorkWithSingleByteChunksAndBomCrLf() {
        listOf(Protocol.CHAT_COMPLETIONS to chatSuccess, Protocol.ANTHROPIC_MESSAGES to anthropicSuccess).forEach { (protocol, fixture) ->
            val stream = ProtocolStream(protocol)
            val events = ("\uFEFF" + fixture.replace("\n", "\r\n")).toByteArray().flatMap { stream.push(byteArrayOf(it)) }
            assertSuccess(events, "中文🙂é")
        }
    }

    @Test fun chatMultilineJsonCommentsPingAndUnknownEventsDoNotPolluteOutput() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS)
        val ignored = ": heartbeat\n\nevent: ping\ndata: {}\n\nevent: future_extension\ndata: {\"synthetic\":true}\n\n"
        val multiline = "data: {\"choices\":[{\"index\":0,\n" +
            "data: \"delta\":{\"content\":\"合成\"},\"finish_reason\":null}]}\n\n"
        val events = stream.push((ignored + chatStart + multiline + chatStop + done).toByteArray())
        assertSuccess(events, "合成")
    }

    @Test fun anthropicIgnoresPingAndUnknownFutureEvents() {
        val ignored = ": heartbeat\n\nevent: ping\ndata: {\"type\":\"ping\"}\n\n" +
            "event: future_extension\ndata: {\"type\":\"future_extension\",\"synthetic\":true}\n\n"
        assertSuccess(ProtocolStream(Protocol.ANTHROPIC_MESSAGES).push((ignored + anthropicSuccess).toByteArray()), "中文🙂é")
    }

    @Test fun emptyDefaultDataFramesCanAppearBetweenValidProtocolEvents() {
        val empty = "data:\n\n"
        assertSuccess(ProtocolStream(Protocol.CHAT_COMPLETIONS).push((chatStart + empty + chatText + empty + chatStop + done).toByteArray()), "中文🙂é")
        assertSuccess(ProtocolStream(Protocol.ANTHROPIC_MESSAGES).push((messageStart + empty + blockStart + empty + blockText + blockStop + messageDelta + messageStop).toByteArray()), "中文🙂é")
    }

    @Test fun namedErrorCannotMasqueradeAsDoneOrEmptyHeartbeat() {
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chatStop + "event: error\ndata: [DONE]\n\n" + done, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + "event: error\ndata:\n\n" + chatStop + done, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + "event: error\ndata:\n\n" + messageDelta + messageStop, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun emptyTextDeltasDoNotCreatePreviewEvents() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS)
        val events = stream.push((chatStart + chat("{\"content\":\"\"}") + chatStop + done).toByteArray())
        assertSuccess(events, "")
        assertTrue(events.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
    }

    @Test fun chatRequiresDoneAfterFinishReason() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS)
        val events = stream.push((chatStart + chatText + chatStop).toByteArray())
        assertFalse(events.contains(StreamEvent.Completed))
        assertFalse(stream.isTerminal)
        assertFailure(stream.finish(), FailureKind.TRUNCATED)
    }

    @Test fun chatDoneWithoutStopCannotBecomeComplete() {
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chatText + done, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.CHAT_COMPLETIONS, done, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun doneWithoutBlankLineIsDiscardedAtEof() {
        listOf("data: [DONE]", "data: [DONE]\n").forEach { unterminated ->
            val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS)
            val events = stream.push((chatStart + chatStop + unterminated).toByteArray())
            assertFalse(events.contains(StreamEvent.Completed))
            assertFailure(stream.finish(), FailureKind.TRUNCATED)
        }
    }

    @Test fun bothProtocolsMarkEofWithPartialTextAsTruncated() {
        listOf(Protocol.CHAT_COMPLETIONS to (chatStart + chatText), Protocol.ANTHROPIC_MESSAGES to (messageStart + blockStart + blockText))
            .forEach { (protocol, fixture) ->
                val stream = ProtocolStream(protocol)
                val events = stream.push(fixture.toByteArray())
                assertEquals("中文🙂é", text(events))
                assertFalse(stream.isTerminal)
                assertFailure(stream.finish(), FailureKind.TRUNCATED)
                assertTrue(stream.isTerminal)
            }
    }

    @Test fun emptyConnectionEndsAsTruncatedRatherThanCompleted() {
        Protocol.entries.forEach { protocol -> assertFailure(ProtocolStream(protocol).finish(), FailureKind.TRUNCATED) }
    }

    @Test fun incompleteUtf8AtEofIsAnEncodingFailure() {
        Protocol.entries.forEach { protocol ->
            val stream = ProtocolStream(protocol)
            assertTrue(stream.push(byteArrayOf(0xE4.toByte(), 0xB8.toByte())).isEmpty())
            assertFailure(stream.finish(), FailureKind.INVALID_UTF8)
        }
    }

    @Test fun invalidUtf8IsTerminalAndDoesNotAppearAsPreview() {
        Protocol.entries.forEach { protocol ->
            val stream = ProtocolStream(protocol)
            assertFailure(stream.push(byteArrayOf(0xC0.toByte(), 0xAF.toByte())), FailureKind.INVALID_UTF8)
            assertTerminalIsInert(stream)
        }
    }

    @Test fun malformedJsonAndWrongRootTypesFailWithoutCompletion() {
        listOf("{not-json}", "[]", "null", "\"synthetic\"").forEach { json ->
            assertFailedWire(Protocol.CHAT_COMPLETIONS, "data: $json\n\n", FailureKind.MALFORMED_RESPONSE)
            assertFailedWire(Protocol.ANTHROPIC_MESSAGES, "event: message_start\ndata: $json\n\n", FailureKind.MALFORMED_RESPONSE)
        }
    }

    @Test fun chatTruncatedRefusedAndToolFinishReasonsRemainFailures() {
        mapOf("length" to FailureKind.TRUNCATED, "content_filter" to FailureKind.REFUSED,
            "tool_calls" to FailureKind.UNSUPPORTED_CONTENT, "function_call" to FailureKind.UNSUPPORTED_CONTENT,
            "synthetic-future-reason" to FailureKind.MALFORMED_RESPONSE).forEach { (reason, kind) ->
            assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chat("{}", "\"$reason\"") + done, kind)
        }
    }

    @Test fun chatToolOrRefusalDeltasCannotBecomeSuccessfulText() {
        listOf("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\"}}]}", "{\"function_call\":{\"arguments\":\"{\"}}")
            .forEach { delta -> assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chat(delta), FailureKind.UNSUPPORTED_CONTENT) }
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chat("{\"refusal\":\"synthetic refusal\"}"), FailureKind.REFUSED)
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chat("{\"audio\":{\"data\":\"synthetic\"}}"), FailureKind.UNSUPPORTED_CONTENT)
    }

    @Test fun chatRejectsMultipleChoicesWrongIndexAndRole() {
        assertFailedWire(Protocol.CHAT_COMPLETIONS,
            "data: {\"choices\":[{\"index\":0,\"delta\":{}},{\"index\":1,\"delta\":{}}]}\n\n", FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chat("{}", index = 1), FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chat("{\"role\":\"user\"}"), FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun chatRejectsTextAfterStopAndUsageBeforeStart() {
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chatStop + chatText, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatUsage, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun anthropicRequiresStopReasonAndClosedContentBlock() {
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + messageStop, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + blockStart + blockText + messageStop, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + blockStart + messageDelta, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun anthropicRejectsUnterminatedMessageStopAtEof() {
        val stream = ProtocolStream(Protocol.ANTHROPIC_MESSAGES)
        val events = stream.push((messageStart + blockStart + blockStop + messageDelta + messageStop.trimEnd('\n')).toByteArray())
        assertFalse(events.contains(StreamEvent.Completed))
        assertFailure(stream.finish(), FailureKind.TRUNCATED)
    }

    @Test fun anthropicTruncatedRefusedAndToolStopReasonsRemainFailures() {
        mapOf("max_tokens" to FailureKind.TRUNCATED, "model_context_window_exceeded" to FailureKind.TRUNCATED,
            "pause_turn" to FailureKind.TRUNCATED, "refusal" to FailureKind.REFUSED,
            "tool_use" to FailureKind.UNSUPPORTED_CONTENT, "synthetic-future-reason" to FailureKind.MALFORMED_RESPONSE)
            .forEach { (reason, kind) ->
                val delta = anthropic("message_delta", ",\"delta\":{\"stop_reason\":\"$reason\"}")
                assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + delta + messageStop, kind)
            }
    }

    @Test fun anthropicTextOnlyContractRejectsToolThinkingAndJsonDeltaBlocks() {
        listOf("tool_use", "thinking", "server_tool_use").forEach { type ->
            val start = anthropic("content_block_start", ",\"index\":0,\"content_block\":{\"type\":\"$type\",\"text\":\"synthetic\"}")
            assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + start, FailureKind.UNSUPPORTED_CONTENT)
        }
        val toolDelta = anthropic("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"}")
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + blockStart + toolDelta, FailureKind.UNSUPPORTED_CONTENT)
    }

    @Test fun anthropicRejectsOutOfOrderMismatchedOrDuplicateEvents() {
        listOf(blockStart, blockText, messageDelta, messageStop).forEach { event ->
            assertFailedWire(Protocol.ANTHROPIC_MESSAGES, event, FailureKind.MALFORMED_RESPONSE)
        }
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + messageStart, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + blockStart + blockStart, FailureKind.MALFORMED_RESPONSE)
        val wrongIndex = anthropic("content_block_delta", ",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"synthetic\"}")
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + blockStart + wrongIndex, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun anthropicRejectsEventTypeMismatchAndNonEmptyInitialContent() {
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, "event: message_start\ndata: {\"type\":\"message_stop\"}\n\n", FailureKind.MALFORMED_RESPONSE)
        val initialText = anthropic("message_start", ",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"synthetic\"}]}")
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, initialText, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun anthropicSupportsInitialBlockTextMultipleBlocksAndStopSequence() {
        val initial = anthropic("content_block_start", ",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"甲\"}")
        val second = anthropic("content_block_start", ",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"乙\"}")
        val secondStop = anthropic("content_block_stop", ",\"index\":1")
        val end = anthropic("message_delta", ",\"delta\":{\"stop_reason\":\"stop_sequence\",\"stop_sequence\":\"synthetic\"}")
        assertSuccess(ProtocolStream(Protocol.ANTHROPIC_MESSAGES).push((messageStart + initial + blockStop + second + secondStop + end + messageStop).toByteArray()), "甲乙")
    }

    @Test fun usageOnlyAnthropicDeltaPreservesPriorInputCount() {
        val usageOnly = anthropic("message_delta", ",\"delta\":{\"stop_reason\":null},\"usage\":{\"output_tokens\":2}")
        val events = ProtocolStream(Protocol.ANTHROPIC_MESSAGES).push((messageStart + usageOnly + messageDelta + messageStop).toByteArray())
        assertSuccess(events, "")
        assertEquals(listOf(StreamEvent.Usage(7, 0), StreamEvent.Usage(7, 2), StreamEvent.Usage(7, 3)), events.filterIsInstance<StreamEvent.Usage>())
    }

    @Test fun anthropicFinalUsageCanFollowLegalStopReason() {
        listOf("{}", "{\"stop_reason\":null}").forEach { delta ->
            val finalUsage = anthropic("message_delta", ",\"delta\":$delta,\"usage\":{\"output_tokens\":4}")
            val events = ProtocolStream(Protocol.ANTHROPIC_MESSAGES).push((messageStart + blockStart + blockText + blockStop + messageDelta + finalUsage + messageStop).toByteArray())
            assertSuccess(events, "中文🙂é")
            assertEquals(StreamEvent.Usage(7, 4), events.filterIsInstance<StreamEvent.Usage>().last())
        }
    }

    @Test fun anthropicCannotChangeStopReasonOrStartNewBlockAfterStop() {
        val changedReason = anthropic("message_delta", ",\"delta\":{\"stop_reason\":\"stop_sequence\"}")
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + messageDelta + changedReason + messageStop, FailureKind.MALFORMED_RESPONSE)
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + messageDelta + blockStart + messageStop, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun rejectsNegativeNonIntegerStringOrDecreasingUsage() {
        listOf("-1", "1.5", "\"2\"").forEach { count ->
            val usage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":$count}}\n\n"
            assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + usage, FailureKind.MALFORMED_RESPONSE)
        }
        val lowerChatUsage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":3}}\n\n"
        assertFailedWire(Protocol.CHAT_COMPLETIONS, chatStart + chatUsage + lowerChatUsage, FailureKind.MALFORMED_RESPONSE)
        val lowerAnthropicUsage = anthropic("message_delta", ",\"delta\":{},\"usage\":{\"input_tokens\":6}")
        assertFailedWire(Protocol.ANTHROPIC_MESSAGES, messageStart + lowerAnthropicUsage, FailureKind.MALFORMED_RESPONSE)
    }

    @Test fun providerErrorsMapToBoundedPublicKindsAndDoNotLeakMessages() {
        mapOf("authentication_error" to FailureKind.AUTH_REQUIRED, "permission_error" to FailureKind.AUTH_REQUIRED,
            "rate_limit_error" to FailureKind.RATE_LIMITED, "overloaded_error" to FailureKind.TEMPORARY,
            "api_error" to FailureKind.TEMPORARY, "server_error" to FailureKind.TEMPORARY,
            "invalid_request_error" to FailureKind.CONFIGURATION, "not_found_error" to FailureKind.CONFIGURATION,
            "synthetic_unknown_error" to FailureKind.PROVIDER_ERROR).forEach { (type, expected) ->
            val error = "\"error\":{\"type\":\"$type\",\"message\":\"synthetic-private-marker\"}"
            listOf(Protocol.CHAT_COMPLETIONS to "data: {$error}\n\n",
                Protocol.ANTHROPIC_MESSAGES to anthropic("error", ",$error")).forEach { (protocol, fixture) ->
                val events = ProtocolStream(protocol).push(fixture.toByteArray())
                assertFailure(events, expected)
                assertFalse(events.toString().contains("synthetic-private-marker"))
            }
        }
    }

    @Test fun textLimitIsCumulativeAcrossChunksAndCannotCompleteAfterFailure() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS, StreamLimits(maxTextChars = 3))
        assertEquals("甲乙", text(stream.push(chat("{\"content\":\"甲乙\"}").toByteArray())))
        val events = stream.push((chat("{\"content\":\"丙丁\"}") + chatStop + done).toByteArray())
        assertFailure(events, FailureKind.LIMIT_EXCEEDED)
        assertTrue(events.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
        assertTerminalIsInert(stream)
    }

    @Test fun textLimitAllowsExactBoundaryIncludingSurrogatePair() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS, StreamLimits(maxTextChars = 3))
        assertSuccess(stream.push((chat("{\"content\":\"甲🙂\"}") + chatStop + done).toByteArray()), "甲🙂")
    }

    @Test fun malformedEscapedSurrogatesAreRejectedWithoutPreview() {
        listOf("\\uD800", "\\uDC00", "\\uD800x").forEach { escaped ->
            assertFailedWire(Protocol.CHAT_COMPLETIONS, chat("{\"content\":\"$escaped\"}"), FailureKind.MALFORMED_RESPONSE)
        }
    }

    @Test fun chunkAndNestingLimitsBecomeTerminalPublicFailures() {
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS, StreamLimits(maxChunkBytes = 4))
        assertFailure(stream.push(chatStart.toByteArray()), FailureKind.LIMIT_EXCEEDED)
        val deep = "{\"synthetic\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertFailedWire(Protocol.CHAT_COMPLETIONS, "data: $deep\n\n", FailureKind.LIMIT_EXCEEDED)
    }

    @Test fun jsonNestingScannerDoesNotCountBracketsInsideStrings() {
        val literal = "[".repeat(100) + "]".repeat(100)
        val stream = ProtocolStream(Protocol.CHAT_COMPLETIONS)
        assertSuccess(stream.push((chat("{\"content\":\"$literal\"}") + chatStop + done).toByteArray()), literal)
    }

    @Test fun cancelBeforeOrAfterPreviewIsOneTerminalEvent() {
        Protocol.entries.forEach { protocol ->
            val empty = ProtocolStream(protocol)
            assertEquals(listOf(StreamEvent.Cancelled), empty.cancel())
            assertTerminalIsInert(empty)
            val partial = ProtocolStream(protocol)
            partial.push((if (protocol == Protocol.CHAT_COMPLETIONS) chatStart + chatText else messageStart + blockStart + blockText).toByteArray())
            assertEquals(listOf(StreamEvent.Cancelled), partial.cancel())
            assertTerminalIsInert(partial)
        }
    }

    @Test fun successfulTerminalStateIgnoresFurtherPushFinishAndCancel() {
        listOf(Protocol.CHAT_COMPLETIONS to chatSuccess, Protocol.ANTHROPIC_MESSAGES to anthropicSuccess).forEach { (protocol, fixture) ->
            val stream = ProtocolStream(protocol)
            assertSuccess(stream.push((fixture + fixture).toByteArray()), "中文🙂é")
            assertTerminalIsInert(stream)
        }
    }

    @Test fun malformedDiagnosticsAndPreviewToStringAreRedacted() {
        val marker = "synthetic-private-marker"
        val events = ProtocolStream(Protocol.CHAT_COMPLETIONS).push((chat("{\"content\":\"$marker\"}") + "data: {\"$marker\": invalid}\n\n").toByteArray())
        assertEquals(marker, text(events))
        assertFailure(events, FailureKind.MALFORMED_RESPONSE)
        assertFalse(events.toString().contains(marker))
    }

    private fun text(events: List<StreamEvent>) = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

    private fun assertSuccess(events: List<StreamEvent>, expectedText: String, label: String = "") {
        assertEquals(label, 1, events.count { it == StreamEvent.Started })
        assertEquals(label, expectedText, text(events))
        assertEquals(label, 1, events.count { it == StreamEvent.Completed })
        assertEquals(label, StreamEvent.Completed, events.last())
        assertFalse(label, events.any { it is StreamEvent.Failed || it == StreamEvent.Cancelled })
    }

    private fun assertFailure(events: List<StreamEvent>, expected: FailureKind) {
        assertEquals(listOf(expected), events.filterIsInstance<StreamEvent.Failed>().map { it.kind })
        assertTrue(events.last() is StreamEvent.Failed)
        assertFalse(events.contains(StreamEvent.Completed))
        assertFalse(events.contains(StreamEvent.Cancelled))
    }

    private fun assertFailedWire(protocol: Protocol, fixture: String, expected: FailureKind) {
        val stream = ProtocolStream(protocol)
        assertFailure(stream.push(fixture.toByteArray()), expected)
        assertTerminalIsInert(stream)
    }

    private fun assertTerminalIsInert(stream: ProtocolStream) {
        assertTrue(stream.isTerminal)
        assertTrue(stream.push(byteArrayOf(0xFF.toByte())).isEmpty())
        assertTrue(stream.finish().isEmpty())
        assertTrue(stream.cancel().isEmpty())
    }
}
