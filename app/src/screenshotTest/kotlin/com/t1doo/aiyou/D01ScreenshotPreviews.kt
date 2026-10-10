package com.t1doo.aiyou

import androidx.compose.runtime.Composable
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
