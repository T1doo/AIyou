package com.t1doo.aiyou.ui

import android.app.Activity
import android.content.ContextWrapper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.domain.ModelConnectionCatalog
import com.t1doo.aiyou.domain.ConnectionSecretVault
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*

internal val LocalModelConfigurationActionObserver = staticCompositionLocalOf<((String,()->Unit)->Unit)?> { null }

/** Private editing stays in this live settings session, never SavedState or ordinary preferences. */
@Composable
fun ModelConnectionSettings(controller: ModelConnectionsController, owner: ModelConnectionCatalog?, available: Boolean,
    secretOwner: ConnectionSecretVault?, capabilityOwner: com.t1doo.aiyou.domain.ModelCapabilityRepository? = null) {
    val context=LocalContext.current
    val activity=remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { base->base!==it } }
        .filterIsInstance<Activity>().firstOrNull() }
    DisposableEffect(controller,owner,secretOwner,capabilityOwner,available) {
        val session=controller.open(owner.takeIf { available })
        onDispose { controller.close(session,retainEditor=activity?.isChangingConfigurations==true) }
    }
    val emission by controller.state.collectAsState()
    val ui=emission.let { controller.visible() }
    val session=ui.session
    Text("本机连接配置",style=MaterialTheme.typography.titleMedium)
    Text("可保存服务、模型、计划用途与本机密钥。能力检查须单独发起；保存不会联网或改变记录的处理范围。当前正式版本尚不可调用。",
        style=MaterialTheme.typography.bodyMedium)
    Text("连接配置仅留在本机，不进入资料库备份；恢复资料库不会覆盖它。",style=MaterialTheme.typography.bodySmall)
    if(ui.busy) Text("正在读取本机配置…")
    ui.error?.let { Text(it,color=AIyouColors.error) }
    ui.capabilityError?.let { Text(it,color=AIyouColors.error,modifier=Modifier.testTag("model-capability-read-error")) }
    ui.notice?.let { Text(it,modifier=Modifier.testTag("model-config-notice")) }
    OutlinedButton(onClick={controller.reload(session)},enabled=available&&!ui.busy&&ui.editor?.busy!=true&&ui.secretEditor?.busy!=true&&ui.probeEditor?.busy!=true,
        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-reload")) { Text("重新读取配置") }
    if(ui.editor==null&&ui.secretEditor==null&&ui.probeEditor==null) {
        Button(onClick={controller.begin(session)},enabled=available&&!ui.busy&&ui.error==null,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-add")) { Text("添加连接配置") }
        if(!ui.busy&&ui.error==null&&ui.configurations.isEmpty()) Text("还没有连接配置。仍可继续本机记录。")
    }
    for(config in ui.configurations) {
        Surface(color=AIyouColors.surfaceMuted,shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd),verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text(config.details.name,style=MaterialTheme.typography.titleSmall)
                Text("版本 ${config.version} · ${protocolLabel(config.details.protocol)}")
                Text(config.details.apiRoot);Text("模型：${config.details.modelId}")
                Text("计划用途："+config.details.purposes.sortedBy { it.ordinal }.joinToString("、"){purposeLabel(it)})
                val profile=ui.capabilityProfiles.singleOrNull { it.connection==config }
                Text(if(config.secretRef==null) "密钥未配置 · 尚不可调用" else "本机密钥引用已保存 · 尚不可调用",style=MaterialTheme.typography.bodySmall)
                Text("文本流：${capabilityStateLabel(profile?.observation?.state?:CapabilityState.UNKNOWN)}",
                    modifier=Modifier.testTag("model-capability-state-${config.id}"))
                if(profile!=null) {
                    Text("检查时间：${java.time.Instant.ofEpochMilli(profile.observation.checkedAtMillis)} · 连接版本 ${profile.connection.version}")
                    Text(capabilityReasonLabel(profile.observation.reason),modifier=Modifier.testTag("model-capability-reason-${config.id}"))
                }
                Text(if(profile?.observation?.state==CapabilityState.SUPPORTED)
                    "支持用途：基础文本对话传输。整理的结构化输出、视觉、转写、语义检索均未验证；能力检查不代表记录外发授权。"
                    else "支持用途尚未验证；视觉、转写、语义检索均未验证。")
                TextButton(onClick={controller.beginProbe(session,config)},enabled=ui.probeAvailable&&config.secretRef!=null&&
                    ui.editor==null&&ui.secretEditor==null&&ui.probeEditor==null&&!ui.busy&&ui.error==null&&ui.capabilityError==null,
                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-capability-open-${config.id}")) { Text("检查此连接的文本能力") }
                if(!ui.probeAvailable) Text("当前版本未开放服务探测与记录发送；可继续本机使用。",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={controller.begin(session,config)},enabled=ui.editor==null&&ui.secretEditor==null&&ui.probeEditor==null&&!ui.busy&&ui.error==null,
                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-edit-${config.id}")) { Text("编辑此配置") }
                TextButton(onClick={controller.beginSecret(session,config)},
                    enabled=ui.secretsAvailable&&ui.editor==null&&ui.secretEditor==null&&ui.probeEditor==null&&!ui.busy&&ui.error==null,
                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-secret-open-${config.id}")) { Text("管理本机密钥") }
            }
        }
    }
    ui.probeEditor?.let { editor ->
        Text("检查版本 ${editor.expected.version} 的文本能力",style=MaterialTheme.typography.titleMedium)
        Text("仅向此连接发送固定合成文本：Reply with exactly AIYOU_CAPABILITY_OK. This is a synthetic text capability check.")
        Text("不读取记录、图片或草稿；最多一次请求、32个输出token、10秒，不自动重试。使用真实服务时可能产生少量费用。")
        Text("保存密钥不会自动检查。检查结果也不会授权记录外发。")
        val run={controller.runProbe(session,editor)}
        LocalModelConfigurationActionObserver.current?.invoke("capability-run",run)
        Button(onClick=run,enabled=!ui.busy&&ui.error==null&&!editor.busy&&!editor.conflict&&editor.pending==null,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-capability-run")) { Text("确认检查固定文本") }
        if(editor.busy) Text("正在检查此连接…")
        editor.error?.let { Text(it,color=AIyouColors.error,modifier=Modifier.testTag("model-capability-error")) }
        if(editor.pending!=null&&!editor.conflict) OutlinedButton(onClick={controller.retryProbePersistence(session,editor)},enabled=!editor.busy,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-capability-retry")) { Text("仅核对本次结果写入") }
        TextButton(onClick={controller.cancelProbe(session,editor)},modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
            .testTag("model-capability-close")) { Text(if(editor.busy) "停止检查" else "关闭检查并重新读取") }
    }
    ui.editor?.let { editor ->
        val enabled=!ui.busy&&ui.error==null&&!editor.busy&&editor.pending==null&&!editor.conflict
        Text(if(editor.version==0L) "新连接配置" else "编辑配置 · 版本 ${editor.version}",style=MaterialTheme.typography.titleMedium)
        if(ui.configurations.any { it.id==editor.id && it.secretRef!=null })
            Text("保存配置修改会撤销已有密钥引用；不会沿用旧授权。")
        OutlinedTextField(editor.form.name,{controller.edit(session,editor,editor.form.copy(name=it))},label={Text("连接名称")},
            enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("model-config-name"))
        OutlinedTextField(editor.form.apiRoot,{controller.edit(session,editor,editor.form.copy(apiRoot=it))},label={Text("HTTPS API 根路径")},
            supportingText={Text("保留服务商版本或代理前缀，不填 chat/completions、messages 等最终操作名。")},
            enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("model-config-root"))
        ConnectionProtocol.entries.forEach { protocol ->
            Row(Modifier.fillMaxWidth().heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-protocol-${protocol.name}")
                .selectable(editor.form.protocol==protocol,enabled=enabled,role=Role.RadioButton,
                    onClick={controller.edit(session,editor,editor.form.copy(protocol=protocol))}),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(editor.form.protocol==protocol,onClick=null,enabled=enabled);Text(protocolLabel(protocol))
            }
        }
        OutlinedTextField(editor.form.modelId,{controller.edit(session,editor,editor.form.copy(modelId=it))},label={Text("手填模型 ID")},
            enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("model-config-model"))
        Text("计划用途（均未验证，可与其他配置重复；不会自动分配或调用）")
        ModelConfigurationPurpose.entries.forEach { purpose ->
            Row(Modifier.fillMaxWidth().heightIn(min=AIyouDimensions.minTouchTarget),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(purpose in editor.form.purposes,onCheckedChange={checked -> controller.edit(session,editor,editor.form.copy(
                    purposes=if(checked) editor.form.purposes+purpose else editor.form.purposes-purpose))},enabled=enabled,
                    modifier=Modifier.testTag("model-config-purpose-${purpose.name}"))
                Text(purposeLabel(purpose))
            }
        }
        editor.error?.let { Text(it,color=AIyouColors.error,modifier=Modifier.testTag("model-config-error")) }
        if(editor.busy) Text("正在核对配置写入…")
        val save={controller.save(session,editor)}
        LocalModelConfigurationActionObserver.current?.invoke("save",save)
        Button(onClick=save,enabled=enabled&&!editor.confirmDelete,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-save")) { Text("保存本机配置") }
        if(editor.pending!=null&&!editor.conflict) OutlinedButton(onClick={controller.retry(session,editor)},enabled=!editor.busy,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-retry")) { Text("重试核对同次修改") }
        if(editor.version>0&&!editor.confirmDelete) TextButton(onClick={controller.confirmDelete(session,editor)},enabled=enabled,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)) { Text("删除配置") }
        if(editor.confirmDelete) {
            Text("确认删除版本 ${editor.version} 的本机连接配置？此操作不删除记录或草稿，也不改变它们的处理范围。")
            val remove={controller.delete(session,editor)}
            LocalModelConfigurationActionObserver.current?.invoke("delete",remove)
            Button(onClick=remove,enabled=enabled,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                .testTag("model-config-confirm-delete")) { Text("确认删除此配置") }
        }
        TextButton(onClick={controller.cancel(session,editor)},enabled=!editor.busy,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)) {
            Text(if(editor.pending!=null||editor.conflict) "关闭编辑并重新读取" else "关闭编辑") }
    }
    ui.secretEditor?.let { editor ->
        // Only this live page owns input; even a TextField's internal saveable state is disabled.
        val input=remember(controller,session,editor.expected) { SecretFieldMemory() }
        DisposableEffect(input) { onDispose { input.close() } }
        val enabled=!ui.busy&&ui.error==null&&!editor.busy&&editor.pending==null&&!editor.conflict
        Text("本机密钥 · ${editor.expected.details.name}",style=MaterialTheme.typography.titleMedium)
        Text("版本 ${editor.expected.version} · ${editor.expected.details.apiRoot}")
        Text("密钥只在本机加密保存，不进入资料库备份。保存不验证服务能力，也不授权发送记录；当前网络调用未开放。")
        if(!editor.confirmRevoke) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                OutlinedTextField(input.text,{ value -> if(controller.canEditSecret(session,editor)) input.edit(value) },
                    label={Text(if(editor.expected.secretRef==null) "API Key（仅本机）" else "新的 API Key（替换已有密钥）")},
                    visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password),
                    singleLine=true,enabled=enabled,isError=input.error!=null,
                    supportingText={Text(input.error?:"不会回显已保存的密钥；关闭页面会清空尚未提交的输入。")},
                    modifier=Modifier.fillMaxWidth().testTag("model-secret-input"))
            }
            val save={ if(controller.canEditSecret(session,editor)) controller.saveSecret(session,editor,input.transfer()) }
            LocalModelConfigurationActionObserver.current?.invoke("secret-save",save)
            Button(onClick=save,enabled=enabled&&input.text.isNotEmpty()&&input.error==null,
                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-secret-save")) {
                Text(if(editor.expected.secretRef==null) "保存本机密钥" else "替换本机密钥")
            }
            if(editor.expected.secretRef!=null) TextButton(onClick={
                if(controller.canEditSecret(session,editor)) { input.clear();controller.confirmSecretRevoke(session,editor) }
            },enabled=enabled,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-secret-revoke")) { Text("撤销本机密钥引用") }
        } else {
            Text("确认撤销版本 ${editor.expected.version} 的本机密钥引用？配置版本会变化，不会删除原始记录。")
            val revoke={controller.revokeSecret(session,editor)}
            LocalModelConfigurationActionObserver.current?.invoke("secret-revoke",revoke)
            Button(onClick=revoke,enabled=enabled,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget)
                .testTag("model-secret-confirm-revoke")) { Text("确认撤销此密钥引用") }
        }
        editor.error?.let { Text(it,color=AIyouColors.error,modifier=Modifier.testTag("model-secret-error")) }
        if(editor.busy) Text("正在核对本机密钥操作…")
        if(editor.pending!=null&&!editor.conflict) OutlinedButton(onClick={controller.retrySecret(session,editor)},enabled=!editor.busy,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-secret-retry")) { Text("重试核对同次密钥操作") }
        TextButton(onClick={controller.cancelSecret(session,editor)},enabled=!editor.busy,
            modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-secret-close")) {
            Text(if(editor.pending!=null||editor.conflict) "关闭密钥编辑并重新读取" else "关闭密钥编辑")
        }
    }
}
/** Ephemeral UI text only; never Saveable, logged, preview-filled, copied out, or resolved from the vault. */
private class SecretFieldMemory {
    var text by mutableStateOf("");private set
    var error by mutableStateOf<String?>(null);private set
    private var retired=false
    fun edit(value: String) {
        if(retired) return
        if(value.length>2048) { error="密钥最多2048个字符，请检查输入。";return }
        text=value;error=null
    }
    fun transfer(): CharArray = (if(retired) CharArray(0) else text.toCharArray()).also { clear() }
    fun clear() { text="";error=null }
    fun close() { retired=true;clear() }
    override fun toString()="SecretFieldMemory(redacted)"
}
private fun protocolLabel(value: ConnectionProtocol) = when(value) {
    ConnectionProtocol.CHAT_COMPLETIONS -> "Chat Completions 兼容"
    ConnectionProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages 兼容"
}
private fun purposeLabel(value: ModelConfigurationPurpose) = when(value) {
    ModelConfigurationPurpose.CONVERSATION -> "对话"
    ModelConfigurationPurpose.ORGANIZATION -> "整理"
    ModelConfigurationPurpose.VISION -> "视觉"
    ModelConfigurationPurpose.TRANSCRIPTION -> "转写"
    ModelConfigurationPurpose.EMBEDDING -> "语义检索"
}
private fun capabilityStateLabel(value: CapabilityState) = when(value) {
    CapabilityState.UNKNOWN -> "UNKNOWN · 未验证"
    CapabilityState.SUPPORTED -> "SUPPORTED · 固定样本文本流通过"
    CapabilityState.UNSUPPORTED -> "UNSUPPORTED · 此配置的文本接口不可用"
}
private fun capabilityReasonLabel(value: CapabilityProbeReason) = when(value) {
    CapabilityProbeReason.IN_PROGRESS -> "上次检查尚未完成，请明确发起新检查。"
    CapabilityProbeReason.TEXT_STREAM_VERIFIED -> "固定样本文本流通过，未验证其他能力。"
    CapabilityProbeReason.TEXT_ENDPOINT_UNAVAILABLE -> "所配文本接口或模型不可用（404/405/501），请核对协议、根路径与模型。"
    CapabilityProbeReason.AUTH_REQUIRED -> "认证未通过，请核对本机密钥与服务权限；能力仍未知。"
    CapabilityProbeReason.RATE_LIMITED -> "服务限流，本次未确认能力，未自动重试。"
    CapabilityProbeReason.TEMPORARY -> "服务暂不可用，本次未确认能力。"
    CapabilityProbeReason.NETWORK -> "连接中断，本次未确认能力。"
    CapabilityProbeReason.TIMEOUT -> "检查超时，本次未确认能力。"
    CapabilityProbeReason.REDIRECT_BLOCKED -> "服务要求跳转，已停止，请核对根路径。"
    CapabilityProbeReason.INVALID_RESPONSE -> "响应格式或固定样本不符，本次未确认能力。"
    CapabilityProbeReason.REFUSED -> "服务拒绝了固定样本，本次未确认能力。"
    CapabilityProbeReason.LIMIT_EXCEEDED -> "响应超过本次检查限额，已停止。"
    CapabilityProbeReason.CANCELLED -> "检查已停止，未确认能力。"
    CapabilityProbeReason.CONFIGURATION_CHANGED -> "连接版本或密钥已变化，请重新读取后明确检查。"
    CapabilityProbeReason.SECRET_UNAVAILABLE -> "本机密钥暂不可读取，未确认能力。"
    CapabilityProbeReason.STORAGE_UNAVAILABLE -> "本机能力档案暂不可读取，未确认能力。"
    CapabilityProbeReason.CONFIGURATION_REJECTED -> "服务未接受请求配置，请核对协议、根路径与模型；能力仍未知。"
}
