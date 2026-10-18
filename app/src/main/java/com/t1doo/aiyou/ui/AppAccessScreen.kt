package com.t1doo.aiyou.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.t1doo.aiyou.designsystem.AIyouDimensions

internal class AppAccessSettingsActions(val change: () -> Unit, val lock: () -> Unit)
internal val LocalAppAccessSettingsObserver = staticCompositionLocalOf<((AppAccessSettingsActions) -> Unit)?> { null }
internal fun interface AppAccessCredentialIntentFactory {
    fun create(context: Context, purpose: AppAccessPurpose): Intent?
}
/** Test seams can replace the platform boundary; the production default always asks Android. */
internal val LocalAppAccessCredentialIntentFactory = staticCompositionLocalOf<AppAccessCredentialIntentFactory> {
    AppAccessCredentialIntentFactory { context, purpose ->
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = if (keyguard?.isDeviceSecure == true) keyguard.createConfirmDeviceCredentialIntent(
            when (purpose) {
                AppAccessPurpose.ENABLE -> "启用爱忆启动应用锁"
                AppAccessPurpose.DISABLE -> "关闭爱忆启动应用锁"
                AppAccessPurpose.UNLOCK -> "解锁爱忆"
            }, "验证设备已有的系统锁屏") else null
        intent
    }
}
private class AppAccessUiOwner { var active = true }

/** The protected subtree is absent until the controller has admitted a current session. */
@Composable
internal fun AppAccessHost(controller: AppAccessController, content: @Composable (Long) -> Unit) {
    val state by controller.state.collectAsState()
    AppAccessCredentialRequest(controller, state)
    if (state.canReadRecords) key(state.session) { content(state.session) }
    else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow)
        .testTag("app-access-gate"), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
        Text("爱忆", style = MaterialTheme.typography.headlineMedium)
        Text(when (state.phase) {
            AppAccessPhase.LOADING -> "正在核对启动保护…"
            AppAccessPhase.SAVING -> "正在保存并核对启动应用锁…"
            AppAccessPhase.OPENING -> "正在核对资料会话…"
            AppAccessPhase.AUTHENTICATING -> "请完成系统锁屏验证"
            AppAccessPhase.FAILED -> "启动保护需要处理"
            else -> "应用已锁定"
        }, style = MaterialTheme.typography.titleLarge)
        Text("启动应用锁使用设备已有的系统锁屏验证。不会创建新的密码。")
        state.message?.let { Text(it, Modifier.testTag("app-access-message")) }
        if (state.phase == AppAccessPhase.LOCKED) {
            if (state.mayReturn) Button(onClick = controller::returnToPriorAccess,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("app-access-return")) { Text("返回应用，设置未更改") }
            else if (state.preference?.enabled == true) Button(onClick = controller::requestUnlock,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("app-access-unlock")) { Text("解锁应用") }
        }
        if (state.phase == AppAccessPhase.FAILED) Button(onClick = controller::retryRead,
            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("app-access-retry")) { Text("重新读取核对") }
    }
}

@Composable
internal fun AppAccessSettings(controller: AppAccessController, session: Long, retirementReady: Boolean) {
    val state by controller.state.collectAsState()
    val owner = remember(controller, session) { AppAccessUiOwner() }
    DisposableEffect(owner) { onDispose { owner.active = false } }
    val ready by rememberUpdatedState(retirementReady)
    val enabled = state.preference?.enabled == true
    val actions = remember(controller, session, enabled, owner) {
        AppAccessSettingsActions(change = {
            if (owner.active && ready) controller.requestChange(session, !enabled)
        }, lock = { if (owner.active && ready) controller.lock(session) })
    }
    val observer = LocalAppAccessSettingsObserver.current
    SideEffect { observer?.invoke(actions) }
    val available = state.canReadRecords && state.session == session && retirementReady
    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Text(if (enabled) "启动应用锁已启用" else "启动应用锁未启用", Modifier.testTag("app-access-setting"))
        Text("新打开应用前验证系统锁屏，也可主动锁定。切到后台保持本次会话；当前不自动锁定。")
        Text("启用或关闭都需要验证设备已有的安全锁屏。请先完成保存并关闭编辑。")
        Text("启用后会限制应用截图和最近任务预览。")
        state.message?.let { Text(it) }
        OutlinedButton(onClick = actions.change, enabled = available,
            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("app-access-change")) {
            Text(if (enabled) "验证并关闭启动应用锁" else "验证并启用启动应用锁")
        }
        if (enabled) Button(onClick = actions.lock, enabled = available,
            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("app-access-lock")) { Text("立即锁定") }
    }
}

@Composable
private fun AppAccessCredentialRequest(controller: AppAccessController, state: AppAccessState) {
    val request = state.authentication ?: return
    key(request.key) {
        val context = LocalContext.current
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
        val intentFactory = LocalAppAccessCredentialIntentFactory.current
        val owner = remember(request.key) { AppAccessUiOwner() }
        var launcher by remember(registry, request.key) { mutableStateOf<ActivityResultLauncher<Intent>?>(null) }
        DisposableEffect(registry, request.key, owner) {
            val registered = registry.register(request.key, ActivityResultContracts.StartActivityForResult()) { result ->
                if (owner.active) controller.authenticationResult(request.key, result.resultCode == Activity.RESULT_OK)
            }
            launcher = registered
            onDispose { owner.active = false; launcher = null; registered.unregister() }
        }
        LaunchedEffect(request.key, launcher) {
            val currentLauncher = launcher
            if (currentLauncher != null && controller.claimAuthentication(request.key)) try {
                val intent = intentFactory.create(context, request.purpose)
                if (intent == null) controller.authenticationUnavailable(request.key) else currentLauncher.launch(intent)
            } catch (_: Exception) { controller.authenticationUnavailable(request.key) }
        }
    }
}
