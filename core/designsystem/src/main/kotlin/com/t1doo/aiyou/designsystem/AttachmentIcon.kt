package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

/** Decorative line icons; parent controls keep explicit text and enabled semantics. */
@Composable
internal fun AttachmentIcon(audio: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(AIyouDimensions.navigationIconSize)) {
        val w = size.width
        val h = size.height
        val stroke = AIyouDimensions.iconStroke.toPx()
        if (audio) {
            drawRoundRect(color, Offset(w * .36f, h * .12f), Size(w * .28f, h * .5f), CornerRadius(w * .14f), style = Stroke(stroke))
            drawArc(color, 0f, 180f, false, Offset(w * .22f, h * .3f), Size(w * .56f, h * .47f), style = Stroke(stroke))
            drawLine(color, Offset(w * .5f, h * .78f), Offset(w * .5f, h * .92f), stroke)
            drawLine(color, Offset(w * .36f, h * .92f), Offset(w * .64f, h * .92f), stroke)
        } else {
            drawRoundRect(color, Offset(w * .12f, h * .16f), Size(w * .76f, h * .68f), CornerRadius(w * .1f), style = Stroke(stroke))
            drawCircle(color, w * .07f, Offset(w * .65f, h * .36f), style = Stroke(stroke))
            drawLine(color, Offset(w * .2f, h * .72f), Offset(w * .4f, h * .48f), stroke)
            drawLine(color, Offset(w * .4f, h * .48f), Offset(w * .59f, h * .7f), stroke)
            drawLine(color, Offset(w * .59f, h * .7f), Offset(w * .71f, h * .6f), stroke)
        }
    }
}
