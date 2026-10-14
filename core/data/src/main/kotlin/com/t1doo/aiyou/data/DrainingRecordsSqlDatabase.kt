package com.t1doo.aiyou.data

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Tracks every borrowed SQL transaction, including tag crypto and running AI observations. */
internal class DrainingRecordsSqlDatabase(private val actual: RecordsSqlDatabase) : RecordsSqlDatabase {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var closing = false
    private var users = 0
    private val localUsers = ThreadLocal.withInitial { 0 }

    override fun <T> transaction(block: (RecordsSqlConnection) -> T): T {
        lock.withLock {
            check(!closing) { "Records storage is closing" }
            users++
        }
        localUsers.set(localUsers.get()+1)
        try { return actual.transaction(block) }
        finally {
            localUsers.set(localUsers.get()-1)
            lock.withLock {
                users--
                if (users == 0) idle.signalAll()
            }
        }
    }

    /** IO owner only. Seal new SQL and wait for actual transactions, never just closed watches. */
    fun seal() = lock.withLock { closing = true }
    fun checkExternalWait() { check(localUsers.get() == 0) { "Cannot wait inside a SQL operation" } }
    fun awaitIdle() = lock.withLock {
        checkExternalWait()
        while (users != 0) idle.awaitUninterruptibly()
    }
    fun stopAndAwaitIdle() { seal(); awaitIdle() }
}
