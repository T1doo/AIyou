package com.t1doo.aiyou.records

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.designsystem.StatusNotice
import com.t1doo.aiyou.model.AttachmentState

private class PickerHandle { var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null }

/** VM-owned UUID registry key survives configuration, but a new VM never adopts saved launcher keys. */
@Composable
fun AttachmentPicker(controller: LocalAttachmentController, selection: AttachmentSelection?, canLaunch: Boolean = true) {
    if (selection?.phase != AttachmentImportPhase.PICKING) return
    key(selection.pickerKey) {
        val requestId = selection.requestId
        val resolver = LocalContext.current.contentResolver
        val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
        val handle = remember(selection.pickerKey) { PickerHandle() }
        val contract = remember(selection.pickerKey) { PickVisualMedia() }
        DisposableEffect(registry, selection.pickerKey, controller) {
            val registered = registry.register(selection.pickerKey, contract) { uri ->
                if (uri == null) controller.selected(requestId, null)
                else try { controller.selected(requestId, PlatformPngSource(resolver, uri)) }
                catch (_: Exception) { controller.pickerFailed(requestId) }
            }
            handle.launcher = registered
            onDispose { handle.launcher = null; registered.unregister() }
        }
        LaunchedEffect(selection.pickerKey, canLaunch) {
            if (canLaunch && controller.claimPicker(requestId)) try {
                checkNotNull(handle.launcher).launch(PickVisualMediaRequest(PickVisualMedia.SingleMimeType("image/png")))
            } catch (_: Exception) { controller.pickerFailed(requestId) }
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
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = AIyouDimensions.contentMaxWidth).fillMaxSize()
        .verticalScroll(rememberScrollState()).padding(AIyouDimensions.pageNarrow).testTag("attachment-scroll"),
        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Text("附件 · 原文版本 ${panel.revision}", style = MaterialTheme.typography.titleLarge)
        Text("仅保存在本机。图片关联此原文版本，编辑原文不会移动附件。",
            style = MaterialTheme.typography.bodyMedium, color = AIyouColors.textSecondary)
        Text("暂支持常规静态 PNG 原图：不超过 16 MiB，宽高各不超过 4096 像素。",
            style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        panel.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        when {
            panel.loading -> StatusNotice("正在读取附件", "正在核对本机保存状态。", state = ComponentState.LOADING)
            panel.selection?.phase == AttachmentImportPhase.PREPARING -> StatusNotice("正在检查图片",
                "原文已保留，图片尚未确认保存。", state = ComponentState.LOADING)
            panel.saving -> StatusNotice("正在保存图片", "请等待保存与读取确认完成。", state = ComponentState.LOADING)
            panel.readingId != null -> StatusNotice("正在读取图片", "完整验证后显示。", state = ComponentState.LOADING)
        }
        panel.error?.let { StatusNotice("附件需要处理", it, state = ComponentState.ERROR,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            if (onRetryRecords != null && !panel.saving) {
                OutlinedButton(onClick = onRetryRecords, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("恢复本机读取") }
            }
            if (!panel.unavailable) {
                if (panel.selection?.phase == AttachmentImportPhase.FAILED && panel.selection.retryable) {
                    OutlinedButton(onClick = onRetrySave, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) {
                        Text(if (panel.selection.sourcePrepared) "重试保存确认" else "重新选择图片")
                    }
                } else if (panel.selection == null) {
                    OutlinedButton(onClick = { onSelect(null) }, enabled = !panel.busy && panel.error == null,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("选择 PNG 图片") }
                    TextButton(onClick = onRefresh, enabled = !panel.busy,
                        modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("重试读取附件") }
                }
            }
            if (panel.selection != null && !panel.saving) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("取消添加") }
            }
        }
        val picture = panel.picture as? PlatformAttachmentPicture
        if (picture != null && !panel.unavailable && panel.error == null) {
            Image(picture.image, "图片附件，关联原文版本 ${panel.revision}", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = AIyouDimensions.contentMaxWidth).testTag("attachment-picture"))
            TextButton(onClick = onClosePicture, modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("收起图片") }
        }
        if (!panel.loading && panel.items.isEmpty() && panel.selection == null && panel.error == null) {
            StatusNotice("这个版本还没有附件", "可以选择一张 PNG 图片，原文与草稿保持不变。", state = ComponentState.EMPTY)
        }
        panel.items.forEachIndexed { index, item ->
            key(item.intent.id) {
                Column(Modifier.fillMaxWidth().testTag("attachment-${item.intent.id}"),
                    verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                    Text("附件 ${index + 1} · ${item.intent.mediaType} · ${item.intent.byteSize} 字节", style = MaterialTheme.typography.bodyMedium)
                    Text(when (item.state) {
                        AttachmentState.WRITING -> "保存已中断，尚未完成。请重试读取状态。"
                        AttachmentState.STAGED -> "文件已暂存，正在等待恢复确认。请重试读取状态。"
                        AttachmentState.READY -> "已保存在本机"
                        AttachmentState.FAILED -> "保存未完成，原文件已保留；重选时需同一图片。"
                        AttachmentState.CORRUPT -> "附件无法通过完整性校验，原文件已保留，不会显示或覆盖。"
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (item.state == AttachmentState.READY && item.intent.mediaType == "image/png") {
                        TextButton(onClick = { onView(item.intent.id) }, enabled = !panel.busy && panel.selection == null && !panel.unavailable,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("查看图片") }
                    } else if (item.state == AttachmentState.FAILED && item.intent.mediaType == "image/png") {
                        TextButton(onClick = { onSelect(item.intent.id) }, enabled = !panel.busy && panel.selection == null && !panel.unavailable && panel.error == null,
                            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget)) { Text("重新选择同一图片") }
                    } else if (item.state == AttachmentState.READY) {
                        Text("此格式的查看功能尚未开放。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    }
}
