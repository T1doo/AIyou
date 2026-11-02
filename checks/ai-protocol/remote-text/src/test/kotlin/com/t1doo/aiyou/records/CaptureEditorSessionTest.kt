package com.t1doo.aiyou.records

import org.junit.Assert.*
import org.junit.Test

class CaptureEditorSessionTest {
    @Test fun chineseCompositionIsOneUndoAndPersistenceAckPreservesSelection() {
        val editor=CaptureEditorSession();editor.sync("draft", "前文")
        editor.accept("draft",CaptureEditorFrame("前文",1,1,1,1))
        editor.accept("draft",CaptureEditorFrame("前n文",2,2,1,2))
        editor.accept("draft",CaptureEditorFrame("前ni文",3,3,1,3))
        editor.accept("draft",CaptureEditorFrame("前你文",2,2))
        editor.sync("draft","前你文")
        assertEquals(2,editor.state.value.frame.start)
        assertEquals("前文",editor.undo("draft")?.text)
        assertFalse(editor.state.value.canUndo)
        assertEquals("前你文",editor.redo("draft")?.text)
    }
    @Test fun imeMayClearCompositionBeforeDeliveringCommittedCharacters() {
        val editor=CaptureEditorSession();editor.sync("draft", "前")
        editor.accept("draft",CaptureEditorFrame("前ni",3,3,1,3))
        editor.accept("draft",CaptureEditorFrame("前ni",3,3))
        editor.accept("draft",CaptureEditorFrame("前你",2,2))
        assertEquals("前",editor.undo("draft")?.text)
        assertFalse(editor.state.value.canUndo)
        assertEquals("前你",editor.redo("draft")?.text)
    }
    @Test fun sameOwnerRemountRetainsRangeAndHistoryButNewDraftAndRetirementRejectLateCallbacks() {
        val editor=CaptureEditorSession();editor.sync("one","a😀b")
        editor.accept("one",CaptureEditorFrame("a😀b",1,3))
        editor.accept("one",CaptureEditorFrame("a字b",2,2))
        editor.sync("one","a字b")
        assertEquals(CaptureEditorFrame("a字b",2,2),editor.state.value.frame)
        assertEquals(CaptureEditorFrame("a😀b",1,3),editor.undo("one"))
        editor.sync("two", "")
        assertFalse(editor.accept("one",CaptureEditorFrame("stale")))
        assertNull(editor.undo("two"))
        editor.retire();editor.sync("three","private")
        assertNull(editor.state.value.draftId)
        assertFalse(editor.accept("two",CaptureEditorFrame("private")))
    }
    @Test fun historyHasHardFrameAndTextBounds() {
        val editor=CaptureEditorSession();editor.sync("draft", "")
        repeat(100) { editor.accept("draft",CaptureEditorFrame(it.toString())) }
        var count=0;while(editor.undo("draft")!=null) count++
        assertEquals(40,count)
        editor.sync("large", "x".repeat(100000))
        repeat(12) { editor.accept("large",CaptureEditorFrame(it.toString().padStart(2,'0').repeat(50000))) }
        count=0;while(editor.undo("large")!=null) count++
        assertEquals(5,count)
    }
}
