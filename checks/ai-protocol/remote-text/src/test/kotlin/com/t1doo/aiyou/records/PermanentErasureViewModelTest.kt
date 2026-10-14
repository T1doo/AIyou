package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual VM actor, recovery, draining owner and SQL/files; Android/Keystore is not simulated as proven. */
class PermanentErasureViewModelTest {
    @Test fun freshVmResumesPendingIntentAndExplicitLostReceiptRetryUsesTheSameDurableConfirmation(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); f.intent(id)
            val other = f.entry(text = "合成独立记录")
            val task = f.tasks.create(ManualTaskCreate("independent-task", "合成独立任务", 55, "UTC", "create-task"))
            lateinit var durable: PermanentErasureIntent
            f.checkpoint = { if (it == "prepared") error("Synthetic PREPARED interruption") }
            Owner(f).use { first ->
                first.ready(); first.openBin(); first.request(id)
                val c = checkNotNull(first.vm.state.value.recycleBin!!.confirmation)
                first.vm.recycleBin.confirm(first.vm.state.value.recycleBin!!.session, c.id)
                withTimeout(10_000) { first.vm.state.first { it.recycleBin?.confirmation?.uncertain == true && it.recycleBin.busy == false } }
                assertEquals(PermanentErasurePhase.PREPARED, f.erase.read(id)!!.phase)
                durable = f.erase.read(id)!!.intent
                assertNotSame(Thread.currentThread(), first.eraseThread.get())
            }
            f.reopen()
            Owner(f).use { second ->
                second.ready()
                assertEquals(listOf(other), second.vm.state.value.entries.map { it.id })
                assertEquals(listOf(task), f.tasks.snapshots())
                second.openBin()
                val ui = second.vm.state.value.recycleBin!!
                assertEquals(durable.mutationId, ui.erasures.getValue(id).intent.mutationId)
                second.vm.recycleBin.select(ui.session, id)
                second.vm.recycleBin.requestRestore(ui.session, id, ui.records.single().generation)
                assertNull(second.vm.state.value.recycleBin!!.confirmation)
                second.request(id)
                val confirmation = second.vm.state.value.recycleBin!!.confirmation!!
                assertEquals(durable.mutationId, confirmation.erasureIntent!!.mutationId)
                val loseAcknowledgement = AtomicBoolean(true)
                f.checkpoint = { if (it == "done-committed" && loseAcknowledgement.compareAndSet(true, false)) error("Synthetic DONE acknowledgement loss") }
                second.vm.recycleBin.confirm(ui.session, confirmation.id)
                withTimeout(10_000) { second.vm.state.first { it.recycleBin?.confirmation?.uncertain == true && it.recycleBin.busy == false } }
                assertTrue(f.erase.read(id)!!.complete)
                assertNull(second.vm.state.value.recycleBin!!.notice)
                assertEquals(durable.mutationId, second.vm.state.value.recycleBin!!.confirmation!!.erasureIntent!!.mutationId)
                second.vm.recycleBin.confirm(ui.session, confirmation.id)
                withTimeout(10_000) { second.vm.state.first { it.recycleBin?.notice != null && it.recycleBin.confirmation == null } }
                assertEquals(listOf(durable.mutationId), second.calls.distinct())
                assertEquals(3, second.calls.size) // One bounded startup attempt, explicit confirm and same-intent retry.
                assertEquals(listOf(other), second.vm.state.value.entries.map { it.id })
                assertEquals(listOf(task), f.tasks.snapshots())
            }
            f.checkpoint = {}; f.reopen()
            Owner(f).use { third ->
                third.ready(); third.openBin()
                assertTrue(third.vm.state.value.recycleBin!!.records.isEmpty())
                assertEquals(listOf(other), third.vm.state.value.entries.map { it.id })
                assertEquals(durable.mutationId, f.erase.read(id)!!.intent.mutationId)
                assertTrue(f.erase.read(id)!!.complete)
                assertTrue(third.calls.isEmpty())
                assertEquals(listOf(task), f.tasks.snapshots())
            }
        }
    }

    private class Owner(f: PermanentErasureFixture) : AutoCloseable {
        private val gate = f.gateway
        private val primary = f.db
        private val tags = f.tags
        private val tasks = f.tasks
        private val media = f.media
        private val edits = f.edits
        private val erase = f.erase
        private val closed = CountDownLatch(1)
        private val failure = AtomicReference<Throwable>()
        val eraseThread = AtomicReference<Thread>()
        val calls = CopyOnWriteArrayList<String>()
        private val lifetime = DrainingRecordsStorage(gate, media, edits, { primary.close() }, { tags.wipeKey(); tasks.wipeKey() })
        private val base = SqliteVmEntryRepository(primary, gate, RemoteTextTransactions(gate)) { error("Owner handles retirement") }
        private val store = object : EntryRepository by base, AwaitableRecordStorage {
            override val attachments = media
            override val editDrafts = edits
            override val manualTags = tags
            override val manualTasks = tasks
            override val localSearch = SqlLocalSearchRepository(gate, tags)
            override val recordPackages = f.packages
            override val recycleBin = f.bin
            override val permanentErasure = object : PermanentErasureRepository by erase {
                override suspend fun erase(intent: PermanentErasureIntent): PermanentErasureReceipt {
                    calls += intent.mutationId; eraseThread.set(Thread.currentThread()); return erase.erase(intent)
                }
            }
            override suspend fun prepare() { resumePermanentErasure(permanentErasure); media.recover() }
            override fun close() = lifetime.close()
            override fun awaitClosed() {
                try { lifetime.awaitClosed() } catch (t: Throwable) { failure.set(t); throw t } finally { closed.countDown() }
            }
        }
        val vm = LocalRecordsViewModel(Application(), { store })
        suspend fun ready() = withTimeout(10_000) { vm.state.first { it.ready && !it.opening } }
        suspend fun openBin() {
            vm.recycleBin.open()
            withTimeout(10_000) { vm.state.first { it.recycleBin?.loading == false && it.recycleBin.error == null } }
        }
        fun request(id: String) {
            val ui = vm.state.value.recycleBin!!
            vm.recycleBin.select(ui.session, id)
            vm.recycleBin.requestErasure(ui.session, id, ui.records.single { it.entry.id == id }.generation)
            assertNotNull(vm.state.value.recycleBin!!.confirmation)
        }
        override fun close() {
            vm.clearForHostTest(); assertTrue(closed.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
        }
    }
}
