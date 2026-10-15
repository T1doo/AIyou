package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

interface SourceTagRepository {
    fun preview(source: SourceVersion): SourceTagPreview
    fun apply(request: SourceTagRequest): ManualTagSnapshot
}
class SourceTagConflict : IllegalStateException("Source or tag preview changed")
class SourceTagStorageFailure : IllegalStateException("Source tag status unavailable")
class InvalidSourceTagFormat : IllegalArgumentException("Invalid explicit tag line")
class SourceTagLimit : IllegalStateException("Manual tag capacity exceeded")

/** Explicit tag lines only, never semantic or preference inference. Offsets use code points. */
object SourceTagRules {
    const val VERSION = 1
    private val tokenPattern = Regex("[^ \\t]+")
    fun extract(source: SourceVersion, original: String): List<SourceTagProposal> {
        require(original.length <= 100_000 && LocalCandidates.validUnicode(original))
        val found = linkedMapOf<String, SourceTagProposal>()
        var lineStart = 0
        var linePoints = 0
        while (lineStart <= original.length) {
            val lineEnd = original.indexOf('\n', lineStart).let { if (it < 0) original.length else it }
            val line = original.substring(lineStart, lineEnd).removeSuffix("\r")
            val prefix = when { line.startsWith("标签：") -> 3; line.startsWith("标签:") -> 3; else -> 0 }
            if (prefix > 0) {
                if (line.length > 2048) throw InvalidSourceTagFormat()
                val tokens = tokenPattern.findAll(line.substring(prefix)).toList()
                if (tokens.size > ManualTags.MAX_COUNT) throw InvalidSourceTagFormat()
                val parsed = tokens.mapNotNull { token ->
                    val raw = token.value
                    if (!raw.startsWith('#') || raw.length < 2) return@mapNotNull null
                    val name = raw.substring(1)
                    if (name.codePointCount(0, name.length) !in 1..ManualTags.MAX_CODE_POINTS ||
                        !name.codePoints().allMatch { cp -> Character.isLetterOrDigit(cp) || cp == 95 || cp == 45 ||
                            Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt() })
                        return@mapNotNull null
                    if (runCatching { ManualTags.canonical(listOf(name)).single() }.getOrNull() != name) return@mapNotNull null
                    val start = linePoints + line.codePointCount(0, prefix + token.range.first)
                    SourceTagProposal(name, OriginalTextReference(source, start, start + raw.codePointCount(0, raw.length), raw))
                }
                // A malformed line is not partially interpreted as a label instruction.
                if (tokens.size != parsed.size) throw InvalidSourceTagFormat()
                parsed.forEach { found.putIfAbsent(it.name, it); if (found.size > ManualTags.MAX_COUNT) throw InvalidSourceTagFormat() }
            }
            if (lineEnd == original.length) break
            linePoints += original.codePointCount(lineStart, lineEnd) + 1
            lineStart = lineEnd + 1
        }
        if (found.size > ManualTags.MAX_COUNT) throw InvalidSourceTagFormat()
        return found.values.toList()
    }
}
