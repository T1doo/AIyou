package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Content-only surface: no click interception, persistence, permissions or status. */
@Composable
fun AIyouPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = AIyouDimensions.spaceLg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = if (LocalSkyglassActive.current) LocalSkyglassPalette.current else null
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = if (glass != null) MaterialTheme.shapes.medium else androidx.compose.ui.graphics.RectangleShape,
        color = glass?.input ?: AIyouColors.surface,
        contentColor = glass?.text ?: AIyouColors.textPrimary,
        tonalElevation = AIyouDimensions.panelElevation,
        shadowElevation = AIyouDimensions.panelElevation,
    ) {
        Column(
            Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm),
        ) {
            content()
            if (glass == null) androidx.compose.material3.HorizontalDivider(color = AIyouColors.outlineDecorative)
        }
    }
}
