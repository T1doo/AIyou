package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.AIyouDimensions

@Composable
internal fun RecordAiSection(
    selected: RecordAiUiState,
    onStart: (Long) -> Unit,
    onStop: (Long) -> Unit,
    onClose: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("record-ai-session"),
        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Text(selected.notice, style = MaterialTheme.typography.bodySmall)
        val text = selected.annotation?.candidate?.text ?: selected.preview
        if (text.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.composerTextMinHeight * 2)
                .verticalScroll(rememberScrollState())) {
                Text(if (selected.annotation == null) "预览 · 尚未保存" else "AI 整理候选",
                    style = MaterialTheme.typography.labelMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            TextButton(enabled = selected.phase == RecordAiPhase.READY,
                onClick = { onStart(selected.sessionId) },
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("record-ai-start")) { Text("开始整理") }
            if (selected.running) TextButton(onClick = { onStop(selected.sessionId) },
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("record-ai-stop")) { Text("停止") }
            TextButton(onClick = { onClose(selected.sessionId) },
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("关闭整理") }
        }
    }
}
