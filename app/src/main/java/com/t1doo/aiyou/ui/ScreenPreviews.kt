package com.t1doo.aiyou.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.ComponentStates

@Preview(name = "常规", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "窄屏大字体", widthDp = 320, heightDp = 800, fontScale = 2f, locale = "zh-rCN")
@Preview(name = "横屏", widthDp = 800, heightDp = 360, locale = "zh-rCN")
@Preview(name = "大屏", widthDp = 840, heightDp = 1000, locale = "zh-rCN")
annotation class ScreenPreviews

@ScreenPreviews
@Composable
fun NowPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    AIyouTheme { MainScreen(Destination.NOW, demo = true, state = state) }
}

@ScreenPreviews
@Composable
fun MemoriesPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    AIyouTheme { MainScreen(Destination.MEMORIES, demo = true, state = state) }
}

@ScreenPreviews
@Composable
fun TasksPreview(@PreviewParameter(ComponentStates::class) state: ComponentState) {
    AIyouTheme { MainScreen(Destination.TASKS, demo = true, state = state) }
}
