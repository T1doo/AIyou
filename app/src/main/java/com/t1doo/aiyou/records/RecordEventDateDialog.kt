package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouDimensions

@Composable
internal fun RecordEventDateDialog(ui: RecordEventDateEditor, pending: RecordEventDateWrite?,
    controller: RecordEventDateController, current: () -> Boolean) {
    DisposableEffect(ui.session) { onDispose { controller.close(ui.session) } }
    // No date, zone, preview, mutation or original text enters a Bundle/Saver.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        Dialog(onDismissRequest = { controller.close(ui.session) }) {
            Surface(shape = MaterialTheme.shapes.large) {
            // Share one finite scroll viewport for the title, fields and close
            // action, following the other metadata editors at large font scales.
            Column(Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.contentMaxWidth)
                .verticalScroll(rememberScrollState()).padding(AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("事件发生日期 · 手工补记", style = MaterialTheme.typography.titleLarge)
                Text("只保存单日日历日期和 IANA 时区，不补时刻。未知请留空；不会从原文、今天或记录时间推测。")
                val saved = ui.snapshot?.date
                Text(if (ui.snapshot == null) "已保存事件日期：尚未读取" else if (saved == null) "已保存事件日期：未补记（未知）"
                    else "已保存事件日期：${saved.localDate} · ${saved.timeZone}")
                Text("记录捕获时间和原文版本保持不变。回顾继续按记录时间分组。")
                OutlinedTextField(ui.dateInput, { if (current()) controller.edit(ui.session, it, ui.zoneInput) },
                    label = { Text("事件日期 YYYY-MM-DD（未知留空）") }, singleLine = true,
                    enabled = !ui.busy && !ui.conflict && pending == null && ui.snapshot != null,
                    modifier = Modifier.fillMaxWidth().testTag("event-date-input"))
                OutlinedTextField(ui.zoneInput, { if (current()) controller.edit(ui.session, ui.dateInput, it) },
                    label = { Text("IANA 时区，例如 Asia/Shanghai") }, singleLine = true,
                    enabled = !ui.busy && !ui.conflict && pending == null && ui.snapshot != null,
                    modifier = Modifier.fillMaxWidth().testTag("event-date-zone"))
                if (ui.busy) Text("正在确认本机事件日期…")
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("event-date-error")) }
                ui.notice?.let { Text(it, modifier = Modifier.testTag("event-date-notice")) }
                val preview = ui.preview
                if (preview != null) {
                    val next = preview.date
                    Text(if (next == null) "预览：清除事件日期，恢复为未补记（未知）。"
                        else "预览：事件发生于 ${next.localDate} · ${next.timeZone}，仅日期，无时刻。",
                        modifier = Modifier.testTag("event-date-preview"))
                    Text("请确认这是事件发生日期。保存后记录日期、原文和原文版本均不变。")
                    Button({ if (current()) controller.confirm(ui.session, preview.id) }, enabled = !ui.busy && !ui.conflict,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("event-date-confirm")) { Text(if (next == null) "确认清除事件日期" else "确认保存事件日期") }
                    TextButton({ if (current()) controller.cancelPreview(ui.session, preview.id) }, enabled = !ui.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("event-date-cancel-preview")) { Text("返回修改") }
                } else {
                    Button({ if (current()) controller.preview(ui.session) },
                        enabled = !ui.busy && !ui.conflict && pending == null && ui.snapshot != null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("event-date-preview-set")) { Text("预览这次日期") }
                    TextButton({ if (current()) controller.preview(ui.session, clear = true) },
                        enabled = !ui.busy && !ui.conflict && pending == null && saved != null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("event-date-preview-clear")) { Text("预览清除日期") }
                }
                if (!ui.busy && (ui.error != null || pending != null || ui.snapshot == null)) {
                    TextButton({ if (current()) controller.retry(ui.session) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("event-date-retry")) {
                        Text(if (pending != null && !pending.conflict) "重试确认同一次选择" else "重新读取事件日期")
                    }
                }
                TextButton({ controller.close(ui.session) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("event-date-close")) { Text("关闭") }
            }
            }
        }
    }
}
