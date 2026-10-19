package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real editor controller and product SQL, with an explicitly controlled private readback boundary. */
class ManualAliasEditorControllerTest {
    @Test fun explicitCreateReplaceRevokeAndTargetRemovalUseOneVersionedAggregateWithoutChangingOriginal() = runBlocking {
        Harness().use { h ->
            val editor = h.open(); val before = h.f.base.originalState()
            h.controller.saveAlias(editor.session, 1, "阿林", "小林")
            assertTrue(h.local.get().manualPeopleEditor!!.busy); assertTrue(h.f.people.read(h.id).aliases.isEmpty())
            h.pump(); assertEquals(listOf(ManualPersonAlias("阿林", "小林")), h.f.people.read(h.id).aliases)
            h.controller.saveAlias(editor.session, 2, "阿林", "小王"); h.pump()
            assertEquals(listOf(ManualPersonAlias("阿林", "小王")), h.f.people.read(h.id).aliases)
            h.controller.add(editor.session, 3, "新称呼"); h.pump()
            assertEquals(listOf(ManualPersonAlias("阿林", "小王")), h.f.people.read(h.id).aliases)
            h.controller.remove(editor.session, 4, "小王"); h.pump()
            assertTrue(h.f.people.read(h.id).aliases.isEmpty()); assertEquals(listOf("小林", "新称呼"), h.f.people.read(h.id).names)
            h.controller.saveAlias(editor.session, 5, "阿林", "小林"); h.pump()
            h.controller.removeAlias(editor.session, 6, "阿林"); h.pump()
            assertTrue(h.f.people.read(h.id).aliases.isEmpty()); assertEquals(before, h.f.base.originalState())
            assertEquals("合成独立草稿", h.local.get().text)
        }
    }

    @Test fun uncertainAliasCommitKeepsInputsAndExactRetryReadsSameReceiptWithoutDuplicateOrNameLoss() = runBlocking {
        Harness().use { h ->
            val editor = h.open(); var writing = false; var lost = false
            h.f.base.db.afterSql = { sql ->
                if (sql.startsWith("UPDATE entry_manual_people")) writing = true
                if (sql == "COMMIT" && writing && !lost) { lost = true; error("Synthetic alias ACK loss") }
            }
            h.controller.saveAlias(editor.session, 1, "阿林", "小林"); h.pump()
            val pending = h.local.get().manualPeopleEditor!!
            assertFalse(pending.busy); assertNotNull(pending.error); assertEquals("阿林", pending.aliasInput); assertEquals("小林", pending.aliasTarget)
            assertEquals(2L, h.f.people.read(h.id).version)
            h.f.base.db.afterSql = null; h.controller.retry(editor.session); h.pump()
            assertEquals(2L, h.f.people.read(h.id).version); assertEquals(listOf("小林", "小王"), h.f.people.read(h.id).names)
            assertEquals(listOf(ManualPersonAlias("阿林", "小林")), h.f.people.read(h.id).aliases)
            assertNull(h.local.get().manualPeopleEditor!!.error)
        }
    }

    @Test(timeout = 12_000) fun lateAliasReadbackCannotReplaceNewerRevocationMapAndOldOwnerCannotWrite() = runBlocking {
        Harness().use { h ->
            val editor = h.open(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            h.holdReadback = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            h.controller.saveAlias(editor.session, 1, "阿林", "小林")
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit { runBlocking { h.pump() } }; assertTrue(entered.await(5, TimeUnit.SECONDS))
                val saved = h.f.people.read(h.id)
                h.f.people.replace(ManualPeopleMutation(saved, saved.names, java.util.UUID.randomUUID().toString(), emptyList()))
                val newer = h.f.people.snapshots(); h.local.set(h.local.get().copy(manualPeople = newer, localSearchEpoch = h.f.search.epoch()))
                release.countDown(); result.get(5, TimeUnit.SECONDS)
                assertSame(newer, h.local.get().manualPeople); assertTrue(h.local.get().manualPeople.getValue(h.id).aliases.isEmpty())
                assertTrue(h.local.get().manualPeopleEditor!!.conflict); assertFalse(h.local.get().manualPeopleEditor!!.busy)
                h.holdReadback = null
                h.controller.retry(editor.session); h.pump()
                val current = h.local.get().manualPeopleEditor!!
                h.controller.saveAlias(current.session, current.snapshot!!.version, "迟到别名", "小林")
                val foreign = object : ManualPeopleRepository by h.people {}
                val next = h.local.get().copy(manualPeopleOwner = foreign, manualPeople = emptyMap(), manualPeopleEditor = null)
                h.local.set(next); h.pump(); assertSame(next, h.local.get()); assertTrue(h.f.people.read(h.id).aliases.isEmpty())
            } finally { release.countDown(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    private class Harness : AutoCloseable {
        val f = ManualPeopleFixture(); val id = f.base.entry()
        var holdReadback: (() -> Unit)? = null
        val people = object : ManualPeopleRepository by f.people {
            override fun snapshots(): Map<String, ManualPeopleSnapshot> {
                val all = f.people.snapshots(); holdReadback?.invoke(); return all
            }
        }
        private val versions = SqlLocalProcessingRepository(f.base.gateway)
        private val base = SqliteVmEntryRepository(f.base.db, f.base.gateway, RemoteTextTransactions(f.base.gateway)) {}
        private val store = object : EntryRepository by base { override val manualPeople = people; override val localProcessing = versions; override val localSearch = f.search }
        private val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val local: AtomicReference<LocalRecordUiState>
        val controller: ManualPeopleController
        init {
            f.people.replace(f.mutation(id, listOf("小林", "小王")))
            local = AtomicReference(LocalRecordUiState(ready = true, opening = false, entries = f.search.candidates(LocalSearchRequest("")).entries,
                text = "合成独立草稿", supportsManualPeople = true, manualPeopleOwner = people, manualPeople = f.people.snapshots(), manualPeopleAvailable = true,
                localProcessingAvailable = true, localProcessingVersions = versions.versions()))
            controller = ManualPeopleController(local::get, { change -> local.updateAndGet(change) }, { queue.add(it); true })
        }
        suspend fun open(): ManualPeopleEditor { controller.open(id, 1); pump(); return checkNotNull(local.get().manualPeopleEditor) }
        suspend fun pump() { queue.removeFirst()(store) }
        override fun close() { controller.dispose(); f.base.gateway.stopAndAwaitIdle(); f.close() }
    }
}
