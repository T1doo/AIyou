package com.t1doo.aiyou.data

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Tracks every AI SQL transaction, including a subscription's already running observation. */
internal class DrainingRecordsSqlDatabase(private val actual: RecordsSqlDatabase) : RecordsSqlDatabase {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var closing = false
    private var users = 0

    override fun <T> transaction(block: (RecordsSqlConnection) -> T): T {
        lock.withLock {
            check(!closing) { "Records storage is closing" }
            users++
        }
        try { return actual.transaction(block) }
        finally {
            lock.withLock {
                users--
                if (users == 0) idle.signalAll()
            }
        }
    }

    /** IO owner only. Seal new SQL and wait for actual transactions, never just closed watches. */
    fun stopAndAwaitIdle() = lock.withLock {
        closing = true
        while (users != 0) idle.awaitUninterruptibly()
    }
}
