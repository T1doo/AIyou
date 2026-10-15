package com.t1doo.aiyou.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordPackageScreen(local: LocalRecordUiState, ui: RecordPackageUiState, controller: RecordPackageController,
    picker: RecordPackagePicker?, ownsRoute: () -> Boolean, modifier: Modifier = Modifier) {
    val available = ownsRoute() && local.ready && !local.opening && local.error == null && local.supportsRecordPackages
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
            Text("包含选定的当前原文、手工标签和当前版本已完整保存的 JPEG/PNG 原图。历史、待办、草稿、AI 结果及所有密钥不包含在内；这不是整库备份。")
            Text("恢复会创建新副本，不覆盖旧记录；初始设置为不参与 AI。同一包重复恢复只核对第一次回执。")
            Text("一次最多 20 条记录、20 张原图；单图最多 16 MiB，图片合计最多 64 MiB。旧 v1 包仅含文本和标签。")
            Text("独立恢复口令丢失无法解密。取消或失败可能留下空文件或部分密文，应用不会自动删除。")
        }
        if (!available) item("unavailable") {
            Text(local.error ?: "当前本机数据暂不可读取，预览与确认已暂停。", color = AIyouColors.error)
        } else {
            when (ui.phase) {
                RecordPackagePhase.HOME, RecordPackagePhase.FAILED, RecordPackagePhase.CANCELLED, RecordPackagePhase.DONE -> item("home-actions") {
                    Column {
                        Button(onClick = { if (ownsRoute()) controller.selectExport(ui.session) }, enabled = local.entries.isNotEmpty(),
                            modifier = Modifier.testTag("package-export")) { Text("选择原文制作加密包") }
                        OutlinedButton(onClick = { if (ownsRoute()) controller.chooseRestore(ui.session) },
                            enabled = picker != null, modifier = Modifier.testTag("package-restore")) { Text("选择加密包恢复") }
                    }
                }
                RecordPackagePhase.SELECTING -> {
                    itemsIndexed(local.entries, key = { _, e -> "source:" + e.id }) { _, entry ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(ui.selection.any { it.sourceId == entry.id }, onCheckedChange = {
                                if (ownsRoute()) controller.toggle(ui.session, entry.id, entry.currentRevision)
                            }, modifier = Modifier.testTag("package-select:" + entry.id))
                            Text(originalMetadata(entry), modifier = Modifier.weight(1f))
                        }
                    }
                    item("prepare") { Button(onClick = { if (ownsRoute()) controller.prepare(ui.session) },
                        enabled = ui.selection.isNotEmpty(), modifier = Modifier.testTag("package-preview")) { Text("预览完整原文与范围") } }
                }
                RecordPackagePhase.EXPORT_PREVIEW -> {
                    itemsIndexed(ui.snapshot?.records.orEmpty(), key = { _, r -> "preview:" + r.entry.id }) { _, record ->
                        PackagePreview(originalMetadata(record.entry), record.entry.originalText, record.tags.names)
                        PackageImages(ui.mediaPreview?.images.orEmpty().filter { it.sourceId == record.entry.id })
                    }
                    item("image-summary") {
                        Text("本次范围：" + ui.snapshot?.records.orEmpty().size + " 条记录，" + ui.mediaPreview?.images.orEmpty().size +
                            " 张原图，原图合计 " + (ui.mediaPreview?.imageBytes ?: 0L) + " 字节。", modifier = Modifier.testTag("package-image-summary"))
                    }
                    item("scope-confirm") {
                        Row {
                            Checkbox(ui.scopeAccepted, onCheckedChange = { accepted ->
                                if (ownsRoute()) ui.snapshot?.let { controller.acceptScope(ui.session, it.id, accepted) }
                            }, modifier = Modifier.testTag("package-scope"))
                            Text("确认以上当前原文、标签及列出的原图范围，并自行保管独立恢复口令。")
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
                    itemsIndexed(ui.verified?.records.orEmpty(), key = { i, _ -> "restore-preview:" + i }) { _, record ->
                        PackagePreview("原捕获时间 " + java.time.Instant.ofEpochMilli(record.capturedAtMillis)
                            .atZone(java.time.ZoneId.of(record.captureTimeZone))
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")) + " · " + record.captureTimeZone +
                            " · 原文版本 " + record.sourceRevision, record.originalText, record.tags)
                        PackageImages(ui.verified?.images.orEmpty().filter { it.sourceId == record.sourceId })
                    }
                    item("restore-summary") {
                        Text("将恢复 " + ui.verified?.records.orEmpty().size + " 条文本和标签、" + ui.verified?.images.orEmpty().size + " 张原图（" + ui.verified?.images.orEmpty().sumOf { it.byteSize } + " 字节），均分配新 ID 并使用本机密钥重新加密。")
                        Text("已完成整个包的口令、完整性和格式认证。来源追溯由文件提供，认证不证明作者身份。")
                    }
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
