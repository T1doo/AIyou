package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class SourceTagRulesTest {
    private val source = SourceVersion("synthetic-source",1)
    @Test fun explicitLinesPreserveCodePointQuotesCaseAndCombiningMarks() {
        val original="🙂合成原文\r\n标签：#旅行\t#Café #𠀀\r\n标签:#旅行 #café"
        val tags=SourceTagRules.extract(source,original)
        assertEquals(listOf("旅行","Café","𠀀","café"),tags.map { it.name })
        tags.forEach { p ->
            assertEquals(source,p.reference.source)
            assertEquals("#"+p.name, original.substring(original.offsetByCodePoints(0,p.reference.start),original.offsetByCodePoints(0,p.reference.end)))
        }
        assertEquals(original.codePointCount(0,original.indexOf("#旅行")),tags.first().reference.start)
    }
    @Test fun ordinaryHashtagsQuestionsAndQuotedPrefixesDoNotBecomeTagLines() {
        assertTrue(SourceTagRules.extract(source,"我是不是喜欢#咖啡？\n他说“标签：#咖啡”\n 标签：#前导空格\n#单独记号").isEmpty())
        assertEquals("想法",SourceTagRules.extract(source,"标签：#想法").single().name)
        // Explicit confirmation assigns a user-owned label, never asserts that an event occurred.
    }
    @Test fun malformedLinesControlsPunctuationAndExcessiveNamesAreRejectedAsAWhole() {
        for(text in listOf("标签：#旅行 #生活。","标签：#合成\u200b","标签：不是记号","标签：#a\u00a0#b",
            "标签：#"+"𠀀".repeat(41),"标签："+(1..13).joinToString(" ") { "#项"+it })) {
            assertThrows(InvalidSourceTagFormat::class.java) { SourceTagRules.extract(source,text) }
        }
        assertThrows(IllegalArgumentException::class.java) { SourceTagRules.extract(source,"标签：#\uD800") }
    }
    @Test fun twelveDistinctTagsAndFortyCodePointsAreNotTruncatedOrNormalized() {
        val original="标签："+(1..11).joinToString(" ") { "#项"+it }+" #"+"𠀀".repeat(40)
        val tags=SourceTagRules.extract(source,original)
        assertEquals(12,tags.size);assertEquals("𠀀".repeat(40),tags.last().name)
        assertEquals(listOf("é","é"),SourceTagRules.extract(source,"标签：#é #é").map { it.name })
    }
}
