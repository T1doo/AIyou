package com.t1doo.aiyou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.*

/** Explicit design specimen, never installed into the production navigation or data. */
@PreviewTest
@Preview(name = "视觉基础", widthDp = 360, locale = "zh-rCN")
@Preview(name = "窄屏200%", widthDp = 320, fontScale = 2f, locale = "zh-rCN")
@Composable
fun UiPolishPrimitivesRender() {
    AIyouTheme {
        Surface(color = AIyouColors.background) {
            Column(Modifier.padding(AIyouDimensions.pageNarrow),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
                Text("视觉组件预览", style = MaterialTheme.typography.headlineMedium)
                Text("白与浅蓝 · 系统字体", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                AIyouPanel {
                    Text("安静地记下生活", style = MaterialTheme.typography.titleLarge)
                    Text("清晰的文字、舒适的留白，让每一件小事都有自己的位置。", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(value = "", onValueChange = {}, label = { Text("预览输入框") },
                        modifier = Modifier.fillMaxWidth().aiyouFocusOutline())
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("预览主操作") }
                }
                Surface(color = AIyouColors.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text("错误状态始终保留文字说明", Modifier.padding(AIyouDimensions.spaceLg),
                        color = AIyouColors.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
