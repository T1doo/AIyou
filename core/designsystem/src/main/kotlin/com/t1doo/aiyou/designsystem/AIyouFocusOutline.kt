package com.t1doo.aiyou.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

/** Optional visible keyboard focus for custom controls; does not create a focus target. */
fun Modifier.aiyouFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (focused) {
            val stroke = AIyouDimensions.focusRingWidth.toPx()
            drawRoundRect(
                color = AIyouColors.primary,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(AIyouDimensions.spaceSm.toPx()),
                style = Stroke(stroke),
            )
        }
    }
}
