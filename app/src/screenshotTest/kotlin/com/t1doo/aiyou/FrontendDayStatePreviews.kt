package com.t1doo.aiyou

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Presentation only: no controller, audio output, store, permission, click or request is executed. */
@PreviewTest
@Preview(name = "原音播放360", widthDp = 360, heightDp = 240, locale = "zh-rCN")
@Preview(name = "原音播放320大字", widthDp = 320, heightDp = 420, fontScale = 2f, locale = "zh-rCN")
@Composable
fun FrontendDayAudioPlaying() {
    val presentation = remember {
        originalAudioPresentation(OriginalAudioUi(phase = OriginalAudioPhase.PLAYING,
            durationMillis = 12_345, playedMillis = 4_321, durationSamples = 544_415))
    }
    AIyouTheme {
        Surface(Modifier.fillMaxSize()) { OriginalAudioStatusContent(presentation, onStop = {}) }
    }
}

/** Same task screen and eligibility as production; the pending target is A, the expanded source is B.
 * The viewport begins at the real state row so the busy notice and source control can be reviewed.
 * This is a synthetic snapshot, not proof of navigation, persistence or queued IO completion.
 */
@PreviewTest
@Preview(name = "提醒提交360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "提醒提交320大字", widthDp = 320, heightDp = 1000, fontScale = 2f, locale = "zh-rCN")
@Composable
fun FrontendDayReminderPending() {
    val clock = remember { Clock.fixed(Instant.parse("2026-11-03T02:30:00Z"), ZoneOffset.UTC) }
    val local = remember {
        fun source(id: String, text: String) = StoredEntry(id, 1, text, 55, "UTC", ProcessingPolicy.LOCAL_ONLY)
        val sourceA = source("synthetic-preview-source-a", "合成原文A：给植物换盆。")
        val sourceB = source("synthetic-preview-source-b", "合成原文B：整理书架。")
        fun receipt(entry: StoredEntry) = SourceTaskReceipt(TaskSourceBinding(SourceVersion(entry.id, 1),
            OriginalExports.hash(entry.originalText), 1, 0, entry.originalText.codePointCount(0, entry.originalText.length)),
            OriginalExports.hash("synthetic-preview-receipt-" + entry.id))
        val other = ManualTaskSnapshot("synthetic-preview-task-b", 1, "合成来源B", ManualTaskStatus.OPEN,
            55, "UTC", 55, ManualTaskDateInput("2026-11-03", "", "UTC").parse(), receipt(sourceB))
        val target = ManualTaskSnapshot("synthetic-preview-task-a", 1, "合成提交A", ManualTaskStatus.OPEN,
            55, "UTC", 55, ManualTaskDateInput("2026-11-03", "15:00", "UTC").parse(), receipt(sourceA))
        val pending = ReminderSubmission(1, TaskReminderMutation(target, TaskReminderMode.APPROXIMATE,
            clock.millis(), "00000000-0000-0000-0000-0000000000a1"))
        LocalRecordUiState(ready = true, opening = false, entries = listOf(sourceA, sourceB),
            tasks = ManualTasksUiState(supported = true, available = true, items = listOf(other, target)),
            reminders = TaskRemindersUiState(supported = true, busy = true, pending = pending))
    }
    val disclosure = rememberManualTaskDisclosureState(local, setOf("synthetic-preview-task-b"))
    val list = rememberLazyListState(initialFirstVisibleItemIndex = 2)
    AIyouTheme {
        Surface(Modifier.fillMaxSize()) {
            ManualTasksScreen(local, onBegin = {}, onEdit = { _, _ -> }, onCreate = { _, _ -> },
                onChange = { _, _, _ -> }, onRetry = {}, onRefresh = {}, onClose = { _ -> },
                onRecordsRetry = {}, onSource = { _, _ -> }, clock = clock, listState = list,
                rowDisclosureState = disclosure)
        }
    }
}
