package com.t1doo.aiyou.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import com.t1doo.aiyou.model.ManualTagSnapshot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.aiyouFocusOutline
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

interface OriginalSearchObserver {
    fun onResults(state: OriginalSearchState, open: (OriginalSearchHit) -> Unit)
    fun onDiagnostics(value: String) = Unit
    fun onInputs(query: TextFieldState, from: TextFieldState, through: TextFieldState) = Unit
}
val LocalOriginalSearchObserver = staticCompositionLocalOf<OriginalSearchObserver?> { null }

/** Position contains no query or original text; retained only by the live composition. */
data class OriginalSearchReturnAnchor(val key: String?, val index: Int, val offset: Int)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalOriginalSearchSection(query: TextFieldState, from: TextFieldState, through: TextFieldState,
    mode: OriginalSearchMode, enabled: Boolean, onMode: () -> Unit, onClear: () -> Unit,
    tag: String? = null, tags: Map<String, ManualTagSnapshot> = emptyMap(), tagsAvailable: Boolean = false,
    tagsError: String? = null, onTag: (String?) -> Unit = {},
    onBeforeSearchInput: (TextFieldState) -> Boolean = { true },
    onHistoryInputCommitted: () -> Unit = {},
    indexSupported: Boolean = false, indexBusy: Boolean = false, indexError: String? = null,
    onRebuild: () -> Unit = {},
    favoritesSupported: Boolean = false, favoritesAvailable: Boolean = false, favoritesLoading: Boolean = false,
    favoritesError: String? = null, onlyFavorites: Boolean = false,
    onFavorites: (Boolean) -> Unit = {}, onRefreshFavorites: () -> Unit = {},
    dateBasis: com.t1doo.aiyou.model.RecordDateBasis = com.t1doo.aiyou.model.RecordDateBasis.CAPTURED,
    onDateBasis: (com.t1doo.aiyou.model.RecordDateBasis) -> Unit = {},
    eventDatesAvailable: Boolean = true, eventDatesError: String? = null,
    onRefreshEventDates: () -> Unit = {}, onlyImages: Boolean = false,
    onImages: (Boolean) -> Unit = {}, person: String? = null, peopleSupported: Boolean = false,
    peopleAvailable: Boolean = false, peopleError: String? = null, onPerson: (String?) -> Unit = {},
    onRefreshPeople: () -> Unit = {},onQuestionKeywords:(()->Unit)?=null,
    searchNeedsAttention: Boolean = false) {
    // These owners live outside the disclosure, never in SavedState. Collapsing an
    // empty filter must not replace its actual editable/Undo object or hide errors.
    var filtersExpanded by remember(query, from, through) { mutableStateOf(false) }
    val personInput = remember(query, person) { TextFieldState(person.orEmpty()) }
    val personInvalid = remember(query, person) { mutableStateOf(false) }
    val keepFiltersVisible = query.text.isNotEmpty() || from.text.isNotEmpty() || through.text.isNotEmpty() ||
        mode != OriginalSearchMode.KEYWORDS || dateBasis != com.t1doo.aiyou.model.RecordDateBasis.CAPTURED ||
        tag != null || person != null || onlyFavorites || onlyImages || !enabled || searchNeedsAttention ||
        tagsError != null || (indexSupported && (indexBusy || indexError != null)) ||
        (favoritesSupported && (favoritesLoading || !favoritesAvailable || favoritesError != null)) ||
        (peopleSupported && (!peopleAvailable || peopleError != null || personInput.text.isNotEmpty() || personInvalid.value))
    val showFilters = filtersExpanded || keepFiltersVisible
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceMd)) {
            fun transform(state: TextFieldState, maximum: Int) =
                InputTransformation.maxLength(maximum).then {
                    // Only retire a private request counter before an actual user
                    // text change. No Flow/Compose/TextFieldState mutation here.
                    if (changes.changeCount > 0 && !asCharSequence().contentEquals(originalText) &&
                        !onBeforeSearchInput(state)) revertAllChanges()
                }
            fun historyKeys(state: TextFieldState) = Modifier.onPreviewKeyEvent { event ->
                val shortcut = event.isCtrlPressed || event.isMetaPressed
                val history = shortcut && (event.key == Key.Z || event.key == Key.Y)
                if (history && event.type == KeyEventType.KeyDown) {
                    val redo = event.key == Key.Y || event.isShiftPressed
                    val available = if (redo) state.undoState.canRedo else state.undoState.canUndo
                    if (available && onBeforeSearchInput(state)) {
                        // Foundation's actual shared Undo, outside transformation.
                        try { if (redo) state.undoState.redo() else state.undoState.undo() }
                        finally { onHistoryInputCommitted() }
                    }
                }
                // Consume both edges, including an unavailable/retired Undo, so
                // a platform legacy fallback cannot insert a literal z/y.
                history
            }
        Surface(color = AIyouColors.primaryContainer, shape = MaterialTheme.shapes.large) {
          Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd),
              verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Text("本机原文查找", style = MaterialTheme.typography.titleMedium,
                color = AIyouColors.primary, modifier = Modifier.semantics { heading() })
            // The actual editable/Undo object is the caller's memory-only state.
            key(query) { CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            OutlinedTextField(state = query,
                enabled = enabled, label = { Text("关键词或精确片段") },
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AIyouColors.surfaceMuted,
                    unfocusedContainerColor = AIyouColors.background,
                    disabledContainerColor = AIyouColors.surfaceMuted),
                modifier = Modifier.fillMaxWidth().then(historyKeys(query)).testTag("original-search-query"),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                inputTransformation = transform(query, 100_000),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None))
            } }
            if(indexSupported)onQuestionKeywords?.let{open->TextButton(onClick=open,enabled=enabled,
                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).aiyouFocusOutline().testTag("question-keyword-open")){Text("从问题里选词")}}
            TextButton(onClick = onMode, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-mode")) {
                Text(if (mode == OriginalSearchMode.EXACT) "精确片段 · 区分大小写和空白" else "关键词 · 全部包含，忽略大小写")
            }
          }
        }
        Surface(color = AIyouColors.surface, shape = MaterialTheme.shapes.large,
            border = BorderStroke(AIyouDimensions.borderWidth, AIyouColors.outlineDecorative)) {
          Column(Modifier.fillMaxWidth().padding(AIyouDimensions.spaceMd),
              verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            TextButton(onClick = { filtersExpanded = !filtersExpanded }, enabled = enabled && !keepFiltersVisible,
                modifier = Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                    .aiyouFocusOutline().testTag("original-search-filters")
                    .semantics { stateDescription = if (showFilters) "已展开" else "已收起" }) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("筛选与范围", modifier = Modifier.width(IntrinsicSize.Max).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium, color = AIyouColors.primary)
                    Text(if (keepFiltersVisible) "已展开" else if (showFilters) "收起" else "展开",
                        modifier = Modifier.width(IntrinsicSize.Max),
                        style = MaterialTheme.typography.titleMedium, color = AIyouColors.primary)
                }
            }
            if (!showFilters) Text("日期 · 人物 · 标签 · 收藏 · 图片", style = MaterialTheme.typography.bodySmall,
                color = AIyouColors.textSecondary)
            if (showFilters) {
            Text("关键词可在当前原文、手工标签或你为本条记录确认的别名关系中满足；精确片段只查原文。标签和别名命中只供查找，不构成原文出处。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onDateBasis(if (dateBasis == com.t1doo.aiyou.model.RecordDateBasis.CAPTURED)
                com.t1doo.aiyou.model.RecordDateBasis.EVENT else com.t1doo.aiyou.model.RecordDateBasis.CAPTURED) }, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-date-basis")) {
                Text(if (dateBasis == com.t1doo.aiyou.model.RecordDateBasis.CAPTURED) "日期依据：记录日期 · 切换事件日期" else "日期依据：事件日期 · 切换记录日期")
            }
            if (dateBasis == com.t1doo.aiyou.model.RecordDateBasis.EVENT && !eventDatesAvailable) {
                Text(eventDatesError ?: "事件日期暂不可读取，未显示不完整结果。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefreshEventDates, enabled = enabled,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-event-date-retry")) {
                    Text("重试读取事件日期")
                }
            }
            key(from) { CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            OutlinedTextField(state = from,
                enabled = enabled, label = { Text("开始日期（YYYY-MM-DD，可留空）") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) filtersExpanded = true }
                    .then(historyKeys(from)).testTag("original-search-from"),
                lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = transform(from, 10))
            } }
            key(through) { CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            OutlinedTextField(state = through,
                enabled = enabled, label = { Text("结束日期（YYYY-MM-DD，可留空）") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) filtersExpanded = true }
                    .then(historyKeys(through)).testTag("original-search-through"),
                lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = transform(through, 10))
            } }
            if (peopleSupported) LocalPeopleFilter(person, enabled, peopleAvailable, peopleError, onPerson, onRefreshPeople,
                personInput, personInvalid, onInputFocused = { filtersExpanded = true })
            var choosing by remember { mutableStateOf(false) }
            TextButton(onClick = { choosing = true }, enabled = enabled && tagsAvailable,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-tag")) {
                Text(tag?.let { "手工标签：$it" } ?: "按手工标签筛选")
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                DropdownMenuItem(text = { Text("全部标签") }, onClick = { choosing = false; onTag(null) })
                tags.values.flatMap { it.names }.distinct().sorted().forEachIndexed { index, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { choosing = false; onTag(name) },
                        modifier = Modifier.testTag("original-search-tag-option-$index"))
                }
            }
            tagsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (favoritesSupported) {
                TextButton(onClick = { onFavorites(!onlyFavorites) },
                    enabled = enabled && (onlyFavorites || (favoritesAvailable && !favoritesLoading)),
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-favorites")) {
                    Text(if (onlyFavorites) "仅收藏 · 切换全部记录" else "仅看收藏记录")
                }
                if (favoritesLoading) Text("正在读取收藏状态…", style = MaterialTheme.typography.bodySmall)
                favoritesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!favoritesAvailable) TextButton(onClick = onRefreshFavorites, enabled = enabled && !favoritesLoading,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-favorites-retry")) { Text("重试读取收藏") }
            }
            TextButton(onClick = { onImages(!onlyImages) }, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-images")) {
                Text(if (onlyImages) "仅当前版本含图片 · 切换全部记录" else "仅看当前版本含图片记录")
            }
            Text("图片筛选只看当前版本的 PNG/JPEG 附件关联；不识别图片文字，历史版本有图不计入。",
                style = MaterialTheme.typography.bodySmall)
            Text(if (dateBasis == com.t1doo.aiyou.model.RecordDateBasis.CAPTURED)
                "记录日期按捕获时区，包含起止两天。关键词、日期、手工标签、收藏与图片条件同时满足；只在本机查找，不使用 AI。"
                else "事件日期按手填的日历日期与 IANA 时区，包含起止两天。未补记不匹配，不回落记录日期；与关键词、手工标签、收藏和图片条件同时满足。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear, enabled = enabled,
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-clear")) { Text("清空查找条件") }
            if (indexSupported) {
                if (indexBusy) Text("正在整理本机查找，原文仍可查找。", style = MaterialTheme.typography.bodySmall)
                indexError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onRebuild, enabled = enabled && !indexBusy,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("original-search-rebuild")) {
                    Text("重新整理本机查找")
                }
            }
            }
          }
        }
    }
}

@Composable
internal fun OriginalSearchTagMatch(hit: OriginalSearchHit) {
    if (hit.tagMatches.isNotEmpty()) Text("手工标签命中：" + hit.tagMatches.joinToString("、") + "；不作为原文出处。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("original-search-tag-match-${hit.entry.id}"))
    if (hit.aliasMatches.isNotEmpty()) Text("别名命中：" + hit.aliasMatches.joinToString("、") { it.alias + " → " + it.name } +
        "（你为本条记录确认的称呼关系）；不表示原文出现该称呼，不作为原文出处。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("original-search-alias-match-${hit.entry.id}"))
}
