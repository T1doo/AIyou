package com.t1doo.aiyou.records

import com.t1doo.aiyou.ai.FixedTextCapabilityProbe
import com.t1doo.aiyou.ai.OkHttpStreamingTransport
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.ModelCapabilityProbe
import com.t1doo.aiyou.domain.ModelCapabilityProbeTicket
import java.time.Clock

/** One admitted records owner and installation-permission snapshot; source authority stays live.
 * Installation creates no client, reads no secret and sends nothing. */
internal class ProductionRecordAi(
    private val networkAllowed: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val openTransport: () -> OkHttpStreamingTransport = { OkHttpStreamingTransport() },
) : AutoCloseable {
    private val lock = Any()
    private var owner: EntryRepository? = null
    private var probeTransport: OkHttpStreamingTransport? = null
    private var closed = false

    fun unavailableNotice(): String? = when {
        synchronized(lock) { closed } -> "本机资料会话已关闭，请重新打开。"
        !networkAllowed() -> "当前安装包未开放网络发送；原文仍保存在本机，不会发送给模型。"
        else -> null
    }

    /** Called after prepare on the existing records actor; cannot replace an admitted owner. */
    fun bindOwner(records: EntryRepository) = synchronized(lock) {
        check(!closed && (owner == null || owner === records)) { "AI records owner changed" }
        owner = records
    }

    /** The controller calls this only after an explicit start, on its owned IO worker. */
    fun openRecord(records: EntryRepository): RecordAiCapability {
        check(unavailableNotice() == null) { "Network execution unavailable" }
        synchronized(lock) { check(!closed && owner === records) { "AI records owner changed" } }
        // Authenticate only through this owner's coherent local connection services.
        checkNotNull(records.localModelConnections) { "Local connection services unavailable" }
        val transport = openTransport()
        return try {
            check(unavailableNotice() == null) { "Network execution unavailable" }
            SourceBoundRecordAiCapability.fromLocalConnections(transport, records, clock, 1024)
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    val probe: ModelCapabilityProbe = object : ModelCapabilityProbe {
        override fun newExchange(ticket: ModelCapabilityProbeTicket) = run {
            check(unavailableNotice() == null) { "Network execution unavailable" }
            val records = synchronized(lock) { check(!closed); checkNotNull(owner) }
            val services = checkNotNull(records.localModelConnections)
            val profiles = checkNotNull(records.modelCapabilities)
            val existing = synchronized(lock) { check(!closed && owner === records); probeTransport }
            val created = if (existing == null) openTransport() else null
            val transport = try {
                synchronized(lock) {
                    check(!closed && owner === records)
                    probeTransport ?: checkNotNull(created).also { probeTransport = it }
                }
            } catch (failure: Throwable) {
                created?.close()
                throw failure
            }
            if (created != null && created !== transport) created.close()
            check(unavailableNotice() == null) { "Network execution unavailable" }
            FixedTextCapabilityProbe(transport, profiles, LocalConnectionSecretResolver(services.secrets), clock)
                .newExchange(ticket)
        }
    }

    /** VM calls only after both AI controllers have drained their real borrowers. Never closes SQL. */
    override fun close() {
        val transport = synchronized(lock) {
            closed = true
            owner = null
            probeTransport.also { probeTransport = null }
        }
        transport?.close()
    }
}
