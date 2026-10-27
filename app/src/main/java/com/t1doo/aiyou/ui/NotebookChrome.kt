package com.t1doo.aiyou.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.aiyouFocusOutline

/** Natural height at large font scales; system insets remain part of the chrome. */
@Composable
fun NotebookAppBar(title: String, actionLabel: String, actionEnabled: Boolean = true,
    containerColor: Color = AIyouColors.surface, onAction: () -> Unit) {
    Surface(color = containerColor) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 56.dp).padding(horizontal = AIyouDimensions.pageNarrow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
            if (title == "爱忆 AIyou") Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("爱忆", Modifier.alignByBaseline().semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text("AIyou", Modifier.alignByBaseline(), style = MaterialTheme.typography.labelSmall, color = AIyouColors.textSecondary)
            } else Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onAction, enabled = actionEnabled,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.textButtonColors(containerColor = AIyouColors.surfaceMuted),
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).aiyouFocusOutline()) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun NotebookNavigation(selected: Destination?, onSelect: (Destination) -> Unit,
    containerColor: Color = AIyouColors.surface, motionAllowed: Boolean = false,
    allowReselect: Boolean = true) {
    val direction = LocalLayoutDirection.current
    Surface(color = containerColor) {
      Column {
        HorizontalDivider(color = AIyouColors.outlineDecorative)
        Row(Modifier.fillMaxWidth().testTag("notebook-navigation").selectableGroup()
            .windowInsetsPadding(WindowInsets.navigationBars)
            // Restrict page gestures to navigation: never steal a text-selection/list drag.
            // Replacing the callback cancels the gesture rather than carrying it into a new owner.
            .pointerInput(selected, onSelect, direction, allowReselect) {
                var travel = 0f
                detectHorizontalDragGestures(onDragStart = { travel = 0f }, onDragCancel = { travel = 0f },
                    onHorizontalDrag = { change, amount -> change.consume(); travel += amount },
                    onDragEnd = {
                        if (selected != null && kotlin.math.abs(travel) >= 48.dp.toPx()) {
                            val step = (if (travel < 0) 1 else -1) * (if (direction == LayoutDirection.Ltr) 1 else -1)
                            Destination.entries.getOrNull(selected.ordinal + step)?.let(onSelect)
                        }
                        travel = 0f
                    })
            },
            verticalAlignment = Alignment.CenterVertically) {
            Destination.entries.forEach { destination ->
                val active = selected == destination
                val color = if (active) AIyouColors.primary else AIyouColors.textSecondary
                val interaction = remember(destination) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed && motionAllowed) .96f else 1f,
                    animationSpec = tween(if (motionAllowed) 110 else 0), label = "navigation-press")
                val indicator by animateColorAsState(if (active) AIyouColors.primaryContainer else containerColor,
                    animationSpec = tween(if (motionAllowed) 160 else 0), label = "navigation-selection")
                Column(Modifier.weight(1f).aiyouFocusOutline()
                    .testTag("notebook-tab-${destination.name}")
                    .selectable(active, interactionSource = interaction, indication = LocalIndication.current,
                        role = Role.Tab, onClick = { if (!active || allowReselect) onSelect(destination) })
                    .heightIn(min = 56.dp).padding(vertical = 4.dp, horizontal = AIyouDimensions.spaceXs)
                    .graphicsLayer { scaleX = scale; scaleY = scale },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Surface(color = indicator,
                        shape = MaterialTheme.shapes.medium) {
                        CompositionLocalProvider(LocalContentColor provides color) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
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
}
