package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ProcessingPolicy

/** Both policy and matching current consent are necessary; this never sends data. */
fun mayProcessRemotely(policy: ProcessingPolicy, hasMatchingCurrentConsent: Boolean): Boolean =
    policy == ProcessingPolicy.REMOTE_ALLOWED && hasMatchingCurrentConsent
