package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape

// The corner never grows with paragraph height; the existing 12/8dp inset protects all corners.
internal val privacyContentShape = RoundedCornerShape(AIyouDimensions.spaceSm)

fun previewText(state: ComponentState): String = when (state) {
    ComponentState.EMPTY -> ""
    ComponentState.LONG_TEXT -> "这是虚构的长中文记录。散步时想到，可以把沿途的树叶颜色和旅行照片整理成一本小册子。".repeat(8)
    else -> "今天散步时看见一片新叶子，想把这个小小的发现记下来。"
}

internal data class MemoryPreviewContent(
    val original: String,
    val capturedAt: String,
    val annotation: String?,
    val attachment: String?,
)

internal fun memoryPreviewContent(state: ComponentState): MemoryPreviewContent =
    if (state == ComponentState.EMPTY) MemoryPreviewContent("", "", null, null)
    else MemoryPreviewContent(previewText(state), "示例 · 2026-10-10 16:20",
        "示例派生摘要：散步中的小发现。", "图片示例 · 中性占位，无真实附件")

internal data class ProposalPreviewContent(val title: String, val time: String, val source: String) {
    val hasProposal: Boolean get() = title.isNotBlank()
}

internal fun proposalPreviewContent(state: ComponentState): ProposalPreviewContent =
    if (state == ComponentState.EMPTY) ProposalPreviewContent("", "", "")
    else ProposalPreviewContent(previewText(state), "明天下午 · 时间待确认", "示例记录 · 版本 1")

// An edited empty composer has input now; the forced gallery state must not still say "暂无内容".
internal fun composerFeedbackState(state: ComponentState, text: String): ComponentState =
    if (state == ComponentState.EMPTY && text.isNotBlank()) ComponentState.NORMAL else state
