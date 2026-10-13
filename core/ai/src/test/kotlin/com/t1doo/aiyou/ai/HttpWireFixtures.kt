package com.t1doo.aiyou.ai

/** Synthetic provider bytes; never credentials, user records or a live provider URL. */
internal object HttpWireFixtures {
    const val text = "合成中文🙂é"

    fun complete(protocol: Protocol): ByteArray = preview(protocol) + terminal(protocol)

    fun preview(protocol: Protocol): ByteArray = when (protocol) {
        Protocol.CHAT_COMPLETIONS ->
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$text\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n"
        Protocol.ANTHROPIC_MESSAGES ->
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":7,\"output_tokens\":0}}}\n\n" +
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"$text\"}}\n\n" +
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n\n"
    }.toByteArray(Charsets.UTF_8)

    fun terminal(protocol: Protocol): ByteArray = when (protocol) {
        Protocol.CHAT_COMPLETIONS -> "data: [DONE]\n\n"
        Protocol.ANTHROPIC_MESSAGES -> "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
    }.toByteArray()

    fun providerTruncation(protocol: Protocol): ByteArray = complete(protocol).toString(Charsets.UTF_8)
        .replace("\"stop\"", "\"length\"")
        .replace("\"end_turn\"", "\"max_tokens\"")
        .toByteArray(Charsets.UTF_8)

    fun prepared(protocol: Protocol): PreparedRequest = ProtocolRequests.prepare(
        "https://example.test/custom/v1/", protocol,
        TextRequest("synthetic-model", listOf(TextMessage(MessageRole.USER, "合成请求🙂")),
            system = "合成系统规则", maxOutputTokens = 73),
    )

    fun summary(events: List<StreamEvent>): List<String> = events.map { event ->
        when (event) {
            StreamEvent.Started -> "Started"
            is StreamEvent.TextDelta -> "TextDelta(${event.text})"
            is StreamEvent.Usage -> "Usage(${event.inputTokens},${event.outputTokens})"
            StreamEvent.Completed -> "Completed"
            is StreamEvent.Failed -> "Failed(${event.kind})"
            StreamEvent.Cancelled -> "Cancelled"
        }
    }
}
