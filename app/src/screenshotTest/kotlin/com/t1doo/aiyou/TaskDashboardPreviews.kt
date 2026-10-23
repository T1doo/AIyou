package com.t1doo.aiyou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Synthetic snapshots in the real task route. No store, permission request or scheduler is opened.
 * The existing LocalTasksRender annotations retain the same four viewport identities. */
@Composable
internal fun TaskDashboardPreview() {
    val clock = remember { Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), ZoneOffset.UTC) }
    val local = remember {
        fun task(id: String, title: String, date: String, phase: TaskReminderPhase): ManualTaskSnapshot {
            val schedule = checkNotNull(ManualTaskDateInput(date, "15:00", "Asia/Shanghai").parse())
            val base = ManualTaskSnapshot(id, 1, title, ManualTaskStatus.OPEN, 55, "UTC", 55, schedule)
            return base.copy(reminder = TaskReminders.confirm(base, TaskReminderMode.APPROXIMATE)
                .copy(phase = phase, commands = emptyList()))
        }
        val original = StoredEntry("synthetic-task-source", 1, "合成原文：整理周末出行清单。", 55, "UTC", ProcessingPolicy.LOCAL_ONLY)
        val binding = TaskSourceBinding(SourceVersion(original.id, 1), OriginalExports.hash(original.originalText),
            1, 0, original.originalText.codePointCount(0, original.originalText.length))
        LocalRecordUiState(ready = true, opening = false, entries = listOf(original),
            tasks = ManualTasksUiState(supported = true, available = true, items = listOf(
                task("synthetic-plan-a", "合成待办：整理旅行清单", "2026-10-23", TaskReminderPhase.SCHEDULED),
                task("synthetic-plan-b", "合成待办：为植物换盆", "2026-10-23", TaskReminderPhase.NOTIFICATION_DENIED),
                task("synthetic-plan-c", "合成待办：归还借阅书籍", "2026-10-24", TaskReminderPhase.POST_UNKNOWN))),
            taskProposals = TaskProposalsUiState(supported = true, available = true, items = listOf(
                TaskProposalItem(TaskProposal("synthetic-pending", 1, binding, "合成尚未确认的想法",
                    ManualTaskDateInput(), 55, "UTC", 55), sourceAvailable = false))))
    }
    AIyouTheme {
        AIyouApp(previewDestination = Destination.TASKS, local = local, taskClock = clock,
            display = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true))
    }
}
