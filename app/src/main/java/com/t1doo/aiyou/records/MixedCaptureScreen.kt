package com.t1doo.aiyou.records

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.PrivacyChip
import com.t1doo.aiyou.model.CaptureImageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MixedCaptureScreen(local: LocalRecordUiState,controller: MixedCaptureController,modifier: Modifier=Modifier,
    listState: LazyListState = rememberLazyListState(), onOpenEntry: (String) -> Unit = {}, selectedEntryId: String? = null,
    defaultPolicyUnavailable: Boolean = false,audio:OriginalAudioController?=null,canImportAudio:()->Boolean={true},
    companion: (@Composable () -> Unit)? = null,motionAllowed:Boolean=false,compactHeader:Boolean=false) {
    val ui=local.mixedCapture ?: MixedCaptureUiState()
    val playback=audio?.state?.collectAsState()?.value ?: OriginalAudioUi()
    val foreground=audio?.foregroundState?.collectAsState()?.value==true
    val enabled=local.ready && !local.opening && local.error==null && ui.draft!=null
    val editable=enabled && !ui.busy && !controller.audioCapture.busy && !playback.busy && ui.selection==null && !ui.uncertainSave && !ui.discardPending && ui.policyChange==null && ui.draft?.discarding!=true
    val day by produceState(controller.today(),controller) {
        while(true) { value=controller.today();delay(60_000) }
    }
    val recent=controller.recentEntries(day)
    val recentIds=recent.map { it.id }
    var seenIds by remember(controller) { mutableStateOf(recentIds) }
    val scope=rememberCoroutineScope()
    val showReturnToInput by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex>1 } }
    var inputFocused by remember(controller) { mutableStateOf(false) }
    val paperOutline by animateColorAsState(if(inputFocused) AIyouColors.primary else AIyouColors.outlineDecorative,
        animationSpec=tween(if(motionAllowed) 160 else 0),label="notebook-input-outline")
    val paperElevation by animateDpAsState(if(inputFocused) 4.dp else 1.dp,
        animationSpec=tween(if(motionAllowed) 160 else 0),label="notebook-input-elevation")
    val saveInteraction=remember(controller) { MutableInteractionSource() }
    val savePressed by saveInteraction.collectIsPressedAsState()
    val saveScale by animateFloatAsState(if(savePressed && motionAllowed) .98f else 1f,
        animationSpec=tween(if(motionAllowed) 100 else 0),label="notebook-save-press")
    val leadingKeys=buildList {
        add("heading");add(if(enabled) "composer" else "unavailable");add("recent")
        if(local.ready && !local.opening && local.error==null && recent.isEmpty()) add("recent-empty")
        addAll(recent.map { "record:"+it.id })
        if(enabled && ui.supportsProcessingChoice) add("processing-policy")
        if(enabled && !ui.discardPending) {add("count");addAll(ui.draft!!.images.map {"draft-image:"+it.intent.id});add("audio")}
    }
    fun reveal(key:String) {
        val index=leadingKeys.indexOf(key)
        if(index>=0) scope.launch {if(motionAllowed) listState.animateScrollToItem(index) else listState.scrollToItem(index)}
    }
    DisposableEffect(controller) { onDispose { controller.cancelSelection() } }
    WaveFilePicker(controller.audioCapture,ui.audio,foreground)
    if(ui.confirmDiscard) AlertDialog(onDismissRequest=controller::cancelDiscard,title={Text(if(ui.discardPending) "清空结果待核对" else "清空这份草稿？")},
        text={Text(if(ui.discardPending) "清空可能已经完成。先核对这次清空，再继续记录；已清理的图片不会自动恢复。"
            else "将清理这份草稿的文字、图片和原音。已保存的记录保持不变。")},
        confirmButton={TextButton(onClick=controller::confirmDiscard,enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mixed-discard-confirm")){Text(if(ui.discardPending) "核对同一次清空" else "确认清空草稿")}},
        dismissButton={TextButton(onClick=controller::cancelDiscard,enabled=!ui.busy){Text(if(ui.discardPending) "稍后核对" else "继续编辑")}})
    Column(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AIyouColors.notebookSky,AIyouColors.notebookMist))),horizontalAlignment=Alignment.CenterHorizontally) {
        if(recentIds.any { it !in seenIds }) TextButton(onClick={seenIds=recentIds;reveal("recent")},
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mixed-new-records")) { Text("有新内容 · 查看今天最近记录") }
        LazyColumn(Modifier.weight(1f).widthIn(max=AIyouDimensions.contentMaxWidth).fillMaxWidth()
            .padding(horizontal=16.dp).testTag("mixed-list"),state=listState,
            contentPadding=PaddingValues(top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item("heading") {
                Surface(modifier=Modifier.testTag("mixed-notebook-heading"),shape=RoundedCornerShape(28.dp),color=Color.Transparent) {
                    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(AIyouColors.accentDecorative,AIyouColors.primaryContainer,AIyouColors.notebookLilac)))
                        .drawBehind { drawCircle(AIyouColors.surface.copy(alpha=.28f),90.dp.toPx(),Offset(size.width-58.dp.toPx(),50.dp.toPx())) }) {
                        Row(Modifier.fillMaxWidth().padding(if(compactHeader) PaddingValues(horizontal=16.dp,vertical=8.dp) else PaddingValues(16.dp)),
                            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(compactHeader) {
                                Text("${day.date.monthValue}月${day.date.dayOfMonth}日",modifier=Modifier.weight(1f).semantics { heading() },
                                    style=MaterialTheme.typography.labelMedium,color=AIyouColors.primary)
                            } else Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("今天 · ${day.date.monthValue}月${day.date.dayOfMonth}日",style=MaterialTheme.typography.labelMedium,color=AIyouColors.primary)
                                Text("生活的小片刻",style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
                                Text("都值得被记住",style=MaterialTheme.typography.bodySmall,color=AIyouColors.textPrimary)
                            }
                            companion?.invoke()
                        }
                    }
                }
                if(defaultPolicyUnavailable) Text("设置暂不可读取；以后新建的草稿暂用不参与 AI。当前草稿范围保持不变，仍可逐稿选择。",
                    modifier=Modifier.padding(top=8.dp).testTag("mixed-default-policy-unavailable"),color=AIyouColors.error) }
            if(!enabled) item("unavailable") {
                CaptureFeedback(local.error ?: ui.error ?: "正在读取加密草稿。",isError=local.error!=null || ui.error!=null)
                TextButton(onClick=controller::refresh,enabled=local.ready,modifier=Modifier.heightIn(min=48.dp)){Text("重试读取草稿")} }
            else {
                item("composer") {
                    Surface(shape=RoundedCornerShape(24.dp),color=AIyouColors.surface,
                        border=BorderStroke(1.dp,paperOutline),shadowElevation=paperElevation) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("此刻手记",Modifier.weight(1f).semantics { heading() },style=MaterialTheme.typography.titleMedium)
                                Surface(color=AIyouColors.notebookPeach,shape=RoundedCornerShape(8.dp)) {
                                    val attachments=ui.draft!!.images.size+(if(ui.draft.audio==null) 0 else 1)
                                    Text(if(attachments==0) "草稿" else "$attachments 个附件",Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium)
                                }
                            }
                            TextField(value=ui.text,onValueChange=controller::edit,enabled=editable,
                                placeholder={Text("此刻有什么想留下的？\n也可以只添加图片或原音。")},minLines=2,maxLines=8,
                                textStyle=MaterialTheme.typography.bodyLarge,shape=RoundedCornerShape(12.dp),
                                colors=TextFieldDefaults.colors(focusedContainerColor=AIyouColors.surface,
                                    unfocusedContainerColor=AIyouColors.surface,disabledContainerColor=AIyouColors.surfaceMuted,
                                    unfocusedIndicatorColor=AIyouColors.outlineFunctional),
                                modifier=Modifier.fillMaxWidth().onFocusChanged { inputFocused=it.hasFocus }.testTag("mixed-text")
                                    .semantics { contentDescription="写下此刻，也可以只添加附件" })
                            if(!ui.discardPending) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(ui.retainJpegMetadata,onCheckedChange=controller::metadata,enabled=editable)
                                    Text("保留 JPEG 拍摄资料",style=MaterialTheme.typography.bodySmall)
                                }
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick={controller.select()},enabled=editable && ui.pendingEdits==0,
                                        shape=RoundedCornerShape(12.dp),modifier=Modifier.heightIn(min=48.dp).testTag("mixed-add")){Text("添加图片")}
                                    if(ui.draft!!.audio==null) OutlinedButton(onClick={controller.audioCapture.startFile(canImportAudio,{audio?.isResumed==true})},
                                        enabled=editable && audio!=null && ui.draft.images.size<20,
                                        shape=RoundedCornerShape(12.dp),modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-import")){Text("导入原音文件")}
                                    else TextButton(onClick={reveal("audio")},modifier=Modifier.heightIn(min=48.dp)){Text("查看草稿原音")}
                                }
                                Text("图片 / WAV 原音 · 不自动转写",style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                                if(controller.audioCapture.busy || ui.audio.error!=null) {
                                    CaptureFeedback(ui.audio.error ?: when(ui.audio.phase) {
                                        CaptureAudioPhase.PICKING -> "等待选择原音；尚未保存这条记录"
                                        CaptureAudioPhase.IMPORTING -> "正在读取和检查原音文件"
                                        CaptureAudioPhase.UNKNOWN -> "原音结果待核对，当前草稿仍冻结"
                                        else -> "正在准备或确认原音"
                                    },isError=ui.audio.error!=null,modifier=Modifier.testTag("mixed-audio-summary"))
                                    TextButton(onClick={reveal("audio")},modifier=Modifier.heightIn(min=48.dp)
                                        .testTag("mixed-audio-status")) {Text("查看本次原音处理")}
                                }
                            }
                            if(ui.supportsProcessingChoice) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text(if(ui.policyChange!=null) "处理范围待核对" else if(ui.draft!!.policy==com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE)
                                    "不参与 AI · 仅作手账" else "仅本机处理 · 不发远端",
                                    Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,
                                    color=if(ui.policyChange!=null) AIyouColors.error else AIyouColors.primary)
                                TextButton(onClick={reveal("processing-policy")},modifier=Modifier.heightIn(min=48.dp).testTag("mixed-policy-details")){Text("范围详情")}
                            }
                            if(ui.discardPending) CaptureFeedback("清空前内容仅供核对。请核对同一次清空后继续记录；也可稍后回来核对。",modifier=Modifier.testTag("mixed-discard-pending"))
                            if(ui.busy || ui.pendingEdits>0 || ui.selection?.phase in setOf(AttachmentImportPhase.PREPARING,AttachmentImportPhase.SAVING)) CaptureFeedback("正在保存或核对本机数据，请稍候。")
                            ui.error?.let { CaptureFeedback(it,isError=true,modifier=Modifier.testTag("mixed-error")) }
                            ui.notice?.let { CaptureFeedback(it,modifier=Modifier.testTag("mixed-notice")) }
                            Button(onClick=controller::save,enabled=enabled && !ui.busy && !controller.audioCapture.busy && !playback.busy && ui.selection==null && ui.pendingEdits==0 && !ui.discardPending && ui.policyChange==null,
                                interactionSource=saveInteraction,
                                shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=14.dp),
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).graphicsLayer {scaleX=saveScale;scaleY=saveScale}
                                    .testTag("mixed-save")){Text(if(ui.uncertainSave) "核对同一次保存" else "保存这条记录")}
                        }
                    }
                }
            }
            item("recent") {
                Text("今天最近记录",style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
                if(recent.isNotEmpty()) Text("按记录时间显示最近 5 条；更多记录可到回忆查看。",Modifier.padding(top=4.dp),
                    style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary) }
            if(local.ready && !local.opening && local.error==null && recent.isEmpty()) item("recent-empty") {
                Text("今天还没有记录。写下第一笔，就会出现在这里。",Modifier.padding(vertical=8.dp),
                    style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary) }
            items(recent,key={"record:"+it.id}) { entry ->
                Surface(Modifier.fillMaxWidth().animateItem(fadeInSpec=if(motionAllowed) tween(180) else null,
                    placementSpec=if(motionAllowed) tween(220) else null,fadeOutSpec=null)
                    .testTag("entry-${entry.id}").semantics { selected=entry.id==selectedEntryId },
                    color=AIyouColors.surface,shape=RoundedCornerShape(20.dp),shadowElevation=1.dp) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(originalMetadata(entry),style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                    Text(if(entry.policy==com.t1doo.aiyou.model.ProcessingPolicy.LOCAL_ONLY) "原文 · 仅本机" else "原文",
                        style=MaterialTheme.typography.labelMedium,color=AIyouColors.primary)
                    Text(originalPreview(entry.originalText),style=MaterialTheme.typography.bodyLarge,maxLines=4,overflow=TextOverflow.Ellipsis)
                    if(entry.policy!=com.t1doo.aiyou.model.ProcessingPolicy.LOCAL_ONLY) PrivacyChip(entry.policy)
                    TextButton(onClick={if(enabled && !ui.busy && ui.selection==null) onOpenEntry(entry.id)},
                        modifier=Modifier.heightIn(min=48.dp)) { Text("阅读全文") }
                }
                }
            }
            if(enabled) {
                if(ui.supportsProcessingChoice) item("processing-policy") { CaptureProcessingChoice(ui,controller) }
                if(!ui.discardPending) {
                    item("count") {
                        HorizontalDivider(Modifier.padding(vertical=8.dp),color=AIyouColors.outlineDecorative)
                        Text("图片与原音",style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
                        Text("已选 ${ui.draft!!.images.size+(if(ui.draft.audio==null)0 else 1)} / 20 个附件 · ${ui.draft.images.sumOf { it.intent.byteSize }+(ui.draft.audio?.intent?.byteSize ?: 0)} 字节",modifier=Modifier.padding(top=8.dp).testTag("mixed-count"),style=MaterialTheme.typography.bodySmall)
                        Text("JPEG / PNG；单张最多 16 MiB，合计最多 64 MiB。原图可能含拍摄资料。无 OCR，仅按文字与手工标签查找。",
                            style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                        Text("保留 JPEG 拍摄资料；取消勾选将移除拍摄资料与注释。",style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary) }
                    items(ui.draft!!.images,key={"draft-image:"+it.intent.id}) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            val picture=ui.pictures[item.intent.id] as? PlatformAttachmentPicture
                            if(picture!=null) Image(picture.image,"草稿图片缩略图",contentScale=ContentScale.Fit,
                                modifier=Modifier.size(120.dp).testTag("mixed-thumb:"+item.intent.id))
                            Text("${item.intent.mediaType} · ${item.intent.byteSize} 字节 · "+if(item.state==CaptureImageState.READY) "已加密暂存" else "尚未确认完整保存")
                            if(item.state!=CaptureImageState.READY) OutlinedButton(onClick={controller.select(item.intent.id)},enabled=editable,
                                modifier=Modifier.heightIn(min=48.dp)){Text("重新选择同一图片")}
                            TextButton(onClick={controller.remove(item.intent.id)},enabled=editable && ui.pendingEdits==0,
                                modifier=Modifier.heightIn(min=48.dp).testTag("mixed-remove:"+item.intent.id)){Text("移除图片")}
                        }
                    }
                    item("audio") {
                        val draft=checkNotNull(ui.draft)
                        Column(Modifier.fillMaxWidth().testTag("mixed-audio"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("原音文件",style=MaterialTheme.typography.titleMedium)
                            Text("仅支持单声道 44100Hz、16位 PCM 的规范 WAV，最多60秒；不进行转写。",style=MaterialTheme.typography.bodySmall)
                            val item=draft.audio
                            if(item!=null) {
                                Text("${item.intent.byteSize} 字节 · 原音未转写 · "+if(item.state==com.t1doo.aiyou.model.CaptureAudioState.READY) "已加入本机草稿" else "保存状态待处理")
                                if(item.state==com.t1doo.aiyou.model.CaptureAudioState.READY && audio!=null)
                                    TextButton(onClick={audio.playDraft()},enabled=editable,
                                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-play")) { Text("播放草稿原音") }
                                TextButton(onClick=controller::removeAudio,enabled=editable && ui.pendingEdits==0,
                                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-remove")) { Text("移除草稿原音") }
                            }
                            if(controller.audioCapture.busy) {
                                Text(when(ui.audio.phase) {
                                    CaptureAudioPhase.PICKING -> "等待选择原音；尚未保存这条记录"
                                    CaptureAudioPhase.IMPORTING -> "正在读取和检查原音文件"
                                    CaptureAudioPhase.UNKNOWN -> "原音结果待核对，当前草稿仍冻结"
                                    else -> "正在准备或确认原音"
                                })
                                if(ui.audio.phase==CaptureAudioPhase.UNKNOWN) {
                                  if(ui.audio.resourceClosureUnknown) {
                                    TextButton(onClick=controller.audioCapture::retryResourceClose,modifier=Modifier.heightIn(min=48.dp)) { Text("重试关闭同一个文件") }
                                  } else {
                                    TextButton(onClick=controller.audioCapture::reconcile,modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-reconcile")) { Text("核对同一次原音") }
                                    TextButton(onClick=controller.audioCapture::retry,modifier=Modifier.heightIn(min=48.dp)) { Text("重试同一次保存") }
                                    TextButton(onClick=controller.audioCapture::discardUnwritten,modifier=Modifier.heightIn(min=48.dp)) { Text("明确放弃未完成原音") }
                                  }
                                } else if(ui.audio.phase in setOf(CaptureAudioPhase.PREPARING,CaptureAudioPhase.PICKING,CaptureAudioPhase.IMPORTING))
                                    TextButton(onClick=controller.audioCapture::cancel,modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-cancel")) { Text("取消本次原音导入") }
                            }
                            ui.audio.error?.let { Text(it,modifier=Modifier.testTag("mixed-audio-error")) }
                        }
                    }
                }
                item("clear") { TextButton(onClick={if(ui.discardPending) controller.confirmDiscard() else controller.requestDiscard()},
                    enabled=(editable || enabled && ui.discardPending && !ui.busy) && ui.pendingEdits==0,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mixed-clear")){Text(if(ui.discardPending) "核对同一次清空" else "清空草稿")}}
            }
            item("status") {
                if(!enabled) {
                if(ui.discardPending) Text("清空前内容仅供核对。请核对同一次清空后继续记录；也可稍后回来核对。",modifier=Modifier.testTag("mixed-discard-pending"))
                if(ui.busy || ui.pendingEdits>0 || ui.selection?.phase in setOf(AttachmentImportPhase.PREPARING,AttachmentImportPhase.SAVING)) CaptureFeedback("正在保存或核对本机数据，请稍候。")
                ui.error?.let { CaptureFeedback(it,isError=true,modifier=Modifier.testTag("mixed-error")) }
                ui.notice?.let { CaptureFeedback(it,modifier=Modifier.testTag("mixed-notice")) }
                }
                ui.selection?.let { selection ->
                    if(selection.phase==AttachmentImportPhase.FAILED) TextButton(onClick=controller::retryImage,modifier=Modifier.heightIn(min=48.dp)){Text("重试确认图片")}
                    TextButton(onClick=controller::cancelSelection,enabled=selection.phase!=AttachmentImportPhase.SAVING,
                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-cancel-picture")){Text("取消本次选择")}
                }
                TextButton(onClick=controller::refresh,enabled=local.ready && !ui.busy && !controller.audioCapture.busy && !playback.busy && ui.selection==null,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-refresh")){Text("重新核对加密草稿")}
            }
        }
        if(enabled && showReturnToInput) FilledTonalButton(onClick={reveal("composer")},
            shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp).fillMaxWidth().heightIn(min=48.dp)
                .testTag("mixed-jump-composer")) { Text(if(ui.discardPending) "回到清空核对" else "回到输入") }
    }
}

/** State text is supplied by the controller; no progress estimate or optimistic success. */
@Composable
private fun CaptureFeedback(text:String,modifier:Modifier=Modifier,isError:Boolean=false) {
    Text(text,modifier.fillMaxWidth().background(if(isError) AIyouColors.errorContainer else AIyouColors.surfaceMuted,
        RoundedCornerShape(12.dp)).padding(16.dp).semantics { liveRegion=LiveRegionMode.Polite },
        style=MaterialTheme.typography.bodyMedium,color=if(isError) AIyouColors.error else AIyouColors.textPrimary)
}
