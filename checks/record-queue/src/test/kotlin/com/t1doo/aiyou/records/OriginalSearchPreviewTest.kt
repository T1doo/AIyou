package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OriginalSearchPreviewTest {
    @Test fun tagAssistedKeywordRecallNeverInventsBodyHighlightAndBodyOnlyMatchKeepsItsOriginalRange() = runBlocking {
        val record = entry("咖啡原文")
        val tags = mapOf(record.id to com.t1doo.aiyou.model.ManualTagSnapshot(record.id, 1, listOf("旅行")))
        for (query in listOf("旅行", "咖啡 旅行")) {
            val hit = LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput(query, tagSnapshot = tags)).hits.single()
            assertFalse(hit.originalMatchesQuery)
            val preview = requireNotNull(originalSearchPreview(record, hit))
            assertEquals(record.originalText, preview.text)
            assertEquals(0, preview.highlightStart); assertEquals(0, preview.highlightEnd)
        }
        val body = LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput("咖啡", tagSnapshot = tags)).hits.single()
        assertTrue(body.originalMatchesQuery); assertTrue(body.tagMatches.isEmpty())
        val preview = requireNotNull(originalSearchPreview(record, body))
        assertEquals("咖啡", preview.text.substring(preview.highlightStart, preview.highlightEnd))
        assertTrue(LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput("旅行", mode = OriginalSearchMode.EXACT, tagSnapshot = tags)).hits.isEmpty())
    }
    private fun entry(text: String) = StoredEntry("synthetic", 1, text, 0L, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private fun hit(entry: StoredEntry, query: String, exact: Boolean = false) = runBlocking {
        LocalOriginalSearchMatcher.match(listOf(entry), OriginalSearchInput(query,
            mode = if (exact) OriginalSearchMode.EXACT else OriginalSearchMode.KEYWORDS)).hits.single()
    }

    @Test fun foldedMatchHighlightsOnlyOriginalCodePointsAndNeverNormalizesOrStoresText() {
        val matched = "İ𐐀🙂MiXeD"
        val record = entry("前🙂".repeat(150) + matched + " e\u0301\t后文\n" + "叶".repeat(100))
        val found = hit(record, "i𐐨🙂mixed")
        val preview = requireNotNull(originalSearchPreview(record, found))
        assertTrue(preview.text.startsWith("…" + matched + " e\u0301\t后文\n"))
        assertTrue(preview.text.endsWith("…")); assertEquals(82, preview.text.codePointCount(0, preview.text.length))
        assertEquals(1, preview.highlightStart)
        assertEquals(matched, preview.text.substring(preview.highlightStart, preview.highlightEnd))
        assertEquals("OriginalSearchPreview(redacted)", preview.toString())
        assertEquals(300, found.matchStartCodePoint)
        assertEquals(ProcessingPolicy.LOCAL_ONLY, record.policy)
    }

    @Test fun longExactMatchClipsAtCodePointBudgetWithoutHighlightingOmissionMarks() {
        val phrase = "🙂e\u0301".repeat(100)
        val record = entry("前文" + phrase + "后文")
        val found = hit(record, phrase, exact = true)
        val preview = requireNotNull(originalSearchPreview(record, found))
        assertEquals("…", preview.text.take(1)); assertEquals("…", preview.text.takeLast(1))
        assertEquals(82, preview.text.codePointCount(0, preview.text.length))
        val marked = preview.text.substring(preview.highlightStart, preview.highlightEnd)
        assertEquals(phrase.substring(0, phrase.offsetByCodePoints(0, 80)), marked)
        assertFalse(marked.last().isHighSurrogate()); assertEquals(preview.text.length - 1, preview.highlightEnd)
        assertEquals(phrase, record.originalText.substring(2, record.originalText.length - 2))
    }

    @Test fun finalSupplementaryMatchAndLeadingWhitespaceUseActualUtf16Boundaries() {
        val record = entry("前文\n\t🙂𐐀")
        val found = hit(record, "\n\t🙂𐐀", exact = true)
        val preview = requireNotNull(originalSearchPreview(record, found))
        assertEquals("…🙂𐐀", preview.text)
        assertEquals(1, preview.highlightStart); assertEquals(5, preview.highlightEnd)
        assertEquals("🙂𐐀", preview.text.substring(preview.highlightStart, preview.highlightEnd))
        assertEquals("前文\n\t🙂𐐀", record.originalText)
    }

    @Test fun filterOnlyAndWhitespaceOnlyMatchesNeverInventAHighlightedWord() {
        val record = entry("前文\n\t后文")
        val filterOnly = runBlocking { LocalOriginalSearchMatcher.match(listOf(record),
            OriginalSearchInput(from = "1970-01-01")).hits.single() }
        for (found in listOf(filterOnly, hit(record, "\n\t", exact = true))) {
            val preview = requireNotNull(originalSearchPreview(record, found))
            assertEquals(record.originalText, preview.text)
            assertEquals(0, preview.highlightStart); assertEquals(0, preview.highlightEnd)
        }
    }

    @Test fun foreignSnapshotChangedRevisionAndInvalidQuoteOrRangesFailClosed() {
        val record = entry("前文🙂needle后文")
        val found = hit(record, "needle")
        for (current in listOf(record.copy(), record.copy(currentRevision = 2), record.copy(originalText = "新文needle"),
            record.copy(id = "other"))) assertNull(originalSearchPreview(current, found))
        fun altered(quote: String = found.quote, startCodePoint: Int = found.startCodePoint,
            endCodePoint: Int = found.endCodePoint, leading: Boolean = found.leading,
            trailing: Boolean = found.trailing, matchStartCodePoint: Int = found.matchStartCodePoint,
            matchEndCodePoint: Int = found.matchEndCodePoint) = OriginalSearchHit(record, quote,
                startCodePoint, endCodePoint, leading, trailing, matchStartCodePoint, matchEndCodePoint)
        val invalid = listOf(altered(quote = "伪造正文"), altered(startCodePoint = -1),
            altered(endCodePoint = Int.MAX_VALUE), altered(leading = true),
            altered(trailing = true), altered(matchStartCodePoint = -1),
            altered(matchEndCodePoint = Int.MAX_VALUE), altered(matchEndCodePoint = found.matchStartCodePoint))
        invalid.forEach { assertNull(originalSearchPreview(record, it)) }
    }
}
