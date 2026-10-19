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
    secretOwner: ConnectionSecretVault?) {
    val context=LocalContext.current
    val activity=remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { base->base!==it } }
        .filterIsInstance<Activity>().firstOrNull() }
    DisposableEffect(controller,owner,secretOwner,available) {
        val session=controller.open(owner.takeIf { available })
        onDispose { controller.close(session,retainEditor=activity?.isChangingConfigurations==true) }
    }
    val emission by controller.state.collectAsState()
    val ui=emission.let { controller.visible() }
    val session=ui.session
    Text("本机连接配置",style=MaterialTheme.typography.titleMedium)
    Text("可保存服务、模型、计划用途与本机密钥。所有能力未验证，尚不可调用；保存不会联网或改变记录的处理范围。",
        style=MaterialTheme.typography.bodyMedium)
    Text("连接配置仅留在本机，不进入资料库备份；恢复资料库不会覆盖它。",style=MaterialTheme.typography.bodySmall)
    if(ui.busy) Text("正在读取本机配置…")
    ui.error?.let { Text(it,color=AIyouColors.error) }
    ui.notice?.let { Text(it,modifier=Modifier.testTag("model-config-notice")) }
    OutlinedButton(onClick={controller.reload(session)},enabled=available&&!ui.busy&&ui.editor?.busy!=true&&ui.secretEditor?.busy!=true,
        modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-reload")) { Text("重新读取配置") }
    if(ui.editor==null&&ui.secretEditor==null) {
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
                Text(if(config.secretRef==null) "密钥未配置 · 能力未验证 · 尚不可调用" else
                    "本机密钥引用已保存 · 能力未验证 · 尚不可调用",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={controller.begin(session,config)},enabled=ui.editor==null&&ui.secretEditor==null&&!ui.busy&&ui.error==null,
                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-config-edit-${config.id}")) { Text("编辑此配置") }
                TextButton(onClick={controller.beginSecret(session,config)},
                    enabled=ui.secretsAvailable&&ui.editor==null&&ui.secretEditor==null&&!ui.busy&&ui.error==null,
                    modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("model-secret-open-${config.id}")) { Text("管理本机密钥") }
            }
        }
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
