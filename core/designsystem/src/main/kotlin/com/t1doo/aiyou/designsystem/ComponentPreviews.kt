package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.t1doo.aiyou.model.ProcessingPolicy

class ComponentStates : PreviewParameterProvider<ComponentState> {
    override val values = ComponentState.entries.asSequence()
}

@Preview(name = "常规", widthDp = 360, showBackground = true, locale = "zh-rCN")
@Preview(name = "窄屏大字体", widthDp = 320, fontScale = 2f, showBackground = true, locale = "zh-rCN")
annotation class ComponentPreviews

@Composable
fun PreviewFrame(content: @Composable () -> Unit) {
    AIyouTheme {
        Surface(color = AIyouColors.background) {
            Box(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow)) { content() }
        }
    }
}

fun previewText(state: ComponentState): String = if (state == ComponentState.LONG_TEXT) {
    "这是虚构的长中文记录。散步时想到，可以把沿途的树叶颜色和旅行照片整理成一本小册子。".repeat(8)
} else {
    "今天散步时看见一片新叶子，想把这个小小的发现记下来。"
}

@ComponentPreviews
@Composable
fun MemoryCardPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        MemoryCard(
            originalText = previewText(state), capturedAt = "示例 · 2026-10-10 16:20",
            annotation = "示例派生摘要：散步中的小发现。",
            attachmentLabel = "图片示例 · 中性占位，无真实附件", state = state,
        )
    }
}

@ComponentPreviews
@Composable
fun CaptureComposerPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        CaptureComposer(
            text = previewText(state), onTextChange = {}, onSubmit = {}, onImage = {}, onAudio = {},
            state = state, availability = "虚构示例 · 未写入记录",
        )
    }
}

@ComponentPreviews
@Composable
fun PrivacyChipPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        PrivacyChip(
            ProcessingPolicy.LOCAL_ONLY, state = state,
            description = if (state == ComponentState.LONG_TEXT) previewText(state) else null,
        )
    }
}

@ComponentPreviews
@Composable
fun SourceCitationCardPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        SourceCitationCard("2026-10-10", previewText(state), "示例来源 · 原文版本 1", onOpen = {}, state = state)
    }
}

@ComponentPreviews
@Composable
fun TaskProposalCardPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        TaskProposalCard(previewText(state), "明天下午 · 时间待确认", "示例记录 · 版本 1", onReview = {}, state = state)
    }
}

@ComponentPreviews
@Composable
fun ProviderConnectionCardPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        ProviderConnectionCard(
            if (state == ComponentState.LONG_TEXT) "虚构的超长服务连接名称与自定义用途说明".repeat(6) else "我的模型 · 未配置",
            "视觉、转写、语义检索能力均未检测", onConfigure = {}, state = state,
        )
    }
}

@ComponentPreviews
@Composable
fun StatusNoticePreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame { StatusNotice("示例状态", previewText(state), state = state, actionLabel = "查看说明", onAction = {}) }
}

@ComponentPreviews
@Composable
fun MascotBadgePreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    PreviewFrame {
        MascotBadge(
            mascotState = when (state) {
                ComponentState.LOADING -> MascotState.Saving
                ComponentState.ERROR -> MascotState.NeedsAttention
                else -> MascotState.Idle
            },
            assets = if (state == ComponentState.LONG_TEXT) MascotAssetProvider { previewText(state) } else NeutralMascotAssets,
            state = state,
        )
    }
}
