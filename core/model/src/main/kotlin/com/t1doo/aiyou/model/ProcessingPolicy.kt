package com.t1doo.aiyou.model

enum class ProcessingPolicy {
    AI_NONE,
    LOCAL_ONLY,
    REMOTE_ALLOWED,
}

object ProcessingDefaults {
    val policy: ProcessingPolicy = ProcessingPolicy.LOCAL_ONLY
}
