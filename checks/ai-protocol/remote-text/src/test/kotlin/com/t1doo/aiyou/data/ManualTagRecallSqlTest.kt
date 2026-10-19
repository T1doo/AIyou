package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual product FTS4, tag AEAD, matcher and private publication proof on host SQLite. */
class ManualTagRecallSqlTest {
    private fun tag(f: LocalSearchSqlTest.Fixture, id: String, names: List<String>) {
        val old = f.tags.read(id)
        f.tags.replace(ManualTagMutation(id, old.version, names, UUID.randomUUID().toString()))
    }
    private fun search(f: LocalSearchSqlTest.Fixture, query: String, exact: Boolean = false) = runBlocking {
        LocalOriginalSearchMatcher.indexed(f.entries(), OriginalSearchInput(query,
            mode = if (exact) OriginalSearchMode.EXACT else OriginalSearchMode.KEYWORDS,
            tagSnapshot = f.tags.snapshots()), f.index)
    }

    @Test fun realFtsRecallsConfirmedTagsAndMixedTermsWithoutInventingOriginalMatchOrChangingRank() {
        LocalSearchSqlTest.Fixture().use { f ->
            f.seed("tag", "和小林喝咖啡", 200); tag(f, "tag", listOf("旅行", "MiXeD"))
            f.seed("body", "旅行", 100); f.seed("other", "无关正文", 300); f.drain()
            var matches = 0
            f.base.db.beforeSql = { if (it.contains(" MATCH ?")) matches++ }
            val found = search(f, "旅行")
            assertEquals(listOf("body", "tag"), found.hits.map { it.entry.id }); assertTrue(matches > 0)
            assertTrue(found.hits.first().originalMatchesQuery)
            val hit = found.hits.last()
            assertFalse(hit.originalMatchesQuery); assertEquals(listOf("旅行"), hit.tagMatches)
            assertEquals(0, hit.matchStartCodePoint); assertEquals(0, hit.matchEndCodePoint)
            assertEquals("和小林喝咖啡", hit.quote)
            val mixed = search(f, "咖啡 旅行 mixed").hits.single()
            assertFalse(mixed.originalMatchesQuery); assertEquals(listOf("旅行", "MiXeD"), mixed.tagMatches)
            assertEquals(listOf("body"), search(f, "旅行", exact = true).hits.map { it.entry.id })
            assertTrue(search(f, "MiXeD", exact = true).hits.isEmpty())
            assertEquals("和小林喝咖啡", f.entries().single { it.id == "tag" }.originalText)
        }
    }

    @Test fun tagReplacementRemovalAndVisibilityAbaRetireProofAndPendingIndexUsesCurrentAuthenticatedTags() {
        LocalSearchSqlTest.Fixture().use { f ->
            f.seed("tag", "咖啡原文", 100); tag(f, "tag", listOf("旅行")); f.drain()
            val old = search(f, "旅行"); assertEquals("tag", old.hits.single().entry.id)
            tag(f, "tag", listOf("新标签"))
            assertFalse(f.index.resolve(old.proof!!, "tag") { fail("Old tag result opened") })
            assertTrue(search(f, "旅行").hits.isEmpty())
            val pending = search(f, "新标签"); assertEquals("tag", pending.hits.single().entry.id)
            assertTrue(pending.proof!!.pending)
            tag(f, "tag", emptyList()); assertTrue(search(f, "新标签").hits.isEmpty())
            assertFalse(f.index.publishCurrent(pending.proof!!) { fail("Removed tag result published") })
            tag(f, "tag", listOf("旅行")); f.drain()
            val beforeTrash = search(f, "旅行")
            val bin = SqlRecycleBinRepository(f.base.db, f.tags); val live = bin.read("tag")
            val deleted = bin.apply(RecycleMutation(SourceVersion("tag", 1), live.generation, false, "synthetic-tag-trash"))
            assertTrue(search(f, "旅行").hits.isEmpty())
            bin.apply(RecycleMutation(SourceVersion("tag", 1), deleted.generation, true, "synthetic-tag-restore"))
            assertFalse(f.index.publishCurrent(beforeTrash.proof!!) { fail("Visibility ABA reused tag proof") })
            assertEquals(ProcessingPolicy.AI_NONE, search(f, "旅行").hits.single().entry.policy)
            assertFalse(f.index.publishCurrent(old.proof!!) {})
        }
    }

    @Test fun singleCodePointPunctuationMissingIndexAndChangedRuntimeProfileKeepLiteralTagRecall() {
        LocalSearchSqlTest.Fixture().use { f ->
            f.seed("tag", "咖啡原文", 100); tag(f, "tag", listOf("旅行", "🙂", "e\u0301", ".*")); f.drain()
            for (q in listOf("旅", "🙂", "e\u0301", ".*")) assertEquals("tag", search(f, q).hits.single().entry.id)
            assertTrue(search(f, ".*", exact = true).hits.isEmpty())
            f.base.db.write("DELETE FROM local_search_fts WHERE entryId='tag'")
            assertEquals("tag", search(f, "旅行").hits.single().entry.id)
            f.drain(); val before = search(f, "旅行")
            f.reopen("v1:" + "f".repeat(64))
            val fallback = search(f, "旅行"); assertTrue(fallback.proof!!.pending)
            assertEquals("tag", fallback.hits.single().entry.id)
            assertFalse(f.index.publishCurrent(before.proof!!) {})
            f.drain(); assertEquals("tag", search(f, "旅行").hits.single().entry.id)
        }
    }

    @Test fun ngramAcrossTagsIsOnlyCandidateAndCorruptTagAeadFailsInsteadOfReturningFalseEmpty() {
        LocalSearchSqlTest.Fixture().use { f ->
            f.seed("tag", "原文没有所查短语", 100); tag(f, "tag", listOf("旅行", "行程")); f.drain()
            assertEquals("tag", f.index.candidates(LocalSearchRequest("旅行程")).entries.single().id)
            assertTrue(search(f, "旅行程").hits.isEmpty())
            val entries = f.entries()
            f.base.db.write("UPDATE entry_manual_tags SET version=version+1,lastMutationId='synthetic-corrupt',ciphertext=? WHERE entryId='tag'", byteArrayOf(1, 2, 3))
            val failed = runBlocking { LocalOriginalSearchMatcher.indexed(entries, OriginalSearchInput("旅"), f.index) }
            assertEquals(OriginalSearchStage.FAILED, failed.stage); assertTrue(failed.hits.isEmpty())
        }
    }

    @Test fun tagOnlyAndMixedRecallCannotEnterEvidenceBudgetOrBecomeAnOriginalReference() {
        LocalSearchSqlTest.Fixture().use { f ->
            f.seed("tag", "咖啡原文", 200); tag(f, "tag", listOf("旅行"))
            f.seed("body", "旅行咖啡原文", 100); f.drain()
            val input = OriginalSearchInput("旅行", tagSnapshot = f.tags.snapshots())
            val result = runBlocking { LocalOriginalSearchMatcher.indexed(f.entries(), input, f.index) }
            val state = OriginalSearchState(input, result.stage, result.hits, entries = result.proof!!.entries,
                proof = result.proof, owner = f.index)
            val packet = localEvidenceCandidates(state, 1)
            assertEquals(listOf("body"), packet.cards.map { it.reference.source.sourceId })
            assertThrows(IllegalArgumentException::class.java) { localEvidenceReference(result.hits.last()) }
            val mixed = search(f, "咖啡 旅行").hits.single { it.entry.id == "tag" }
            assertThrows(IllegalArgumentException::class.java) { localEvidenceReference(mixed) }
            val only = OriginalSearchState(input, OriginalSearchStage.READY, listOf(mixed))
            assertEquals(LocalEvidenceStage.EMPTY, localEvidenceCandidates(only, 2).stage)
        }
    }

    @Test fun currentTagRecallIntersectsCapturedEventDatesExplicitTagFavoriteAndCurrentImageFilters() = runBlocking {
        RecordEventDateFixture().use { f ->
            val image = f.entry(text = "咖啡原文"); val plain = f.entry(text = "咖啡原文")
            f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,9,'READY')", UUID.randomUUID().toString(), image, "a".repeat(64))
            for (id in listOf(image, plain)) {
                f.tags.replace(ManualTagMutation(id, 0, listOf("旅行", "筛选"), UUID.randomUUID().toString()))
                f.favorites.set(f.favorite(id, true)); f.dates.set(f.intent(id, RecordEventDate("2000-01-01", "UTC")))
            }
            f.drain()
            val entries = f.search.candidates(LocalSearchRequest("")).entries
            val input = OriginalSearchInput("旅行", from = "1970-01-01", through = "1970-01-01", tag = "筛选",
                tagSnapshot = f.tags.snapshots(), onlyFavorites = true, favoriteSnapshot = f.favorites.snapshots(), onlyImages = true)
            assertEquals(listOf(image), LocalOriginalSearchMatcher.indexed(entries, input, f.search).hits.map { it.entry.id })
            val event = input.copy(from = "2000-01-01", through = "2000-01-01", dateBasis = RecordDateBasis.EVENT, eventDateSnapshot = f.dates.snapshots())
            assertEquals(listOf(image), LocalOriginalSearchMatcher.indexed(entries, event, f.search).hits.map { it.entry.id })
            assertTrue(LocalOriginalSearchMatcher.indexed(entries, event.copy(query = "旅行", mode = OriginalSearchMode.EXACT), f.search).hits.isEmpty())
            assertTrue(LocalOriginalSearchMatcher.indexed(entries, input.copy(tag = "其他"), f.search).hits.isEmpty())
        }
    }
}
