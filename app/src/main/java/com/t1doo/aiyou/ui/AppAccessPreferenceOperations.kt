package com.t1doo.aiyou.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned coordination of full blocking preference operations, not UI result publication.
 * A cancelled owner may already be inside a non-cancellable storage method. Keep the operation
 * admitted until that method finishes; replacement owners then read the actual resulting state.
 * Check owner authority after entering this boundary. Exceptions release it so a fresh read can
 * resolve an unknown acknowledgement, rather than permanently poisoning future admission.
 */
internal interface AppAccessPreferenceRunner { suspend fun <T> run(action:suspend ()->T):T }

internal class AppAccessPreferenceOperations {
    @Volatile var runner:AppAccessPreferenceRunner?=null
    private val mutex = Mutex()
    suspend fun <T> perform(action: () -> T): T = runner?.run { performDirect(action) } ?: performDirect(action)
    suspend fun <T> performDirect(action: ()->T):T=mutex.withLock{action()}
}

internal val processAppAccessPreferenceOperations = AppAccessPreferenceOperations()
