package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

/** Actual grid controller + production search proof/SQL, synthetic image and attachment ports.
 * These checks do not execute Android decoding, Compose, Room or SQLCipher.
 */
class CurrentImageGridControllerTest {
    @Test fun visibleOnlyUsesResultOrderAndOneCurrentRepresentativeWithoutReadingOffscreen(): Unit = Harness(3).use { h ->
        h.enter(listOf(h.ids[2], h.ids[0])); assertEquals(1, h.queue.size)
        h.drain()
        assertEquals(listOf(h.ids[0], h.ids[2]), h.reads.map { h.rows.getValue(it).intent.entryId })
        assertEquals(setOf(h.ids[0], h.ids[2]), h.controller.visible(h.search).tiles.keys)
        assertTrue(h.delivered.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(h.controller.visible(h.search).tiles.values.all { it.picture != null })
        assertTrue(h.controller.visible(h.search).tiles.values.sumOf { it.picture!!.allocationBytes } <= CurrentImageGridController.MAX_RETAINED_BYTES)
    }

    @Test fun boundedAdmissionTrimsHiddenImagesAndKeepsOnlyOneQueuedRead(): Unit = Harness(15).use { h ->
        h.enter(h.ids); repeat(20) { h.enter(h.ids) }; assertEquals(1, h.queue.size)
        h.drain(); assertEquals(12, h.reads.size); assertEquals(12, h.controller.state.value.tiles.size)
        assertTrue(h.maxQueued <= 1)
        h.enter(listOf(h.ids.last())); assertTrue(h.controller.state.value.tiles.isEmpty())
        h.drain(); assertEquals(setOf(h.ids.last()), h.controller.state.value.tiles.keys)
    }

    @Test fun corruptAndFailedAssociationsKeepFailureCardsWithoutReadOrAutomaticRetry(): Unit = Harness(2, "CORRUPT").use { h ->
        h.enter(h.ids); h.drain(); repeat(20) { h.enter(h.ids); h.drain() }
        assertTrue(h.reads.isEmpty()); assertEquals(0, h.decodes)
        assertEquals(h.ids.toSet(), h.controller.state.value.tiles.keys)
        assertTrue(h.controller.state.value.tiles.values.all { it.failed && it.picture == null })
    }

    @Test fun inputChangeBeforeNextSyncRejectsLateDecodedPictureAndReleasesIt(): Unit = Harness().use { h ->
        h.onDecode = { h.active = false }
        h.enter(h.ids); h.drain()
        assertTrue(h.controller.visible(h.search).tiles.isEmpty())
        assertTrue(h.pictures.single().released); assertTrue(h.delivered.single().all { it == 0.toByte() })
    }

    @Test fun accountOwnerChangeWithSameIdsRejectsLateDecodedPictureBeforeUiSync(): Unit = Harness().use { h ->
        h.onDecode = { h.local = h.local.copy(localSearch = object : LocalSearchRepository by h.f.search {}) }
        h.enter(h.ids); h.drain()
        assertTrue(h.controller.visible(h.search).tiles.isEmpty()); assertTrue(h.pictures.single().released)
    }

    @Test fun revisionChangeAndDeletionDuringDecodeCannotBackfillOldCard(): Unit = runBlocking {
        for (delete in listOf(false, true)) Harness().use { h ->
            h.onDecode = {
                if (delete) h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", h.ids.single())
                else h.f.append(h.ids.single())
            }
            h.enter(h.ids); h.drain()
            assertNull(h.controller.state.value.tiles[h.ids.single()]?.picture)
            assertTrue(h.pictures.single().released); assertEquals(1, h.reads.size)
        }
    }

    @Test fun attachmentProofChangeAfterDecodeRejectsPictureEvenWithUnchangedUiSource(): Unit = Harness().use { h ->
        h.onDecode = { h.f.db.write("UPDATE attachments SET state='CORRUPT' WHERE id=?", h.rows.keys.first()) }
        h.enter(h.ids); h.drain()
        assertNull(h.controller.state.value.tiles[h.ids.single()]?.picture)
        assertTrue(h.pictures.single().released); assertEquals(1, h.reads.size)
    }

    @Test fun offscreenAndClosedSameSearchRequestsCannotReceiveOldRead(): Unit = runBlocking {
        for (reenter in listOf(false, true)) Harness().use { h ->
            h.onDecode = {
                h.controller.clear(h.search)
                if (reenter) { h.onDecode = {}; h.enter(h.ids) }
            }
            h.enter(h.ids); h.drain()
            assertTrue(h.pictures.first().released)
            if (reenter) {
                assertEquals(2, h.reads.size); assertSame(h.pictures.last(), h.controller.state.value.tiles[h.ids.single()]?.picture)
            } else assertTrue(h.controller.state.value.tiles.isEmpty())
        }
        Harness().use { h ->
            h.onDecode = { h.enter(emptyList()) }
            h.enter(h.ids); h.drain()
            assertTrue(h.controller.state.value.tiles.isEmpty()); assertTrue(h.pictures.single().released)
        }
    }

    @Test fun invalidAllocationIsRejectedReleasedAndDoesNotBlockNextCard(): Unit = Harness(2).use { h ->
        h.makePicture = { Picture(allocation = CurrentImageGridController.MAX_TILE_BYTES + 1) }
        h.enter(h.ids); h.drain()
        assertEquals(2, h.decodes); assertTrue(h.pictures.all { it.released })
        assertTrue(h.controller.state.value.tiles.values.all { it.failed })
        assertTrue(h.delivered.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun readAndMetadataFailuresRemainExplicitWithoutRepeatedIo(): Unit = runBlocking {
        for (metadata in listOf(false, true)) Harness().use { h ->
            if (metadata) h.failList = true else h.failRead = true
            h.enter(h.ids); h.drain(); repeat(5) { h.enter(h.ids); h.drain() }
            assertTrue(h.controller.state.value.tiles.values.single().failed)
            assertEquals(0, h.decodes); assertEquals(if (metadata) 0 else 1, h.reads.size)
        }
    }

    @Test fun payloadHashMismatchIsWipedBeforeDecodeAndNoFallbackImageIsUsed(): Unit = Harness().use { h ->
        h.payload = h.payload + 1.toByte()
        h.enter(h.ids); h.drain()
        assertTrue(h.controller.state.value.tiles.values.single().failed); assertEquals(0, h.decodes)
        assertTrue(h.delivered.single().all { it == 0.toByte() })
    }

    @Test fun disposedOrUnprovenOrNonImageSearchNeverReadsAttachments(): Unit = Harness().use { h ->
        val unproven = OriginalSearchState(h.search.input, OriginalSearchStage.READY, h.search.hits,
            entries = h.search.entries, owner = h.search.owner, indexEpoch = h.search.indexEpoch)
        h.controller.sync(unproven, h.ids) { true }; assertTrue(h.queue.isEmpty())
        h.controller.sync(OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.READY), h.ids) { true }
        assertTrue(h.queue.isEmpty())
        h.enter(h.ids); h.controller.dispose(); h.drain(); h.enter(h.ids)
        assertTrue(h.reads.isEmpty()); assertTrue(h.queue.isEmpty()); assertTrue(h.controller.state.value.tiles.isEmpty())
    }

    private class Picture(val allocation: Long = CurrentImageGridController.MAX_TILE_BYTES) : AttachmentPicture {
        override val width = 256
        override val height = 256
        override val allocationBytes get() = allocation
        var released = false
        override fun releaseUnpublished() { check(!released); released = true }
    }

    private class Harness(count: Int = 1, attachmentState: String = "READY") : AutoCloseable {
        val f = RecordEventDateFixture()
        val ids = (1..count).map { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val rows = linkedMapOf<String, LocalAttachment>()
        var payload = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1)
        val reads = mutableListOf<String>()
        val delivered = mutableListOf<ByteArray>()
        val pictures = mutableListOf<Picture>()
        var active = true
        var failList = false
        var failRead = false
        var decodes = 0
        var maxQueued = 0
        var onDecode: () -> Unit = {}
        var makePicture: () -> Picture = { Picture() }
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val search: OriginalSearchState
        var local: LocalRecordUiState
        val controller: CurrentImageGridController
        private val attachments = object : AttachmentStore {
            override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment = error("read only")
            override suspend fun recover() = error("grid must not initiate writes/recovery")
            override suspend fun list(entryId: String, revision: Int): List<LocalAttachment> {
                if (failList) error("synthetic metadata failure")
                return rows.values.filter { it.intent.entryId == entryId && it.intent.revision == revision }
            }
            override suspend fun read(id: String): ByteArray {
                reads += id; if (failRead) throw AttachmentIntegrityFailure()
                return payload.copyOf().also { delivered += it }
            }
        }
        private val repository = object : EntryRepository {
            override val attachments get() = this@Harness.attachments
            override suspend fun entries() = f.search.candidates(LocalSearchRequest("")).entries
            override suspend fun readDraft(): TextDraft? = error("read only")
            override suspend fun updateDraft(text: String): TextDraft = error("read only")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("read only")
            override suspend fun revisions(entryId: String): List<OriginalRevision> = error("current versions only")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("read only")
        }
        init {
            ids.forEachIndexed { index, id ->
                f.entry(id, if (index == 0) "" else "合成图片原文")
                // Two same-version pictures; only the stable smaller ID may be read.
                for (extra in 0..1) {
                    val image = "10000000-0000-4000-8000-${(index * 2 + extra).toString().padStart(12, '0')}"
                    val item = LocalAttachment(AttachmentIntent(image, id, 1, "image/png", LocalAttachmentController.hash(payload), payload.size.toLong()), AttachmentState.valueOf(attachmentState))
                    rows[image] = item
                    f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,?,?)", image, id, item.intent.sha256, item.intent.byteSize, attachmentState)
                }
            }
            f.drain()
            val entries = f.search.candidates(LocalSearchRequest("")).entries
            val input = OriginalSearchInput(onlyImages = true)
            val result = runBlocking { LocalOriginalSearchMatcher.indexed(entries, input, f.search) }
            check(result.stage == OriginalSearchStage.READY)
            search = OriginalSearchState(input, result.stage, result.hits, entries = entries, proof = result.proof,
                owner = f.search, indexEpoch = f.search.epoch())
            local = LocalRecordUiState(ready = true, opening = false, entries = entries, localSearch = f.search, localSearchEpoch = search.indexEpoch)
            controller = CurrentImageGridController({ queue.addLast(it); maxQueued = maxOf(maxQueued, queue.size); true }, {
                decodes++; onDecode(); makePicture().also { pictures += it }
            }, { local })
        }
        fun enter(visible: List<String>) = controller.sync(search, visible) { active }
        fun drain() = runBlocking {
            var steps = 0
            while (queue.isNotEmpty()) { check(++steps <= 30) { "Unbounded grid IO retry" }; queue.removeFirst()(repository) }
        }
        override fun close() { controller.dispose(); f.close() }
    }
}
