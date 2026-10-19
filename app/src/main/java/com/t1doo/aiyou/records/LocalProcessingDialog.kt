package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.domain.LocalProcessingChoice
import com.t1doo.aiyou.model.*

internal class LocalProcessingDialogActions(val confirm: () -> Unit, val cancel: () -> Unit)
internal fun interface LocalProcessingDialogObserver { fun onActions(actions: LocalProcessingDialogActions) }
internal val LocalProcessingDialogObservation = staticCompositionLocalOf<LocalProcessingDialogObserver?> { null }

@Composable
internal fun LocalProcessingDialog(ui: LocalProcessingUiState, entry: StoredEntry,
    controller: LocalProcessingController, canUse: () -> Boolean) {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    DisposableEffect(controller, ui.session) { onDispose { controller.close(ui.session) } }
    val confirmation = ui.confirmation
    val withdrawal = ui.withdrawalConfirmation
    val authorization = ui.authorizationConfirmation
    val connectionReview = ui.connectionReview
    val actions = LocalProcessingDialogActions(
        { if (canUse()) { if (withdrawal != null) controller.confirmWithdrawal(ui.session, withdrawal)
            else if (authorization != null) controller.confirmAuthorization(ui.session, authorization)
            else if (confirmation != null) controller.confirm(ui.session, confirmation) } },
        { if (canUse()) { if (withdrawal != null) controller.cancelWithdrawal(ui.session, withdrawal)
            else if (authorization != null) controller.cancelAuthorization(ui.session, authorization)
            else if (confirmation != null) controller.cancel(ui.session, confirmation) } })
    LocalProcessingDialogObservation.current?.let { observer -> SideEffect { observer.onActions(actions) } }
    Dialog(onDismissRequest = { if (canUse()) controller.close(ui.session) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(AIyouDimensions.pageNarrow).widthIn(max = AIyouDimensions.contentMaxWidth)
                .heightIn(max = height), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow)
                    .testTag("local-processing-dialog"), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("这条记录的处理范围", style = MaterialTheme.typography.titleLarge)
                    Text("原文版本 ${ui.source.revision} · ${entry.captureTimeZone} · ${java.time.Instant.ofEpochMilli(entry.capturedAtMillis)}")
                    val excerpt = entry.originalText.let { it.substring(0, it.offsetByCodePoints(0, minOf(160, it.codePointCount(0, it.length)))) }
                    Text(excerpt.ifBlank { "图片记录 · 无文字正文" }, modifier = Modifier.testTag("local-processing-source"))
                    Text("仅影响这条记录。原文、附件、收藏、手工标签及人工待办保留。切换会清除相关 AI 派生内容并使旧候选失效。")
                    Text("本操作不发送记录，也不会自动生成或恢复旧结论。已另存的文件和服务商数据无法由此撤回。")
                    if (ui.busy) Text("正在核对本机处理范围…")
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("local-processing-error")) }
                    ui.notice?.let { Text(it, modifier = Modifier.testTag("local-processing-notice")) }
                    if (authorization != null && connectionReview != null) {
                        val c=checkNotNull(connectionReview.selectedConfiguration)
                        Text("审阅这条记录的远端授权",style=MaterialTheme.typography.titleMedium)
                        Text("原文版本 ${authorization.expected.binding.source.revision} · 授权代次 ${authorization.expected.binding.generation}",Modifier.testTag("record-connection-source-version"))
                        Text("连接：${c.details.name} · 配置版本 ${c.version}")
                        Text("主机：${java.net.URI(c.details.apiRoot).host}")
                        Text("HTTPS API根路径：${c.details.apiRoot}",Modifier.testTag("record-connection-destination"))
                        Text("协议：${if(c.details.protocol==ConnectionProtocol.CHAT_COMPLETIONS) "Chat Completions" else "Anthropic Messages"}")
                        Text("模型：${c.details.modelId}")
                        Text(authorizationStatus(connectionReview),Modifier.testTag("record-connection-secret-status"))
                        val observation=connectionReview.capabilityProfile?.observation
                        Text("固定样本文本流："+when(observation?.state) {
                            CapabilityState.SUPPORTED -> "已通过；不代表问答或整理语义质量。"
                            CapabilityState.UNSUPPORTED -> "所配文本端点/模型不可用。"
                            else -> "尚未验证或本次未能确认。"
                        },Modifier.testTag("record-connection-capability-boundary"))
                        observation?.let { Text("检查时间：${java.time.Instant.ofEpochMilli(it.checkedAtMillis)} · ${com.t1doo.aiyou.ui.capabilityReasonLabel(it.reason)}") }
                        Text("仅授权用途：${if(connectionReview.purpose==TextPurpose.CONVERSATION) "对话" else "整理"}")
                        Text("有效区间（一小时）：${authorization.grant?.issuedAt} 至 ${authorization.grant?.expiresAt}",Modifier.testTag("record-connection-interval"))
                        Text("本次将替换这条记录此前的连接和单一用途授权，使旧作业与结果失效。仅保存授权，不发送记录；之后发送仍须单独明确操作，当前生产发送关闭。")
                        Button(enabled=!ui.busy,onClick=actions.confirm,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                            .testTag("record-connection-confirm")){Text("仅保存这条记录的授权")}
                        TextButton(enabled=!ui.busy,onClick=actions.cancel,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                            .testTag("record-connection-cancel")){Text("取消授权审阅")}
                    } else if (confirmation != null || withdrawal != null) {
                        Text(if (withdrawal != null) "确认撤回这条记录的远端授权？"
                            else if (confirmation?.target == LocalProcessingChoice.AI_NONE) "确认不要据此推断？" else "确认仅在本机处理？",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (withdrawal != null) "改为仅本机并清除这条记录的远端授权，使旧整理结果失效。原文和人工内容保留。"
                            else if (confirmation?.target == LocalProcessingChoice.AI_NONE) "保存与人工浏览，不参与任何 AI；相关派生内容将清除。"
                            else "允许已安装的端侧处理，不能送到远端；需要你重新主动生成。")
                        Button(enabled = !ui.busy, onClick = actions.confirm,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-confirm")) { Text("确认修改这条记录") }
                        TextButton(enabled = !ui.busy, onClick = actions.cancel,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-cancel")) { Text("取消修改") }
                    } else ui.snapshot?.let { snapshot ->
                        Text("当前：" + when (snapshot.policy) {
                            ProcessingPolicy.AI_NONE -> "不要据此推断"
                            ProcessingPolicy.LOCAL_ONLY -> "仅本机"
                            ProcessingPolicy.REMOTE_ALLOWED -> "远端处理策略（还需匹配的当前授权）"
                        }, modifier = Modifier.testTag("local-processing-current"))
                        ui.authorization?.let { preview ->
                            Text(authorizationStatus(preview), modifier = Modifier.testTag("record-authorization-connection"))
                            Text(if (preview.authorizationCurrent) "本次核对时，此用途授权在有效期内；当前不能发送。"
                                else "本次核对未发现有效的远端授权。", modifier = Modifier.testTag("record-authorization-current"))
                            preview.snapshot.consent?.let { grant ->
                                Text("授权用途：" + if (grant.purpose == com.t1doo.aiyou.model.TextPurpose.ORGANIZATION) "整理" else "对话")
                                Text("授权区间：${grant.issuedAt} 至 ${grant.expiresAt}")
                            }
                            if (preview.snapshot.connection != null || preview.snapshot.consent != null || snapshot.policy == ProcessingPolicy.REMOTE_ALLOWED) {
                                TextButton(enabled = !ui.busy && ui.pending == null && ui.pendingWithdrawal == null && ui.pendingAuthorization == null && snapshot.generation < Long.MAX_VALUE,
                                    onClick = { if (canUse()) controller.prepareWithdrawal(ui.session) },
                                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("record-authorization-withdraw")) { Text("撤回远端授权，改为仅本机") }
                            }
                        } ?: if (snapshot.policy == ProcessingPolicy.REMOTE_ALLOWED) {
                            Text("远端授权详情暂不可核对。当前页面不提供发送能力。")
                        } else Unit
                        ui.connectionError?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("record-connection-directory-error")) }
                        if(ui.connectionCatalogOwner!=null && ui.connectionError==null) {
                            Text("从本机连接目录选择本条用途",style=MaterialTheme.typography.titleMedium)
                            Text("每条记录仅保留一个当前连接和用途；选择后先审阅，不会自动发送。")
                            if(ui.connections.isEmpty())Text("本机尚无连接配置，请在设置中保存连接及密钥引用后重新打开。")
                            ui.connections.forEach { c ->
                                Column(Modifier.fillMaxWidth().testTag("record-connection-choice-${c.id}"),verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                                    Text("${c.details.name} · 版本 ${c.version} · ${java.net.URI(c.details.apiRoot).host}")
                                    Text("模型：${c.details.modelId}")
                                    Text(if(c.secretRef==null)"尚未配置密钥引用；不能审阅授权。" else "密钥引用已配置；审阅时再核验，不展示密钥。")
                                    TextPurpose.entries.forEach { purpose ->
                                        val planned=if(purpose==TextPurpose.CONVERSATION)ModelConfigurationPurpose.CONVERSATION else ModelConfigurationPurpose.ORGANIZATION
                                        TextButton(enabled=!ui.busy && ui.pending==null && ui.pendingWithdrawal==null && ui.pendingAuthorization==null &&
                                            c.secretRef!=null && planned in c.details.purposes && snapshot.generation<Long.MAX_VALUE,
                                            onClick={if(canUse())controller.prepareAuthorization(ui.session,c,purpose)},
                                            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("record-connection-review-${c.id}-${purpose.name}")) {
                                            Text(if(purpose==TextPurpose.CONVERSATION)"审阅本条对话授权" else "审阅本条整理授权")
                                        }
                                    }
                                }
                            }
                        }
                        if (snapshot.policy != ProcessingPolicy.REMOTE_ALLOWED) {
                            TextButton(enabled = !ui.busy && ui.pending == null && ui.pendingWithdrawal == null && ui.pendingAuthorization == null && snapshot.policy != ProcessingPolicy.AI_NONE && snapshot.generation < Long.MAX_VALUE,
                                onClick = { if (canUse()) controller.prepare(ui.session, LocalProcessingChoice.AI_NONE) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-none")) { Text("不要据此推断") }
                            TextButton(enabled = !ui.busy && ui.pending == null && ui.pendingWithdrawal == null && ui.pendingAuthorization == null && snapshot.policy != ProcessingPolicy.LOCAL_ONLY && snapshot.generation < Long.MAX_VALUE,
                                onClick = { if (canUse()) controller.prepare(ui.session, LocalProcessingChoice.LOCAL_ONLY) },
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-local")) { Text("仅本机") }
                        }
                    }
                    if (ui.error != null) TextButton(enabled = !ui.busy, onClick = { if (canUse()) controller.retry(ui.session, ui.request) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-retry")) { Text("重新核对处理范围") }
                    if (ui.pending != null || ui.pendingWithdrawal != null || ui.pendingAuthorization != null || ui.busy) Text("关闭不会撤回已经提交的修改；可重新打开核对实际范围。")
                    TextButton(onClick = { if (canUse()) controller.close(ui.session) },
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("local-processing-close")) { Text("关闭") }
                }
            }
        }
    }
}
