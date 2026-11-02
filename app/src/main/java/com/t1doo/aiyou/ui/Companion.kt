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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.MascotAssetProvider
import com.t1doo.aiyou.designsystem.MascotState
import com.t1doo.aiyou.designsystem.LuluMascotAssets
import com.t1doo.aiyou.designsystem.CompanionPose
import com.t1doo.aiyou.designsystem.aiyouFocusOutline
import kotlinx.coroutines.delay

/** Read-only test observation; absent in production and carries no record content. */
internal val LocalCompanionObserver = staticCompositionLocalOf<((MascotState, Float) -> Unit)?> { null }

internal data class CompanionDialogActions(val greet: () -> Unit, val encourage: () -> Unit,
                                         val quiet: () -> Unit, val close: () -> Unit,
                                         val think: () -> Unit, val yawn: () -> Unit)
internal val LocalCompanionInteractionObserver = staticCompositionLocalOf<
    ((CompanionInteraction.Snapshot, Float, CompanionDialogActions) -> Unit)?> { null }

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
    artworkSize: Dp = 56.dp,
    containerColor: Color = AIyouColors.surface,
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
    Surface(onClick = onOpen, color = containerColor, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.size(artworkSize).testTag("companion-entry").semantics { contentDescription = "打开陪伴伙伴" }) {
        assets.Artwork(state, Modifier.size(artworkSize).graphicsLayer {
            translationY = nod.value * 2.dp.toPx()
            rotationZ = nod.value * 2f
        })
    }
}

@Composable
fun CompanionDialog(onClose: () -> Unit, motionAllowed: Boolean, assets: MascotAssetProvider = LuluMascotAssets, sceneVisible: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val interaction = remember(lifecycle) { CompanionInteraction() }
    var ui by remember(interaction) { mutableStateOf(interaction.snapshot) }
    val currentClose by rememberUpdatedState(onClose)
    val movement = remember(interaction) { Animatable(0f) }
    var handled by remember(interaction) { mutableStateOf<CompanionInteraction.Run?>(null) }
    val actions = remember(interaction, lifecycle) {
        fun act(pose: CompanionPose) {
            interaction.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            ui = interaction.start(pose)
        }
        CompanionDialogActions({ act(CompanionPose.Greeting) }, { act(CompanionPose.Encouraging) }, {
            interaction.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            ui = interaction.quiet()
        }, { if (interaction.retire()) currentClose() },
            { act(CompanionPose.Thinking) }, { act(CompanionPose.Yawning) })
    }
    DisposableEffect(interaction, lifecycle) {
        fun update() { ui = interaction.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val listener = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(listener)
        update()
        onDispose { interaction.retire(); lifecycle.removeObserver(listener) }
    }
    val run = ui.run
    LaunchedEffect(run, motionAllowed) {
        val fresh = run != null && handled !== run
        handled = run // Consume before suspending, including reduced-motion requests.
        movement.snapTo(0f)
        if (run != null) {
            if (fresh && motionAllowed) {
                if (run.pose == CompanionPose.Greeting) {
                    for (value in listOf(-1f, 1f, -.55f, .55f, 0f)) movement.animateTo(value, tween(90))
                } else {
                    movement.animateTo(1f, tween(180)); movement.animateTo(0f, tween(220))
                }
            } else if (fresh) delay(700) // Brief static pose, never a reduced-motion animation.
            ui = interaction.finish(run) // A cancelled/retired run cannot finish a later action.
        }
    }
    val motion = if (motionAllowed && ui.foreground && ui.run != null) movement.value else 0f
    LocalCompanionInteractionObserver.current?.let { observer -> SideEffect { observer(ui, motion, actions) } }
    Dialog(onDismissRequest = actions.close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.fillMaxSize(), color=AIyouColors.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .testTag("companion-scroll"),verticalArrangement=Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("噜噜",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)
                TextButton(onClick=actions.close,modifier=Modifier.heightIn(min=48.dp).testTag("companion-return")) {Text("回到此刻")}
            }
            Box(Modifier.fillMaxWidth().heightIn(min=240.dp).testTag("companion-static-scene"),contentAlignment=Alignment.BottomCenter) {
                if(sceneVisible) Canvas(Modifier.matchParentSize().background(AIyouColors.companionSky)) {
                    // Original static landscape, no texture, particle or continuous animator.
                    val far=Path().apply {moveTo(0f,size.height*.62f);cubicTo(size.width*.35f,size.height*.42f,size.width*.7f,size.height*.76f,size.width,size.height*.65f);lineTo(size.width,size.height);lineTo(0f,size.height);close()}
                    val near=Path().apply {moveTo(0f,size.height*.84f);quadraticTo(size.width*.5f,size.height*.66f,size.width,size.height*.87f);lineTo(size.width,size.height);lineTo(0f,size.height);close()}
                    drawPath(far,AIyouColors.companionFar);drawPath(near,AIyouColors.companionNear)
                }
                val poseAvailable = assets.interactionArtwork(ui.pose, Modifier.size(152.dp).testTag("companion-interaction-artwork"), Modifier.graphicsLayer {
                    when (ui.pose) {
                        CompanionPose.Greeting -> rotationZ = motion * 6f
                        CompanionPose.Encouraging -> { translationY = -motion * 4.dp.toPx(); scaleX = 1f + motion * .04f; scaleY = scaleX }
                        CompanionPose.Thinking -> rotationZ = -motion * 3f
                        CompanionPose.Yawning -> { translationY = motion * 2.dp.toPx(); scaleX = 1f - motion * .02f; scaleY = scaleX }
                        CompanionPose.Idle -> Unit
                    }
                })
                if (!poseAvailable)
                    Text("当前动作插画暂不可用，正在显示静态占位。", Modifier.testTag("companion-artwork-fallback"),
                        style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(when (ui.feedback) {
                    CompanionPose.Greeting -> "你好，慢慢记录就好。"
                    CompanionPose.Encouraging -> "按自己的节奏来，小小一步也很好。"
                    CompanionPose.Idle -> "想记就记，停一会儿也很好。"
                    CompanionPose.Thinking -> "慢慢想，不着急。这只是陪伴动作。"
                    CompanionPose.Yawning -> "打个哈欠，歇一小会儿。"
                }, Modifier.testTag("companion-feedback").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge)
                Text("本地小互动，不读取或修改你的记录。", style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.greet, enabled = ui.foreground && ui.run == null && !ui.retired,
                    modifier = Modifier.heightIn(min = 48.dp).aiyouFocusOutline().testTag("companion-greet")) { Text("打个招呼") }
                TextButton(onClick = actions.encourage, enabled = ui.foreground && ui.run == null && !ui.retired,
                    modifier = Modifier.heightIn(min = 48.dp).aiyouFocusOutline().testTag("companion-encourage")) { Text("给我一点鼓励") }
                TextButton(onClick = actions.think, enabled = ui.foreground && ui.run == null && !ui.retired,
                    modifier = Modifier.heightIn(min = 48.dp).aiyouFocusOutline().testTag("companion-think")) { Text("一起想一想") }
                TextButton(onClick = actions.yawn, enabled = ui.foreground && ui.run == null && !ui.retired,
                    modifier = Modifier.heightIn(min = 48.dp).aiyouFocusOutline().testTag("companion-yawn")) { Text("打个哈欠") }
                TextButton(onClick = actions.quiet, enabled = ui.foreground && !ui.retired,
                    modifier = Modifier.heightIn(min = 48.dp).aiyouFocusOutline().testTag("companion-quiet")) { Text("安静陪伴") }
                }
                HorizontalDivider(color=AIyouColors.outlineDecorative)
                Text(if(sceneVisible)"静态场景已开启，可在设置中单独关闭。" else "静态场景已关闭。",style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                TextButton(onClick=actions.close,modifier=Modifier.heightIn(min=48.dp).aiyouFocusOutline()) {Text("关闭陪伴面板")}
            }
        }
      }
    }
}
