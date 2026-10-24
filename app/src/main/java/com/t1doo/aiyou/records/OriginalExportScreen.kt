package com.t1doo.aiyou.records

import android.content.Context
import android.content.Intent

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
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
import com.t1doo.aiyou.model.LocalPcmAudio
import com.t1doo.aiyou.model.OriginalExports
import com.t1doo.aiyou.model.RecordPackageActivities
import com.t1doo.aiyou.model.ManualTaskStatus
import com.t1doo.aiyou.model.ManualTaskOccurrence

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
                checkNotNull(handle.launcher).launch((if(ui.includeActivities) "AIyou-originals-and-activities." else "AIyou-current-originals.") + ui.format.extension)
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
    AlertDialog(onDismissRequest = { act { controller.close(ui.session) } }, title = { Text("开放格式导出") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ui.format == OriginalExportFormat.MEDIA_ZIP)
                "原文与媒体 ZIP 含选定当前原文、手工标签、手工人物称呼、手填事件日期及 IANA 时区、原图、原音 WAV、Markdown、JSONL 和哈希清单。不含历史版本、待办或密钥，不能用于完整恢复。图片与原音合计最多 20 件、64 MiB；单图最多 16 MiB，原音沿用本机短音频上限。缺失或损坏会使整个导出失败，不会省略原音。"
                else if(ui.includeActivities) "导出选定当前原文、手工标签、手工人物称呼、手填事件日期及 IANA 时区，以及本机全部已保存正式待办和待确认草稿。正式待办包含未完成、已完成、已取消；草稿只含已保存 DRAFT，不含未保存表单或已处理历史。各最多 256 条，超限整体失败。不含附件、提醒、密钥或联网授权，不能用于完整恢复。"
                else "仅导出选定当前原文、手工标签、手工人物称呼和手填事件日期及 IANA 时区。不含附件、历史版本、待办或密钥，不能用于完整恢复。")
            Text(if(ui.format==OriginalExportFormat.MEDIA_ZIP) "可另选包含当前有效的人工转写；默认不包含，不导出纠正历史或已撤回文字。完整保留请使用全量加密备份。"
                else "人工转写不包含在此格式中；可选择媒体 ZIP 并明确勾选，或使用全量加密备份。")
            Text("文件是明文。目的地可能是云盘，访问该文件的人可以读取内容。请自行选择可信位置；写入可能覆盖所选已有文件，建议创建新文件。")
            Text("需要保密或跨设备恢复时，建议使用独立口令加密包。")
            if (ui.format == OriginalExportFormat.MEDIA_ZIP) Text("原图可能含拍摄位置等原始元数据；原音可能含私人谈话。它们会以明文写入 ZIP，不会生成转写。需要加密时请使用加密记录包。")
            if (!available) Text("本机记录暂不可读取，预览已隐藏；恢复记录后再继续。")
            when (ui.phase) {
                OriginalExportPhase.SELECTING -> {
                    if(ui.format==OriginalExportFormat.MEDIA_ZIP)
                        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("export-include-transcripts")
                            .toggleable(ui.includeTranscripts,enabled=available && !ui.busy,role=Role.Checkbox,
                                onValueChange={act {controller.includeTranscripts(ui.session,it)}}),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(ui.includeTranscripts,onCheckedChange=null)
                            Text("包含当前人工转写与片段范围（明文，可不选）",Modifier.weight(1f))
                        }
                    if(ui.format != OriginalExportFormat.MEDIA_ZIP && local.tasks.supported && local.taskProposals.supported)
                        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("export-include-activities")
                            .toggleable(ui.includeActivities,role=Role.Checkbox,onValueChange={included -> act {controller.includeActivities(ui.session,included)}}),
                            verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(ui.includeActivities,onCheckedChange=null)
                            Text("包含全部正式待办与已保存待确认草稿（可不选原文）",Modifier.weight(1f))
                        }
                    Text("请选择原文，最多 ${OriginalExports.MAX_RECORDS} 条；默认不选中。")
                    if (available) local.entries.forEach { entry ->
                        val checked = ui.selection.any { it.sourceId == entry.id }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-select-" + entry.id)
                            .toggleable(checked, enabled = available && !ui.busy, role = Role.Checkbox,
                                onValueChange = { act { controller.toggle(ui.session, entry.id, entry.currentRevision) } }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(entry.originalText.takeCodePoints(80).ifBlank { "附件记录 · ${entry.id}" }, Modifier.weight(1f))
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OriginalExportFormat.entries.forEach { format ->
                        FilterChip(selected = ui.format == format, onClick = { act { controller.format(ui.session, format) } },
                            label = { Text(when (format) {
                                OriginalExportFormat.MARKDOWN -> "Markdown"
                                OriginalExportFormat.JSONL -> "JSONL"
                                OriginalExportFormat.MEDIA_ZIP -> "原文与媒体 ZIP"
                            }) },
                            modifier = Modifier.testTag("export-format-" + format.name))
                    }
                }
            }
            if (snapshotVisible && ui.phase != OriginalExportPhase.SELECTING) ui.snapshot?.let { snapshot ->
                Text("确认快照 · ${snapshot.records.size} 条原文 · ${ui.format.name}")
                ui.mediaPreview?.let { media ->
                    media.openTranscripts?.let {scope->
                        Text("已明确包含人工转写范围 · 当前有效 ${scope.items.size} 条"+
                            if(scope.items.isEmpty()) "；本次集合为空，不等于未选择该范围。" else "；不是自动语音识别结果。",
                            Modifier.testTag("export-transcript-total"))
                    }
                    Text("已认证 ${media.images.size} 张原图 · ${media.imageBytes} 字节", Modifier.testTag("export-image-total"))
                    if (media.audios.isNotEmpty()) Text("已认证 ${media.audios.size} 段原音 · ${media.audios.sumOf { it.byteSize }} 字节",
                        Modifier.testTag("export-audio-total"))
                }
                snapshot.records.forEach { record ->
                    Text("记录 ${record.entry.id} · 版本 ${record.entry.currentRevision}")
                    Text("记录时间 ${record.entry.capturedAtMillis} · ${record.entry.captureTimeZone}")
                    Text(record.eventDate.date?.let { "事件日期 ${it.localDate} · ${it.timeZone}（仅日期，无时刻）" }
                        ?: "事件日期未知或已清除；不从记录时间推测", Modifier.testTag("export-event-date-" + record.entry.id))
                    Text("正文 SHA-256 ${record.contentHash}", style = MaterialTheme.typography.bodySmall)
                    Text("手工标签：" + record.tags.names.joinToString("、").ifEmpty { "无" })
                    Text("手工关联人物：" + record.people.names.joinToString("、").ifEmpty { "无" })
                    Text(record.entry.originalText.takeCodePoints(300), Modifier.testTag("export-preview-" + record.entry.id))
                    ui.mediaPreview?.images?.filter { it.sourceId == record.entry.id }?.forEach { image ->
                        Text("原图 ${image.attachmentId} · ${image.mediaType} · ${image.byteSize} 字节\nSHA-256 ${image.sha256}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export-image-" + image.attachmentId))
                        if (ui.phase == OriginalExportPhase.PREVIEW) TextButton(enabled = !ui.busy, onClick = { act {
                            controller.viewImage(ui.session, image.attachmentId)
                        } }, modifier = Modifier.heightIn(min = 48.dp).testTag("export-view-" + image.attachmentId)) { Text("预览这张原图") }
                        if (ui.pictureId == image.attachmentId) (ui.picture as? PlatformAttachmentPicture)?.let { picture ->
                            Image(picture.image, "选定原图预览", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(180.dp).testTag("export-picture"))
                        }
                    }
                    ui.mediaPreview?.audios?.filter { it.sourceId == record.entry.id }?.forEach { audio ->
                        Text("原音 ${audio.attachmentId} · WAV · 版本 ${audio.sourceRevision} · ${audio.byteSize} 字节\nSHA-256 ${audio.sha256}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export-audio-" + audio.attachmentId))
                    }
                    ui.mediaPreview?.openTranscripts?.items?.filter {it.anchor.intent.entryId==record.entry.id}?.forEach {t->
                        Text("人工转写 · 原音 ${t.anchor.intent.id} · 保存版本 ${t.version} · 文字版本 ${t.current.revision}",
                            Modifier.testTag("export-transcript-source-"+t.id))
                        Text(t.current.range?.let {"片段采样范围 [${it.startFrame}, ${it.endFrameExclusive}) · ${LocalPcmAudio.SAMPLE_RATE} Hz"}
                            ?: "未标注片段范围；仅关联此原音。原音总帧数 ${t.anchor.samples}（元数据）· ${LocalPcmAudio.SAMPLE_RATE} Hz",
                            Modifier.testTag("export-transcript-range-"+t.id))
                        Text(t.current.text,Modifier.fillMaxWidth().testTag("export-transcript-text-"+t.id))
                    }
                    if (record.entry.originalText.codePointCount(0, record.entry.originalText.length) > 300)
                        Text("这里只预览前 300 字符，导出包含完整正文。")
                }
                ui.activities?.let { OpenActivityPreview(it) }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export-error")) }
            ui.notice?.let { Text(it, Modifier.testTag("export-notice")) }
            if (ui.readingImageId != null) Text("正在认证并读取选定原图预览…")
            if (ui.phase == OriginalExportPhase.PREVIEW && snapshotVisible && !ui.hasFile && !ui.busy) {
                val snapshot = checkNotNull(ui.snapshot)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-risk")
                    .toggleable(ui.riskAccepted, role = Role.Checkbox, onValueChange = { value -> act {
                        controller.acceptRisk(ui.session, snapshot.id, ui.format, ui.epoch, value)
                    } }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ui.riskAccepted, onCheckedChange = null)
                    Text("我确认导出以上快照"+(if(ui.includeTranscripts)"（含已选人工转写范围）" else "")+"，理解明文、云盘和覆盖已有文件的风险。", Modifier.weight(1f))
                }
                OutlinedButton(enabled = ui.riskAccepted, onClick = { act {
                    controller.choose(ui.session, snapshot.id, ui.format, ui.epoch)
                } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-choose")) { Text("选择导出目的地") }
            }
            if (!ui.busy && ui.phase != OriginalExportPhase.PICKING && !ui.hasFile && ui.phase != OriginalExportPhase.DONE && available)
                OutlinedButton(enabled = ui.selection.isNotEmpty() || ui.includeActivities, onClick = { act {
                    controller.prepare(ui.session, refreshCurrent = ui.phase != OriginalExportPhase.SELECTING)
                } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export-prepare")) {
                    Text(if(ui.includeActivities) {
                        if(ui.phase == OriginalExportPhase.SELECTING) "预览选定内容" else "重新预览最新内容"
                    } else if (ui.phase == OriginalExportPhase.SELECTING) "预览选定原文" else "重新预览最新原文")
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

@Composable
private fun OpenActivityPreview(a: RecordPackageActivities) {
    Text("正式待办 ${a.tasks.size} 条；待确认草稿 ${a.drafts.size} 条；未完整包含来源 ${a.unavailableSources} 处。",Modifier.testTag("export-activity-summary"))
    Text("草稿保留原始输入，不会自动确认或执行。未完整包含的来源只导出关联信息，不表示原文已删除或可在此文件中找到。")
    a.tasks.forEach { item ->
        val t=item.task
        Column(Modifier.testTag("export-task:"+t.id)) {
            Text(t.title)
            Text("正式待办 · "+when(t.status) {ManualTaskStatus.OPEN->"未完成";ManualTaskStatus.COMPLETED->"已完成";ManualTaskStatus.CANCELLED->"已取消"})
            Text(t.schedule?.let { s -> s.date.toString()+(s.time?.let {" $it"} ?: " · 仅日期")+" · "+s.timeZone+
                s.offsetSeconds?.let {" · 偏移 "+java.time.ZoneOffset.ofTotalSeconds(it).id}.orEmpty()} ?: "未设日期和时刻")
            Text(if(t.sourceReceipt==null) "独立手工待办，没有来源" else if(item.sourceRecord>=0) "来源完整匹配本次原文序号 ${item.sourceRecord}" else "来源未完整包含，仅保留关联信息")
        }
    }
    a.drafts.forEach { item ->
        val p=item.proposal
        Column(Modifier.testTag("export-draft:"+p.id)) {
            Text(p.titleInput.ifEmpty {"尚未填写标题"})
            Text("待确认草稿 · 原输入："+p.dateInput.date+" "+p.dateInput.time+" · "+p.dateInput.timeZone)
            Text("重复时刻选择："+when(p.dateInput.occurrence) {ManualTaskOccurrence.FIRST->"第一次";ManualTaskOccurrence.SECOND->"第二次";null->"未选择"})
            Text(if(item.sourceRecord>=0) "来源完整匹配本次原文序号 ${item.sourceRecord}，仍未确认" else "来源未完整包含，仅保留关联信息")
        }
    }
}

private fun String.takeCodePoints(maximum: Int): String = substring(0, offsetByCodePoints(0, minOf(maximum, codePointCount(0, length))))
