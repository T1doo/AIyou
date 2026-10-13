package com.t1doo.aiyou.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProtocolRequestsTest {
    private val request = TextRequest(
        "synthetic-model",
        listOf(TextMessage(MessageRole.USER, "合成输入\n\"quoted\""), TextMessage(MessageRole.ASSISTANT, "合成回复")),
        system = "合成系统指令", maxOutputTokens = 123,
    )

    @Test fun preservesCustomVersionPrefixForBothProtocols() {
        assertEquals("https://example.test/custom/v1/chat/completions",
            ProtocolRequests.endpoint("https://example.test/custom/v1/", Protocol.CHAT_COMPLETIONS).toString())
        assertEquals("https://example.test/custom/v1/messages",
            ProtocolRequests.endpoint("https://example.test/custom/v1/", Protocol.ANTHROPIC_MESSAGES).toString())
    }

    @Test fun handlesBareHostTrailingSlashesAndPort() {
        assertEquals("https://example.test/messages", ProtocolRequests.endpoint("https://example.test", Protocol.ANTHROPIC_MESSAGES).toString())
        assertEquals("https://example.test:8443/v1/chat/completions",
            ProtocolRequests.endpoint("https://example.test:8443/v1///", Protocol.CHAT_COMPLETIONS).toString())
    }

    @Test fun permitsCaseInsensitiveHttpsSchemeAndBoundsRootLength() {
        assertEquals("HTTPS://example.test/v1/messages",
            ProtocolRequests.endpoint("HTTPS://example.test/v1", Protocol.ANTHROPIC_MESSAGES).toString())
        assertRejectedRoot("https://example.test/" + "x".repeat(4096))
    }

    @Test fun preservesEncodedPathWithoutDoubleEncoding() {
        assertEquals("https://example.test/team%20one/v1/messages",
            ProtocolRequests.endpoint("https://example.test/team%20one/v1/", Protocol.ANTHROPIC_MESSAGES).toASCIIString())
        assertEquals("https://example.test/%E6%A8%A1%E5%9E%8B/messages",
            ProtocolRequests.endpoint("https://example.test/模型", Protocol.ANTHROPIC_MESSAGES).toASCIIString())
    }

    @Test fun rejectsCredentialsQueryAndFragment() {
        listOf("https://synthetic-user:synthetic-password@example.test/v1", "https://example.test/v1?key=synthetic-secret",
            "https://example.test/v1#synthetic-secret", "https://example.test/v1?", "https://example.test/v1#")
            .forEach(::assertRejectedRoot)
    }

    @Test fun rejectsInsecureOpaqueHostlessOrMalformedRoots() {
        listOf("http://example.test/v1", "file:///v1", "https:example.test", "/v1", "https:///v1", "https://example.test/invalid space")
            .forEach(::assertRejectedRoot)
    }

    @Test fun rejectsTraversalAndEncodedSeparators() {
        listOf("https://example.test/a/../v1", "https://example.test/a/./v1", "https://example.test/a/%2e%2e/v1",
            "https://example.test/a/%2E/v1", "https://example.test/a%2fb/v1", "https://example.test/a%2Fb/v1",
            "https://example.test/a%5cb/v1", "https://example.test/a%5Cb/v1").forEach(::assertRejectedRoot)
    }

    @Test fun rejectsOperationUrlsInsteadOfDuplicatingTheirSuffix() {
        listOf("https://example.test/v1/chat/completions", "https://example.test/v1/chat/completions/",
            "https://example.test/v1/messages", "https://example.test/v1/responses").forEach(::assertRejectedRoot)
    }

    @Test fun rejectsInvalidPorts() {
        listOf("https://example.test:0/v1", "https://example.test:65536/v1", "https://example.test:invalid/v1").forEach(::assertRejectedRoot)
    }

    @Test fun mapsChatSystemToFirstMessageAndPreservesJsonEscapes() {
        val prepared = ProtocolRequests.prepare("https://example.test/custom/v1/", Protocol.CHAT_COMPLETIONS, request)
        val body = Json.parseToJsonElement(prepared.bodyJson).jsonObject
        assertEquals("synthetic-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals("123", body.getValue("max_tokens").jsonPrimitive.content)
        assertFalse(body.containsKey("system"))
        val messages = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user", "assistant"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("合成系统指令", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("合成输入\n\"quoted\"", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(AuthenticationKind.BEARER, prepared.authentication)
    }

    @Test fun mapsAnthropicSystemToTopLevelWithoutSystemMessage() {
        val prepared = ProtocolRequests.prepare("https://example.test/custom/v1/", Protocol.ANTHROPIC_MESSAGES, request)
        val body = Json.parseToJsonElement(prepared.bodyJson).jsonObject
        assertEquals("合成系统指令", body.getValue("system").jsonPrimitive.content)
        assertEquals(listOf("user", "assistant"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("123", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("2023-06-01", prepared.headers["anthropic-version"])
        assertEquals(AuthenticationKind.ANTHROPIC_API_KEY, prepared.authentication)
    }

    @Test fun explicitChatCompletionBudgetDoesNotChangeAnthropicBudgetField() {
        val configured = TextRequest("synthetic-model", request.messages, request.system,
            maxOutputTokens = 123, chatTokenBudget = ChatTokenBudget.MAX_COMPLETION_TOKENS)
        val chat = Json.parseToJsonElement(ProtocolRequests.prepare("https://example.test/v1", Protocol.CHAT_COMPLETIONS, configured).bodyJson).jsonObject
        assertEquals("123", chat.getValue("max_completion_tokens").jsonPrimitive.content)
        assertFalse(chat.containsKey("max_tokens"))
        assertFalse(chat.containsKey("chatTokenBudget"))
        val anthropic = Json.parseToJsonElement(ProtocolRequests.prepare("https://example.test/v1", Protocol.ANTHROPIC_MESSAGES, configured).bodyJson).jsonObject
        assertEquals("123", anthropic.getValue("max_tokens").jsonPrimitive.content)
        assertFalse(anthropic.containsKey("max_completion_tokens"))
        assertFalse(anthropic.containsKey("chatTokenBudget"))
        val defaultChat = Json.parseToJsonElement(ProtocolRequests.prepare("https://example.test/v1", Protocol.CHAT_COMPLETIONS, request).bodyJson).jsonObject
        assertTrue(defaultChat.containsKey("max_tokens"))
        assertFalse(defaultChat.containsKey("max_completion_tokens"))
    }

    @Test fun rejectsUnpairedUtf16InAllInputFieldsAndPreservesEmoji() {
        listOf("\uD800", "\uDC00", "\uD800x", "x\uDC00").forEach { malformed ->
            assertBadRequest(TextRequest("synthetic-$malformed", request.messages))
            assertBadRequest(TextRequest("synthetic", listOf(TextMessage(MessageRole.USER, malformed))))
            assertBadRequest(TextRequest("synthetic", listOf(TextMessage(MessageRole.ASSISTANT, malformed))))
            assertBadRequest(TextRequest("synthetic", request.messages, system = malformed))
        }
        Protocol.entries.forEach { protocol ->
            val emoji = TextRequest("synthetic-🙂", listOf(TextMessage(MessageRole.USER, "合成🙂")), system = "规则🙂")
            val body = Json.parseToJsonElement(ProtocolRequests.prepare("https://example.test/v1", protocol, emoji).bodyJson).jsonObject
            assertEquals("synthetic-🙂", body.getValue("model").jsonPrimitive.content)
            val messages = body.getValue("messages").jsonArray
            assertEquals("合成🙂", messages.last().jsonObject.getValue("content").jsonPrimitive.content)
            if (protocol == Protocol.CHAT_COMPLETIONS) assertEquals("规则🙂", messages.first().jsonObject.getValue("content").jsonPrimitive.content)
            else assertEquals("规则🙂", body.getValue("system").jsonPrimitive.content)
        }
    }

    @Test fun omitsAbsentSystemInBothProtocols() {
        Protocol.entries.forEach { protocol ->
            val prepared = ProtocolRequests.prepare("https://example.test/v1", protocol,
                TextRequest("synthetic-model", listOf(TextMessage(MessageRole.USER, "synthetic"))))
            val body = Json.parseToJsonElement(prepared.bodyJson).jsonObject
            assertFalse(body.containsKey("system"))
            assertEquals(1, body.getValue("messages").jsonArray.size)
        }
    }

    @Test fun carriesOnlyNonSecretProtocolHeaders() {
        Protocol.entries.forEach { protocol ->
            val prepared = ProtocolRequests.prepare("https://example.test/v1", protocol, request)
            val expected = if (protocol == Protocol.CHAT_COMPLETIONS) setOf("Content-Type", "Accept")
                else setOf("Content-Type", "Accept", "anthropic-version")
            assertEquals(expected, prepared.headers.keys)
            assertEquals("application/json", prepared.headers["Content-Type"])
            assertEquals("text/event-stream", prepared.headers["Accept"])
            assertFalse(prepared.headers.keys.any { it.lowercase() in setOf("authorization", "x-api-key", "host", "content-length") })
        }
    }

    @Test fun boundsModelIdsMessagesAndOutputBudget() {
        listOf("", " ", "synthetic\nmodel", "x".repeat(257)).forEach { model -> assertBadRequest(TextRequest(model, request.messages)) }
        assertBadRequest(TextRequest("synthetic", emptyList()))
        assertBadRequest(TextRequest("synthetic", List(257) { TextMessage(MessageRole.USER, "x") }))
        assertBadRequest(TextRequest("synthetic", listOf(TextMessage(MessageRole.USER, ""))))
        listOf(0, -1, 100_001).forEach { budget -> assertBadRequest(TextRequest("synthetic", request.messages, maxOutputTokens = budget)) }
    }

    @Test fun boundsCombinedTextAndSystemInput() {
        val allowed = TextRequest("synthetic", listOf(TextMessage(MessageRole.USER, "x".repeat(262_143))), system = "y")
        assertNotNull(ProtocolRequests.prepare("https://example.test/v1", Protocol.CHAT_COMPLETIONS, allowed))
        assertBadRequest(TextRequest("synthetic", allowed.messages, system = "yy"))
    }

    @Test fun requestAndValidationDiagnosticsRedactSyntheticContent() {
        val secret = "synthetic-private-marker"
        val message = TextMessage(MessageRole.USER, secret)
        val input = TextRequest(secret, listOf(message), system = secret)
        val prepared = ProtocolRequests.prepare("https://example.test/$secret", Protocol.CHAT_COMPLETIONS, input)
        listOf(message.toString(), input.toString(), prepared.toString()).forEach { assertFalse(it.contains(secret)) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtocolRequests.endpoint("https://user:$secret@example.test/v1", Protocol.CHAT_COMPLETIONS)
        }
        assertFalse(error.toString().contains(secret))
    }

    private fun assertRejectedRoot(root: String) {
        Protocol.entries.forEach { protocol -> assertThrows("root=$root protocol=$protocol", IllegalArgumentException::class.java) { ProtocolRequests.endpoint(root, protocol) } }
    }

    private fun assertBadRequest(request: TextRequest) {
        Protocol.entries.forEach { protocol -> assertThrows(IllegalArgumentException::class.java) { ProtocolRequests.prepare("https://example.test/v1", protocol, request) } }
    }
}
