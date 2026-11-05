package com.t1doo.aiyou

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.*

/** Render the four actual design-system Preview entry points; synthetic text only. */
@PreviewTest @Preview(widthDp=360,locale="zh-rCN") @Composable
fun SkyglassBlueRender() = SkyglassBluePreview()
@PreviewTest @Preview(widthDp=360,locale="zh-rCN") @Composable
fun SkyglassSunsetRender() = SkyglassSunsetPreview()
@PreviewTest @Preview(widthDp=360,locale="zh-rCN") @Composable
fun SkyglassNightRender() = SkyglassNightPreview()
@PreviewTest @Preview(widthDp=320,fontScale=2f,locale="zh-rCN") @Composable
fun SkyglassLargeTextRender() = SkyglassLargeTextPreview()
