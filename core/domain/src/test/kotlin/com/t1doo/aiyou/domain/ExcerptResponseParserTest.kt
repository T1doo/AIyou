package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class ExcerptResponseParserTest {
    @Test fun validBoundedJsonPreservesExactUnicodeAndEscapedQuotation() {
        val original = "引用：\"我不喝咖啡\"🙂\n不是人格判断"
        val doc = ExcerptResponseParser.parse(json(original))
        LocalExcerptRules.validate(doc, SOURCE, OriginalExports.hash(original), original)
        assertEquals(original, doc.proposals.single().quote)
        assertEquals(original.codePointCount(0, original.length), doc.proposals.single().end)
    }
    @Test fun schemaRuleAndProvenanceMustMatchTheLocalContract() {
        val value = json("合成原文")
        invalid(value.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        invalid(value.replace("\"ruleVersion\":1", "\"ruleVersion\":0"))
        invalid(value.replace("LOCAL_RULE_EXCERPT", "REMOTE_MODEL"))
    }
    @Test fun missingUnknownAndDuplicateRootFieldsAreRejected() {
        val value = json("合成原文")
        invalid(value.replace("\"schemaVersion\":1,", ""))
        invalid(value.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,"))
        invalid(value.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schema\\u0056ersion\":1,"))
        invalid(value.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"accepted\":true,"))
    }
    @Test fun nestedDuplicateFieldsAndModelReviewStatesAreRejected() {
        val value = json("合成原文")
        invalid(value.replace("\"kind\":\"EXCERPT\"", "\"kind\":\"EXCERPT\",\"kind\":\"EXCERPT\""))
        invalid(value.replace("\"kind\":\"EXCERPT\"", "\"kind\":\"EXCERPT\",\"reviewState\":\"accepted\""))
        invalid(value.replace("\"kind\":\"EXCERPT\"", "\"kind\":\"EXECUTED_TASK\""))
        invalid(value.replace("\"kind\":\"EXCERPT\"", "\"kind\":\"STABLE_FACT\""))
    }
    @Test fun codeFencesTrailingDocumentsAndRootArraysCannotBecomeCandidates() {
        val value = json("合成原文")
        invalid("~~~json\n" + value + "\n~~~"); invalid(value + value); invalid("[" + value + "]")
        invalid("\uFEFF" + value); invalid(value + " true")
        assertNotNull(ExcerptResponseParser.parse(" \r\n" + value + "\t "))
    }
    @Test fun integerGrammarRejectsNegativeFractionExponentLeadingZeroAndOverflow() {
        val value = json("合成原文")
        listOf("-1", "+1", "1.0", "1e0", "01", "9223372036854775808").forEach {
            invalid(value.replace("\"sourceRevision\":1", "\"sourceRevision\":" + it))
        }
        invalid(value.replace("\"start\":0", "\"start\":-1"))
        invalid(value.replace("\"end\":4", "\"end\":2147483648"))
    }
    @Test fun wrongScalarNullAndCollectionTypesFailClosed() {
        val value = json("合成原文")
        invalid(value.replace("\"sourceRevision\":1", "\"sourceRevision\":\"1\""))
        invalid(value.replace("\"provenance\":\"LOCAL_RULE_EXCERPT\"", "\"provenance\":null"))
        invalid(value.replace("\"candidates\":[", "\"candidates\":{"))
        invalid(value.replace("\"quote\":\"合成原文\"", "\"quote\":false"))
        invalid(value.replace("\"quote\":\"合成原文\"", "\"quote\":\"\""))
    }
    @Test fun itemCountDuplicateProposalAndQuoteBoundsAreEnforced() {
        val item = item("合成原文")
        invalid(json("合成原文", ""))
        invalid(json("合成原文", List(9) { item }.joinToString(",")))
        invalid(json("合成原文", item + "," + item))
        invalid(json("字".repeat(321)))
        assertEquals(320, ExcerptResponseParser.parse(json("🙂".repeat(320))).proposals.single().end)
    }
    @Test fun responseAndSourceIdentityBoundsAreEnforced() {
        invalid(" ".repeat(LocalCandidates.MAX_RESPONSE_CHARS + 1))
        val value = json("合成原文")
        invalid(value.replace("\"sourceId\":\"source\"", "\"sourceId\":\"" + "x".repeat(129) + "\""))
        invalid(value.replace("\"sourceRevision\":1", "\"sourceRevision\":0"))
        invalid(value.replace(OriginalExports.hash("合成原文"), "not-a-hash"))
    }
    @Test fun rawAndEscapedLoneSurrogatesAndInvalidEscapesAreRejected() {
        val value = json("合成原文")
        invalid(value.replace("合成原文", "\uD800"))
        invalid(value.replace("合成原文", "\\uD800"))
        invalid(value.replace("合成原文", "\\uDC00"))
        invalid(value.replace("合成原文", "\\q"))
        invalid(value.replace("合成原文", "\\uZZZZ"))
        invalid(value.replace("合成原文", "合成\n原文"))
    }
    @Test fun everySourceRevisionHashRangeAndQuoteMustMatchTheActualOriginal() {
        val original = "原文🙂e\u0301"
        val hash = OriginalExports.hash(original); val doc = ExcerptResponseParser.parse(json(original))
        assertThrows(IllegalArgumentException::class.java) { LocalExcerptRules.validate(doc, SourceVersion("other", 1), hash, original) }
        assertThrows(IllegalArgumentException::class.java) { LocalExcerptRules.validate(doc, SourceVersion("source", 2), hash, original) }
        assertThrows(IllegalArgumentException::class.java) { LocalExcerptRules.validate(doc, SOURCE, OriginalExports.hash("不同"), original) }
        val foreign = ExcerptDocument(SOURCE, hash, listOf(ExcerptProposal(0, 2, "原文字")))
        assertThrows(IllegalArgumentException::class.java) { LocalExcerptRules.validate(foreign, SOURCE, hash, original) }
        val out = ExcerptDocument(SOURCE, hash, listOf(ExcerptProposal(0, 100, original)))
        assertThrows(IllegalArgumentException::class.java) { LocalExcerptRules.validate(out, SOURCE, hash, original) }
    }
    @Test fun referencesUseCodePointsWithoutNormalizationOrUtf16Offsets() {
        val original = "A🙂e\u0301中"
        val hash = OriginalExports.hash(original)
        LocalExcerptRules.validate(ExcerptDocument(SOURCE, hash, listOf(ExcerptProposal(1, 4, "🙂e\u0301"))), SOURCE, hash, original)
        assertThrows(IllegalArgumentException::class.java) {
            LocalExcerptRules.validate(ExcerptDocument(SOURCE, hash, listOf(ExcerptProposal(1, 5, "🙂e\u0301"))), SOURCE, hash, original)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalExcerptRules.validate(ExcerptDocument(SOURCE, hash, listOf(ExcerptProposal(2, 4, "é"))), SOURCE, hash, original)
        }
    }
    @Test fun conservativeRuleRetainsNegationQuotationHypothesisAndPlansLiterally() {
        listOf("我不喝咖啡", "他说“我喜欢咖啡”", "假如我喜欢咖啡", "我打算明天喝咖啡",
            "朋友喜欢咖啡", "不要据此推断我的身份", "今天难过不等于抑郁诊断", "他问我是否已经完成").forEach { original ->
            val doc = LocalExcerptRules.extract(job(original))
            assertEquals(original, doc.proposals.single().quote)
            LocalExcerptRules.validate(doc, SOURCE, OriginalExports.hash(original), original)
        }
    }
    @Test fun ruleIsBoundedAndSkipsOnlyLeadingWhitespaceWithoutInventingContent() {
        val original = " ".repeat(400) + "🙂".repeat(400)
        val doc = LocalExcerptRules.extract(job(original))
        assertEquals(400, doc.proposals.single().start); assertEquals(720, doc.proposals.single().end)
        assertEquals("🙂".repeat(320), doc.proposals.single().quote)
        LocalExcerptRules.validate(doc, SOURCE, OriginalExports.hash(original), original)
    }
    @Test fun proposalCollectionsAreFrozenAndDiagnosticsAreRedacted() {
        val raw = mutableListOf(ExcerptProposal(0, 4, "私密原文"))
        val doc = ExcerptDocument(SOURCE, OriginalExports.hash("私密原文"), raw); raw.clear()
        assertEquals(1, doc.proposals.size)
        assertThrows(UnsupportedOperationException::class.java) { (doc.proposals as MutableList<ExcerptProposal>).clear() }
        assertFalse(doc.toString().contains("私密")); assertFalse(doc.proposals.single().toString().contains("私密"))
        assertFalse(job("私密原文").toString().contains("私密"))
        assertEquals("Invalid local excerpt response", assertThrows(InvalidExcerptResponse::class.java) { ExcerptResponseParser.parse("私密故障正文") }.message)
    }
    companion object {
        val SOURCE = SourceVersion("source", 1)
        fun q(value: String): String = "\"" + buildString { value.forEach { ch -> when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch < ' ' -> append("\\u" + ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        } } } + "\""
        fun item(original: String) = "{\"kind\":\"EXCERPT\",\"start\":0,\"end\":" +
            original.codePointCount(0, original.length) + ",\"quote\":" + q(original) + "}"
        fun json(original: String, items: String = item(original)) = "{\"schemaVersion\":1,\"ruleVersion\":1," +
            "\"provenance\":\"LOCAL_RULE_EXCERPT\",\"sourceId\":\"source\",\"sourceRevision\":1,\"sourceHash\":" +
            q(OriginalExports.hash(original)) + ",\"candidates\":[" + items + "]}"
        fun job(original: String) = LocalCandidateJob(LocalCandidateStart("batch", SOURCE, 5), OriginalExports.hash(original), 0, original, 5, "UTC")
        fun invalid(value: String) { assertThrows(InvalidExcerptResponse::class.java) { ExcerptResponseParser.parse(value) } }
    }
}
