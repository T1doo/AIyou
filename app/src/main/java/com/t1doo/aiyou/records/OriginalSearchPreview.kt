package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.StoredEntry

/** Display-only UTF-16 range in a bounded excerpt. Never a source/citation offset or saved value. */
internal data class OriginalSearchPreview(val text: String, val highlightStart: Int = 0, val highlightEnd: Int = 0) {
    override fun toString() = "OriginalSearchPreview(redacted)"
}

/** Reuse the matcher's original-code-point proof; never search, fold or normalize the display text. */
internal fun originalSearchPreview(entry: StoredEntry, hit: OriginalSearchHit): OriginalSearchPreview? {
    if (hit.entry !== entry) return null
    val source = entry.originalText
    val total = source.codePointCount(0, source.length)
    if (hit.startCodePoint !in 0..total || hit.endCodePoint !in hit.startCodePoint..total ||
        hit.endCodePoint - hit.startCodePoint > 240 ||
        hit.leading != (hit.startCodePoint > 0) || hit.trailing != (hit.endCodePoint < total)) return null
    val from = source.offsetByCodePoints(0, hit.startCodePoint)
    val through = source.offsetByCodePoints(from, hit.endCodePoint - hit.startCodePoint)
    if (through - from != hit.quote.length || !source.regionMatches(from, hit.quote, 0, hit.quote.length)) return null
    if (!hit.originalMatchesQuery) return OriginalSearchPreview(hit.excerpt)
    if (hit.matchStartCodePoint == 0 && hit.matchEndCodePoint == 0) return OriginalSearchPreview(hit.excerpt)
    if (hit.matchStartCodePoint !in hit.startCodePoint until hit.endCodePoint ||
        hit.matchEndCodePoint !in (hit.matchStartCodePoint + 1)..total) return null

    // Start at a visible part of the verified match, rather than hiding it after 80 code points
    // of context in a four-line card. Whitespace-only matches retain the unstyled original excerpt.
    var anchor = hit.matchStartCodePoint
    var offset = hit.quote.offsetByCodePoints(0, anchor - hit.startCodePoint)
    val visibleEnd = minOf(hit.matchEndCodePoint, hit.endCodePoint)
    while (anchor < visibleEnd) {
        val point = hit.quote.codePointAt(offset)
        if (!Character.isWhitespace(point) && !Character.isSpaceChar(point) && !Character.isISOControl(point)) break
        offset += Character.charCount(point)
        anchor++
    }
    if (anchor == visibleEnd) return OriginalSearchPreview(hit.excerpt)
    val end = minOf(anchor + 80, hit.endCodePoint)
    val endOffset = hit.quote.offsetByCodePoints(offset, end - anchor)
    val highlightEndOffset = hit.quote.offsetByCodePoints(offset, minOf(hit.matchEndCodePoint, end) - anchor)
    val prefix = if (anchor > 0) "…" else ""
    return OriginalSearchPreview(prefix + hit.quote.substring(offset, endOffset) + if (end < total) "…" else "",
        prefix.length, prefix.length + highlightEndOffset - offset)
}
