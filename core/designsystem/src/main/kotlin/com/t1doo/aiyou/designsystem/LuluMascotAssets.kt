package com.t1doo.aiyou.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration

/** Static AI-generated artwork, reviewed against the user's character references.
 * State labels still come from the application; the illustration never claims a save succeeded.
 */
object LuluMascotAssets : MascotAssetProvider {
    override fun labelFor(state: MascotState): String = state.description

    @Composable
    override fun Artwork(state: MascotState, modifier: Modifier) { drawLulu(CompanionPose.Idle, state, modifier) }

    @Composable
    override fun interactionArtwork(pose: CompanionPose, modifier: Modifier, actionModifier: Modifier) =
        drawLulu(pose, MascotState.Idle, modifier, actionModifier)

    @Composable
    private fun drawLulu(pose: CompanionPose, fallback: MascotState, modifier: Modifier,
                         actionModifier: Modifier = Modifier): Boolean {
        val resources = LocalResources.current
        val configuration = LocalConfiguration.current
        // Three fixed 1254px sources, decoded lazily at627px: at most about4.5MiB
        // per mounted consumer. No network or repeated decode on each animation frame.
        val decoded = remember(resources, configuration) { mutableMapOf<CompanionPose, ImageBitmap?>() }
        fun bitmapFor(requested: CompanionPose): ImageBitmap? {
            if (!decoded.containsKey(requested)) {
                val resource = when (requested) {
                    CompanionPose.Idle -> R.drawable.lulu_hello
                    CompanionPose.Greeting -> R.drawable.lulu_wave
                    CompanionPose.Encouraging -> R.drawable.lulu_encourage
                }
                decoded[requested] = BitmapFactory.decodeResource(resources, resource,
                    BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false })?.asImageBitmap()
            }
            return decoded[requested]
        }
        val requested = bitmapFor(pose)
        val bitmap = requested ?: bitmapFor(CompanionPose.Idle)
        if (bitmap != null) Image(bitmap, contentDescription = null,
            modifier = if (requested != null) modifier.then(actionModifier) else modifier,
            contentScale = ContentScale.Fit)
        else NeutralCapybara(fallback, modifier)
        return requested != null
    }
}
