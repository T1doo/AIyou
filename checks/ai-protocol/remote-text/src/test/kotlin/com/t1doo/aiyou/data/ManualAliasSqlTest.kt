package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual AEAD/SQL/FTS/private publication proofs, with synthetic record-local declarations only. */
class ManualAliasSqlTest {
    private fun declare(f: ManualPeopleFixture, id: String, alias: String = "阿林", name: String = "小林") {
        val before = f.people.read(id)
        f.people.replace(ManualPeopleMutation(before, listOf(name), uuid(), listOf(ManualPersonAlias(alias, name))))
    }
    private fun search(f: ManualPeopleFixture, query: String, exact: Boolean = false) = runBlocking {
        LocalOriginalSearchMatcher.indexed(f.search.candidates(LocalSearchRequest("")).entries,
            OriginalSearchInput(query, mode = if (exact) OriginalSearchMode.EXACT else OriginalSearchMode.KEYWORDS,
                peopleSnapshot = f.people.snapshots(), tagSnapshot = f.base.tags.snapshots()), f.search)
    }

    @Test fun indexedRecallAddsOnlyDeclaringRecordAndKeepsLiteralOriginalPriorityAndExactMode() {
        ManualPeopleFixture().use { f ->
            val own = f.base.entry(text = "与朋友喝咖啡"); declare(f, own)
            val other = f.base.entry(text = "另一条咖啡原文"); f.people.replace(f.mutation(other, listOf("小林")))
            val literal = f.base.entry(text = "阿林的原文"); f.base.db.write("UPDATE entries SET capturedAtMillis=0 WHERE id=?", literal)
            f.base.drain(); var matches = 0
            f.base.db.beforeSql = { if (it.contains(" MATCH ?")) matches++ }
            val found = search(f, "阿林"); assertTrue(matches > 0)
            assertEquals(listOf(literal, own), found.hits.map { it.entry.id })
            assertTrue(found.hits.first().originalMatchesQuery)
            val assisted = found.hits.last(); assertFalse(assisted.originalMatchesQuery)
            assertEquals(listOf(ManualPersonAlias("阿林", "小林")), assisted.aliasMatches)
            assertEquals(0, assisted.matchStartCodePoint); assertEquals(0, assisted.matchEndCodePoint)
            assertEquals("与朋友喝咖啡", assisted.quote)
            assertEquals(listOf(own), search(f, "小林").hits.map { it.entry.id })
            assertEquals(listOf(literal), search(f, "阿林", true).hits.map { it.entry.id })
            assertTrue(search(f, "小林", true).hits.isEmpty())
        }
    }

    @Test fun replaceRevokeColdReadAndForgedBeforeCannotLoseAliasesOrReuseOldPublication() {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(); declare(f, id); f.base.drain()
            val old = search(f, "阿林"); val before = f.people.read(id)
            val forged = ManualPeopleSnapshot(id, before.version, before.names, before.sourceRevision, before.sourceHash, before.generation)
            assertThrows(ManualPeopleConflict::class.java) { f.people.replace(ManualPeopleMutation(forged, listOf("小林", "新称呼"), uuid())) }
            val changed = ManualPeopleMutation(before, before.names, uuid(), listOf(ManualPersonAlias("林同学", "小林")))
            f.people.replace(changed); f.reopen(); assertEquals(changed.aliases, f.people.replace(changed).aliases)
            assertFalse(f.search.resolve(old.proof!!, id) { fail("Old alias opened") })
            assertTrue(search(f, "阿林").hits.isEmpty()); assertEquals(id, search(f, "林同学").hits.single().entry.id)
            val added = f.people.replace(f.mutation(id, listOf("小林", "新称呼")))
            assertEquals(changed.aliases, added.aliases)
            val revoked = f.people.replace(ManualPeopleMutation(added, added.names, uuid(), emptyList()))
            assertTrue(revoked.aliases.isEmpty()); assertEquals(added.names, revoked.names)
            assertTrue(search(f, "林同学").hits.isEmpty()); f.reopen(); assertTrue(f.people.read(id).aliases.isEmpty())
        }
    }

    @Test fun editTrashRestoreAndPermanentEraseRetireProofWithoutExpandingOtherRecords() = runBlocking {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(); val other = f.base.entry(); declare(f, id); declare(f, other, "别的别名", "小林")
            f.base.drain(); val old = search(f, "阿林"); val intent = f.mutation(id, emptyList())
            f.base.append(id); assertFalse(f.search.publishCurrent(old.proof!!) {})
            assertThrows(ManualPeopleConflict::class.java) { f.people.replace(intent) }
            val current = search(f, "阿林"); assertEquals(2, current.hits.single().entry.currentRevision)
            val bin = SqlRecycleBinRepository(f.base.gateway, f.base.tags)
            val live = bin.read(id)
            val trash = bin.apply(RecycleMutation(SourceVersion(id, 2), live.generation, false, uuid()))
            assertTrue(search(f, "阿林").hits.isEmpty()); assertFalse(f.search.resolve(current.proof!!, id) {})
            bin.apply(RecycleMutation(SourceVersion(id, 2), trash.generation, true, uuid()))
            assertEquals(id, search(f, "阿林").hits.single().entry.id)
            assertFalse(f.search.publishCurrent(current.proof!!) {})
            assertEquals(other, search(f, "别的别名").hits.single().entry.id)
        }
        PermanentErasureFixture(ManualPeopleFixture.KEY).use { f ->
            f.db.transaction { sql -> ManualPeopleSqlSchema.migration11To12.forEach { sql.execute(it) } }
            val people = SqlManualPeopleRepository(f.gateway, ManualPeopleFixture.KEY)
            try {
                val id = f.entry(); val other = f.entry()
                for (record in listOf(id, other)) people.replace(ManualPeopleMutation(people.read(record), listOf("小林"), uuid(), listOf(ManualPersonAlias("阿林", "小林"))))
                assertTrue(f.erase.erase(f.intent(id)).complete)
                assertTrue(f.db.read("SELECT entryId FROM entry_manual_people WHERE entryId=?", id).isEmpty())
                assertEquals(listOf(ManualPersonAlias("阿林", "小林")), people.read(other).aliases)
            } finally { people.wipeKey() }
        }
    }

    @Test fun wholeNamesWithSpacesUnicodeAndMissingIndexUseCurrentAuthenticatedRelations() {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(text = "只有咖啡原文"); declare(f, id, "Dr Lin", "小林")
            f.base.drain(); assertEquals(id, search(f, "dr LIN").hits.single().entry.id)
            assertTrue(search(f, "dr", true).hits.isEmpty())
            f.base.db.write("DELETE FROM local_search_fts WHERE entryId=?", id)
            assertEquals(id, search(f, "Dr Lin").hits.single().entry.id)
            declare(f, id, "🙂", "小林"); assertEquals(id, search(f, "🙂 咖啡").hits.single().entry.id)
            assertTrue(search(f, "🙂", true).hits.isEmpty())
            declare(f, id, "' OR 1=1 --", "小林")
            assertEquals(id, search(f, "' OR 1=1 --").hits.single().entry.id)
            assertTrue(search(f, "不存在的画面内容").hits.isEmpty())
        }
    }

    @Test fun mixedAliasHitsNeverEnterOriginalReferenceOrAutomaticEvidenceBudget() {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(text = "咖啡原文"); declare(f, id); f.base.drain()
            for (query in listOf("阿林", "阿林 咖啡", "小林")) {
                val result = search(f, query); val hit = result.hits.single()
                assertFalse(hit.originalMatchesQuery); assertEquals(0, hit.matchStartCodePoint); assertEquals(0, hit.matchEndCodePoint)
                assertThrows(IllegalArgumentException::class.java) { localEvidenceReference(hit) }
                val state = OriginalSearchState(OriginalSearchInput(query), result.stage, result.hits,
                    entries = result.proof!!.entries, proof = result.proof, owner = f.search)
                assertEquals(LocalEvidenceStage.EMPTY, localEvidenceCandidates(state, 1).stage)
            }
        }
    }

    @Test fun candidateLimitRejectsWholeScopeWhileFavoriteFilterIsAppliedBeforeAliasExpansion() {
        ManualPeopleFixture().use { f ->
            val cipher = ManualTagCipher(ManualPeopleFixture.KEY, ManualLabelPurpose.PEOPLE)
            try { f.base.db.transaction { sql ->
                repeat(513) { index ->
                    val id = "synthetic-alias-" + index; val mutation = "synthetic-declaration"
                    sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,1,'UTC','LOCAL_ONLY')", listOf(id))
                    sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,1)", listOf(id, "咖啡", OriginalExports.hash("咖啡")))
                    sql.execute("INSERT INTO entry_manual_people VALUES(?,1,?,?)", listOf(id, mutation,
                        cipher.sealPeople(id, 1, mutation, listOf("小林"), listOf(ManualPersonAlias("阿林", "小林")))))
                }
                sql.execute("UPDATE entries SET isFavorite=1,favoriteVersion=1,lastFavoriteMutationId='synthetic-favorite',lastFavoriteSourceRevision=1 WHERE id='synthetic-alias-0'")
            } } finally { cipher.wipe() }
            assertThrows(LocalSearchTooBroad::class.java) { f.search.candidates(LocalSearchRequest("阿林")) }
            val only = f.search.candidates(LocalSearchRequest("阿林", onlyFavorites = true))
            assertEquals(listOf("synthetic-alias-0"), only.entries.map { it.id })
        }
    }

    @Test fun legacyPayloadAndSourcePurposeRemainCompatibleWhileAliasesAreBoundedEncryptedAndImmutable() {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(); f.people.replace(f.mutation(id, listOf("小林")))
            assertTrue(f.people.read(id).aliases.isEmpty()); declare(f, id, "synthetic-private-alias", "小林")
            val snapshot = f.people.read(id); val raw = f.rows().single()[3] as ByteArray
            assertTrue(raw.size <= 8192); assertFalse(raw.toString(Charsets.UTF_8).contains("synthetic-private-alias"))
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.aliases as MutableList).clear() }
            assertFalse(snapshot.aliases.single().toString().contains("synthetic-private-alias"))
            for (aliases in listOf(listOf(ManualPersonAlias("阿林", "未关联")),
                listOf(ManualPersonAlias("阿林", "小林"), ManualPersonAlias("阿林", "小林")),
                List(13) { ManualPersonAlias("称呼" + it, "小林") }))
                assertThrows(IllegalArgumentException::class.java) { ManualPeopleMutation(snapshot, snapshot.names, uuid(), aliases) }
            assertThrows(IllegalArgumentException::class.java) { ManualPeopleMutation(snapshot, listOf("小林", "阿林"), uuid(), listOf(ManualPersonAlias("阿林", "小林"))) }
            val removed = f.people.replace(f.mutation(id, emptyList())); assertTrue(removed.aliases.isEmpty())
            assertTrue(removed.names.isEmpty()); assertEquals(12L, f.base.db.read("PRAGMA user_version").single().single())
        }
    }

    @Test fun corruptAliasIsExplicitFailureAndDoesNotChangeExactOriginalLookup() {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(text = "咖啡原文"); declare(f, id); f.base.drain()
            f.base.db.write("UPDATE entry_manual_people SET version=version+1,lastMutationId='synthetic-corrupt',ciphertext=? WHERE entryId=?", ByteArray(40), id)
            val entries = f.search.candidates(LocalSearchRequest("", exact = true)).entries
            val failed = runBlocking { LocalOriginalSearchMatcher.indexed(entries, OriginalSearchInput("阿林"), f.search) }
            assertEquals(OriginalSearchStage.FAILED, failed.stage); assertTrue(failed.hits.isEmpty())
            val exact = runBlocking { LocalOriginalSearchMatcher.indexed(entries, OriginalSearchInput("咖啡", mode = OriginalSearchMode.EXACT), f.search) }
            assertEquals(id, exact.hits.single().entry.id); assertTrue(exact.hits.single().originalMatchesQuery)
        }
    }

    @Test fun dateScopeIsAppliedBeforeMetadataCapAndUnrelatedCiphertextIsNotReadAsAnotherRecord() = runBlocking {
        ManualPeopleFixture().use { f ->
            val id = f.base.entry(text = "咖啡目标原文"); declare(f, id); f.base.drain()
            f.base.db.transaction { sql ->
                // 4096 other-day rows are deliberately unreadable: none belongs to this lookup scope.
                sql.execute("WITH d(x) AS (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15)) " +
                    "INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) " +
                    "SELECT 'outside-day-'||(a.x+16*b.x+256*c.x),1,86400000,'UTC','LOCAL_ONLY' FROM d a CROSS JOIN d b CROSS JOIN d c")
                sql.execute("INSERT INTO entry_revisions SELECT id,1,?,?,86400000 FROM entries WHERE id LIKE 'outside-day-%'",
                    listOf("无关正文", OriginalExports.hash("无关正文")))
                sql.execute("INSERT INTO entry_manual_people SELECT id,1,'outside-scope',zeroblob(40) FROM entries WHERE id LIKE 'outside-day-%'")
            }
            assertEquals(4097L, f.base.db.read("SELECT count(*) FROM entry_manual_people").single().single())
            val input = OriginalSearchInput("阿林", from = "1970-01-01", through = "1970-01-01")
            val entries = f.search.candidates(LocalSearchRequest("", exact = true, from = input.from, through = input.through)).entries
            val aliases = LocalOriginalSearchMatcher.indexed(entries, input, f.search)
            assertEquals(OriginalSearchStage.READY, aliases.stage); assertEquals(id, aliases.hits.single().entry.id)
            assertFalse(aliases.hits.single().originalMatchesQuery)
            val body = LocalOriginalSearchMatcher.indexed(entries, input.copy(query = "咖啡"), f.search)
            assertEquals(id, body.hits.single().entry.id); assertTrue(body.hits.single().originalMatchesQuery)
        }
    }

    @Test fun fallbackRejectsForeignPeopleSnapshotEvenWhenPlacedUnderTheSelectedRecordKey() = runBlocking {
        ManualPeopleFixture().use { f ->
            val own = f.base.entry(); val other = f.base.entry(text = "别的记录正文"); declare(f, own); declare(f, other)
            val entries = f.search.candidates(LocalSearchRequest("")).entries
            // Both current rows have identical versions/names/aliases. Only the substituted entryId is wrong.
            val input = OriginalSearchInput("阿林", peopleSnapshot = mapOf(own to f.people.read(own), other to f.people.read(own)))
            val fallback = LocalOriginalSearchMatcher.match(entries.filter { it.id == other }, input)
            assertEquals(OriginalSearchStage.FAILED, fallback.stage); assertTrue(fallback.hits.isEmpty())
            val indexed = LocalOriginalSearchMatcher.indexed(entries, input, f.search)
            assertEquals(OriginalSearchStage.WAITING, indexed.stage); assertTrue(indexed.hits.isEmpty())
        }
    }
    companion object { private fun uuid() = UUID.randomUUID().toString() }
}
