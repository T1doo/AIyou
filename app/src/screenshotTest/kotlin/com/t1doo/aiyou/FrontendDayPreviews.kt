package com.t1doo.aiyou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.CaptureDraft
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.records.MixedCaptureController
import com.t1doo.aiyou.records.MixedCaptureUiState
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Actual production composables with fixed synthetic snapshots. No store, network or device.
 * The exact same file is also rendered against the handoff baseline for viewport comparisons. */
@PreviewTest
@Preview(name="此刻360", widthDp=360, heightDp=800, locale="zh-rCN")
@Preview(name="此刻320大字", widthDp=320, heightDp=1000, fontScale=2f, locale="zh-rCN")
@Composable
fun FrontendDayNow() {
    val clock=remember { Clock.fixed(Instant.parse("2026-11-02T02:30:00Z"),ZoneOffset.UTC) }
    val local=remember {
        val draft=CaptureDraft("00000000-0000-0000-0000-000000000009",1,"今天想留下一件小事。",emptyList())
        LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,
            mixedCapture=MixedCaptureUiState(draft=draft,text=draft.text),entries=listOf(
                StoredEntry("00000000-0000-0000-0000-000000000001",1,"路边的银杏开始变黄了。\n想等周末再走一次。",clock.millis()-60000,"UTC",ProcessingPolicy.LOCAL_ONLY),
                StoredEntry("00000000-0000-0000-0000-000000000002",1,"昨天买的那本书很好读，先把这几句话存下来。",clock.millis()-120000,"UTC",ProcessingPolicy.LOCAL_ONLY)))
    }
    val controller=remember {MixedCaptureController({local},{},{false},{error("No fixture images")},clock)}
    AIyouTheme {AIyouApp(local=local,mixedCapture=controller,
        display=UiPreferencesState(UiPreferences(reduceMotion=true),ready=true))}
}

@PreviewTest
@Preview(name="回忆360", widthDp=360, heightDp=800, locale="zh-rCN")
@Preview(name="回忆320大字", widthDp=320, heightDp=1000, fontScale=2f, locale="zh-rCN")
@Composable
fun FrontendDayMemories() {
    val local=remember {LocalRecordUiState(ready=true,opening=false,entries=listOf(
        StoredEntry("synthetic-reading-1",1,"把旅行照片做成一本小册子。\n今天把旧照片重新看了一遍，想把几个小故事收在一起。",1793586600000,"UTC",ProcessingPolicy.LOCAL_ONLY),
        StoredEntry("synthetic-reading-2",1,"想再读的两句话。慢慢来，也是一种认真。",1793500200000,"UTC",ProcessingPolicy.AI_NONE)))}
    AIyouTheme {AIyouApp(previewDestination=Destination.MEMORIES,local=local,
        display=UiPreferencesState(UiPreferences(showCompanion=false,reduceMotion=true),ready=true))}
}

@PreviewTest
@Preview(name="待办360", widthDp=360, heightDp=800, locale="zh-rCN")
@Preview(name="待办320大字", widthDp=320, heightDp=1000, fontScale=2f, locale="zh-rCN")
@Composable
fun FrontendDayTasks() {TaskDashboardPreview()}
