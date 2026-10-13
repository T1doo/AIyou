package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.t1doo.aiyou.designsystem.AIyouDimensions

interface OriginalSearchObserver {
    fun onResults(state: OriginalSearchState, open: (OriginalSearchHit) -> Unit)
}
val LocalOriginalSearchObserver = staticCompositionLocalOf<OriginalSearchObserver?> { null }

/** Position contains no query or original text; retained only by the live composition. */
data class OriginalSearchReturnAnchor(val key: String?, val index: Int, val offset: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalOriginalSearchSection(query: TextFieldState, from: TextFieldState, through: TextFieldState,
    mode: OriginalSearchMode, enabled: Boolean, onMode: () -> Unit, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Text("本机原文查找", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state = query, enabled = enabled, label = { Text("关键词或精确片段") },
                modifier = Modifier.fillMaxWidth().testTag("original-search-query"),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                inputTransformation = InputTransformation.maxLength(100_000),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None))
            TextButton(onClick = onMode, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-mode")) {
                Text(if (mode == OriginalSearchMode.EXACT) "精确片段 · 区分大小写和空白" else "关键词 · 全部包含，忽略大小写")
            }
            OutlinedTextField(state = from, enabled = enabled, label = { Text("开始日期（YYYY-MM-DD，可留空）") },
                modifier = Modifier.fillMaxWidth().testTag("original-search-from"),
                lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = InputTransformation.maxLength(10))
            OutlinedTextField(state = through, enabled = enabled, label = { Text("结束日期（YYYY-MM-DD，可留空）") },
                modifier = Modifier.fillMaxWidth().testTag("original-search-through"),
                lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = InputTransformation.maxLength(10))
            Text("日期按记录保存的时区，包含起止两天。仅查当前本机原文，不使用 AI。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-clear")) { Text("清空查找条件") }
        }
    }
}
