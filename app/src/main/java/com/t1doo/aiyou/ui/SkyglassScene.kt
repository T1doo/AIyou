package com.t1doo.aiyou.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1doo.aiyou.R
import com.t1doo.aiyou.designsystem.SkyglassAppearance
import com.t1doo.aiyou.designsystem.SkyglassMotion
import kotlin.math.sin

/** Decorative local asset only. No record state, network or background service. */
@Composable
fun SkyglassScene(appearance: SkyglassAppearance, motion: SkyglassMotion, motionAllowed: Boolean,
    modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var phase by remember { mutableFloatStateOf(0f) }
    val playing = foreground && motionAllowed && motion != SkyglassMotion.STILL
    LaunchedEffect(playing) {
        if (playing) {
            var previous = withFrameNanos { it }
            while (true) withFrameNanos { now ->
                phase = (phase + (now - previous).coerceAtMost(100_000_000L) / 36_000_000_000f) % 1f
                previous = now
            }
        }
    }
    val resource = when (appearance) {
        SkyglassAppearance.BLUE -> R.drawable.skyglass_blue
        SkyglassAppearance.SUNSET -> R.drawable.skyglass_sunset
        SkyglassAppearance.NIGHT -> R.drawable.skyglass_night
    }
    Box(modifier) {
        Image(painterResource(resource), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (motion == SkyglassMotion.DRIFT && motionAllowed) {
                    scaleX = 1.025f; scaleY = 1.025f
                    translationX = sin(phase * 6.283185f) * size.width * .008f
                }
            })
        if (motion == SkyglassMotion.GLOW && motionAllowed) Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = .035f + (sin(phase * 6.283185f) + 1f) * .025f
        }.background(Brush.linearGradient(listOf(Color.White, Color.Transparent))))
    }
}
