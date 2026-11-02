package com.t1doo.aiyou.records

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Memory only. Selection/composition use UTF-16 indices, matching the native editable. */
data class CaptureEditorFrame(val text: String = "", val start: Int = text.length, val end: Int = start,
    val composingStart: Int? = null, val composingEnd: Int? = null) {
    init {
        require(start in 0..text.length && end in 0..text.length)
        require((composingStart == null) == (composingEnd == null))
        composingStart?.let { require(it in 0..text.length && checkNotNull(composingEnd) in it..text.length) }
    }
    fun committed() = copy(composingStart = null, composingEnd = null)
    override fun toString() = "CaptureEditorFrame(redacted)"
}

data class CaptureEditorState(val draftId: String? = null, val frame: CaptureEditorFrame = CaptureEditorFrame(),
    val canUndo: Boolean = false, val canRedo: Boolean = false) {
    override fun toString() = "CaptureEditorState(redacted)"
}

/** Owned by the capture controller, never a field node, Bundle, disk file, or reusable entry ID.
 * History is bounded to 40 frames / 524288 UTF-16 code units (about 1 MiB of text).
 */
class CaptureEditorSession {
    private val mutable = MutableStateFlow(CaptureEditorState())
    val state = mutable.asStateFlow()
    private val undo = ArrayDeque<CaptureEditorFrame>()
    private val redo = ArrayDeque<CaptureEditorFrame>()
    private var retired = false
    private var compositionBase: CaptureEditorFrame? = null
    private var compositionRecorded = false
    private var awaitingCommit = false

    @Synchronized fun sync(draftId: String?, text: String) {
        if (retired || draftId == null) return
        val old = mutable.value
        if (old.draftId != draftId) {
            undo.clear(); redo.clear(); compositionBase = null; compositionRecorded = false; awaitingCommit=false; mutable.value = CaptureEditorState(draftId, CaptureEditorFrame(text))
        } else if (old.frame.text != text) {
            // External current-draft changes are explicit text changes; ordinary persistence ACKs
            // have the same text and leave selection/composing/Undo completely untouched.
            accept(draftId, CaptureEditorFrame(text))
        }
    }

    @Synchronized fun accept(draftId: String, next: CaptureEditorFrame): Boolean {
        val old = mutable.value
        if (retired || old.draftId != draftId) return false
        if (old.frame.composingStart == null && next.composingStart != null) {
            compositionBase = old.frame.committed(); compositionRecorded = false; awaitingCommit=false
        }
        if (old.frame.text != next.text) {
            // One Chinese composing transaction is one Undo operation, even when the IME
            // announces an empty composing region in an earlier selection-only callback.
            if (old.frame.composingStart == null && next.composingStart == null && !awaitingCommit) undo.addLast(old.frame.committed())
            else if (!compositionRecorded) {
                undo.addLast(compositionBase ?: old.frame.committed()); compositionRecorded = true
            }
            redo.clear(); trim()
        }
        // Some IMEs end composing in a selection-only callback, then send committed Chinese
        // characters separately. Keep that transaction through this one pending commit.
        val endingWithoutText=old.frame.composingStart!=null && next.composingStart==null && old.frame.text==next.text
        if(endingWithoutText) awaitingCommit=true
        else if(next.composingStart==null && (old.frame.text!=next.text || old.frame.start!=next.start || old.frame.end!=next.end)) {
            compositionBase=null;compositionRecorded=false;awaitingCommit=false
        }
        publish(old.copy(frame = next))
        return true
    }

    @Synchronized fun undo(draftId: String) = move(draftId, undo, redo)
    @Synchronized fun redo(draftId: String) = move(draftId, redo, undo)
    private fun move(draftId: String, from: ArrayDeque<CaptureEditorFrame>, to: ArrayDeque<CaptureEditorFrame>): CaptureEditorFrame? {
        val old = mutable.value
        if (retired || old.draftId != draftId || from.isEmpty()) return null
        compositionBase = null; compositionRecorded = false;awaitingCommit=false
        val next = from.removeLast().committed()
        to.addLast(old.frame.committed()); trim(); publish(old.copy(frame = next))
        return next
    }
    private fun trim() {
        while (undo.size + redo.size > 40 || undo.sumOf { it.text.length } + redo.sumOf { it.text.length } > 524288) {
            if (undo.isNotEmpty()) undo.removeFirst() else if (redo.isNotEmpty()) redo.removeFirst() else break
        }
    }
    private fun publish(value: CaptureEditorState) { mutable.value = value.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    @Synchronized fun retire() { retired = true; undo.clear(); redo.clear();compositionBase=null;compositionRecorded=false;awaitingCommit=false; mutable.value = CaptureEditorState() }
}
