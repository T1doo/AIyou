package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Original geometric placeholder; no third-party reference pixels, costume or insignia. */
@Composable
fun NeutralCapybara(state: MascotState, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        withTransform({ scale(size.width / 100f, size.height / 100f, pivot = Offset.Zero) }) {
            drawOval(AIyouColors.companionShade.copy(alpha = .15f), Offset(18f, 86f), Size(67f, 8f))
            drawRoundRect(AIyouColors.companionBody, Offset(31f, 42f), Size(48f, 45f), CornerRadius(20f))
            drawRoundRect(AIyouColors.companionShade, Offset(29f, 78f), Size(17f, 12f), CornerRadius(5f))
            drawRoundRect(AIyouColors.companionShade, Offset(64f, 78f), Size(18f, 12f), CornerRadius(5f))
            drawCircle(AIyouColors.companionShade, 9f, Offset(32f, 24f))
            drawCircle(AIyouColors.companionBody, 6f, Offset(32f, 24f))
            drawCircle(AIyouColors.companionShade, 8f, Offset(66f, 21f))
            drawCircle(AIyouColors.companionBody, 5f, Offset(66f, 21f))
            drawRoundRect(AIyouColors.companionBody, Offset(21f, 23f), Size(57f, 44f), CornerRadius(21f))
            drawRoundRect(AIyouColors.companionFace, Offset(12f, 43f), Size(48f, 25f), CornerRadius(12f))
            drawOval(AIyouColors.companionInk, Offset(17f, 48f), Size(7f, 4f))
            if (state == MascotState.Saved) {
                drawLine(AIyouColors.companionInk, Offset(40f, 40f), Offset(46f, 39f), 2.5f, StrokeCap.Round)
                drawLine(AIyouColors.companionInk, Offset(62f, 39f), Offset(68f, 40f), 2.5f, StrokeCap.Round)
            } else {
                drawCircle(AIyouColors.companionInk, 2.2f, Offset(43f, 39f))
                drawCircle(AIyouColors.companionInk, 2.2f, Offset(65f, 39f))
            }
            drawLine(AIyouColors.companionInk, Offset(26f, 59f), Offset(34f, 59f), 1.8f, StrokeCap.Round)
            drawLine(AIyouColors.companionShade, Offset(56f, 67f), Offset(55f, 78f), 3f, StrokeCap.Round)
        }
    }
}
