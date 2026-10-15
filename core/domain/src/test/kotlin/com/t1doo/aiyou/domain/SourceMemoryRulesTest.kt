package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class SourceMemoryRulesTest {
    private val source=SourceVersion("synthetic",1)
    private fun preview(text:String)=SourceMemoryPreview(source,OriginalExports.hash(text),0,text,55,"Asia/Shanghai",ProcessingPolicy.LOCAL_ONLY,2)
    @Test fun uniqueUnicodeExcerptUsesExactCodePointsWithoutChangingNegationOrQuotedContext() {
        val text="🙂我说“开心”只是引用；假如去旅行，并不代表已经去过。"
        val p=preview(text);val quote="“开心”只是引用"
        val ref=SourceMemoryRules.reference(p,quote)
        assertEquals(text.codePointCount(0,text.indexOf(quote)),ref.start)
        val content=SourceMemoryContent(ref,SourceMemoryKind.OCCASION_EMOTION,SourceMemoryAssertion.CONTEXT,"人工备注，不是情绪诊断")
        SourceMemoryRules.validate(content,source,text);assertEquals(quote,content.reference.quote);assertEquals(text,p.original)
        assertEquals(SourceMemoryAssertion.CONTEXT,content.assertion)
    }
    @Test fun duplicateForgedOldAndPartialSurrogateReferencesCannotBeSaved() {
        val p=preview("合成 合成")
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.reference(p,"合成") }
        val content=SourceMemoryContent(OriginalTextReference(source,0,2,"合成"),SourceMemoryKind.EVENT,SourceMemoryAssertion.UNCERTAIN,"")
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.validate(content,source,p.original) }
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.reference(preview("🙂合成"),"\uD83D") }
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.validate(content,SourceVersion("synthetic",2),"合成") }
    }
    @Test fun boundariesAreCodePointsAndManualNotesCannotSmuggleControls() {
        val text="𠀀".repeat(320);val ref=SourceMemoryRules.reference(preview(text),text)
        val content=SourceMemoryContent(ref,SourceMemoryKind.EVENT,SourceMemoryAssertion.UNCERTAIN,"𠀀".repeat(200))
        SourceMemoryRules.validate(content,source,text);assertEquals(320,ref.end)
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryContent(ref,content.kind,content.assertion,"𠀀".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryContent(ref,content.kind,content.assertion,"合成\u0000备注") }
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.reference(preview(text+"𠀀"),text+"𠀀") }
    }
    @Test fun onlyExplicitLegalReviewsAdvanceAndCorrectionAlwaysReturnsToProposed() {
        assertEquals(SourceMemoryReview.ACCEPTED,SourceMemoryRules.decision(SourceMemoryReview.PROPOSED,SourceMemoryAction.ACCEPT))
        assertEquals(SourceMemoryReview.REJECTED,SourceMemoryRules.decision(SourceMemoryReview.PROPOSED,SourceMemoryAction.REJECT))
        assertEquals(SourceMemoryReview.REVOKED,SourceMemoryRules.decision(SourceMemoryReview.ACCEPTED,SourceMemoryAction.REVOKE))
        for(state in listOf(SourceMemoryReview.ACCEPTED,SourceMemoryReview.REJECTED,SourceMemoryReview.REVOKED,SourceMemoryReview.PROPOSED))
            assertEquals(SourceMemoryReview.PROPOSED,SourceMemoryRules.decision(state,SourceMemoryAction.CORRECT))
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.decision(SourceMemoryReview.REJECTED,SourceMemoryAction.ACCEPT) }
        assertThrows(IllegalArgumentException::class.java) { SourceMemoryRules.decision(SourceMemoryReview.SUPERSEDED,SourceMemoryAction.CORRECT) }
    }
}
