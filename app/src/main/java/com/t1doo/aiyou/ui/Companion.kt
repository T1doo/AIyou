package com.t1doo.aiyou.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.MascotAssetProvider
import com.t1doo.aiyou.designsystem.MascotState
import com.t1doo.aiyou.designsystem.LuluMascotAssets

/** Read-only test observation; absent in production and carries no record content. */
internal val LocalCompanionObserver = staticCompositionLocalOf<((MascotState, Float) -> Unit)?> { null }

@Composable
internal fun companionMotionAllowed(reduceMotion: Boolean): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun allowed() = !reduceMotion && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        ValueAnimator.areAnimatorsEnabled() && context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
    var result by remember(context, lifecycle, reduceMotion) { mutableStateOf(allowed()) }
    DisposableEffect(context, lifecycle, reduceMotion) {
        val listener = LifecycleEventObserver { _, _ -> result = allowed() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { result = allowed() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { result = allowed() }
        }
        lifecycle.addObserver(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        result = allowed()
        onDispose {
            lifecycle.removeObserver(listener)
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return result
}

@Composable
fun CompanionEntry(
    state: MascotState,
    pulse: Long,
    motionAllowed: Boolean,
    onOpen: () -> Unit,
    assets: MascotAssetProvider = LuluMascotAssets,
) {
    val nod = remember { Animatable(0f) }
    var handled by remember { mutableLongStateOf(pulse) }
    LaunchedEffect(pulse, motionAllowed) {
        val fresh = handled != pulse
        handled = pulse // Consume before any suspending reset, including reduced-motion events.
        nod.snapTo(0f)
        if (fresh && motionAllowed) { nod.animateTo(1f, tween(100)); nod.animateTo(0f, tween(140)) }
    }
    LocalCompanionObserver.current?.let { observer ->
        val value = nod.value
        SideEffect { observer(state, value) }
    }
    Surface(onClick = onOpen, color = AIyouColors.surface, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.size(56.dp).testTag("companion-entry").semantics { contentDescription = "打开陪伴伙伴" }) {
        assets.Artwork(state, Modifier.size(56.dp).graphicsLayer {
            translationY = nod.value * 2.dp.toPx()
            rotationZ = nod.value * 2f
        })
    }
}

@Composable
fun CompanionDialog(onClose: () -> Unit, motionAllowed: Boolean, assets: MascotAssetProvider = LuluMascotAssets) {
    var greeting by remember { mutableStateOf(false) }
    var pulse by remember { mutableLongStateOf(0L) }
    val nod = remember { Animatable(0f) }
    var handled by remember { mutableLongStateOf(pulse) }
    LaunchedEffect(pulse, motionAllowed) {
        val fresh = handled != pulse
        handled = pulse
        nod.snapTo(0f)
        if (fresh && motionAllowed) { nod.animateTo(1f, tween(100)); nod.animateTo(0f, tween(140)) }
    }
    AlertDialog(onDismissRequest = onClose, containerColor = AIyouColors.surface,
        title = { Text("安静陪你一会儿") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
                assets.Artwork(MascotState.Idle, Modifier.size(112.dp).graphicsLayer { rotationZ = nod.value * 3f })
                Text(if (greeting) "你好，慢慢记录就好。" else "想记就记，停一会儿也很好。",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                Text("本地小互动，不读取或修改你的记录。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                TextButton(onClick = { greeting = true; pulse++ }) { Text("打个招呼") }
                TextButton(onClick = { greeting = false }) { Text("安静陪伴") }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("关闭陪伴面板") } })
}
