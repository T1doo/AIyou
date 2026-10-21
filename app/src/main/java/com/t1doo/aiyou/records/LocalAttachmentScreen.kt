package com.t1doo.aiyou.records

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.model.AttachmentState

private class PickerHandle { var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null }

/** VM-owned UUID registry key survives configuration, but a new VM never adopts saved launcher keys. */
@Composable
fun AttachmentPicker(controller: LocalAttachmentController, selection: AttachmentSelection?, canLaunch: Boolean = true) {
    ScopedImagePicker(controller,selection,canLaunch,controller::claimPicker,controller::selected,controller::pickerFailed)
}
@Composable
fun MixedCapturePicker(controller: MixedCaptureController,selection: AttachmentSelection?) {
    ScopedImagePicker(controller,selection,true,controller::claimPicker,controller::selected,controller::pickerFailed)
}
@Composable
private fun ScopedImagePicker(owner: Any,selection: AttachmentSelection?,canLaunch: Boolean,
    claim: (Long)->Boolean,selected: (Long,SelectedImageSource?)->Unit,failed: (Long)->Unit) {
    if (selection?.phase != AttachmentImportPhase.PICKING) return
    key(selection.pickerKey) {
        val requestId = selection.requestId
        val resolver = LocalContext.current.contentResolver
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
        val handle = remember(selection.pickerKey) { PickerHandle() }
        val contract = remember(selection.pickerKey) { PickVisualMedia() }
        DisposableEffect(registry, selection.pickerKey, owner) {
            val registered = registry.register(selection.pickerKey, contract) { uri ->
                if (uri == null) selected(requestId, null)
                else try { selected(requestId, PlatformPngSource(resolver, uri)) }
                catch (_: Exception) { failed(requestId) }
            }
            handle.launcher = registered
            onDispose { handle.launcher = null; registered.unregister() }
        }
        LaunchedEffect(selection.pickerKey, canLaunch) {
            if (canLaunch && claim(requestId)) try {
                val type = if (Build.VERSION.SDK_INT >= 28) PickVisualMedia.ImageOnly else PickVisualMedia.SingleMimeType("image/png")
                checkNotNull(handle.launcher).launch(PickVisualMediaRequest(type))
            } catch (_: Exception) { failed(requestId) }
        }
    }
}

@Composable
fun LocalAttachmentScreen(
    panel: AttachmentUiState,
    onSelect: (String?) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onRetrySave: () -> Unit,
    onView: (String) -> Unit,
    onClosePicture: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryRecords: (() -> Unit)? = null,
    onMetadataChoice: (Boolean) -> Unit = {},
    showPictureClose: Boolean = true,
    audio:OriginalAudioController?=null,
    transcripts:AudioTranscriptController?=null,
    canUse:()->Boolean={true},
    imageTexts:ImageTextController?=null,
) {
    val playback=audio?.state?.collectAsState()?.value ?: OriginalAudioUi()
    val imageTextBusy=imageTexts?.inFlight?.collectAsState()?.value==true
    // The list remains owned by this exact panel while the viewer replaces its composition.
    val listScroll = key(panel.sessionId) { rememberScrollState() }
    var showLimits by remember(panel.sessionId) { mutableStateOf(false) }
    val picture = (panel.picture as? PlatformAttachmentPicture)?.takeIf { !panel.unavailable && panel.error == null }
    if (showLimits) {
        AlertDialog(onDismissRequest = { showLimits = false }, title = { Text("图片格式与保存说明") },
            text = { Column(Modifier.heightIn(max = AIyouDimensions.contentMaxWidth).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("仅保存在本机。图片关联此原文版本，编辑原文不会移动附件。")
                if (panel.readOnly) Text("历史阅读仅查看此版本已有附件，不能新增图片或重试添加。")
                Text(if (Build.VERSION.SDK_INT >= 28) "支持 JPEG 与静态 PNG，每张不超过 16 MiB。JPEG 宽高各不超过 8192 像素、总计不超过 6400 万像素；PNG 宽高各不超过 4096 像素。"
                    else "此设备暂支持静态 PNG：每张不超过 16 MiB，宽高各不超过 4096 像素。JPEG 需要 Android 9 或更高版本。",
                    style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                Text("原图可能包含拍摄位置等资料，仅加密保存在本机。")
                Text("选择移除资料后，将移除拍摄资料与注释，保存字节会变化；保留画面方向和色彩资料。色彩资料仍可能包含说明。PNG 原图不变。")
            } },
            confirmButton = { TextButton(onClick = { showLimits = false },
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("关闭说明") } })
    }
    if (picture != null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize().padding(AIyouDimensions.pageNarrow),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                Text("图片 · 原文版本 ${panel.revision}" + if (panel.readOnly) " · 只读" else "", style = MaterialTheme.typography.titleMedium)
                if (showPictureClose) TextButton(onClick = onClosePicture,
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("返回附件列表") }
                // Bounded viewport + Fit displays the complete picture, regardless of its aspect ratio.
                Image(picture.image, "图片附件，关联原文版本 ${panel.revision}", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("attachment-picture"))
                val selected=panel.items.singleOrNull{it.intent.id==panel.pictureId&&it.state==AttachmentState.READY&&it.intent.mediaType in LocalAttachmentController.IMAGE_TYPES}
                if(imageTexts!=null&&!panel.readOnly&&selected!=null)TextButton(onClick={if(canUse())imageTexts.open(selected.intent)},enabled=!imageTextBusy&&!panel.busy&&panel.error==null,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("image-text-open:"+selected.intent.id)){Text("提取／纠正此图文字")}
                if(imageTextBusy)Text("上一图片提取仍在结束，等待资源释放后可再次打开文字。")
                TextButton(onClick = { showLimits = true },
                    modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("attachment-limits")) { Text("图片格式与保存说明") }
            }
        }
        return
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
            .verticalScroll(listScroll).padding(AIyouDimensions.pageNarrow).testTag("attachment-scroll"),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Text("附件 · 原文版本 ${panel.revision}" + if (panel.readOnly) " · 只读" else "", style = MaterialTheme.typography.titleLarge)
            Text("仅保存在本机。图片关联此原文版本，编辑原文不会移动附件。",
                style = MaterialTheme.typography.bodyMedium, color = AIyouColors.textSecondary)
            panel.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            when {
                panel.loading -> StatusNotice("正在读取附件", "正在核对本机保存状态。", state = ComponentState.LOADING)
                panel.selection?.phase == AttachmentImportPhase.PREPARING -> StatusNotice("正在检查图片",
                    "原文已保留，图片尚未确认保存。", state = ComponentState.LOADING)
                panel.saving -> StatusNotice("正在保存图片", "请等待保存与读取确认完成。", state = ComponentState.LOADING)
                panel.readingId != null -> StatusNotice("正在读取图片", "认证与格式检查通过后显示。", state = ComponentState.LOADING)
            }
            panel.error?.let { StatusNotice("附件需要处理", it, state = ComponentState.ERROR,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                if (onRetryRecords != null && !panel.saving) {
                    OutlinedButton(onClick = onRetryRecords, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("恢复本机读取") }
                }
                if (panel.selection == null) {
                    TextButton(onClick = onRefresh, enabled = !panel.busy,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("重试读取附件") }
                }
            }
            if (!panel.loading && panel.items.isEmpty() && panel.selection == null && panel.error == null) {
                StatusNotice("这个版本还没有附件", if (panel.readOnly) "此版本只读，当前版本的图片不会显示在这里。"
                    else "选择支持格式的图片，原文与草稿保持不变。", state = ComponentState.EMPTY)
            }
            panel.items.filter{panel.focusedIntent==null||it.intent==panel.focusedIntent}.forEachIndexed { index, item ->
                key(item.intent.id) {
                    Column(Modifier.fillMaxWidth().testTag("attachment-${item.intent.id}"),
                        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                        Text("附件 ${index + 1} · ${item.intent.mediaType} · ${item.intent.byteSize} 字节", style = MaterialTheme.typography.bodyMedium)
                        Text(when (item.state) {
                            AttachmentState.WRITING -> "保存已中断，尚未完成。请重试读取状态。"
                            AttachmentState.STAGED -> "文件已暂存，正在等待恢复确认。请重试读取状态。"
                            AttachmentState.READY -> "已保存在本机"
                            AttachmentState.FAILED -> if (panel.readOnly) "保存未完成，原文件已保留。此版本只读，不能重试添加。"
                                else "保存未完成，原文件已保留；重选时需同一图片。"
                            AttachmentState.CORRUPT -> "附件无法通过完整性校验，原文件已保留，不会显示或覆盖。"
                        }, style = MaterialTheme.typography.bodyMedium)
                        if (item.state == AttachmentState.READY && item.intent.mediaType in LocalAttachmentController.IMAGE_TYPES &&
                            (item.intent.mediaType != "image/jpeg" || Build.VERSION.SDK_INT >= 28)) {
                            TextButton(onClick = { onView(item.intent.id) }, enabled = !panel.busy && panel.selection == null && !panel.unavailable,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("查看图片") }
                        } else if(item.state==AttachmentState.READY && item.intent.mediaType=="audio/wav" && (audio!=null || transcripts!=null)) {
                            Text("原音 · 原文版本 ${panel.revision}",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={if(canUse())audio?.playAttachment(panel.sessionId,item.intent.id)},
                                enabled=audio!=null && !playback.busy && !panel.busy && panel.selection==null && !panel.unavailable && panel.error==null,
                                modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("attachment-audio-play:"+item.intent.id)) { Text("播放原音") }
                            if(!panel.readOnly && transcripts!=null)TextButton(onClick={if(canUse())transcripts.open(item.intent)},enabled=!playback.busy && !panel.busy && panel.selection==null && !panel.unavailable && panel.error==null,modifier=Modifier.heightIn(min=AIyouDimensions.minTouchTarget).testTag("attachment-transcript:"+item.intent.id)){Text("人工补转写／纠正")}
                        } else if (!panel.readOnly && item.state == AttachmentState.FAILED && item.intent.mediaType in LocalAttachmentController.IMAGE_TYPES) {
                            TextButton(onClick = { onSelect(item.intent.id) }, enabled = !panel.busy && panel.selection == null && !panel.unavailable && panel.error == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("重新选择同一图片") }
                        } else if (item.state == AttachmentState.READY) {
                            Text("此格式的查看功能尚未开放。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!panel.readOnly && panel.focusedIntent==null) {
                Text("添加图片", style = MaterialTheme.typography.titleMedium)
                Text(if (Build.VERSION.SDK_INT >= 28) "JPEG 或静态 PNG · 每张不超过 16 MiB" else "静态 PNG · 每张不超过 16 MiB",
                    style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                if (Build.VERSION.SDK_INT >= 28) {
                    Row(Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget)
                        .testTag("jpeg-metadata-choice").toggleable(value = panel.retainJpegMetadata,
                            enabled = !panel.busy && panel.selection == null && panel.error == null && !panel.unavailable,
                            role = Role.Checkbox, onValueChange = onMetadataChoice), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(panel.retainJpegMetadata, onCheckedChange = null)
                        Text("保留 JPEG 原图拍摄资料", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(if (panel.retainJpegMetadata) "原图可能包含拍摄位置等资料，仅加密保存在本机。"
                        else "将移除拍摄资料与注释，保存字节会变化；保留画面方向和色彩资料。色彩资料仍可能包含说明。PNG 原图不变。",
                        style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    if (!panel.unavailable) {
                        if (panel.selection?.phase == AttachmentImportPhase.FAILED && panel.selection.retryable) {
                            OutlinedButton(onClick = onRetrySave, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) {
                                Text(if (panel.selection.sourcePrepared) "重试保存确认" else "重新选择图片")
                            }
                        } else if (panel.selection == null) {
                            OutlinedButton(onClick = { onSelect(null) }, enabled = !panel.busy && panel.error == null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("选择图片") }

                        }
                    }
                    if (panel.selection != null && !panel.saving) {
                        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("取消添加") }
                    }
                }
            }
            TextButton(onClick = { showLimits = true },
                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("attachment-limits")) { Text("图片格式与保存说明") }
        }
    }
}
