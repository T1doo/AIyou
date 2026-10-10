package com.t1doo.aiyou.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1doo.aiyou.designsystem.AIyouDimensions

/** Notebook, pen and checklist line icons; navigation text labels remain present. */
@Composable
fun DestinationIcon(destination: Destination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(AIyouDimensions.navigationIconSize)) {
        val stroke = AIyouDimensions.iconStroke.toPx()
        val w = size.width
        val h = size.height
        drawRoundRect(color, Offset(w * .15f, h * .12f), Size(w * .7f, h * .76f),
            CornerRadius(w * .09f), style = Stroke(stroke))
        when (destination) {
            Destination.NOW -> {
                drawLine(color, Offset(w * .36f, h * .64f), Offset(w * .68f, h * .3f), stroke)
                drawLine(color, Offset(w * .33f, h * .7f), Offset(w * .48f, h * .65f), stroke)
            }
            Destination.MEMORIES -> {
                drawLine(color, Offset(w * .34f, h * .12f), Offset(w * .34f, h * .88f), stroke)
                drawLine(color, Offset(w * .46f, h * .34f), Offset(w * .71f, h * .34f), stroke)
                drawLine(color, Offset(w * .46f, h * .54f), Offset(w * .71f, h * .54f), stroke)
            }
            Destination.TASKS -> {
                for (y in listOf(.34f, .56f)) {
                    drawLine(color, Offset(w * .28f, h * y), Offset(w * .34f, h * (y + .06f)), stroke)
                    drawLine(color, Offset(w * .34f, h * (y + .06f)), Offset(w * .43f, h * (y - .07f)), stroke)
                    drawLine(color, Offset(w * .53f, h * y), Offset(w * .72f, h * y), stroke)
                }
            }
        }
    }
}
