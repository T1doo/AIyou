package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class SourceMemoryInboxSqlTest {
    private fun create(f: SourceMemoryFixture, id: String, source: SourceVersion = f.source, at: Long = 100): SourceMemorySnapshot {
        val p = f.store.preview(source)
        return f.store.create(SourceMemoryCreate(id, p, SourceMemoryContent(SourceMemoryRules.reference(p, p.original),
            SourceMemoryKind.EVENT, SourceMemoryAssertion.UNCERTAIN, "合成备注"), "create-" + id, at))
    }
    @Test fun allSourcePendingPageIsReadOnlyAndKeepsOriginalTagsTasksAndExactCiphertext(): Unit = SourceMemoryFixture().use { f ->
        val a = create(f, "a"); create(f, "b", SourceVersion("other", 1), 101)
        val legacy = f.base.store.begin(LocalCandidateStart("legacy-excerpt", f.source, 102))
        f.base.store.complete(legacy, LocalExcerptRules.extract(legacy))
        val before = f.base.snapshotOld(); val bytes = f.bytes("a").copyOf()
        val page = f.store.inbox.capture()
        assertEquals(listOf("b", "a"), page.pending.map { it.id }); assertNull(page.next)
        assertTrue(page.pending.all { it.current!!.review == SourceMemoryReview.PROPOSED })
        var navigations = 0
        assertTrue(f.store.inbox.publishCurrent(page) {})
        assertTrue(f.store.inbox.resolve(page, page.pending.last()) { navigations++ })
        assertEquals(1, navigations); assertArrayEquals(bytes, f.bytes("a")); assertEquals(before, f.base.snapshotOld())
        assertEquals(a.version, f.store.memories(f.source).single().version)
    }
    @Test fun acceptanceAndCorrectionRetireTheWholePageWithoutReusingOldNavigationOrAutoAccepting(): Unit = SourceMemoryFixture().use { f ->
        val saved = create(f, "memory"); val old = f.store.inbox.capture(); var calls = 0
        val accepted = f.store.mutate(f.change(saved, SourceMemoryAction.ACCEPT))
        assertFalse(f.store.inbox.publishCurrent(old) { calls++ })
        assertFalse(f.store.inbox.resolve(old, old.pending.single()) { calls++ })
        assertTrue(f.store.inbox.capture().pending.isEmpty()); assertEquals(0, calls)
        val corrected = f.store.mutate(f.change(accepted, SourceMemoryAction.CORRECT,
            accepted.current!!.content.copy(note = "合成纠正"), "correction"))
        val fresh = f.store.inbox.capture().pending.single()
        assertEquals(corrected.version, fresh.version); assertEquals(2, fresh.current!!.revision)
        assertEquals(SourceMemoryReview.PROPOSED, fresh.current!!.review)
        assertFalse(f.store.inbox.resolve(old, fresh) { calls++ }); assertEquals(0, calls)
    }
    @Test fun creationTimeTiesPaginatePastAcceptedRowsAndNeverMisreportAnEmptyPageAsTheWholeInbox(): Unit = SourceMemoryFixture().use { f ->
        repeat(20) { n -> val item = create(f, "p" + n.toString().padStart(2, '0'))
            f.store.mutate(f.change(item, SourceMemoryAction.ACCEPT, mutation = "accept-" + n)) }
        create(f, "z-pending", SourceVersion("other", 1))
        val first = f.store.inbox.capture(); assertTrue(first.pending.isEmpty()); assertNotNull(first.next)
        assertEquals(SourceMemoryInboxCursor(100, "p19"), first.next)
        f.reopen()
        val second = f.store.inbox.capture(first.next)
        assertEquals(listOf("z-pending"), second.pending.map { it.id }); assertNull(second.next)
        assertFalse(f.store.inbox.publishCurrent(first) {})
        assertEquals(listOf("z-pending"), f.store.inbox.capture(second.cursor).pending.map { it.id })
    }
    @Test fun sourcePolicyAbaTrashAndPermanentErasureFencesExcludeCandidatesBeforePrivateReads(): Unit = SourceMemoryFixture().use { f ->
        create(f, "first"); val old = f.store.inbox.capture(); var opened = false
        f.db.transaction { sql -> sql.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'")
            sql.execute("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id='source'") }
        assertTrue(f.store.inbox.capture().pending.isEmpty())
        assertFalse(f.store.inbox.resolve(old, old.pending.single()) { opened = true }); assertFalse(opened)
        create(f, "fresh")
        f.db.transaction { it.execute("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId='source'") }
        assertTrue(f.store.inbox.capture().pending.isEmpty())
        f.db.transaction { it.execute("UPDATE remote_source_access SET deleted=0,generation=generation+1 WHERE entryId='source'") }
        assertTrue(f.store.inbox.capture().pending.isEmpty())
        val restored = create(f, "after-restore")
        f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',2,?,'erase','PREPARED','','')", listOf(restored.generation)) }
        assertTrue(f.store.inbox.capture().pending.isEmpty())
    }
    @Test fun authenticatedNonPendingRowsCannotHideCorruptionAsAnEmptySuccess(): Unit = SourceMemoryFixture().use { f ->
        val item = create(f, "memory"); f.store.mutate(f.change(item, SourceMemoryAction.ACCEPT))
        f.db.transaction { it.execute("UPDATE local_candidate_batches SET version=version+1,payload=X'0000' WHERE id='memory'") }
        assertThrows(SourceMemoryStorageFailure::class.java) { f.store.inbox.capture() }
    }
    @Test fun foreignOrFabricatedProofAndEqualButUnownedItemsCannotNavigate(): Unit = SourceMemoryFixture().use { f ->
        create(f, "memory"); val page = f.store.inbox.capture(); val other = f.store.inbox.capture(); var calls = 0
        assertFalse(f.store.inbox.resolve(page, other.pending.single()) { calls++ })
        val forged = object : SourceMemoryInboxSnapshot {
            override val cursor = page.cursor; override val next = page.next; override val pending = page.pending
        }
        assertFalse(f.store.inbox.publishCurrent(forged) { calls++ })
        f.reopen(); assertFalse(f.store.inbox.resolve(page, page.pending.single()) { calls++ }); assertEquals(0, calls)
    }
}
