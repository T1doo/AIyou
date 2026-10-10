package com.t1doo.aiyou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions

/** Natural height at large font scales; system insets remain part of the chrome. */
@Composable
fun NotebookAppBar(title: String, actionLabel: String, onAction: () -> Unit) {
    Surface(color = AIyouColors.surface) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 56.dp).padding(horizontal = AIyouDimensions.pageNarrow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun NotebookNavigation(selected: Destination?, onSelect: (Destination) -> Unit) {
    Surface(color = AIyouColors.surface) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
            verticalAlignment = Alignment.CenterVertically) {
            Destination.entries.forEach { destination ->
                val active = selected == destination
                val color = if (active) AIyouColors.primary else AIyouColors.textSecondary
                Column(Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = { onSelect(destination) })
                    .heightIn(min = 72.dp).padding(vertical = AIyouDimensions.spaceSm, horizontal = AIyouDimensions.spaceXs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs, Alignment.CenterVertically)) {
                    Surface(color = if (active) AIyouColors.primaryContainer else AIyouColors.surface,
                        shape = MaterialTheme.shapes.small) {
                        CompositionLocalProvider(LocalContentColor provides color) {
                            Row(Modifier.padding(horizontal = AIyouDimensions.spaceLg, vertical = AIyouDimensions.spaceXs)) {
                                DestinationIcon(destination)
                            }
                        }
                    }
                    Text(destination.label, style = MaterialTheme.typography.bodySmall, color = color,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
