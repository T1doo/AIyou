package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections

/** v6 private authenticated fields; deliberately separate from the frozen R1 request encoders. */
internal object ReminderRuntimeCipherFields {
    fun write(out: DataOutputStream, receipts: List<ReminderRuntimeReceipt>) {
        if (receipts.size > ReminderRuntimeReceipts.MAX_SLOTS) throw ReminderRuntimeLimit()
        val raw = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { body ->
                body.writeInt(receipts.size)
                receipts.forEach { r ->
                    val slot = encodeSlot(r)
                    try { body.writeInt(slot.size); body.write(slot) } finally { slot.fill(0) }
                }
            }; bytes.toByteArray()
        }
        try { require(raw.size <= ReminderRuntimeReceipts.MAX_BYTES); out.write(raw) } finally { raw.fill(0) }
    }
    fun read(input: DataInputStream): List<ReminderRuntimeReceipt> {
        val before = input.available(); val count = input.readInt(); require(count in 0..ReminderRuntimeReceipts.MAX_SLOTS)
        val list = List(count) {
            val size = input.readInt(); require(size in 1..ReminderRuntimeReceipts.MAX_SLOT_BYTES && size <= input.available())
            val raw = ByteArray(size); input.readFully(raw)
            try { DataInputStream(ByteArrayInputStream(raw)).use { decodeSlot(it, size) } } finally { raw.fill(0) }
        }
        require(before - input.available() <= ReminderRuntimeReceipts.MAX_BYTES)
        return Collections.unmodifiableList(list)
    }
    private fun encodeSlot(r: ReminderRuntimeReceipt): ByteArray = ByteArrayOutputStream().use { b ->
        DataOutputStream(b).use { o ->
            o.writeByte(1); o.scope(r.event.scope)
            o.text(r.event.id); o.writeLong(r.event.expectedVersion); o.writeLong(r.event.observedAtMillis)
            o.capability(r.event.capability); o.text(r.eventDecision.name)
            val eventSize = b.size(); require(eventSize <= ReminderRuntimeReceipts.MAX_EVENT_BYTES)
            o.writeBoolean(r.claim != null)
            r.claim?.let { c ->
                o.text(c.id); o.text(c.event.requestHash); o.writeLong(c.expectedVersion); o.writeLong(c.claimAtMillis)
                o.capability(c.capability); o.text(c.specification); o.text(requireNotNull(r.claimDecision).name)
            }
            o.writeBoolean(r.result != null)
            r.result?.let { result ->
                o.text(result.id); o.text(result.claim.event.requestHash); o.text(result.claim.requestHash)
                o.writeLong(result.expectedVersion); o.writeLong(result.observedAtMillis)
                o.text(result.observation.name); o.text(result.reason.name)
                o.writeBoolean(result.capability != null); result.capability?.let { o.capability(it) }
            }
            o.writeBoolean(r.closed != null); r.closed?.let { o.writeLong(it.atMillis); o.text(it.reason.name) }
            o.writeBoolean(r.retired != null); r.retired?.let { o.writeLong(it.atMillis); o.text(it.reason.name) }
            require(b.size() - eventSize <= ReminderRuntimeReceipts.MAX_EVENT_BYTES)
        }
        b.toByteArray().also { require(it.size <= ReminderRuntimeReceipts.MAX_SLOT_BYTES) }
    }
    private fun decodeSlot(i: DataInputStream, size: Int): ReminderRuntimeReceipt {
        require(i.readUnsignedByte() == 1)
        val scope = i.scope()
        val event = ReminderEventRequest(i.text(100), scope, i.readLong(), i.readLong(), i.capability())
        val decision = ReminderEventDecision.valueOf(i.text(32))
        val eventSize = size - i.available(); require(eventSize <= ReminderRuntimeReceipts.MAX_EVENT_BYTES)
        var claimDecision: ReminderClaimDecision? = null
        val claim = if (i.flag()) {
            val id = i.text(100); require(i.text(64) == event.requestHash)
            ReminderClaimRequest(id, event, i.readLong(), i.readLong(), i.capability(), i.text(16)).also {
                claimDecision = ReminderClaimDecision.valueOf(i.text(32))
            }
        } else null
        val result = if (i.flag()) {
            val id = i.text(100); require(i.text(64) == event.requestHash)
            require(i.text(64) == requireNotNull(claim).requestHash)
            val version = i.readLong(); val at = i.readLong()
            val observation = ReminderPostObservation.valueOf(i.text(32)); val reason = ReminderResultReason.valueOf(i.text(64))
            val capability = if (i.flag()) i.capability() else null
            ReminderResultRequest(id, claim, version, at, observation, reason, capability)
        } else null
        val closed = if (i.flag()) ReminderRuntimeClosure(i.readLong(), ReminderClosureReason.valueOf(i.text(64))) else null
        val retired = if (i.flag()) ReminderRuntimeRetirement(i.readLong(), ReminderRetirementReason.valueOf(i.text(64))) else null
        require(i.available() == 0 && size - eventSize <= ReminderRuntimeReceipts.MAX_EVENT_BYTES)
        return ReminderRuntimeReceipt(event, decision, claim, claimDecision, result, closed, retired)
    }
    private fun DataOutputStream.text(s: String) { val raw = s.toByteArray(Charsets.UTF_8); try { require(raw.size in 1..512); writeInt(raw.size); write(raw) } finally { raw.fill(0) } }
    private fun DataInputStream.text(limit: Int): String {
        val size = readInt(); require(size in 1..limit && size <= available())
        val raw = ByteArray(size); readFully(raw)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() } finally { raw.fill(0) }
    }
    private fun DataInputStream.flag(): Boolean = readUnsignedByte().also { require(it in 0..1) } == 1
    private fun DataOutputStream.scope(s: ReminderRuntimeScope) {
        text(s.token.taskId); text(s.token.reminderId); writeLong(s.token.scheduleVersion)
        writeBoolean(true); text(s.schedule.date.toString()); text(s.schedule.timeZone); writeBoolean(true)
        text(requireNotNull(s.schedule.time).toString()); writeInt(requireNotNull(s.schedule.offsetSeconds)); text(s.mode.name)
    }
    private fun DataInputStream.scope(): ReminderRuntimeScope {
        val token = TaskReminderToken(text(512), text(64), readLong()); require(flag())
        val date = LocalDate.parse(text(10)); val zone = text(128); require(flag())
        val schedule = ManualTaskSchedule(date, LocalTime.parse(text(5)), zone, readInt())
        return ReminderRuntimeScope(token, schedule, TaskReminderMode.valueOf(text(16)))
    }
    private fun DataOutputStream.capability(c: ReminderCapabilityObservation) {
        writeLong(c.checkedAtMillis); writeBoolean(c.availability.platformConnected); text(c.availability.notifications.name)
        text(c.availability.exactAlarms.name); writeBoolean(c.availability.channelEnabled)
    }
    private fun DataInputStream.capability() = ReminderCapabilityObservation(readLong(), TaskReminderAvailability(flag(),
        TaskReminderPermission.valueOf(text(16)), TaskReminderPermission.valueOf(text(16)), flag()))
}
