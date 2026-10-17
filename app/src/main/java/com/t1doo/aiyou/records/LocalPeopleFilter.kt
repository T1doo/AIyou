package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.model.ManualTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalPeopleFilter(person: String?, enabled: Boolean, available: Boolean, error: String?,
    onPerson: (String?) -> Unit, onRefresh: () -> Unit) {
    val input = remember(person) { TextFieldState(person.orEmpty()) }
    var invalid by remember(person) { mutableStateOf(false) }
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        Text(person?.let { "人物筛选：$it" } ?: "人物筛选：全部", style = MaterialTheme.typography.bodyMedium)
        Text("只按你手工关联的称呼精确匹配，与日期、标签、收藏等条件同时满足。同名不表示身份相同。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(state = input, enabled = enabled && available, label = { Text("人物称呼") },
            inputTransformation = InputTransformation.maxLength(160), lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier.fillMaxWidth().testTag("original-search-person-input"))
        TextButton(enabled = enabled && available, onClick = {
            try { val name = ManualTags.canonical(listOf(input.text.toString())).single(); invalid = false; onPerson(name) }
            catch (_: IllegalArgumentException) { invalid = true }
        }, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-person-apply")) { Text("应用人物筛选") }
        if (person != null) TextButton(onClick = { onPerson(null) }, enabled = enabled,
            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-person-clear")) { Text("清除人物筛选") }
        if (invalid) Text("称呼需为1–40个字符，不可包含控制字符。", color = MaterialTheme.colorScheme.error)
        if (!available) {
            Text(error ?: "手工人物关联暂不可读取，未显示不完整筛选结果。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)
                .testTag("original-search-person-retry")) { Text("重试读取人物关联") }
        }
    }
}
