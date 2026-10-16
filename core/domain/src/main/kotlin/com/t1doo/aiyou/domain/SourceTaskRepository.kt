package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class SourceTaskPreview(val binding: TaskSourceBinding, val quote: String, val existing: ManualTaskSnapshot? = null) {
    init { require(quote.codePointCount(0, quote.length) == binding.end - binding.start) }
    override fun toString() = "SourceTaskPreview(redacted)"
}
data class SourceTaskCreate(val preview: SourceTaskPreview, val task: ManualTaskCreate) {
    init { require(task.id == SourceTasks.id(preview.binding)) }
    override fun toString() = "SourceTaskCreate(redacted)"
}
interface SourceTaskRepository {
    fun preview(binding: TaskSourceBinding): SourceTaskPreview
    fun create(request: SourceTaskCreate): ManualTaskSnapshot
    /** Current exact original only. Changed/deleted sources never redirect to another version. */
    fun locate(taskId: String, version: Long, publish: (TaskSourceBinding) -> Unit): Boolean
}
class SourceTaskConflict : IllegalStateException("Task source or confirmation changed")
class SourceTaskStorageFailure : IllegalStateException("Task source unavailable")

object SourceTasks {
    /** One confirmed task per exact source fragment/generation, including cold reopen. */
    fun id(binding: TaskSourceBinding) = "SOURCE_TASK:" + digest { writeBinding(this,binding) }
    fun receipt(request: SourceTaskCreate) = SourceTaskReceipt(request.preview.binding,digest {
        writeBinding(this,request.preview.binding)
        writeUTF(request.preview.quote)
        val t=request.task
        writeUTF(t.id);writeUTF(t.mutationId);writeUTF(t.title);writeLong(t.createdAtMillis);writeUTF(t.creationTimeZone)
        writeBoolean(t.schedule!=null)
        t.schedule?.let { due -> writeUTF(due.date.toString());writeUTF(due.timeZone);writeBoolean(due.time!=null)
            due.time?.let { time -> writeUTF(time.toString());writeInt(checkNotNull(due.offsetSeconds)) } }
    })
    private fun writeBinding(out:DataOutputStream,b:TaskSourceBinding) = with(out) {
        writeUTF("AIyou source task v1");writeUTF(b.source.sourceId);writeLong(b.source.revision)
        writeUTF(b.sourceHash);writeLong(b.generation);writeInt(b.start);writeInt(b.end)
    }
    private fun digest(write:DataOutputStream.()->Unit):String {
        val bytes=ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } } finally { bytes.fill(0) }
    }
}
