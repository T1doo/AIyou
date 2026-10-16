package com.t1doo.aiyou.model

/** No quote copy: locating a source always reads the authenticated original again. */
data class TaskSourceBinding(val source: SourceVersion, val sourceHash: String, val generation: Long,
    val start: Int, val end: Int) {
    init {
        ManualTags.identifier(source.sourceId)
        require(source.revision in 1..Int.MAX_VALUE.toLong() && sourceHash.matches(Regex("[0-9a-f]{64}")))
        require(generation >= 0 && start >= 0 && end > start && end - start <= 320)
    }
    override fun toString() = "TaskSourceBinding(redacted)"
}
data class SourceTaskReceipt(val binding: TaskSourceBinding, val requestHash: String) {
    init { require(requestHash.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "SourceTaskReceipt(redacted)"
}
