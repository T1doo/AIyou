package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Fixed-depth, bounded JSON subset for the versioned excerpt contract; no provider diagnostics. */
object ExcerptResponseParser {
    fun parse(text: String): ExcerptDocument = try { Reader(text).read() }
        catch (_: Exception) { throw InvalidExcerptResponse() }

    private class Reader(private val text: String) {
        private var at = 0
        init { require(text.length <= LocalCandidates.MAX_RESPONSE_CHARS && LocalCandidates.validUnicode(text)) }
        fun read(): ExcerptDocument {
            var version: Long? = null; var rule: Long? = null; var provenance: String? = null
            var id: String? = null; var revision: Long? = null; var hash: String? = null
            var proposals: List<ExcerptProposal>? = null
            obj(setOf("schemaVersion", "ruleVersion", "provenance", "sourceId", "sourceRevision", "sourceHash", "candidates")) { key ->
                when (key) {
                    "schemaVersion" -> version = number()
                    "ruleVersion" -> rule = number()
                    "provenance" -> provenance = string(64)
                    "sourceId" -> id = string(128)
                    "sourceRevision" -> revision = number()
                    "sourceHash" -> hash = string(64)
                    "candidates" -> {
                        take('['); val values = ArrayList<ExcerptProposal>()
                        if (!peek(']')) do {
                            require(values.size < LocalCandidates.MAX_ITEMS)
                            var kind: String? = null; var start: Long? = null; var end: Long? = null; var quote: String? = null
                            obj(setOf("kind", "start", "end", "quote")) { field -> when (field) {
                                "kind" -> kind = string(32)
                                "start" -> start = number()
                                "end" -> end = number()
                                "quote" -> quote = string(LocalCandidates.MAX_EXCERPT_POINTS * 2)
                            } }
                            require(kind == "EXCERPT")
                            values.add(ExcerptProposal(Math.toIntExact(checkNotNull(start)), Math.toIntExact(checkNotNull(end)), checkNotNull(quote)))
                        } while (comma())
                        take(']'); proposals = values
                    }
                }
            }
            whitespace(); require(at == text.length && version == 1L && rule == LocalCandidates.RULE_VERSION.toLong() &&
                provenance == LocalCandidates.PROVENANCE)
            return ExcerptDocument(SourceVersion(checkNotNull(id), checkNotNull(revision)), checkNotNull(hash), checkNotNull(proposals))
        }
        private fun obj(required: Set<String>, field: (String) -> Unit) {
            take('{'); val seen = HashSet<String>()
            if (!peek('}')) do {
                val key = string(64); require(key in required && seen.add(key)); take(':'); field(key)
            } while (comma())
            take('}'); require(seen == required)
        }
        private fun number(): Long {
            whitespace(); val start = at
            require(at < text.length && text[at] in '0'..'9')
            if (text[at] == '0') at++ else while (at < text.length && text[at] in '0'..'9') at++
            return text.substring(start, at).toLong()
        }
        private fun string(limit: Int): String {
            take('"'); val out = StringBuilder()
            while (at < text.length) {
                val ch = text[at++]
                if (ch == '"') return out.toString().also { require(it.length <= limit && LocalCandidates.validUnicode(it)) }
                require(ch >= ' ')
                if (ch != '\\') out.append(ch) else {
                    require(at < text.length)
                    when (val escaped = text[at++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(at + 4 <= text.length)
                            val digits = text.substring(at, at + 4); require(digits.all { it in "0123456789abcdefABCDEF" })
                            out.append(digits.toInt(16).toChar()); at += 4
                        }
                        else -> error("Invalid escape")
                    }
                }
                require(out.length <= limit)
            }
            error("Unterminated string")
        }
        private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun peek(ch: Char): Boolean { whitespace(); return at < text.length && text[at] == ch }
        private fun take(ch: Char) { whitespace(); require(at < text.length && text[at++] == ch) }
        private fun comma(): Boolean = if (peek(',')) { at++; true } else false
    }
}
