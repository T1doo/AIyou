package com.t1doo.aiyou.data

import android.app.Application
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real production ViewModel actor and public save/recycle controllers, production SQL/search
 * proof and recycle transactions. Android lifecycle, bitmap decode and attachment bytes use
 * explicit host boundaries. No ArrayDeque stands in for the production command queue.
 * The grid's route predicate is held true to test actor ordering independently of Compose navigation.
 */
class CurrentImageGridQueueTest {
    @Test(timeout = 20_000) fun actualViewModelSaveRunsBetweenGridReadsAndRetiresOldRequest(): Unit = runBlocking {
        Harness().use { h ->
            val old = h.start(h.ids)
            assertTrue(h.firstStarted.await(5, TimeUnit.SECONDS))
            h.vm.changeText("合成插队保存\n保持原文换行")
            h.vm.save()
            assertTrue(h.vm.state.value.saving)
            assertEquals(listOf("read:${h.images[0]}"), h.events.filter { it.startsWith("read:") })
            h.releaseFirst.countDown()
            withTimeout(5_000) { h.vm.state.first { !it.saving && it.entries.any { e -> e.originalText == "合成插队保存\n保持原文换行" } } }
            h.ready()
            assertTrue(h.pictures.first().released)
            assertTrue(h.vm.imageGrid.visible(old).tiles.isEmpty())
            assertEquals(listOf("read:${h.images[0]}"), h.events.filter { it.startsWith("read:") })
            val fresh = h.start(listOf(h.ids[1]))
            withTimeout(5_000) { h.vm.imageGrid.state.first { it.tiles[h.ids[1]]?.picture != null } }
            assertEquals(listOf("read:${h.images[0]}", "read:${h.images[1]}"), h.events.filter { it.startsWith("read:") })
            assertTrue(h.events.indexOf("save") in 1 until h.events.indexOf("read:${h.images[1]}"))
            assertNotSame(old, fresh)
            assertTrue(h.vm.state.value.entries.all { it.policy == ProcessingPolicy.LOCAL_ONLY })
            assertEquals(4, h.vm.state.value.entries.size)
            assertTrue(h.f.db.read("SELECT * FROM capture_draft").isEmpty())
            assertTrue(h.delivered.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test(timeout = 20_000) fun actualViewModelConfirmedDeletionRunsBeforeNextGridReadWithoutBackfill(): Unit = runBlocking {
        Harness(holdSecond = true).use { h ->
            val old = h.start(h.ids)
            assertTrue(h.firstStarted.await(5, TimeUnit.SECONDS))
            // A genuine confirmation read is queued behind the first thumbnail.
            h.vm.recycleBin.requestMove(h.ids[2], 1)
            h.releaseFirst.countDown()
            val local = withTimeout(5_000) { h.vm.state.first { it.recycleBin?.confirmation != null } }
            assertTrue(h.secondStarted.await(5, TimeUnit.SECONDS))
            val ui = local.recycleBin!!
            // Confirm while thumbnail two holds the actor: delete must precede thumbnail three.
            h.vm.recycleBin.confirm(ui.session, ui.confirmation!!.id)
            assertTrue(h.vm.state.value.recycleBin!!.confirmation!!.busy)
            h.releaseSecond.countDown()
            withTimeout(5_000) { h.vm.state.first { it.entries.none { e -> e.id == h.ids[2] } && it.recycleNotice != null } }
            h.ready() // The post-delete index command is after the stale third-image command.
            assertEquals(listOf("read:${h.images[0]}", "read:${h.images[1]}"), h.events.filter { it.startsWith("read:") })
            assertTrue(h.events.indexOf("delete") > h.events.indexOf("read:${h.images[1]}"))
            assertEquals(listOf(1L), h.f.db.read("SELECT deleted FROM remote_source_access WHERE entryId=?", h.ids[2]).single())
            assertEquals(2, h.vm.state.value.entries.size)
            assertTrue(h.vm.imageGrid.visible(old).tiles.isEmpty())
            assertNull(h.vm.imageGrid.state.value.tiles[h.ids[2]]?.picture)
            assertTrue(h.delivered.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    private class Picture : AttachmentPicture {
        override val width = 1
        override val height = 1
        var released = false
        override fun releaseUnpublished() { released = true }
    }

    private class Harness(private val holdSecond: Boolean = false) : AutoCloseable {
        val f = RecordEventDateFixture()
        val ids = (1..3).map { "20000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val images = (1..3).map { "30000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val events = CopyOnWriteArrayList<String>()
        val pictures = CopyOnWriteArrayList<Picture>()
        val delivered = CopyOnWriteArrayList<ByteArray>()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val decodeCalls = AtomicInteger()
        private val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1)
        private val rows = ids.mapIndexed { i, id -> LocalAttachment(AttachmentIntent(images[i], id, 1,
            "image/png", LocalAttachmentController.hash(bytes), bytes.size.toLong()), AttachmentState.READY) }
        val vm: LocalRecordsViewModel
        init {
            ids.forEachIndexed { i, id ->
                f.entry(id, "合成网格原文 $i\n第二行")
                f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,?,'READY')", images[i], id, rows[i].intent.sha256, bytes.size.toLong())
            }
            f.drain()
            val raw = SqliteVmEntryRepository(f.db, f.gateway, RemoteTextTransactions(f.gateway), {})
            val bin = SqlRecycleBinRepository(f.gateway, f.tags)
            val repository = object : EntryRepository by raw, AutoCloseable {
                override val localSearch = f.search
                override val manualTags = f.tags
                override val recycleBin = object : RecycleBinRepository by bin {
                    override fun apply(mutation: RecycleMutation) = bin.apply(mutation).also { events += "delete" }
                }
                override val attachments = object : AttachmentStore {
                    override suspend fun list(entryId: String, revision: Int) = rows.filter { it.intent.entryId == entryId && it.intent.revision == revision }
                    override suspend fun read(id: String): ByteArray {
                        events += "read:$id"
                        check(rows.any { it.intent.id == id })
                        return bytes.copyOf().also { delivered += it }
                    }
                    override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment = error("No image writes in this contract")
                    override suspend fun recover() = error("No explicit grid recovery")
                }
                override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single() == 11L) }
                override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry =
                    raw.saveDraft(expected, capturedAtMillis, captureTimeZone).also { events += "save" }
                override fun close() { closed.countDown() }
            }
            vm = LocalRecordsViewModel(Application(), { repository }, decodeGrid = {
                val n = decodeCalls.incrementAndGet(); events += "decode:$n"
                if (n == 1) { firstStarted.countDown(); check(releaseFirst.await(5, TimeUnit.SECONDS)) }
                if (n == 2 && holdSecond) { secondStarted.countDown(); check(releaseSecond.await(5, TimeUnit.SECONDS)) }
                Picture().also { pictures += it }
            })
        }
        suspend fun ready() = withTimeout(5_000) { vm.state.first {
            it.ready && !it.opening && it.error == null && !it.localIndexBusy && it.localSearchEpoch == f.search.epoch()
        } }
        suspend fun start(visible: List<String>): OriginalSearchState {
            val local = ready()
            val input = OriginalSearchInput(onlyImages = true)
            val result = LocalOriginalSearchMatcher.indexed(local.entries, input, f.search)
            check(result.stage == OriginalSearchStage.READY)
            val search = OriginalSearchState(input, result.stage, result.hits, entries = local.entries,
                proof = result.proof, owner = f.search, indexEpoch = local.localSearchEpoch)
            vm.imageGrid.sync(search, visible) { true }
            return search
        }
        override fun close() {
            releaseFirst.countDown(); releaseSecond.countDown()
            vm.clearForHostTest()
            check(closed.await(5, TimeUnit.SECONDS)) { "Actual ViewModel IO actor did not close" }
            f.close()
        }
    }
}
