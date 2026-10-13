package com.t1doo.aiyou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions

@Composable
fun NotebookPageHeader(title: String, subtitle: String, stacked: Boolean, companion: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            if (!stacked && companion == null) LocalProcessingStatus()
            if (!stacked) companion?.invoke()
        }
        Text(subtitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        if (stacked || companion != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            LocalProcessingStatus()
            if (stacked) companion?.invoke()
        }
    }
}
