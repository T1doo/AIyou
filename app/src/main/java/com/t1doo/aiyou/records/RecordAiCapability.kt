package com.t1doo.aiyou.records

import com.t1doo.aiyou.ai.OkHttpStreamingTransport
import com.t1doo.aiyou.ai.SecretResolver
import com.t1doo.aiyou.ai.SourceBoundStreamingExchange
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.model.RemoteSendIntent
import java.time.Clock

/** App-owned transport; the records repository is borrowed and never closed here. */
internal interface RecordAiCapability : AutoCloseable {
    fun newExchange(intent: RemoteSendIntent): SourceBoundStreamingExchange
    override fun close()
}

/** Production adapter, enabled only by a future explicit model/secret/permission configuration. */
internal class SourceBoundRecordAiCapability(
    private val transport: OkHttpStreamingTransport,
    private val repository: SourceBoundTextRepository,
    private val secrets: SecretResolver,
    private val clock: Clock,
    private val maxOutputTokens: Int,
) : RecordAiCapability {
    companion object {
        /** Future explicit bindAi uses only coherent local services, never the legacy text port. */
        fun fromLocalConnections(transport: OkHttpStreamingTransport, records: EntryRepository,
            clock: Clock, maxOutputTokens: Int): SourceBoundRecordAiCapability {
            val local=checkNotNull(records.localModelConnections) { "Local connection services unavailable" }
            return SourceBoundRecordAiCapability(transport,local.text,LocalConnectionSecretResolver(local.secrets),clock,maxOutputTokens)
        }
    }
    override fun newExchange(intent: RemoteSendIntent) = transport.newSourceBoundExchange(
        repository, intent, maxOutputTokens, secrets, clock::instant)
    override fun close() = transport.close()
}

internal class RecordsStorageOwnership(val records: EntryRepository, val openAi: (() -> RecordAiCapability)?)
