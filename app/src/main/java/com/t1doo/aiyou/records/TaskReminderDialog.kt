package com.t1doo.aiyou.records

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.t1doo.aiyou.model.*

class TaskReminderActions(val confirm: () -> Unit, val close: () -> Unit, val mode: (TaskReminderMode) -> Unit)
fun interface TaskReminderObserver { fun actions(actions: TaskReminderActions) }
val LocalTaskReminderObserver = staticCompositionLocalOf<TaskReminderObserver> { TaskReminderObserver {} }

internal fun taskReminderLabel(reminder: TaskReminderSnapshot): String =
    (if (reminder.mode == TaskReminderMode.EXACT) "精确提醒计划 · " else "大约提醒计划 · ") + when (reminder.phase) {
    TaskReminderPhase.PENDING -> "提醒确认已保存 · 系统安排待核对"
    TaskReminderPhase.SCHEDULED -> "调度适配器已确认安排 · 通知送达未验证"
    TaskReminderPhase.REVOKED -> if (reminder.commands.isEmpty()) "提醒计划已撤销" else "提醒计划已撤销 · 系统撤销尚待核对"
    TaskReminderPhase.TRIGGER_ACCEPTED -> "提醒触发已核对 · 未证明通知送达"
    TaskReminderPhase.PLATFORM_UNAVAILABLE -> "提醒确认已保存 · 系统调度未接入，不会发送通知"
    TaskReminderPhase.NOTIFICATION_REQUIRED -> "提醒未安排 · 尚未获得通知授权"
    TaskReminderPhase.NOTIFICATION_DENIED -> "提醒未安排 · 通知授权已拒绝"
    TaskReminderPhase.CHANNEL_DISABLED -> "提醒未安排 · 通知渠道已关闭"
    TaskReminderPhase.EXACT_REQUIRED -> "精确提醒未安排 · 尚未获得精确闹钟授权"
    TaskReminderPhase.EXACT_DENIED -> "精确提醒未安排 · 精确闹钟授权已拒绝，可另行明确选择大约提醒"
    TaskReminderPhase.FAILED -> "提醒未确认安排 · 请重新读取状态后再决定是否重新确认"
    TaskReminderPhase.TRIGGER_BLOCKED -> "触发时能力不可用 · 未发布通知"
    TaskReminderPhase.DELIVERY_BLOCKED -> "发布前能力不可用 · 未发布，不会自动补发"
    TaskReminderPhase.POSTING, TaskReminderPhase.POST_UNKNOWN -> "通知发布结果未核实 · 不会自动重发"
    TaskReminderPhase.NOTIFICATION_POSTED -> "通知发布调用已返回 · 不证明已看见"
    TaskReminderPhase.POST_FAILED -> "通知发布失败 · 未自动重发"
    TaskReminderPhase.TRIGGER_UNDELIVERED -> "触发已记录 · 未发布，不会自动补发"
    TaskReminderPhase.TIME_PASSED -> "时间已过 · 未重新安排，通知结果未核实"
}

@Composable
fun TaskReminderDialog(local: LocalRecordUiState, controller: TaskReminderController, ownsRoute: () -> Boolean) {
    val editor = local.reminders.editor ?: return
    val context=LocalContext.current
    val currentEditor by rememberUpdatedState(editor.session)
    val route by rememberUpdatedState(ownsRoute)
    var requested by remember { mutableStateOf<Long?>(null) }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val owner=requested;requested=null
        if(owner==currentEditor&&route())controller.capabilitiesChanged(requireNotNull(owner))
    }
    val specialPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val owner=requested;requested=null
        if(owner==currentEditor&&route())controller.capabilitiesChanged(requireNotNull(owner))
    }
    val state = local.reminders
    val ready = local.ready && !local.opening && local.error == null && local.tasks.available && !local.tasks.loading
    val canConfirm = ready && !state.busy && state.pending == null && !state.conflict &&
        local.tasks.items.singleOrNull { it.id == editor.task.id } == editor.task
    val actions = TaskReminderActions({ if (ownsRoute()) controller.confirm(editor.session) },
        { if (ownsRoute()) controller.close(editor.session) }, { if (ownsRoute()) controller.mode(editor.session, it) })
    val observer = LocalTaskReminderObserver.current
    SideEffect { observer.actions(actions) }
    Dialog(onDismissRequest = actions.close) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (editor.cancelling) "撤销提醒计划" else "单独确认提醒计划", style = MaterialTheme.typography.titleLarge)
                if (ready) {
                    Text(editor.task.title, Modifier.testTag("reminder-confirm-title"))
                    Text(manualTaskDateLabel(editor.task))
                } else Text("本机待办暂不可核对，预览已隐藏，请恢复读取后再操作。")
                if (ready && local.tasks.items.singleOrNull { it.id == editor.task.id } != editor.task)
                    Text("待办或提醒状态已变化，请关闭后重新查看，再单独确认。")
                Text("确认会保存本机提醒计划，并在当前系统能力允许时安排提醒；通知是否准时或可见仍受系统限制。系统授权需点击下方按钮单独开启。")
                if(!editor.cancelling){
                    TextButton(enabled=canConfirm&&controller.mayRequestCapability(editor.session)&&requested==null,onClick={
                        if(ownsRoute()&&controller.mayRequestCapability(editor.session)){
                            requested=editor.session
                            try{
                                if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else specialPermission.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
                            }catch(_:Exception){requested=null;controller.capabilitiesChanged(editor.session)}
                        }
                    },modifier=Modifier.testTag("reminder-notification-permission")){Text("检查通知授权")}
                    if(editor.mode==TaskReminderMode.EXACT&&Build.VERSION.SDK_INT>=31)TextButton(enabled=canConfirm&&controller.mayRequestCapability(editor.session)&&requested==null,onClick={
                        if(ownsRoute()&&controller.mayRequestCapability(editor.session)){
                            requested=editor.session
                            try{specialPermission.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+context.packageName)))}
                            catch(_:Exception){requested=null;controller.capabilitiesChanged(editor.session)}
                        }
                    },modifier=Modifier.testTag("reminder-exact-permission")){Text("检查精确闹钟授权")}
                }
                Text("修改、延后或清除待办时间会撤销旧提醒计划；新时间需要再次单独确认。")
                if (!editor.cancelling) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskReminderMode.entries.forEach { mode ->
                        FilterChip(selected = editor.mode == mode, onClick = { actions.mode(mode) },
                            enabled = ready && !state.busy && state.pending == null,
                            label = { Text(if (mode == TaskReminderMode.EXACT) "精确提醒" else "大约提醒") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("reminder-mode-" + mode))
                    }
                }
                if (state.busy) Text("正在保存，尚未确认…")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = canConfirm, onClick = actions.confirm,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reminder-confirm")) {
                    Text(if (editor.cancelling) "确认撤销提醒计划" else "确认保存提醒计划")
                }
                if (state.error != null) TextButton(enabled = ready && !state.busy,
                    onClick = { if (ownsRoute()) controller.retry() }, modifier = Modifier.testTag("reminder-dialog-retry")) {
                    Text(if (state.conflict) "重新读取待办" else if (state.pending != null) "重试确认" else "重新读取提醒状态")
                }
                TextButton(enabled = !state.busy, onClick = actions.close,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reminder-close")) { Text("关闭") }
            }
        }
    }
}
