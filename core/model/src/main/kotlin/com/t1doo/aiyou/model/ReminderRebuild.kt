package com.t1doo.aiyou.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId

data class ReminderPlatformFingerprint(val bootCount: Long?, val zone: String, val appVersion: Long,
    val availability: TaskReminderAvailability) {
    init { require(bootCount == null || bootCount >= 0); require(appVersion >= 0 && zone.length <= 128); ZoneId.of(zone) }
    val digest: String get() = rebuildDigest { fingerprint(this@ReminderPlatformFingerprint) }
    override fun toString() = "ReminderPlatformFingerprint(redacted)"
}

data class ReminderRebuildRequest(val id: String, val expectedMetaVersion: Long, val reasons: Int,
    val platform: ReminderPlatformFingerprint) {
    init {
        require(expectedMetaVersion in 0 until Long.MAX_VALUE && reasons in 1..127)
        if (!id.matches(Regex("b1:[a-f0-9]{32}:[a-f0-9]{64}")) || makeId(id.substring(3,35), expectedMetaVersion, reasons, platform) != id)
            throw ReminderRuntimeConflict()
    }
    override fun toString() = "ReminderRebuildRequest(redacted)"
    companion object {
        const val ADMISSION = 1; const val BOOT = 2; const val PACKAGE = 4; const val CLOCK = 8
        const val ZONE = 16; const val CAPABILITY = 32; const val USER = 64
        fun create(expected: Long, reasons: Int, platform: ReminderPlatformFingerprint,
            nonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()): ReminderRebuildRequest =
            ReminderRebuildRequest(makeId(nonce,expected,reasons,platform),expected,reasons,platform)
        private fun makeId(nonce:String, v:Long, reasons:Int, platform:ReminderPlatformFingerprint):String {
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            return "b1:$nonce:" + rebuildDigest {
                text("AIyou/reminder-rebuild/request/1");writeInt(1);writeInt(1);text(nonce)
                writeLong(v);writeInt(reasons);fingerprint(platform)
            }
        }
    }
}

/** SQL BINARY task-ID order followed by numeric command generation. Not an execution token. */
data class ReminderScanCursor(val taskId: String, val afterGeneration: Long? = null, val rowExhausted: Boolean = true) {
    init { ManualTags.identifier(taskId); require(afterGeneration == null || afterGeneration > 0)
        require(rowExhausted == (afterGeneration == null)) }
    override fun toString() = "ReminderScanCursor(redacted)"
}
data class ReminderDrainPass(val id: String, val upperTaskId: String?, val next: ReminderScanCursor? = null,
    val done: Boolean = false, val dirty: Boolean = false) {
    init { require(id.matches(Regex("[a-f0-9-]{36}")));upperTaskId?.let(ManualTags::identifier);require(upperTaskId != null || done) }
    override fun toString() = "ReminderDrainPass(done=$done)"
}
data class ReminderRuntimeMeta(val version: Long = 0, val lastWriteId: String = "",
    val request: ReminderRebuildRequest? = null, val planUpperTaskId: String? = null,
    val planCursor: String? = null, val planDone: Boolean = true, val drain: ReminderDrainPass? = null,
    val failedRows: Int = 0) {
    init {
        require(version >= 0 && failedRows >= 0)
        if (version == 0L) require(lastWriteId.isEmpty() && request == null && planDone && drain == null)
        else ManualTags.identifier(lastWriteId)
        request?.let { require(it.expectedMetaVersion < version) }
        planUpperTaskId?.let(ManualTags::identifier);planCursor?.let(ManualTags::identifier)
        require(planDone || request != null && planUpperTaskId != null)
    }
    override fun toString() = "ReminderRuntimeMeta(version=$version, planDone=$planDone)"
}

private fun rebuildDigest(body: DataOutputStream.() -> Unit): String {
    val raw=ByteArrayOutputStream().use { b->DataOutputStream(b).use{it.body()};b.toByteArray() }
    return try { MessageDigest.getInstance("SHA-256").digest(raw).hex() } finally { raw.fill(0) }
}
private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
private fun DataOutputStream.text(s:String){val b=s.toByteArray(Charsets.UTF_8);try{writeInt(b.size);write(b)}finally{b.fill(0)}}
private fun DataOutputStream.fingerprint(p:ReminderPlatformFingerprint){
    writeBoolean(p.bootCount!=null);p.bootCount?.let(::writeLong);text(p.zone);writeLong(p.appVersion)
    writeBoolean(p.availability.platformConnected);text(p.availability.notifications.name)
    text(p.availability.exactAlarms.name);writeBoolean(p.availability.channelEnabled)
}
