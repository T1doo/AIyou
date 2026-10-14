package com.t1doo.aiyou.records

import android.content.Context
import android.content.Intent

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.model.OriginalExportFormat
import com.t1doo.aiyou.model.OriginalExports

private class ExportPickerHandle { var launcher: ActivityResultLauncher<String>? = null }
private class ExportDocumentContract(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

/** Keys live in this VM session, not SavedState. Recreated VMs cannot adopt old results. */
@Composable
fun OriginalExportPicker(controller: OriginalExportController, ui: OriginalExportUiState, canLaunch: Boolean, ownsRoute: () -> Boolean) {
    val requestKey = ui.requestKey ?: return
    if (ui.phase != OriginalExportPhase.PICKING) return
    key(requestKey) {
        val resolver = LocalContext.current.contentResolver
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
        val currentOwner by rememberUpdatedState(ownsRoute)
        val handle = remember(requestKey) { ExportPickerHandle() }
        val contract = remember(requestKey) { ExportDocumentContract(ui.format.mimeType) }
        DisposableEffect(registry, requestKey, controller) {
            val launcher = registry.register(requestKey, contract) { uri ->
                if (!currentOwner()) controller.cancel(ui.session)
                else if (uri == null) controller.picked(ui.session, requestKey, null)
                else try { controller.picked(ui.session, requestKey, PlatformExportDocument(resolver, uri)) }
                catch (_: Exception) { controller.pickerFailed(ui.session, requestKey) }
            }
            handle.launcher = launcher
            onDispose { handle.launcher = null; launcher.unregister() }
        }
        LaunchedEffect(requestKey, canLaunch) {
            if (canLaunch && currentOwner() && controller.claimPicker(ui.session, requestKey)) try {
                checkNotNull(handle.launcher).launch("AIyou-current-originals." + ui.format.extension)
            } catch (_: Exception) { controller.pickerFailed(ui.session, requestKey) }
        }
    }
}

@Composable
fun OriginalExportDialog(local: LocalRecordUiState, ui: OriginalExportUiState,
    controller: OriginalExportController, ownsRoute: () -> Boolean) {
    var deletionKey by remember(ui.session) { mutableStateOf<String?>(null) }
    val available = local.ready && !local.opening && local.error == null && local.supportsOriginalExport
    val snapshotVisible = available && ui.snapshot?.records?.all { record ->
        local.entries.any { it.id == record.entry.id && it.currentRevision == record.entry.currentRevision }
    } == true
    fun act(block: () -> Unit) { if (ownsRoute()) block() }
    deletionKey?.let { key ->
        AlertDialog(onDismissRequest = { deletionKey = null }, title = { Text("删除所选导出文件？") },
            text = { Text("将删除刚才由你选择的导出文件。若选择了已有文件，也会删除它；此操作无法在应用内撤销。") },
            confirmButton = { TextButton(onClick = { deletionKey = null; act { controller.deleteSelectedFile(ui.session, key) } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("export-confirm-delete")) { Text("确认删除所选文件") } },
            dismissButton = { TextButton(onClick = { deletionKey = null }) { Text("保留文件") } })
    }
    AlertDialog(onDismissRequest = { act { controller.close(ui.session) } }, title = { Text("导出选定原文") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅导出选定当前原文和手工标签。不含附件、历史版本、待办或密钥，不能用于完整恢复。")
            Text("文件是明文。目的地可能是云盘，访问该文件的人可以读取内容。请自行选择可信位置；写入可能覆盖所选已有文件，建议创建新文件。")
            if (!available) Text("本机记录暂不可读取，预览已隐藏；恢复记录后再继续。")
            when (ui.phase) {
                OriginalExportPhase.SELECTING -> {
                    Text("请选择原文，最多 ${OriginalExports.MAX_RECORDS} 条；默认不选中。")
                    if (available) local.entries.forEach { entry ->
                        val checked = ui.selection.any { it.sourceId == entry.id }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-select-" + entry.id)
                            .toggleable(checked, enabled = available && !ui.busy, role = Role.Checkbox,
                                onValueChange = { act { controller.toggle(ui.session, entry.id, entry.currentRevision) } }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(entry.originalText.takeCodePoints(80), Modifier.weight(1f))
                        }
                    }
                    Text("已选 ${ui.selection.size} 条")
                }
                OriginalExportPhase.PREPARING -> Text("正在读取原文与标签的同一份本机快照…")
                OriginalExportPhase.CHECKING -> Text("正在核对确认的快照…")
                OriginalExportPhase.PICKING -> Text("请在系统选择器中创建或选择导出文件。取消选择不会写入。")
                OriginalExportPhase.WRITING -> Text("正在写入并读取核对所选文件…")
                OriginalExportPhase.CANCELLING -> Text("正在停止。文件服务完成当前读写后才能结束；文件可能不完整。")
                OriginalExportPhase.CLEANING -> Text("正在删除明确确认的所选文件…")
                else -> Unit
            }
            if (!ui.busy && ui.phase != OriginalExportPhase.PICKING && !ui.hasFile && available) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OriginalExportFormat.entries.forEach { format ->
                        FilterChip(selected = ui.format == format, onClick = { act { controller.format(ui.session, format) } },
                            label = { Text(if (format == OriginalExportFormat.MARKDOWN) "Markdown" else "JSONL") },
                            modifier = Modifier.testTag("export-format-" + format.name))
                    }
                }
            }
            if (snapshotVisible && ui.phase != OriginalExportPhase.SELECTING) ui.snapshot?.let { snapshot ->
                Text("确认快照 · ${snapshot.records.size} 条原文 · ${ui.format.name}")
                snapshot.records.forEach { record ->
                    Text("记录 ${record.entry.id} · 版本 ${record.entry.currentRevision}")
                    Text("记录时间 ${record.entry.capturedAtMillis} · ${record.entry.captureTimeZone}")
                    Text("正文 SHA-256 ${record.contentHash}", style = MaterialTheme.typography.bodySmall)
                    Text("手工标签：" + record.tags.names.joinToString("、").ifEmpty { "无" })
                    Text(record.entry.originalText.takeCodePoints(300), Modifier.testTag("export-preview-" + record.entry.id))
                    if (record.entry.originalText.codePointCount(0, record.entry.originalText.length) > 300)
                        Text("这里只预览前 300 字符，导出包含完整正文。")
                }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export-error")) }
            ui.notice?.let { Text(it, Modifier.testTag("export-notice")) }
            if (ui.phase == OriginalExportPhase.PREVIEW && snapshotVisible && !ui.hasFile) {
                val snapshot = checkNotNull(ui.snapshot)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-risk")
                    .toggleable(ui.riskAccepted, role = Role.Checkbox, onValueChange = { value -> act {
                        controller.acceptRisk(ui.session, snapshot.id, ui.format, ui.epoch, value)
                    } }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ui.riskAccepted, onCheckedChange = null)
                    Text("我确认导出以上快照，理解明文、云盘和覆盖已有文件的风险。", Modifier.weight(1f))
                }
                OutlinedButton(enabled = ui.riskAccepted, onClick = { act {
                    controller.choose(ui.session, snapshot.id, ui.format, ui.epoch)
                } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-choose")) { Text("选择导出目的地") }
            }
            if (!ui.busy && ui.phase != OriginalExportPhase.PICKING && !ui.hasFile && ui.phase != OriginalExportPhase.DONE && available)
                OutlinedButton(enabled = ui.selection.isNotEmpty(), onClick = { act {
                    controller.prepare(ui.session, refreshCurrent = ui.phase != OriginalExportPhase.SELECTING)
                } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-prepare")) {
                    Text(if (ui.phase == OriginalExportPhase.SELECTING) "预览选定原文" else "重新预览最新原文")
                }
            if (ui.hasFile && !ui.busy && ui.phase != OriginalExportPhase.DONE && ui.requestKey != null)
                TextButton(onClick = { deletionKey = ui.requestKey }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("export-delete")) { Text("删除所选导出文件…") }
            if (ui.busy && ui.phase != OriginalExportPhase.CLEANING && ui.phase != OriginalExportPhase.CANCELLING)
                TextButton(onClick = { act { controller.cancel(ui.session) } }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("export-cancel")) { Text("停止本次导出") }
        } },
        confirmButton = { TextButton(onClick = { act { controller.close(ui.session) } },
            enabled = ui.phase != OriginalExportPhase.CLEANING, modifier = Modifier.heightIn(min = 48.dp).testTag("export-close")) {
            Text(if (ui.phase == OriginalExportPhase.WRITING || ui.phase == OriginalExportPhase.CANCELLING) "停止写入" else "关闭")
        } })
}

private fun String.takeCodePoints(maximum: Int): String = substring(0, offsetByCodePoints(0, minOf(maximum, codePointCount(0, length))))
