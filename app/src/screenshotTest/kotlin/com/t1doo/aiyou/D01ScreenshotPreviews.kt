package com.t1doo.aiyou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.ui.*
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.records.OriginalHistoryUiState
import com.t1doo.aiyou.records.OriginalHistoryReplacement
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.records.OriginalEditUiState
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.LocalAttachment
import com.t1doo.aiyou.records.AttachmentUiState
import com.t1doo.aiyou.records.AttachmentSelection
import com.t1doo.aiyou.records.AttachmentImportPhase
import com.t1doo.aiyou.records.LocalAttachmentScreen
import com.t1doo.aiyou.records.PlatformPngDecoder
import com.t1doo.aiyou.records.AttachmentHistoryOwner
import com.t1doo.aiyou.records.LocalAttachmentController

// Initial render smoke, not regression against approved visual golden files.
@PreviewTest
@ComponentPreviews
@Composable
fun MemoryRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { MemoryCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun ComposerRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { CaptureComposerPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun PrivacyRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { PrivacyChipPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun SourceRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { SourceCitationCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun ProposalRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { TaskProposalCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun ProviderRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { ProviderConnectionCardPreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun NoticeRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { StatusNoticePreview(state) }

@PreviewTest
@ComponentPreviews
@Composable
fun MascotRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { MascotBadgePreview(state) }

@PreviewTest
@ScreenPreviews
@Composable
fun NowRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { NowPreview(state) }

@PreviewTest
@ScreenPreviews
@Composable
fun MemoriesRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { MemoriesPreview(state) }

@PreviewTest
@ScreenPreviews
@Composable
fun TasksRender(@PreviewParameter(ComponentStates::class) state: ComponentState) { TasksPreview(state) }

@PreviewTest
@Preview(name = "主导航", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "主导航窄屏大字体", widthDp = 320, heightDp = 800, fontScale = 2f, locale = "zh-rCN")
@Composable
fun ShellRender() { AIyouTheme { AIyouApp() } }

// Review the whole phone page, including app bar, primary input and bottom navigation.
@PreviewTest
@Preview(name = "手机壳常规", widthDp = 360, heightDp = 800, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "手机壳窄屏大字体", widthDp = 320, heightDp = 1000, fontScale = 2f, showSystemUi = true, locale = "zh-rCN")
@Composable
fun ReviewNowRender() { AIyouTheme { AIyouApp(previewDestination = Destination.NOW, previewDemo = true) } }

@PreviewTest
@Preview(name = "手机壳常规", widthDp = 360, heightDp = 800, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "手机壳窄屏大字体", widthDp = 320, heightDp = 1000, fontScale = 2f, showSystemUi = true, locale = "zh-rCN")
@Composable
fun ReviewMemoriesRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, previewDemo = true) } }

// The actual production route: no previewDemo switch, database or model call.
@Preview(name = "本机常规", widthDp = 360, heightDp = 800, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "本机窄屏大字体", widthDp = 320, heightDp = 1000, fontScale = 2f, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "本机横屏", widthDp = 800, heightDp = 360, showSystemUi = true, locale = "zh-rCN")
@Preview(name = "本机大屏", widthDp = 840, heightDp = 1000, showSystemUi = true, locale = "zh-rCN")
annotation class LocalRecordPreviews

private val syntheticEntries = listOf(StoredEntry("synthetic-ui-1", 1,
    "合成记录：散步时看见一片新叶子，把这个小小的发现记下来。", 1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY))
private val restoredLocal = LocalRecordUiState(ready = true, opening = false,
    text = "合成恢复的草稿：把沿途的树叶颜色记下来。", draftNotice = "已恢复本机草稿", entries = syntheticEntries)

// Actual mixed-capture route with synthetic state. The render never opens a database,
// accepts media, or reports a successful write from its non-executing actor port.
@Composable
private fun MixedNotebookPreview(withRecent:Boolean) {
    val clock=remember { java.time.Clock.fixed(java.time.Instant.parse("2026-10-23T12:00:00Z"),java.time.ZoneOffset.UTC) }
    val local=remember(withRecent) {
        LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,
            entries=if(withRecent) listOf(StoredEntry("31a33c5b-b173-4688-a430-3597d59ebd20",1,
                "合成记录：散步回来，记下路边那棵树。",clock.millis(),"UTC",ProcessingPolicy.LOCAL_ONLY)) else emptyList(),
            mixedCapture=com.t1doo.aiyou.records.MixedCaptureUiState(
                draft=com.t1doo.aiyou.model.CaptureDraft("68a2a5f4-4be7-4dc0-9b85-6b26e4518821",1,"",emptyList()),
                supportsProcessingChoice=true))
    }
    val controller=remember(local) { com.t1doo.aiyou.records.MixedCaptureController(
        {local},{},{false},{error("Preview does not import media")},clock) }
    AIyouTheme { AIyouApp(local=local,mixedCapture=controller,
        display=UiPreferencesState(UiPreferences(reduceMotion=true),ready=true)) }
}

@PreviewTest @LocalRecordPreviews @Composable
fun MixedNotebookEmptyRender() { MixedNotebookPreview(withRecent=false) }

@PreviewTest @LocalRecordPreviews @Composable
fun MixedNotebookRecentRender() { MixedNotebookPreview(withRecent=true) }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalEmptyRender() { AIyouTheme { AIyouApp(display = UiPreferencesState(UiPreferences(), ready = true), local = LocalRecordUiState(ready = true, opening = false, draftNotice = "没有未保存的草稿")) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalRestoredRender() { AIyouTheme { AIyouApp(display = UiPreferencesState(UiPreferences(), ready = true), local = restoredLocal) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalCaptureDiscardRender() { AIyouTheme { AIyouApp(local = restoredLocal.copy(
    draftNotice = "当前输入未确认保存在本机，退出应用可能丢失。",
    captureDiscard = com.t1doo.aiyou.records.CaptureDiscardUiState(1, recoveryRequired = true, recoveryFailed = true,
        error = "保留草稿尚未确认，退出应用可能丢失当前输入。请重试保留，或返回编辑后自行保留输入。"))) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalSavingRender() { AIyouTheme { AIyouApp(local = restoredLocal.copy(saving = true, savePending = true, draftNotice = "草稿已保存在本机")) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalSaveFailedRender() { AIyouTheme { AIyouApp(display = UiPreferencesState(UiPreferences(), ready = true), local = restoredLocal.copy(savePending = true,
    error = "合成保存失败。输入与原数据已保留，可以重试。", draftNotice = "草稿保存状态待确认")) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalReadFailedRender() { AIyouTheme { AIyouApp(local = LocalRecordUiState(opening = false,
    error = "合成读取失败。请保留原数据并先解锁设备重试。", draftNotice = "草稿尚未读取")) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalSavedRender() { AIyouTheme { AIyouApp(local = restoredLocal.copy(text = "", saveNotice = "已保存在本机", draftNotice = "没有未保存的草稿")) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalRejectedInputRender() { AIyouTheme { AIyouApp(local = restoredLocal.copy(
    inputNotice = "合成超限输入。输入长度上限为 100000，本次输入未替换原草稿，请缩短后重试。")) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalMemoriesRender() { NotebookLibraryPreview(fullBackup=false) }

// Whole production task route, including navigation/settings and the optional companion.
// Local records are synthetic; no task creation/completion or demo proposal is fabricated.
@PreviewTest @LocalRecordPreviews @Composable
fun LocalTasksRender() { AIyouTheme { AIyouApp(display = UiPreferencesState(UiPreferences(), ready = true),
    previewDestination = Destination.TASKS, local = restoredLocal) } }

private val longOriginal = syntheticEntries.single().copy(id = "synthetic-long-original",
    originalText = "合成长原文：" + "散步时记录叶片的纹理与颜色。\n".repeat(90) + "合成全文末端。")
private val readingLocal = restoredLocal.copy(entries = listOf(longOriginal))

@PreviewTest @LocalRecordPreviews @Composable
fun LocalLongListRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, local = readingLocal) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalReadRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = readingLocal, previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalMissingOriginalRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = restoredLocal, previewEntryId = "synthetic-removed-id") } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalReadFailureRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = readingLocal.copy(error = "合成读取失败。请保留原数据，解锁设备后重试。"), previewEntryId = longOriginal.id) } }

private val editingLocal = readingLocal.copy(supportsEditDrafts = true, originalEdit = OriginalEditUiState(1L, longOriginal.id, 1,
    longOriginal.originalText, "合成修改：补上叶片颜色与散步路线。\n".repeat(8),
    draftNotice = "编辑草稿已加密保存在本机"))

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalEditRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = editingLocal, previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalEditSavingRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = editingLocal.copy(originalEdit = editingLocal.originalEdit!!.copy(saving = true)), previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalEditFailedRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = editingLocal.copy(originalEdit = editingLocal.originalEdit!!.copy(error = "合成保存失败。你的修改与原文已保留，可以重试。")),
    previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalEditConflictRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = editingLocal.copy(entries = listOf(longOriginal.copy(currentRevision = 2)),
        originalEdit = editingLocal.originalEdit!!.copy(conflict = true, error = "合成原文已有新版本，你的修改已保留。")), previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalEditStorageFailedRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = editingLocal.copy(error = "合成先前此刻草稿写入失败。输入与原数据仍保留，可以重试。"),
    previewEntryId = longOriginal.id) } }

// Reuse unabridged actual component content at explicit heights; no parameter multiplication.
@Composable
private fun CompleteContentFrame(label: String, content: @Composable () -> Unit) {
    AIyouTheme {
        Column {
            content()
            Text("完整内容结束 · $label", Modifier.padding(AIyouDimensions.pageNarrow))
        }
    }
}

@PreviewTest
@CompleteContentPreviews
@Composable
fun PrivacyCompleteRender() { CompleteContentFrame("隐私提示") { PrivacyChipPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun MemoryCompleteRender() { CompleteContentFrame("记忆卡片") { MemoryCardPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun NoticeCompleteRender() { CompleteContentFrame("状态说明") { StatusNoticePreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun ProposalCompleteRender() { CompleteContentFrame("任务提案") { TaskProposalCardPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun SourceCompleteRender() { CompleteContentFrame("来源引用") { SourceCitationCardPreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun MascotCompleteRender() { CompleteContentFrame("中性角色说明") { MascotBadgePreview(ComponentState.LONG_TEXT) } }

@PreviewTest
@CompleteContentPreviews
@Composable
fun ProviderCompleteRender() { CompleteContentFrame("服务连接") { ProviderConnectionCardPreview(ComponentState.LONG_TEXT) } }

// Synthetic saved revisions of the actual production history route; no repository/network call.
private val historyCurrent = longOriginal.copy(currentRevision = 2, originalText = "合成当前原文：散步时发现叶色变化。")
private val historyLocal = editingLocal.copy(entries = listOf(historyCurrent), originalHistory = OriginalHistoryUiState(
    1L, longOriginal.id, loading = false, currentRevision = 2,
    revisions = listOf(OriginalRevision(longOriginal.id, 2, historyCurrent.originalText, "synthetic", historyCurrent.capturedAtMillis),
        OriginalRevision(longOriginal.id, 1, longOriginal.originalText, "synthetic", longOriginal.capturedAtMillis))))

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalHistoryRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyLocal, previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalHistoryReadRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyLocal.copy(originalHistory = historyLocal.originalHistory!!.copy(selectedRevision = 1)), previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalHistoryLoadingRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyLocal.copy(originalHistory = historyLocal.originalHistory!!.copy(loading = true, revisions = emptyList())), previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalHistoryFailedRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyLocal.copy(originalHistory = historyLocal.originalHistory!!.copy(error = "合成历史版本读取失败，正在编辑的修改仍保留。", revisions = emptyList())), previewEntryId = longOriginal.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalOriginalHistoryUnavailableRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyLocal.copy(entries = emptyList(), originalHistory = historyLocal.originalHistory!!.copy(unavailable = true, revisions = emptyList())), previewEntryId = longOriginal.id) } }

private val historyEditor = historyLocal.copy(originalHistory = null, originalEdit = OriginalEditUiState(
    2L, historyCurrent.id, 2, historyCurrent.originalText,
    text = "合成历史原文：记录沿途树叶的颜色，再补充今天的发现。", sourceRevision = 1))

@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoryEditorRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyEditor, previewEntryId = historyCurrent.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoryEditorFailedRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyEditor.copy(originalEdit = historyEditor.originalEdit!!.copy(error = "合成保存失败。你的修改与旧原文已保留，可以重试。")),
    previewEntryId = historyCurrent.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoryEditorUnchangedRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyEditor.copy(originalEdit = historyEditor.originalEdit!!.copy(text = historyCurrent.originalText)),
    previewEntryId = historyCurrent.id) } }

@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoryReplacementRender() { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
    local = historyLocal.copy(originalHistory = historyLocal.originalHistory!!.copy(selectedRevision = 1,
        replacement = OriginalHistoryReplacement(1L, historyLocal.originalEdit!!.sessionId))), previewEntryId = historyCurrent.id) } }

// Actual attachment screen with synthetic display states; no picker, repository, permission or device claim.
private val attachmentPanel = AttachmentUiState(1L, "synthetic-original", 1, loading = false)
private val attachmentIntent = AttachmentIntent("synthetic-attachment", "synthetic-original", 1,
    "image/png", "0".repeat(64), 70)
@Composable private fun AttachmentFrame(panel: AttachmentUiState) {
    AIyouTheme { androidx.compose.material3.Surface { LocalAttachmentScreen(panel, {}, {}, {}, {}, {}, {}) } }
}
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentEmptyRender() { AttachmentFrame(attachmentPanel) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentLoadingRender() { AttachmentFrame(attachmentPanel.copy(loading = true)) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentSavingRender() { AttachmentFrame(attachmentPanel.copy(selection = AttachmentSelection(2L,
    attachmentIntent.id, phase = AttachmentImportPhase.SAVING, sourcePrepared = true))) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentConfirmFailedRender() { AttachmentFrame(attachmentPanel.copy(selection = AttachmentSelection(2L,
    attachmentIntent.id, phase = AttachmentImportPhase.FAILED, sourcePrepared = true), error = "合成保存确认失败。原文与图片已保留，可以重试确认。")) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentReadyRender() {
    val bytes = androidx.compose.runtime.remember { AttachmentUiImages.landscape() }
    val picture = androidx.compose.runtime.remember { PlatformPngDecoder.decode(bytes) }
    androidx.compose.runtime.DisposableEffect(picture) { onDispose { picture.releaseUnpublished() } }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val intent = attachmentIntent.copy(sha256 = digest, byteSize = bytes.size.toLong())
    AttachmentFrame(attachmentPanel.copy(items = listOf(LocalAttachment(intent, AttachmentState.READY)),
        picture = picture, notice = "图片已保存在本机 · 原文版本 1"))
}
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentJpegReadyRender() {
    val bytes = androidx.compose.runtime.remember { AttachmentUiImages.portrait() }
    val picture = androidx.compose.runtime.remember { com.t1doo.aiyou.records.PlatformImageDecoder.decode(bytes) }
    androidx.compose.runtime.DisposableEffect(picture) { onDispose { picture.releaseUnpublished() } }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val intent = attachmentIntent.copy(mediaType = "image/jpeg", sha256 = digest, byteSize = bytes.size.toLong())
    AttachmentFrame(attachmentPanel.copy(items = listOf(LocalAttachment(intent, AttachmentState.READY)),
        picture = picture, notice = "图片已保存在本机 · 原文版本 1"))
}
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentJpegStrippedRender() { AttachmentFrame(attachmentPanel.copy(retainJpegMetadata = false)) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentFailedRestartRender() { AttachmentFrame(attachmentPanel.copy(items = listOf(LocalAttachment(attachmentIntent, AttachmentState.FAILED)))) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentCorruptRender() { AttachmentFrame(attachmentPanel.copy(items = listOf(LocalAttachment(attachmentIntent, AttachmentState.CORRUPT)))) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalAttachmentUnavailableRender() { AttachmentFrame(attachmentPanel.copy(unavailable = true,
    error = "合成原文已有新版本。附件保留在原版本，请返回原文。")) }

private val historicalAttachmentPanel = attachmentPanel.copy(historyOwner = AttachmentHistoryOwner(7L, 3L, 2))
@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoricalAttachmentReadyRender() {
    val bytes = androidx.compose.runtime.remember { AttachmentUiImages.landscape() }
    val picture = androidx.compose.runtime.remember { PlatformPngDecoder.decode(bytes) }
    androidx.compose.runtime.DisposableEffect(picture) { onDispose { picture.releaseUnpublished() } }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val intent = attachmentIntent.copy(sha256 = digest, byteSize = bytes.size.toLong())
    AttachmentFrame(historicalAttachmentPanel.copy(items = listOf(LocalAttachment(intent, AttachmentState.READY)),
        picture = picture))
}
@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoricalAttachmentFailedRender() { AttachmentFrame(historicalAttachmentPanel.copy(
    items = listOf(LocalAttachment(attachmentIntent, AttachmentState.FAILED)))) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoricalAttachmentCorruptRender() { AttachmentFrame(historicalAttachmentPanel.copy(
    items = listOf(LocalAttachment(attachmentIntent, AttachmentState.CORRUPT)))) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoricalAttachmentUnavailableRender() { AttachmentFrame(historicalAttachmentPanel.copy(
    unavailable = true, error = "合成历史请求已变化。此版本附件已保留，请返回历史原文重新打开。")) }
@PreviewTest @LocalRecordPreviews @Composable
fun LocalHistoricalAttachmentEntryRender() {
    // Capability exposes the actual historical button; no preview IO/picker/storage calls.
    val capability = androidx.compose.runtime.remember { LocalAttachmentController({ false }, PlatformPngDecoder::decode) }
    AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES,
        local = historyLocal.copy(originalHistory = historyLocal.originalHistory!!.copy(selectedRevision = 1)),
        previewEntryId = longOriginal.id, attachments = capability) }
}

// Production settings and original neutral companion; render smoke, not device evidence.
@PreviewTest @LocalRecordPreviews @Composable
fun LocalSettingsRender() { AIyouTheme { AIyouApp(previewSettings = true,
    display = UiPreferencesState(UiPreferences(), ready = true), local = restoredLocal) } }

@PreviewTest
@Preview(name = "陪伴面板", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "陪伴面板大字", widthDp = 320, heightDp = 1000, fontScale = 2f, locale = "zh-rCN")
@Composable
fun LocalCompanionRender() { AIyouTheme { CompanionDialog(onClose = {}, motionAllowed = false) } }

// Full production entry points, synthetic local state, and explicitly non-executing
// actor/SAF ports. This is a layout render, not a storage/navigation execution test.
@Composable
private fun NotebookLibraryPreview(fullBackup:Boolean) {
    val clock=remember { java.time.Clock.fixed(java.time.Instant.parse("2026-10-23T12:00:00Z"),java.time.ZoneOffset.UTC) }
    val owner=remember { object:com.t1doo.aiyou.domain.UnifiedKeywordRepository {
        override suspend fun search(keyword:String,scope:com.t1doo.aiyou.domain.UnifiedKeywordScope):com.t1doo.aiyou.domain.UnifiedKeywordSnapshot = error("Preview does not query")
        override suspend fun resolve(snapshot:com.t1doo.aiyou.domain.UnifiedKeywordSnapshot,item:com.t1doo.aiyou.domain.UnifiedKeywordEvidence?,admit:()->Unit):Boolean = error("Preview does not resolve")
    } }
    val local=remember(fullBackup) { restoredLocal.copy(supportsImageTexts=true,supportsAudioTranscripts=true,
        supportsSourcePreferences=true,supportsMemoryInbox=true,supportsFactRecaps=true,
        supportsRecordPackages=true,supportsFullBackup=true,supportsOriginalExport=true,supportsRecycleBin=true,
        unifiedKeywordOwner=owner,recordPackage=if(fullBackup)com.t1doo.aiyou.records.RecordPackageUiState(1,fullMode=true)else null) }
    val current=remember(local) { {local} }
    val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit=remember { {} }
    val enqueue:(suspend(com.t1doo.aiyou.records.EntryRepository)->Unit)->Boolean=remember { {false} }
    val unified=remember(local) { com.t1doo.aiyou.records.UnifiedKeywordController(current,publish,enqueue) }
    val images=remember(local) { com.t1doo.aiyou.records.ImageTextController(current,publish,enqueue,clock) }
    val audio=remember(local) { com.t1doo.aiyou.records.AudioTranscriptController(current,publish,enqueue,clock) }
    val preferences=remember(local) { com.t1doo.aiyou.records.SourcePreferenceController(current,publish,enqueue,clock) }
    val memories=remember(local) { com.t1doo.aiyou.records.SourceMemoryInboxController(current,publish,enqueue) }
    val recaps=remember(local) { com.t1doo.aiyou.records.FactRecapController(current,publish,enqueue,clock) }
    val exports=remember(local) { com.t1doo.aiyou.records.OriginalExportController(current,publish,enqueue,clock) }
    val recycle=remember(local) { com.t1doo.aiyou.records.RecycleBinController(current,publish,enqueue) }
    val packages=remember(local) { com.t1doo.aiyou.records.RecordPackageController(current,publish,enqueue,
        com.t1doo.aiyou.data.JcaRecordPackageCipher(),clock) }
    val picker=remember { object:com.t1doo.aiyou.records.RecordPackagePicker {
        override fun launch(export:Boolean,requestKey:String,callback:(com.t1doo.aiyou.records.SelectedRecordPackageDocument?)->Unit)=false
        override fun cancel(requestKey:String)=Unit
    } }
    AIyouTheme { AIyouApp(previewDestination=Destination.MEMORIES,local=local,currentRecords=current,
        display=UiPreferencesState(UiPreferences(reduceMotion=true),ready=true),unifiedKeywords=unified,
        imageTexts=images,audioTranscripts=audio,sourcePreferences=preferences,memoryInbox=memories,
        factRecaps=recaps,exports=exports,recycleBin=recycle,recordPackages=packages,recordPackagePicker=picker) }
}

@PreviewTest @LocalRecordPreviews @Composable
fun FullBackupHomeRender() { NotebookLibraryPreview(fullBackup=true) }
