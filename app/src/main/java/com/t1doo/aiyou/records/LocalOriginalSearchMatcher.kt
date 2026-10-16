package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.RecordDateBasis
import com.t1doo.aiyou.model.RecordEventDateSnapshot
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
    internal val tagSnapshot: Map<String, com.t1doo.aiyou.model.ManualTagSnapshot>? = null,
    val onlyFavorites: Boolean = false,
    internal val favoriteSnapshot: Map<String, com.t1doo.aiyou.model.RecordFavoriteSnapshot>? = null,
    val dateBasis: RecordDateBasis = RecordDateBasis.CAPTURED,
    internal val eventDateSnapshot: Map<String, RecordEventDateSnapshot>? = null) {
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
    val startCodePoint: Int, val endCodePoint: Int, val leading: Boolean, val trailing: Boolean,
    val matchStartCodePoint: Int = startCodePoint, val matchEndCodePoint: Int = endCodePoint) {
    val excerpt: String get() = (if (leading) "…" else "") + quote + (if (trailing) "…" else "")
    override fun toString() = "OriginalSearchHit(redacted)"
}

internal class OriginalSearchMatches(val stage: OriginalSearchStage, val hits: List<OriginalSearchHit> = emptyList(),
    val message: String? = null, val proof: com.t1doo.aiyou.domain.LocalSearchSnapshot? = null,
    val owner: com.t1doo.aiyou.domain.LocalSearchRepository? = null)

/** Literal lookup of the current originals, without an index, normalization or AI. */
internal object LocalOriginalSearchMatcher {
    suspend fun indexed(entries: List<StoredEntry>, input: OriginalSearchInput,
        owner: com.t1doo.aiyou.domain.LocalSearchRepository): OriginalSearchMatches {
        val validation = match(emptyList(), input)
        if (validation.stage != OriginalSearchStage.READY) return validation
        return try {
            val proof = owner.candidates(com.t1doo.aiyou.domain.LocalSearchRequest(input.query,
                input.mode == OriginalSearchMode.EXACT, input.from, input.through, input.tag, input.onlyFavorites, input.dateBasis))
            if (proof.entries.any { fresh -> entries.none { it == fresh } })
                return OriginalSearchMatches(OriginalSearchStage.WAITING)
            if (input.tag != null && proof.tags.any { (id, fresh) ->
                input.tagSnapshot?.get(id)?.let { it.version == fresh.version && it.names == fresh.names } != true })
                return OriginalSearchMatches(OriginalSearchStage.WAITING)
            if (input.onlyFavorites && proof.favorites == null)
                return OriginalSearchMatches(OriginalSearchStage.FAILED, message = "收藏查找暂不可读取，请重试本机读取。")
            if (input.onlyFavorites && proof.entries.any { entry ->
                val fresh = proof.favorites?.get(entry.id)
                input.favoriteSnapshot?.get(entry.id)?.let { fresh != null && it.version == fresh.version && it.isFavorite == fresh.isFavorite } != true
            }) return OriginalSearchMatches(OriginalSearchStage.WAITING)
            if (input.dateBasis == RecordDateBasis.EVENT && proof.eventDates == null)
                return OriginalSearchMatches(OriginalSearchStage.FAILED, message = "事件日期暂不可读取，请重试本机读取。")
            if (input.dateBasis == RecordDateBasis.EVENT && proof.entries.any { entry ->
                val fresh = proof.eventDates?.get(entry.id)
                input.eventDateSnapshot?.get(entry.id)?.let { fresh != null && it.version == fresh.version && it.date == fresh.date } != true
            }) return OriginalSearchMatches(OriginalSearchStage.WAITING)
            val result = match(proof.entries, input.copy(tagSnapshot = proof.tags, favoriteSnapshot = proof.favorites,
                eventDateSnapshot = proof.eventDates))
            val whole = if (input.mode == OriginalSearchMode.KEYWORDS) fold(input.query).text else input.query
            val ranked = ArrayList<Pair<OriginalSearchHit, Boolean>>()
            for (hit in result.hits) {
                currentCoroutineContext().ensureActive()
                val body = if (input.mode == OriginalSearchMode.KEYWORDS) fold(hit.entry.originalText).text else hit.entry.originalText
                ranked += hit to (whole.isNotEmpty() && body.contains(whole))
            }
            OriginalSearchMatches(result.stage, ranked.sortedWith(compareByDescending<Pair<OriginalSearchHit, Boolean>> { it.second }
                .thenByDescending { it.first.entry.capturedAtMillis }.thenBy { it.first.entry.id }).map { it.first },
                if (proof.pending) "本机查找正在更新；未整理的记录仍按当前原文核验。" else null, proof, owner)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: com.t1doo.aiyou.domain.LocalSearchFavoriteLimit) {
            OriginalSearchMatches(OriginalSearchStage.FAILED, message = "收藏读取范围过大，无法确认完整结果。可以先切换全部记录浏览。")
        } catch (_: com.t1doo.aiyou.domain.LocalSearchTooBroad) {
            OriginalSearchMatches(OriginalSearchStage.FAILED, message = "查找范围过大，请缩短关键词或收窄日期、手工标签后重试。")
        } catch (_: com.t1doo.aiyou.domain.LocalSearchInvalid) {
            OriginalSearchMatches(OriginalSearchStage.INVALID, message = "请检查查找条件。")
        } catch (_: Exception) {
            OriginalSearchMatches(OriginalSearchStage.FAILED, message = "本机查找暂不可读取，原文已保留。可以重试本机读取或重新整理查找。")
        }
    }

    suspend fun match(entries: List<StoredEntry>, input: OriginalSearchInput): OriginalSearchMatches {
        fun date(value: String): LocalDate? {
            if (value.isEmpty()) return null
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
            return LocalDate.parse(value).also { require(it.year in 1..9999) }
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
        if (input.onlyFavorites && input.favoriteSnapshot == null) return OriginalSearchMatches(OriginalSearchStage.FAILED,
            message = "收藏状态暂不可读取，请重试本机读取。")
        if (input.dateBasis == RecordDateBasis.EVENT && input.eventDateSnapshot == null)
            return OriginalSearchMatches(OriginalSearchStage.FAILED, message = "事件日期暂不可读取，请重试本机读取。")
        if (terms.isEmpty() && input.dateBasis == RecordDateBasis.CAPTURED && from == null && through == null && input.tag == null && !input.onlyFavorites) return OriginalSearchMatches(OriginalSearchStage.BROWSE)
        val needles = terms.map { compileNeedle(if (input.mode == OriginalSearchMode.KEYWORDS) fold(it).text else it) }
        val hits = ArrayList<OriginalSearchHit>()
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (input.onlyFavorites && input.favoriteSnapshot?.get(entry.id)?.isFavorite != true) continue
            if (input.tag != null && input.tag !in input.tagSnapshot?.get(entry.id)?.names.orEmpty()) continue
            if (input.dateBasis == RecordDateBasis.EVENT || from != null || through != null) {
                val captured = try {
                    if (input.dateBasis == RecordDateBasis.EVENT) {
                        val snapshot = input.eventDateSnapshot?.get(entry.id)
                            ?: return OriginalSearchMatches(OriginalSearchStage.FAILED, message = "事件日期不完整，请重试本机读取。")
                        val event = snapshot.date ?: continue
                        LocalDate.parse(event.localDate)
                    } else Instant.ofEpochMilli(entry.capturedAtMillis).atZone(ZoneId.of(entry.captureTimeZone)).toLocalDate()
                }
                catch (_: java.time.DateTimeException) { return OriginalSearchMatches(OriginalSearchStage.FAILED,
                    message = "记录日期暂不可读取，请返回原文核对。") }
                if ((from != null && captured < from) || (through != null && captured > through)) continue
            }
            val text = entry.originalText
            val haystack = if (input.mode == OriginalSearchMode.KEYWORDS && needles.isNotEmpty()) fold(text) else null
            var firstStart = Int.MAX_VALUE
            var firstEnd = 0
            var found = true
            for (needle in needles) {
                currentCoroutineContext().ensureActive()
                val index = find((haystack?.text ?: text), needle)
                if (index < 0) { found = false; break }
                val start = haystack?.originalOffsets?.get(index) ?: index
                if (start < firstStart) {
                    firstStart = start
                    firstEnd = haystack?.originalOffsets?.get(index + needle.text.length) ?: (index + needle.text.length)
                }
            }
            if (found) {
                val total = text.codePointCount(0, text.length)
                val matchStart = if (firstStart == Int.MAX_VALUE) 0 else text.codePointCount(0, firstStart)
                val start = (matchStart - 80).coerceAtLeast(0)
                val end = (start + 240).coerceAtMost(total)
                val quote = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
                val matchEnd = if (firstStart == Int.MAX_VALUE) 0 else text.codePointCount(0, firstEnd)
                hits += OriginalSearchHit(entry, quote, start, end, start > 0, end < total, matchStart, matchEnd)
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
