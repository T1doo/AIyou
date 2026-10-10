package com.t1doo.aiyou

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.ui.*

// Initial render smoke, not regression against approved visual golden files.
@PreviewTest
@ComponentPreviews
@Composable
fun MemoryRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { MemoryCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun ComposerRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { CaptureComposerPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun PrivacyRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { PrivacyChipPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun SourceRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { SourceCitationCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun ProposalRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { TaskProposalCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun ProviderRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { ProviderConnectionCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun NoticeRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { StatusNoticePreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun MascotRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { MascotBadgePreview(state) }

@PreviewTest
@ScreenPreviews
@Composable
fun NowRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { NowPreview(state) }

@PreviewTest
@ScreenPreviews
@Composable
fun MemoriesRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { MemoriesPreview(state) }

@PreviewTest
@ScreenPreviews
@Composable
fun TasksRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { TasksPreview(state) }

@PreviewTest
@Preview(name = "主导航", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "主导航窄屏大字体", widthDp = 320, heightDp = 800, fontScale = 2f, locale = "zh-rCN")
@Composable
fun ShellRender() { AIyouTheme { AIyouApp() } }

// Review the whole phone page, including app bar, primary input and bottom navigation.
@PreviewTest
@Preview(name = "手机壳常规", widthDp = 360, heightDp = 800, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "手机壳窄屏大字体", widthDp = 320, heightDp = 1000, fontScale = 2f, showSystemUi = true, locale = "zh-rCN")
@Composable
fun ReviewNowRender() { AIyouTheme { AIyouApp(previewDestination = Destination.NOW, previewDemo = true) } }

@PreviewTest
@Preview(name = "手机壳常规", widthDp = 360, heightDp = 800, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "手机壳窄屏大字体", widthDp = 320, heightDp = 1000, fontScale = 2f, showSystemUi = true, locale = "zh-rCN")
@Composable
fun ReviewMemoriesRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, previewDemo = true) } }

// Reuse unabridged actual component content at explicit heights; no parameter multiplication.
@Composable
private fun CompleteContentFrame(label: String, content: @Composable () -> Unit) {
    AIyouTheme {
        Column {
            content()
            Text("完整内容结束 · $label", Modifier.padding(AIyouDimensions.pageNarrow))
        }
    }
}

@PreviewTest
@CompleteContentPreviews
@Composable
fun PrivacyCompleteRender() { CompleteContentFrame("隐私提示") { PrivacyChipPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun MemoryCompleteRender() { CompleteContentFrame("记忆卡片") { MemoryCardPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun NoticeCompleteRender() { CompleteContentFrame("状态说明") { StatusNoticePreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun ProposalCompleteRender() { CompleteContentFrame("任务提案") { TaskProposalCardPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun SourceCompleteRender() { CompleteContentFrame("来源引用") { SourceCitationCardPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun MascotCompleteRender() { CompleteContentFrame("中性角色说明") { MascotBadgePreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun ProviderCompleteRender() { CompleteContentFrame("服务连接") { ProviderConnectionCardPreview(ComponentState.LONG_TEXT) } }
