package com.t1doo.aiyou.data

/** One app-side code-point rule, independent of the SQLite Unicode tokenizer. */
internal object LocalSearchTokenizer {
    const val VERSION = 1
    /** Cached on the first IO operation, before acquiring a database transaction.
     * Bind every Character operation used below, including query whitespace, across
     * all Unicode code points. VM/OS names or a few sentinel characters cannot
     * reliably identify Unicode table updates (including Android mainline updates).
     * Contains rule metadata only, never record/query text.
     */
    val profile: String by lazy {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("AIyou local tokenizer rule 1".toByteArray(Charsets.US_ASCII))
        val bytes = ByteArray(8)
        for (raw in 0..Character.MAX_CODE_POINT) {
            val folded = fold(raw)
            for (i in 0..3) bytes[i] = (folded ushr (24 - i * 8)).toByte()
            bytes[4] = Character.getType(raw).toByte()
            bytes[5] = (if (Character.isLetterOrDigit(folded)) 1 else 0).toByte()
            bytes[6] = (if (Character.isDigit(folded)) 1 else 0).toByte()
            bytes[7] = ((if (han(folded)) 1 else 0) or
                (if (Character.isWhitespace(raw)) 2 else 0) or
                (if (Character.isSpaceChar(raw)) 4 else 0)).toByte()
            digest.update(bytes)
        }
        "v1:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun fold(point: Int) = Character.toLowerCase(Character.toUpperCase(point))
    private fun han(point: Int) = Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN
    private fun encoded(prefix: Char, points: List<Int>) =
        prefix + points.joinToString("") { Integer.toHexString(it).padStart(6, '0') }

    fun document(text: String): String {
        require(text.length <= 100_000)
        val tokens = LinkedHashSet<String>()
        val run = ArrayList<Int>()
        var isHan = false
        fun flush() {
            if (run.isEmpty()) return
            if (!isHan) {
                tokens += encoded('w', run)
                val digits = ArrayList<Int>()
                fun number() { if (digits.isNotEmpty()) { tokens += encoded('n', digits); digits.clear() } }
                for (p in run) if (Character.isDigit(p)) digits += p else number()
                number()
            }
            for (i in 1 until run.size) tokens += encoded('b', listOf(run[i - 1], run[i]))
            run.clear()
        }
        var at = 0
        while (at < text.length) {
            val p = fold(text.codePointAt(at)); at += Character.charCount(text.codePointAt(at))
            val nextHan = han(p)
            if (!Character.isLetterOrDigit(p)) { flush(); continue }
            if (run.isNotEmpty() && nextHan != isHan) flush()
            isHan = nextHan; run += p
        }
        flush()
        return tokens.joinToString(" ")
    }

    /** Null means a literal fallback, never interpolation of user MATCH syntax. */
    fun query(text: String, exact: Boolean): String? {
        if (exact) return null
        val terms = text.split(Regex("[\\p{javaWhitespace}\\p{Zs}]+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        val tokens = LinkedHashSet<String>()
        for (term in terms) {
            val run = ArrayList<Int>()
            var at = 0
            var previousHan: Boolean? = null
            while (at < term.length) {
                val raw = term.codePointAt(at); at += Character.charCount(raw)
                val p = fold(raw)
                if (!Character.isLetterOrDigit(p)) return null
                if (previousHan != null && previousHan != han(p)) {
                    if (run.size < 2) return null
                    for (i in 1 until run.size) tokens += encoded('b', listOf(run[i - 1], run[i]))
                    run.clear()
                }
                previousHan = han(p); run += p
            }
            if (run.size < 2) return null
            for (i in 1 until run.size) tokens += encoded('b', listOf(run[i - 1], run[i]))
            if (tokens.size > 64) return null
        }
        return tokens.takeIf { it.isNotEmpty() && it.size <= 64 }?.joinToString(" ") { "bodyTokens:" + it }
    }
}
