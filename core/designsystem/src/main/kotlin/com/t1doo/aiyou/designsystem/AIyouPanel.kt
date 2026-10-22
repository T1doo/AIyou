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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = AIyouColors.surface,
        contentColor = AIyouColors.textPrimary,
        border = BorderStroke(AIyouDimensions.borderWidth, AIyouColors.outlineDecorative),
        tonalElevation = AIyouDimensions.panelElevation,
        shadowElevation = AIyouDimensions.panelElevation,
    ) {
        Column(
            Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm),
            content = content,
        )
    }
}
