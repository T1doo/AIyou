package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.*

@Composable
fun RecycleBinScreen(local: LocalRecordUiState, ui: RecycleBinUiState, controller: RecycleBinController,
    ownsRoute: () -> Boolean, modifier: Modifier = Modifier) {
    val available = local.ready && !local.opening && local.error == null && local.supportsRecycleBin
    val selected = ui.records.singleOrNull { it.entry.id == ui.selectedId }
    val erasing = selected?.entry?.id in ui.erasures
    LazyColumn(modifier.fillMaxSize().padding(horizontal = AIyouDimensions.pageNarrow),
        contentPadding = PaddingValues(vertical = AIyouDimensions.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
        item("summary") {
            Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("回收站", style = MaterialTheme.typography.headlineMedium)
                Text(if(local.supportsPermanentErasure) "移入的记录不出现在当前列表、检索、新导出或 AI 使用范围。可以明确恢复，也可以选定单条永久删除。独立手工待办保持原状态。"
                    else "移入的记录不出现在当前列表、检索、新导出或 AI 使用范围。原文版本、手工标签和附件保留，可明确恢复。独立手工待办保持原状态。")
                Text("已导出或另存的文件无法撤回。本页不会自动清空。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { if (ownsRoute()) controller.refresh(ui.session) }, enabled = available && !ui.busy && ui.confirmation == null,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("recycle-refresh")) { Text("重新读取") }
            }
        }
        when {
            !available -> item("unavailable") { StatusNotice("回收站暂不可读取", local.error ?: "请等待本机记录读取完成。", state = ComponentState.ERROR) }
            ui.loading -> item("loading") { StatusNotice("正在读取回收站", "请稍候。", state = ComponentState.LOADING) }
            ui.error != null -> item("error") { StatusNotice("回收站读取失败", ui.error, state = ComponentState.ERROR) }
            else -> {
                ui.notice?.let { item("notice") { Text(it, modifier = Modifier.testTag("recycle-notice")) } }
                if (selected != null) item("original") {
                    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        Text(originalMetadata(selected.entry), style = MaterialTheme.typography.bodySmall)
                        Text("已保留 ${selected.revisionCount} 个原文版本、${selected.attachmentCount} 项附件记录（含待确认项）。")
                        if (selected.tags.names.isNotEmpty()) Text("手工标签：" + selected.tags.names.joinToString("、"))
                        Text(selected.entry.originalText, modifier = Modifier.testTag("recycle-original"), style = MaterialTheme.typography.bodyLarge)
                        if (erasing) Text("永久删除未完成：这条记录已封闭，不能恢复。请继续确认同一删除操作。", modifier=Modifier.testTag("recycle-erasure-pending"))
                        TextButton(onClick = { if (ownsRoute()) controller.select(ui.session, null) }, enabled = !ui.busy && ui.confirmation == null,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("返回回收站列表") }
                        TextButton(onClick = { if (ownsRoute()) controller.requestRestore(ui.session, selected.entry.id, selected.generation) },
                            enabled = !ui.busy && ui.confirmation == null && !erasing,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("recycle-restore")) { Text("恢复这条记录") }
                        if (local.supportsPermanentErasure) TextButton(onClick = {
                            if (ownsRoute()) controller.requestErasure(ui.session,selected.entry.id,selected.generation)
                        }, enabled=!ui.busy && ui.confirmation==null,
                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("recycle-erase")) {
                            Text(if(erasing) "继续永久删除" else "永久删除这条记录")
                        }
                    }
                } else {
                    if (ui.records.isEmpty()) item("empty") { StatusNotice("回收站为空", "这里没有可恢复的记录。", state = ComponentState.EMPTY) }
                    items(ui.records, key = { it.entry.id }) { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            Text(originalMetadata(record.entry), style = MaterialTheme.typography.bodySmall)
                            Text(originalPreview(record.entry.originalText), maxLines = 3)
                            TextButton(onClick = { if (ownsRoute()) controller.select(ui.session, record.entry.id) }, enabled = !ui.busy && ui.confirmation == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("recycle-view-${record.entry.id}")) { Text("查看原文") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecycleConfirmationDialog(local: LocalRecordUiState, ui: RecycleBinUiState,
    controller: RecycleBinController, ownsRoute: () -> Boolean) {
    val confirmation = ui.confirmation
    val restoring = confirmation?.record?.deleted == true
    val permanent = confirmation?.permanent == true
    val available = local.ready && !local.opening && local.error == null
    if (ui.browsing && confirmation == null) return
    AlertDialog(onDismissRequest = {
        if (ownsRoute() && !ui.busy) {
            if (confirmation != null) controller.cancelConfirmation(ui.session, confirmation.id) else controller.close(ui.session)
        }
    }, title = { Text(if(permanent) "永久删除这条记录？" else if (restoring) "恢复这条记录？" else "移入回收站？") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            if (available && confirmation != null && !confirmation.uncertain && !confirmation.conflict) {
                Text(originalMetadata(confirmation.record.entry), style = MaterialTheme.typography.bodySmall)
                Text("确认时的原文（节选）：" + originalPreview(confirmation.record.entry.originalText), maxLines = 3)
            }
            Text(if(permanent) "将删除本机这条记录的全部原文版本、手工标签、派生内容、登记的附件和编辑草稿。确认后不能恢复；中断会保留删除意图并提供重试。独立手工待办保持原状态。"
                else if (restoring) "恢复到本机列表和本地检索，原文版本、手工标签和附件保留。恢复后不参与 AI，旧远端授权不会恢复。"
                else "移入后从当前列表、检索、新导出和 AI 使用范围排除，旧 AI 授权撤销。原文版本、手工标签和附件保留，可在回收站恢复。独立手工待办保持原状态。")
            Text("已经导出、另存或发给服务商的数据无法通过本次操作撤回。")
            if (ui.loading) Text("正在确认本机原文状态…")
            if (confirmation?.busy == true) Text("正在确认本机操作结果…")
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            confirmation?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!available) Text("当前本机记录暂不可读取，请重试本机读取。")
        } },
        confirmButton = { TextButton(onClick = {
            if (ownsRoute() && confirmation != null) controller.confirm(ui.session, confirmation.id)
        }, enabled = available && !ui.busy && confirmation != null && !confirmation.conflict,
            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("recycle-confirm")) {
            Text(if (confirmation?.uncertain == true) "重试确认" else if(permanent) "确认永久删除" else if (restoring) "确认恢复" else "确认移入")
        } },
        dismissButton = { TextButton(onClick = {
            if (ownsRoute()) {
                if (confirmation != null) controller.cancelConfirmation(ui.session, confirmation.id) else controller.close(ui.session)
            }
        }, enabled = !ui.busy, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("recycle-cancel")) {
            Text(if (confirmation?.uncertain == true) "返回（不撤销已提交操作）" else "取消")
        } })
}
