package com.t1doo.aiyou.model

import java.util.Collections

/** User-authored labels, independent of original revisions and AI annotations. */
class ManualTagSnapshot(val entryId: String, val version: Long, names: List<String>) {
    val names: List<String> = Collections.unmodifiableList(ManualTags.canonical(names))
    init { ManualTags.identifier(entryId); require(version >= 0) { "Invalid tag version" } }
    override fun toString() = "ManualTagSnapshot(version=$version, redacted)"
}

class ManualTagMutation(val entryId: String, val expectedVersion: Long, names: List<String>, val mutationId: String) {
    val names: List<String> = Collections.unmodifiableList(ManualTags.canonical(names))
    init {
        ManualTags.identifier(entryId); ManualTags.identifier(mutationId)
        require(expectedVersion in 0 until Long.MAX_VALUE) { "Invalid tag version" }
    }
    override fun toString() = "ManualTagMutation(redacted)"
}

object ManualTags {
    const val MAX_COUNT = 12
    const val MAX_CODE_POINTS = 40
    fun identifier(value: String) {
        require(value.isNotBlank() && value.length <= 128 && validUnicode(value) &&
            value.none { Character.isISOControl(it) }) { "Invalid tag identity" }
    }
    /** Trim outer Unicode whitespace only; preserve case, internal whitespace and combining marks. */
    fun canonical(values: List<String>): List<String> {
        require(values.size <= MAX_COUNT) { "Too many manual tags" }
        val result = values.map { raw ->
            require(raw.length <= 160 && validUnicode(raw) && raw.none { Character.isISOControl(it) }) { "Invalid manual tag" }
            raw.trim { it.isWhitespace() || Character.isSpaceChar(it) }.also { name ->
                require(name.isNotEmpty() && name.codePointCount(0, name.length) <= MAX_CODE_POINTS &&
                    name.none { Character.isISOControl(it) } &&
                    name.codePoints().anyMatch { !Character.isWhitespace(it) && !Character.isSpaceChar(it) &&
                        Character.getType(it) != Character.FORMAT.toInt() }) { "Invalid manual tag" }
            }
        }
        require(result.distinct().size == result.size) { "Duplicate manual tag" }
        return result
    }
    private fun validUnicode(value: String): Boolean {
        var at = 0
        while (at < value.length) {
            val ch = value[at++]
            if (Character.isHighSurrogate(ch)) {
                if (at == value.length || !Character.isLowSurrogate(value[at++])) return false
            } else if (Character.isLowSurrogate(ch)) return false
        }
        return true
    }
}
