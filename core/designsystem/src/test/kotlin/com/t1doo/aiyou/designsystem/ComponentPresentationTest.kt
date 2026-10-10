package com.t1doo.aiyou.designsystem

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentPresentationTest {
    @Test
    fun privacyCornersRemainInsideContentInsetsForTallParagraphs() {
        for (density in listOf(Density(1f), Density(2.625f, fontScale = 2f))) {
            for (size in listOf(Size(320f, 48f), Size(945f, 966f), Size(840f, 2400f), Size(840f, 10500f))) {
                for (direction in LayoutDirection.entries) {
                    val outline = privacyContentShape.createOutline(size, direction, density) as Outline.Rounded
                    val horizontal = with(density) { AIyouDimensions.spaceMd.toPx() }
                    val vertical = with(density) { AIyouDimensions.spaceSm.toPx() }
                    val rect = outline.roundRect
                    for (corner in listOf(rect.topLeftCornerRadius, rect.topRightCornerRadius,
                        rect.bottomLeftCornerRadius, rect.bottomRightCornerRadius)) {
                        assertTrue("corner must not cut into the content inset for $size", corner.x <= horizontal && corner.y <= vertical)
                    }
                }
            }
        }
    }

    @Test
    fun privacyCornerDoesNotGrowWithContentHeight() {
        val density = Density(2.625f)
        val short = privacyContentShape.createOutline(Size(945f, 966f), LayoutDirection.Ltr, density) as Outline.Rounded
        val tall = privacyContentShape.createOutline(Size(945f, 10500f), LayoutDirection.Ltr, density) as Outline.Rounded
        assertEquals(short.roundRect.topLeftCornerRadius, tall.roundRect.topLeftCornerRadius)
    }

    @Test
    fun emptyMemoryHasNoOriginalAttachmentOrAiDerivedFixture() {
        val empty = memoryPreviewContent(ComponentState.EMPTY)
        assertTrue(empty.original.isBlank())
        assertTrue(empty.capturedAt.isBlank())
        assertNull(empty.attachment)
        assertNull(empty.annotation)
    }

    @Test
    fun emptyProposalHasNoContentOrConfirmationAction() {
        val empty = proposalPreviewContent(ComponentState.EMPTY)
        assertTrue(empty.title.isBlank() && empty.time.isBlank() && empty.source.isBlank())
        assertFalse(empty.hasProposal)
        assertTrue(proposalPreviewContent(ComponentState.NORMAL).hasProposal)
    }

    @Test
    fun emptyComposerStartsBlankAndEditingRemovesEmptyFeedback() {
        assertTrue(previewText(ComponentState.EMPTY).isBlank())
        assertEquals(ComponentState.EMPTY, composerFeedbackState(ComponentState.EMPTY, "  "))
        assertEquals(ComponentState.NORMAL, composerFeedbackState(ComponentState.EMPTY, "新的输入"))
    }

    @Test
    fun editingCannotRemoveErrorLoadingOrDisabledStates() {
        for (state in listOf(ComponentState.ERROR, ComponentState.LOADING, ComponentState.DISABLED)) {
            assertEquals(state, composerFeedbackState(state, "新的输入"))
            assertEquals(state, composerFeedbackState(state, ""))
        }
    }

    @Test
    fun longTextRemainsUnabridgedAndMemoryKeepsItsSeparateSections() {
        val original = "这是虚构的长中文记录。散步时想到，可以把沿途的树叶颜色和旅行照片整理成一本小册子。".repeat(8)
        assertEquals(original, previewText(ComponentState.LONG_TEXT))
        val memory = memoryPreviewContent(ComponentState.LONG_TEXT)
        assertEquals(original, memory.original)
        assertTrue(!memory.annotation.isNullOrBlank() && memory.annotation != memory.original)
        assertTrue(!memory.attachment.isNullOrBlank())
        assertEquals(original, proposalPreviewContent(ComponentState.LONG_TEXT).title)
    }
}
