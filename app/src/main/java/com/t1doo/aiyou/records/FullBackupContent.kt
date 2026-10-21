package com.t1doo.aiyou.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FullBackupContent(ui:RecordPackageUiState,controller:RecordPackageController,
    available:Boolean,pickerAvailable:Boolean,ownsRoute:()->Boolean,password:TextFieldState,again:TextFieldState,
    clearPasswords:()->Unit,modifier:Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp).testTag("full-backup"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("完整资料库备份",style=MaterialTheme.typography.headlineSmall)
            Text("包含全部记录与回收站、完整修订和原图、收藏、手工人物／标签／事件日期、正式待办及提案、手工记忆及候选确认历史、捕获草稿和已保留编辑草稿、完成回执与删除证明。未保存的表单输入不在资料库中。")
            Text("不包含模型结果、搜索索引、联网授权、任何密钥、设备锁及显示偏好；不是设备镜像。恢复后保持本机处理，提醒意图保留为已撤销，须重新确认。")
            Text("上限：256 条记录、4096 个修订、256 张 JPEG/PNG 原图；单图16 MiB、原图共64 MiB、资料清单16 MiB，任务／提案与记忆／确认各4096行。超限、损坏或未完成操作会整体拒绝。")
            Text("仅恢复到严格空资料库，保留原身份、版本和回收站状态。已有资料、删除证明或未保存输入会阻止导入；请先保全现有内容。本入口不提供覆盖和清库。")
            Text("请保存独立恢复口令，丢失后无法解密。失败文件会保留；同一备份完成后的重试只核对回执，不会复活后来的删除。") }
        ui.fullSummary?.let { summary -> item {
            Text("范围清单",style=MaterialTheme.typography.titleMedium)
            Text("记录 ${summary.records} 条（回收站 ${summary.trash} 条）；修订 ${summary.revisions} 个；原图 ${summary.images} 张／${summary.imageBytes} 字节。",Modifier.testTag("full-backup-summary"))
            if(summary.audioClips>0) Text("原音 ${summary.audioClips} 段／${summary.audioBytes} 字节，包含已确认的原音草稿；不会恢复录音、播放或系统权限。",Modifier.testTag("full-backup-audio-summary"))
            Text("编辑草稿 ${summary.editDrafts} 份；捕获草稿槽 ${summary.captureDrafts} 个；任务与提案 ${summary.tasksAndProposals} 行；记忆与确认历史（含普通偏好自述）${summary.memoriesAndReviews} 行；永久删除证明 ${summary.deletionProofs} 份。")
        } }
        if(available) when(ui.phase) {
            RecordPackagePhase.HOME,RecordPackagePhase.DONE,RecordPackagePhase.FAILED,RecordPackagePhase.CANCELLED -> item {
                Button(onClick={if(ownsRoute()) controller.prepareFull(ui.session)},Modifier.testTag("full-backup-preview")) { Text("核对完整备份范围") }
                OutlinedButton(onClick={if(ownsRoute()) controller.chooseRestore(ui.session)},enabled=pickerAvailable,modifier=Modifier.testTag("full-backup-open")) { Text("选择完整备份恢复") }
            }
            RecordPackagePhase.EXPORT_PREVIEW -> {
                item { Row { Checkbox(ui.scopeAccepted,{ accepted -> if(ownsRoute()) ui.fullSummary?.let { controller.acceptFullScope(ui.session,it.backupId,accepted) } },Modifier.testTag("full-backup-scope"))
                    Text("我已核对完整范围、排除项与上限。") } }
                item { OutlinedSecureTextField(password,label={Text("独立恢复口令")},textObfuscationMode=TextObfuscationMode.Hidden,
                    inputTransformation=InputTransformation.maxLength(128),modifier=Modifier.fillMaxWidth().testTag("full-backup-password")) }
                item { OutlinedSecureTextField(again,label={Text("再次输入恢复口令")},textObfuscationMode=TextObfuscationMode.Hidden,
                    inputTransformation=InputTransformation.maxLength(128),modifier=Modifier.fillMaxWidth().testTag("full-backup-password-again")) }
                item { Button(onClick={ if(ownsRoute()) ui.fullSummary?.let { controller.encryptFull(ui.session,it.backupId,
                    CharArray(password.text.length) { password.text[it] },CharArray(again.text.length) { again.text[it] }) };clearPasswords() },
                    enabled=ui.scopeAccepted && pickerAvailable,modifier=Modifier.testTag("full-backup-save")) { Text("加密并选择保存位置") } }
            }
            RecordPackagePhase.RESTORE_PASSWORD -> item {
                Text("先认证全部成员和终止帧；认证后还需单独确认恢复。")
                OutlinedSecureTextField(password,label={Text("独立恢复口令")},textObfuscationMode=TextObfuscationMode.Hidden,
                    inputTransformation=InputTransformation.maxLength(128),modifier=Modifier.fillMaxWidth().testTag("full-backup-password"))
                Button(onClick={if(ownsRoute()) controller.authenticate(ui.session,CharArray(password.text.length) { password.text[it] });clearPasswords()},modifier=Modifier.testTag("full-backup-authenticate")) { Text("认证并预览范围") }
            }
            RecordPackagePhase.RESTORE_PREVIEW,RecordPackagePhase.UNCERTAIN -> item {
                Text("完整备份已认证。来源由文件提供，口令认证不证明作者身份。只允许空目标；草稿仍是草稿，不会自动保存或执行任务。")
                Button(onClick={ if(ownsRoute()) ui.fullSummary?.let { summary -> ui.fullDigest?.let { controller.restoreFull(ui.session,summary.backupId,it,ui.epoch) } } },
                    modifier=Modifier.testTag("full-backup-restore")) { Text(if(ui.phase==RecordPackagePhase.UNCERTAIN) "核对同一备份回执" else "确认导入空资料库") }
            }
            else -> item { Text("正在核对或等待文件选择；已开始的提交可能在退出后完成。") }
        } else item { Text("本机资料暂不可用，操作已暂停。") }
        item {
            ui.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("full-backup-error")) }
            ui.notice?.let { Text(it,modifier=Modifier.testTag("full-backup-notice")) }
            if(ui.phase!=RecordPackagePhase.HOME) TextButton(onClick={if(ownsRoute()) { clearPasswords();controller.cancel(ui.session) }},modifier=Modifier.testTag("full-backup-cancel")) { Text("取消并保留已有文件或提交") }
        }
    }
}
