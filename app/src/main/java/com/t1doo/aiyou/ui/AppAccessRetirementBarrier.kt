package com.t1doo.aiyou.ui

import kotlinx.coroutines.Deferred

/** Holds only close acknowledgements, never records or Activity references. Failed closes stay sealed. */
internal class AppAccessRetirementBarrier {
    private val guard = Any()
    private val pending = LinkedHashSet<Deferred<Unit>>()
    fun register(closure: Deferred<Unit>) = synchronized(guard) { pending.add(closure); Unit }
    fun isClear(): Boolean = synchronized(guard) { pending.isEmpty() }
    suspend fun awaitClosed() {
        while (true) {
            val batch = synchronized(guard) { pending.toList() }
            if (batch.isEmpty()) return
            batch.forEach { it.await() }
            synchronized(guard) { pending.removeAll(batch.toSet()) }
        }
    }
}

/** Keeps an old Activity's asynchronous storage retirement visible to a new Activity owner. */
internal val processAppAccessRetirement = AppAccessRetirementBarrier()
