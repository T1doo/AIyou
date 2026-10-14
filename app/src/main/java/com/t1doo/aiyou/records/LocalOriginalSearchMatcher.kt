package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.StoredEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

enum class OriginalSearchMode { KEYWORDS, EXACT }

/** Memory only. Never include the query in diagnostics, saved state or persistence. */
data class OriginalSearchInput(val query: String = "", val from: String = "", val through: String = "",
    val mode: OriginalSearchMode = OriginalSearchMode.KEYWORDS, internal val ownership: Any? = null, val tag: String? = null,
    internal val tagSnapshot: Map<String, com.t1doo.aiyou.model.ManualTagSnapshot>? = null) {
    override fun toString() = "OriginalSearchInput(redacted)"
}

/** Text snapshots fence an edit-and-return to the same words before the next composition. */
internal class OriginalSearchInputOwnership(private val query: CharSequence, private val from: CharSequence,
    private val through: CharSequence, private val modeGeneration: Long) {
    override fun equals(other: Any?) = other is OriginalSearchInputOwnership && query === other.query &&
        from === other.from && through === other.through && modeGeneration == other.modeGeneration
    override fun hashCode() = System.identityHashCode(query) * 31 + System.identityHashCode(from) * 17 +
        System.identityHashCode(through) + modeGeneration.hashCode()
    override fun toString() = "OriginalSearchInputOwnership(redacted)"
}

enum class OriginalSearchStage { BROWSE, WAITING, RUNNING, READY, INVALID, FAILED }

class OriginalSearchHit internal constructor(val entry: StoredEntry, val quote: String,
    val startCodePoint: Int, val endCodePoint: Int, val leading: Boolean, val trailing: Boolean) {
    val excerpt: String get() = (if (leading) "…" else "") + quote + (if (trailing) "…" else "")
    override fun toString() = "OriginalSearchHit(redacted)"
}

internal class OriginalSearchMatches(val stage: OriginalSearchStage, val hits: List<OriginalSearchHit> = emptyList(),
    val message: String? = null)

/** Literal lookup of the current originals, without an index, normalization or AI. */
internal object LocalOriginalSearchMatcher {
    suspend fun match(entries: List<StoredEntry>, input: OriginalSearchInput): OriginalSearchMatches {
        fun date(value: String): LocalDate? {
            if (value.isEmpty()) return null
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
            return LocalDate.parse(value)
        }
        val from: LocalDate?
        val through: LocalDate?
        try {
            from = date(input.from); through = date(input.through)
            require(from == null || through == null || from <= through)
        } catch (_: IllegalArgumentException) {
            return OriginalSearchMatches(OriginalSearchStage.INVALID, message = "日期请用 YYYY-MM-DD，开始日期不能晚于结束日期。")
        } catch (_: java.time.DateTimeException) {
            return OriginalSearchMatches(OriginalSearchStage.INVALID, message = "日期请用 YYYY-MM-DD，开始日期不能晚于结束日期。")
        }
        if (!wellFormed(input.query)) return OriginalSearchMatches(OriginalSearchStage.INVALID,
            message = "关键词包含不完整的字符，请重新输入。")
        val terms = if (input.mode == OriginalSearchMode.EXACT) listOf(input.query).filter { it.isNotEmpty() }
            else input.query.split(Regex("[\\p{javaWhitespace}\\p{Zs}]+" )).filter { it.isNotEmpty() }
        if (input.tag != null && input.tagSnapshot == null) return OriginalSearchMatches(OriginalSearchStage.FAILED,
            message = "手工标签暂不可读取，请重试本机读取。")
        if (terms.isEmpty() && from == null && through == null && input.tag == null) return OriginalSearchMatches(OriginalSearchStage.BROWSE)
        val needles = terms.map { compileNeedle(if (input.mode == OriginalSearchMode.KEYWORDS) fold(it).text else it) }
        val hits = ArrayList<OriginalSearchHit>()
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (input.tag != null && input.tag !in input.tagSnapshot?.get(entry.id)?.names.orEmpty()) continue
            if (from != null || through != null) {
                val captured = try { Instant.ofEpochMilli(entry.capturedAtMillis).atZone(ZoneId.of(entry.captureTimeZone)).toLocalDate() }
                catch (_: java.time.DateTimeException) { return OriginalSearchMatches(OriginalSearchStage.FAILED,
                    message = "记录日期暂不可读取，请返回原文核对。") }
                if ((from != null && captured < from) || (through != null && captured > through)) continue
            }
            val text = entry.originalText
            val haystack = if (input.mode == OriginalSearchMode.KEYWORDS && needles.isNotEmpty()) fold(text) else null
            var firstStart = Int.MAX_VALUE
            var found = true
            for (needle in needles) {
                currentCoroutineContext().ensureActive()
                val index = find((haystack?.text ?: text), needle)
                if (index < 0) { found = false; break }
                val start = haystack?.originalOffsets?.get(index) ?: index
                if (start < firstStart) firstStart = start
            }
            if (found) {
                val total = text.codePointCount(0, text.length)
                val matchStart = if (firstStart == Int.MAX_VALUE) 0 else text.codePointCount(0, firstStart)
                val start = (matchStart - 80).coerceAtLeast(0)
                val end = (start + 240).coerceAtMost(total)
                val quote = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                hits += OriginalSearchHit(entry, quote, start, end, start > 0, end < total)
            }
        }
        return OriginalSearchMatches(OriginalSearchStage.READY, hits)
    }

    private data class Folded(val text: String, val originalOffsets: IntArray)
    private class Needle(val text: String, val prefix: IntArray)
    private class Checkpoint(private val context: CoroutineContext) {
        private var steps = 0
        fun step() { if (++steps >= 512) { context.ensureActive(); steps = 0 } }
    }

    // Linear literal matching with bounded cancellation checkpoints, even for repeated long text.
    private suspend fun compileNeedle(text: String): Needle {
        val checkpoint = Checkpoint(currentCoroutineContext())
        val prefix = IntArray(text.length)
        var matched = 0
        for (at in 1 until text.length) {
            checkpoint.step()
            while (matched > 0 && text[at] != text[matched]) {
                checkpoint.step()
                matched = prefix[matched - 1]
            }
            if (text[at] == text[matched]) matched++
            prefix[at] = matched
        }
        return Needle(text, prefix)
    }

    private suspend fun find(text: String, needle: Needle): Int {
        val checkpoint = Checkpoint(currentCoroutineContext())
        var matched = 0
        for (at in text.indices) {
            checkpoint.step()
            while (matched > 0 && text[at] != needle.text[matched]) {
                checkpoint.step()
                matched = needle.prefix[matched - 1]
            }
            if (text[at] == needle.text[matched]) matched++
            if (matched == needle.text.length) return at - matched + 1
        }
        return -1
    }
    private suspend fun fold(source: String): Folded {
        val out = StringBuilder(source.length)
        val offsets = ArrayList<Int>(source.length + 1)
        var at = 0
        var checkedAt = -512
        while (at < source.length) {
            if (at - checkedAt >= 512) { currentCoroutineContext().ensureActive(); checkedAt = at }
            val point = source.codePointAt(at)
            val lowered = Character.toLowerCase(Character.toUpperCase(point))
            repeat(Character.charCount(lowered)) { offsets += at }
            out.appendCodePoint(lowered)
            at += Character.charCount(point)
        }
        offsets += source.length
        return Folded(out.toString(), offsets.toIntArray())
    }

    private fun wellFormed(value: String): Boolean {
        var at = 0
        while (at < value.length) {
            val char = value[at++]
            if (Character.isHighSurrogate(char)) {
                if (at == value.length || !Character.isLowSurrogate(value[at++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }
}
