package com.t1doo.aiyou.records

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.Clipboard
import androidx.compose.foundation.text.selection.SelectionState
import com.t1doo.aiyou.ui.Destination

/** Observe the actual app callbacks, never detached framework Modifier-node actions.
 * Internal, optional and memory-only; production has no observer or retained callback registry.
 */
internal data class OriginalReadingCallbacks(
    val openEntry: (String) -> Unit,
    val selectDestination: (Destination) -> Unit,
    val returnToList: () -> Unit,
    val retry: () -> Unit,
    val copy: (String, Int) -> Boolean,
    val beginEdit: (String, Int) -> Unit,
    val changeEdit: (Long, String) -> Unit,
    val saveEdit: (Long) -> Unit,
    val cancelEdit: (Long) -> Unit,
    val discardEdit: (Long) -> Unit,
    val beginHistory: (String, Int) -> Unit,
    val selectHistory: (Long, Int) -> Unit,
    val backHistory: (Long) -> Unit,
    val retryHistory: (Long) -> Unit,
    val copyHistory: (String, Int) -> Boolean,
)

internal interface OriginalReadingObserver {
    fun onNavigation(callbacks: OriginalReadingCallbacks)
    fun onClipboard(clipboard: Clipboard)
    fun onSelection(selection: SelectionState)
}

internal val LocalOriginalReadingObserver = staticCompositionLocalOf<OriginalReadingObserver?> { null }
