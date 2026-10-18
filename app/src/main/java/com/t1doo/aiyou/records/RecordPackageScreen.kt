package com.t1doo.aiyou.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.model.RecordPackageActivities

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordPackageScreen(local: LocalRecordUiState, ui: RecordPackageUiState, controller: RecordPackageController,
    picker: RecordPackagePicker?, ownsRoute: () -> Boolean, modifier: Modifier = Modifier) {
    val available = ownsRoute() && local.ready && !local.opening && local.error == null && local.supportsRecordPackages
    val historyRecords = ui.mediaPreview?.history
    val historyRestore = ui.verified?.takeIf { it.includesHistory }
    // Explicit remember, never rememberTextFieldState / saveable: secrets retire at every epoch/phase.
    val password = remember(ui.session, ui.epoch, ui.phase) { TextFieldState() }
    val again = remember(ui.session, ui.epoch, ui.phase) { TextFieldState() }
    fun clearPasswords() { password.edit { replace(0, length, "") }; again.edit { replace(0, length, "") }
        password.undoState.clearHistory(); again.undoState.clearHistory() }
    DisposableEffect(password, again) { onDispose { clearPasswords() } }
    val key = ui.requestKey
    LaunchedEffect(ui.session, ui.epoch, key, available, picker) {
        if (!available) clearPasswords()
        if (available && key != null && picker != null && controller.claimPicker(ui.session, key)) {
            if (!picker.launch(ui.phase == RecordPackagePhase.PICKING_EXPORT, key) { document ->
                controller.picked(ui.session, key, document)
            }) controller.pickerFailed(ui.session, key)
        }
    }
    DisposableEffect(picker, key) { onDispose { if (key != null) picker?.cancel(key) } }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = AIyouDimensions.pageNarrow).testTag("package-list"),
        contentPadding = PaddingValues(vertical = AIyouDimensions.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        item("scope") {
            Text("加密原文包", style = MaterialTheme.typography.headlineSmall)
            Text("默认包含选定的当前原文、手工标签、手工人物称呼、手填事件日期及 IANA 时区和当前版本已完整保存的 JPEG/PNG 原图；可另选这些记录的完整历史、已保留的记录编辑草稿，或本机全部正式待办与待办建议草稿。正在输入但未保留的内容、AI 结果及所有密钥不包含在内；这不是整库备份。")
            Text("事件日期只迁移当前值，不迁移日期修改历史或操作回执；未知与已清除在新副本中都为未补记。")
            Text("恢复会创建新副本，不覆盖旧记录；初始设置为不参与 AI。同一包重复恢复只核对第一次回执。")
            Text("一次最多 20 条记录、20 张原图；单图最多 16 MiB，图片合计最多 64 MiB。任务和待办建议草稿各最多 256 条；记录编辑草稿每条至多一份、共最多20份。超限整体失败，不会少带。旧 v1–v6 包仍可恢复；缺少事件日期时保持未知。")
            Text("文字总量也有上限；内容过多时本次整体失败，不会静默截断。")
            Text("恢复的草稿仍须另行确认；不安排提醒、不恢复联网授权。未随包完整匹配的来源保持不可用。")
            Text("独立恢复口令丢失无法解密。取消或失败可能留下空文件或部分密文，应用不会自动删除。")
        }
        if (!available) item("unavailable") {
            Text(local.error ?: "当前本机数据暂不可读取，预览与确认已暂停。", color = AIyouColors.error)
        } else {
            when (ui.phase) {
                RecordPackagePhase.HOME, RecordPackagePhase.FAILED, RecordPackagePhase.CANCELLED, RecordPackagePhase.DONE -> item("home-actions") {
                    Column {
                        Button(onClick = { if (ownsRoute()) controller.selectExport(ui.session) },
                            modifier = Modifier.testTag("package-export")) { Text("选择内容制作加密包") }
                        OutlinedButton(onClick = { if (ownsRoute()) controller.chooseRestore(ui.session) },
                            enabled = picker != null, modifier = Modifier.testTag("package-restore")) { Text("选择加密包恢复") }
                    }
                }
                RecordPackagePhase.SELECTING -> {
                    item("history-choice") {
                        Row {
                            Checkbox(ui.includeHistory, onCheckedChange = { if (ownsRoute()) controller.includeHistory(ui.session, it) },
                                modifier = Modifier.testTag("package-include-history"))
                            Text("包含所选记录从版本1到当前版本的全部历史及对应原图；默认关闭。")
                        }
                        if (ui.includeHistory) HistoryWarning()
                        Text("完整历史最多每条128版、合计256版，历史文字UTF-8合计最多约2 MiB；媒体仍受同一总量限制，超限整包拒绝。")
                    }
                    item("edit-draft-choice") {
                        Row {
                            Checkbox(ui.includeEditDrafts, onCheckedChange = { if (ownsRoute()) controller.includeEditDrafts(ui.session, it) },
                                modifier = Modifier.testTag("package-include-edit-drafts"))
                            Text("包含所选记录已保留的编辑草稿；默认关闭，开启时同时包含完整历史。未保留的当前输入不会被带出。")
                        }
                    }
                    item("activity-choice") { Row {
                        Checkbox(ui.includeActivities,onCheckedChange={if(ownsRoute()) controller.includeActivities(ui.session,it)},modifier=Modifier.testTag("package-include-activities"))
                        Text("包含全部已保存正式待办与待确认草稿（各最多256条）；未保存的表单输入不在包内。")
                    } }
                    itemsIndexed(local.entries, key = { _, e -> "source:" + e.id }) { _, entry ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(ui.selection.any { it.sourceId == entry.id }, onCheckedChange = {
                                if (ownsRoute()) controller.toggle(ui.session, entry.id, entry.currentRevision)
                            }, modifier = Modifier.testTag("package-select:" + entry.id))
                            Text(originalMetadata(entry), modifier = Modifier.weight(1f))
                        }
                    }
                    item("prepare") { Button(onClick = { if (ownsRoute()) controller.prepare(ui.session) },
                        enabled = ui.selection.isNotEmpty()||ui.includeActivities, modifier = Modifier.testTag("package-preview")) { Text("预览完整内容与范围") } }
                }
                RecordPackagePhase.EXPORT_PREVIEW -> {
                    if (historyRecords != null) item("history-summary") {
                        HistoryWarning()
                        Text("本次完整历史范围：${historyRecords.size} 条记录，${historyRecords.sumOf { it.history.size }} 个原文版本；恢复时保留当前版本指针。",
                            modifier = Modifier.testTag("package-history-summary"))
                    }
                    itemsIndexed(ui.snapshot?.records.orEmpty(), key = { _, r -> "preview:" + r.entry.id }) { _, record ->
                        val history = ui.mediaPreview?.history?.singleOrNull { it.sourceId == record.entry.id }
                        if (history == null) {
                            PackagePreview(originalMetadata(record.entry), record.entry.originalText, record.tags.names)
                            Text("手工关联人物：" + record.people.names.joinToString("、").ifEmpty { "无" })
                            Text(packageEventDateText(record.eventDate.date), Modifier.testTag("package-event-date-" + record.entry.id))
                            PackageImages(ui.mediaPreview?.images.orEmpty().filter { it.sourceId == record.entry.id })
                        } else PackageHistoryPreview(history, ui.mediaPreview?.images.orEmpty())
                    }
                    item("image-summary") {
                        Text("本次范围：" + ui.snapshot?.records.orEmpty().size + " 条记录，" + ui.mediaPreview?.images.orEmpty().size +
                            " 张原图，原图合计 " + (ui.mediaPreview?.imageBytes ?: 0L) + " 字节。", modifier = Modifier.testTag("package-image-summary"))
                    }
                    packageActivities(ui.mediaPreview?.activities, ui.mediaPreview?.history != null,
                        ui.snapshot?.records.orEmpty().any { it.eventDate.date != null })
                    packageEditDrafts(ui.mediaPreview?.editDrafts)
                    item("scope-confirm") {
                        Row {
                            Checkbox(ui.scopeAccepted, onCheckedChange = { accepted ->
                                if (ownsRoute()) ui.snapshot?.let { controller.acceptScope(ui.session, it.id, accepted) }
                            }, modifier = Modifier.testTag("package-scope"))
                            Text(if (ui.includeHistory) "确认以上完整历史（含后来删掉的文字）、当前标签、事件日期及 IANA 时区、各版原图及列出的任务和草稿范围，并自行保管独立恢复口令。"
                                else "确认以上原文、标签、事件日期及 IANA 时区、原图及明确列出的任务和草稿范围，并自行保管独立恢复口令。")
                        }
                    }
                    item("password-rule") { Text("口令：12–128 个可打印 ASCII 字符，大小写和首尾空格原样使用。") }
                    item("export-password") {
                        OutlinedSecureTextField(state = password, label = { Text("独立恢复口令") },
                            textObfuscationMode = TextObfuscationMode.Hidden, inputTransformation = InputTransformation.maxLength(128),
                            modifier = Modifier.fillMaxWidth().testTag("package-password"))
                    }
                    item("export-password-again") {
                        OutlinedSecureTextField(state = again, label = { Text("再次输入恢复口令") },
                            textObfuscationMode = TextObfuscationMode.Hidden, inputTransformation = InputTransformation.maxLength(128),
                            modifier = Modifier.fillMaxWidth().testTag("package-password-again"))
                    }
                    item("export-confirm") {
                        Button(onClick = {
                            if (ownsRoute()) ui.snapshot?.let { snapshot -> controller.encrypt(ui.session, snapshot.id,
                                CharArray(password.text.length) { password.text[it] }, CharArray(again.text.length) { again.text[it] }) }
                            clearPasswords()
                        }, enabled = ui.scopeAccepted && picker != null, modifier = Modifier.heightIn(min = androidx.compose.ui.unit.Dp(48f)).testTag("package-encrypt")) { Text("加密并选择保存位置") }
                    }
                }

                RecordPackagePhase.RESTORE_PASSWORD -> item("restore-password") {
                    Column {
                        Text("先验证完整加密包。此步骤不会写入记录；认证成功后还有单独的恢复确认。")
                        OutlinedSecureTextField(state = password, label = { Text("独立恢复口令") },
                            textObfuscationMode = TextObfuscationMode.Hidden, inputTransformation = InputTransformation.maxLength(128),
                            modifier = Modifier.fillMaxWidth().testTag("package-password"))
                        Button(onClick = { if (ownsRoute()) controller.authenticate(ui.session, CharArray(password.text.length) { password.text[it] }); clearPasswords() },
                            modifier = Modifier.testTag("package-authenticate")) { Text("认证并预览完整内容") }
                    }
                }
                RecordPackagePhase.RESTORE_PREVIEW, RecordPackagePhase.UNCERTAIN -> {
                    if (historyRestore != null) item("restore-history-summary") {
                        HistoryWarning()
                        Text("将以新身份恢复 ${historyRestore.records.size} 条记录、${historyRestore.records.sumOf { it.history.size }} 个原文版本，保持包内当前版本指针；不会覆盖本机原文。",
                            modifier = Modifier.testTag("package-restore-history-summary"))
                    }
                    itemsIndexed(ui.verified?.records.orEmpty(), key = { i, _ -> "restore-preview:" + i }) { _, record ->
                        if (historyRestore != null) PackageHistoryPreview(record, historyRestore.images)
                        else {
                            PackagePreview("原捕获时间 " + java.time.Instant.ofEpochMilli(record.capturedAtMillis)
                            .atZone(java.time.ZoneId.of(record.captureTimeZone))
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")) + " · " + record.captureTimeZone +
                            " · 原文版本 " + record.sourceRevision, record.originalText, record.tags)
                        Text("手工关联人物：" + record.people.joinToString("、").ifEmpty { "无" })
                            Text(packageEventDateText(record.eventDate), Modifier.testTag("package-restore-event-date-" + record.sourceId))
                            PackageImages(ui.verified?.images.orEmpty().filter { it.sourceId == record.sourceId })
                        }
                    }
                    item("restore-summary") {
                        Text("将恢复 " + ui.verified?.records.orEmpty().size + " 条文本和标签、" + ui.verified?.images.orEmpty().size + " 张原图（" + ui.verified?.images.orEmpty().sumOf { it.byteSize } + " 字节），均分配新 ID 并使用本机密钥重新加密。")
                        Text("已完成整个包的口令、完整性和格式认证。来源追溯由文件提供，认证不证明作者身份。")
                    }
                    packageActivities(ui.verified?.activities, ui.verified?.includesHistory == true,
                        ui.verified?.records.orEmpty().any { it.eventDate != null })
                    packageEditDrafts(ui.verified?.editDrafts)
                    item("confirm-restore") {
                        Button(onClick = { if (ownsRoute()) ui.verified?.let { controller.confirmRestore(ui.session, it.manifest.backupId, it.digest, ui.epoch) } },
                            enabled = ui.verified != null, modifier = Modifier.heightIn(min = androidx.compose.ui.unit.Dp(48f)).testTag("package-confirm")) {
                            Text(if (ui.phase == RecordPackagePhase.UNCERTAIN) "核对同一包恢复回执" else "确认恢复全部为新副本")
                        }
                    }
                }
                else -> item("busy") {
                    Text(if (ui.phase == RecordPackagePhase.IMPORTING) "正在保存并核对新副本；已开始事务可能在退出后完成。"
                        else if (ui.phase == RecordPackagePhase.PICKING_EXPORT || ui.phase == RecordPackagePhase.PICKING_RESTORE) "等待你选择文件。"
                        else "正在核对，请稍候。")
                }
            }
        }
        item("result") {
            ui.error?.let { Text(it, color = AIyouColors.error, modifier = Modifier.testTag("package-error")) }
            ui.notice?.let { Text(it, modifier = Modifier.testTag("package-notice")) }
            if (ui.phase !in setOf(RecordPackagePhase.HOME, RecordPackagePhase.DONE, RecordPackagePhase.FAILED, RecordPackagePhase.CANCELLED))
                TextButton(onClick = { if (ownsRoute()) { clearPasswords(); controller.cancel(ui.session) } },
                    modifier = Modifier.testTag("package-cancel")) { Text("取消并保留已有文件或提交") }
        }
    }
}

private fun LazyListScope.packageEditDrafts(drafts: List<com.t1doo.aiyou.model.OriginalEditDraft>?) {
    if (drafts == null) return
    item("original-edit-summary") {
        Text("独立记录编辑草稿 ${drafts.size} 份", Modifier.testTag("package-edit-draft-summary"))
        Text("每份草稿随完整历史恢复到新副本，正式原文保持包内当前版本；之后仍需手动恢复编辑并保存新版本。不会替换当前输入。")
        Text("本机已修改、弃稿或删除的源草稿不能首次恢复旧包。另一设备离线包无法获知本机之后的修改或删除，请逐份核对。")
    }
    itemsIndexed(drafts, key = { index, _ -> "original-edit:$index" }) { _, draft ->
        Column(Modifier.testTag("package-edit-draft:" + draft.entryId)) {
            Text("记录 ${draft.entryId} · 基于版本 ${draft.baseRevision} · 编辑来源版本 ${draft.sourceRevision}")
            SelectionContainer { Text(draft.text.ifEmpty { "编辑草稿正文为空。" }) }
        }
    }
}

private fun LazyListScope.packageActivities(a:RecordPackageActivities?, history: Boolean = false, eventDates: Boolean = false) {
    if(a==null) return
    item("activity-summary") {
        Text((if (eventDates) "v5" else if (history) "v4" else "v3") + " 包内正式待办 ${a.tasks.size} 条、待确认草稿 ${a.drafts.size} 条；未映射来源 ${a.unavailableSources} 处。",modifier=Modifier.testTag("package-activity-summary"))
        Text("每条内容分配新身份；完整匹配包内原文的来源将映射到新副本，其余来源不可用。草稿不会自动变成正式待办，也没有系统提醒。")
    }
    itemsIndexed(a.tasks,key={i,_->"activity-task:$i"}) {_,item -> Column(Modifier.testTag("package-task:"+item.task.id)) {
        Text(item.task.title);Text("正式待办 · "+when(item.task.status) {
            com.t1doo.aiyou.model.ManualTaskStatus.OPEN->"未完成"
            com.t1doo.aiyou.model.ManualTaskStatus.COMPLETED->"已完成"
            com.t1doo.aiyou.model.ManualTaskStatus.CANCELLED->"已取消"
        })
        Text(item.task.schedule?.let {it.date.toString()+(it.time?.let {t->" "+t} ?: " · 仅日期")+" · "+it.timeZone+
            it.offsetSeconds?.let {seconds->" · 偏移 "+java.time.ZoneOffset.ofTotalSeconds(seconds).id}.orEmpty()} ?: "未设日期和时刻")
        Text(if(item.task.sourceReceipt==null) "独立手工待办，没有来源" else if(item.sourceRecord>=0) "来源完整匹配包内原文" else "来源未完整包含，恢复后不可跳转")
    } }
    itemsIndexed(a.drafts,key={i,_->"activity-draft:$i"}) {_,item -> Column(Modifier.testTag("package-draft:"+item.proposal.id)) {
        Text(item.proposal.titleInput.ifEmpty {"尚未填写标题"});Text("待确认草稿 · 原输入："+item.proposal.dateInput.date+" "+item.proposal.dateInput.time+" · "+item.proposal.dateInput.timeZone)
        Text("重复时刻选择："+when(item.proposal.dateInput.occurrence) {
            com.t1doo.aiyou.model.ManualTaskOccurrence.FIRST->"第一次"
            com.t1doo.aiyou.model.ManualTaskOccurrence.SECOND->"第二次"
            null->"未选择"
        })
        Text(if(item.sourceRecord>=0) "来源完整匹配包内原文，仍需另行预览和确认" else "来源未完整包含，保留输入但不能确认")
    } }
}

@Composable
private fun HistoryWarning() {
    Text("完整历史可能包含你后来修改或删掉的文字及旧版图片。请逐版核对后再确认，恢复会重新保存这些历史内容。",
        modifier = Modifier.testTag("package-history-warning"), color = AIyouColors.error)
}

@Composable
private fun PackageHistoryPreview(record: com.t1doo.aiyou.model.RecordPackageOriginal,
    images: List<com.t1doo.aiyou.model.RecordPackageImage>) {
    Column {
        Text("原捕获时间 " + java.time.Instant.ofEpochMilli(record.capturedAtMillis)
            .atZone(java.time.ZoneId.of(record.captureTimeZone)) + " · 当前原文版本 ${record.sourceRevision}")
        Text(packageEventDateText(record.eventDate), Modifier.testTag("package-history-event-date-" + record.sourceId))
        Text("当前手工标签：" + record.tags.joinToString(" · ").ifEmpty { "无" })
        Text("手工关联人物：" + record.people.joinToString("、").ifEmpty { "无" })
        record.history.forEach { version ->
            Column(Modifier.testTag("package-history-record:${record.sourceId}:${version.revision}")) {
                Text("原文版本 ${version.revision}" + if (version.revision == record.sourceRevision) " · 当前版本" else " · 历史版本")
                Text("版本保存时间：" + java.time.Instant.ofEpochMilli(version.createdAtMillis)
                    .atZone(java.time.ZoneId.of(record.captureTimeZone)), style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(version.originalText.ifEmpty { "此版本没有文字正文。" }) }
                PackageImages(images.filter { it.sourceId == record.sourceId && it.sourceRevision == version.revision })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PackagePreview(metadata: String, text: String, tags: List<String>) {
    Column {
        Text(metadata, style = MaterialTheme.typography.bodySmall)
        Text("手工标签：" + if (tags.isEmpty()) "无" else tags.joinToString(" · "))
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
        HorizontalDivider()
    }
}

@Composable
private fun PackageImages(images: List<com.t1doo.aiyou.model.RecordPackageImage>) {
    Column(Modifier.fillMaxWidth()) {
        Text("原图：${images.size} 张 · ${images.sumOf { it.byteSize }} 字节")
        images.forEachIndexed { index, image ->
            Text("原图 ${index+1} · ${image.mediaType} · ${image.byteSize} 字节 · 来源版本 ${image.sourceRevision}")
            Text("SHA-256 ${image.sha256}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun packageEventDateText(date: com.t1doo.aiyou.model.RecordEventDate?): String =
    date?.let { "事件日期 ${it.localDate} · ${it.timeZone}（仅日期，无时刻）" } ?: "事件日期未知或已清除；旧包不会从记录时间推测"
