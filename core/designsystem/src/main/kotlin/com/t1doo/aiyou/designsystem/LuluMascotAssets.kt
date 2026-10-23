package com.t1doo.aiyou.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/** Static AI-generated artwork, reviewed against the user's character references.
 * State labels still come from the application; the illustration never claims a save succeeded.
 */
object LuluMascotAssets : MascotAssetProvider {
    override fun labelFor(state: MascotState): String = state.description

    @Composable
    override fun Artwork(state: MascotState, modifier: Modifier) {
        val resources = LocalContext.current.resources
        // Bundled 1254px source; decode at about 627px for the 56dp/112dp placements.
        // No network, full-resolution decode, mutable user data or image-loading dependency.
        val bitmap = remember(resources) {
            BitmapFactory.decodeResource(resources, R.drawable.lulu_hello,
                BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false })?.asImageBitmap()
        }
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier,
            contentScale = ContentScale.Fit)
        else NeutralCapybara(state, modifier)
    }
}
