package com.t1doo.aiyou.records

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.designsystem.AttachmentIcon
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouColors
import com.t1doo.aiyou.designsystem.PrivacyChip
import com.t1doo.aiyou.model.CaptureImageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixedCaptureScreen(local: LocalRecordUiState,controller: MixedCaptureController,modifier: Modifier=Modifier,
    listState: LazyListState = rememberLazyListState(), onOpenEntry: (String) -> Unit = {}, selectedEntryId: String? = null,
    defaultPolicyUnavailable: Boolean = false,audio:OriginalAudioController?=null,canImportAudio:()->Boolean={true},
    companion: (@Composable () -> Unit)? = null,motionAllowed:Boolean=false,compactHeader:Boolean=false,
    onAskQuestion:(()->Unit)?=null) {
    val ui=local.mixedCapture ?: MixedCaptureUiState()
    val editor by controller.editor.state.collectAsState()
    val frame=editor.frame
    val fieldValue=TextFieldValue(frame.text,TextRange(frame.start,frame.end),
        frame.composingStart?.let { TextRange(it,checkNotNull(frame.composingEnd)) })
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
    var detailsOpen by remember(controller) { mutableStateOf(false) }
    val moreFocus=remember(controller) {FocusRequester()}
    var returnMoreFocus by remember(controller) {mutableStateOf(false)}
    fun dismissDetails() {detailsOpen=false;returnMoreFocus=true}
    LaunchedEffect(detailsOpen,returnMoreFocus) {if(!detailsOpen && returnMoreFocus) {withFrameNanos {};moreFocus.requestFocus();returnMoreFocus=false}}
    var detailsTarget by remember(controller) { mutableStateOf<String?>(null) }
    val detailsList=rememberLazyListState()
    var focusEditor by remember(controller) { mutableStateOf(false) }
    // The controller owns text, selection, composing region and bounded Undo across route changes.
    // A single TextField stays in the composition within this screen. Focus mode changes allocation, never the input owner.
    val inputScroll=rememberScrollState()
    BackHandler(enabled=focusEditor && !detailsOpen) { focusEditor=false }
    fun reveal(key:String) { detailsTarget=key;detailsOpen=true }
    val detailKeys=buildList {
        add("heading")
        if(enabled && ui.supportsProcessingChoice) add("processing-policy")
        if(enabled && !ui.discardPending) {
            add("count");addAll(ui.draft!!.images.map {"draft-image:"+it.intent.id});add("audio")
        }
        if(enabled) add("clear")
        add("status")
    }
    LaunchedEffect(detailsOpen,detailsTarget,detailKeys) {
        val target=detailsTarget ?: return@LaunchedEffect
        if(!detailsOpen) return@LaunchedEffect
        val index=detailKeys.indexOf(target)
        if(index>=0) {
            snapshotFlow {detailsList.layoutInfo.totalItemsCount}.first {it==detailKeys.size}
            detailsList.scrollToItem(index)
            detailsTarget=null
        }
    }
    DisposableEffect(controller) { onDispose { controller.cancelSelection() } }
    WaveFilePicker(controller.audioCapture,ui.audio,foreground)
    if(ui.confirmDiscard) AlertDialog(onDismissRequest=controller::cancelDiscard,title={Text(if(ui.discardPending) "清空结果待核对" else "清空这份草稿？")},
        text={Text(if(ui.discardPending) "清空可能已经完成。先核对这次清空，再继续记录；已清理的图片不会自动恢复。"
            else "将清理这份草稿的文字、图片和原音。已保存的记录保持不变。")},
        confirmButton={TextButton(onClick=controller::confirmDiscard,enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mixed-discard-confirm")){Text(if(ui.discardPending) "核对同一次清空" else "确认清空草稿")}},
        dismissButton={TextButton(onClick=controller::cancelDiscard,enabled=!ui.busy){Text(if(ui.discardPending) "稍后核对" else "继续编辑")}})
    BoxWithConstraints(modifier.fillMaxSize().background(AIyouColors.surface)) {
      val availableHeight=maxHeight
      Column(Modifier.widthIn(max=AIyouDimensions.contentMaxWidth).fillMaxSize().align(Alignment.TopCenter)) {
        if(!focusEditor && recentIds.any {it !in seenIds}) TextButton(
            onClick={seenIds=recentIds;scope.launch {listState.scrollToItem(0)}},
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mixed-new-records")) {Text("有新内容 · 查看今天最近记录")}
        if(!focusEditor) LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mixed-list"),state=listState,
            contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp)) {
            item("heading") {
                Row(Modifier.fillMaxWidth().testTag("mixed-notebook-heading"),verticalAlignment=Alignment.CenterVertically) {
                    Text("${day.date.monthValue}月${day.date.dayOfMonth}日",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                    companion?.invoke()
                }
                if(defaultPolicyUnavailable) CaptureFeedback("设置暂不可读取；新草稿暂用不参与 AI。当前草稿范围不变。",isError=true,
                    modifier=Modifier.testTag("mixed-default-policy-unavailable"))
            }
            item("recent") {
                Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("今天",Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleMedium)
                    Text("${recent.size} 条最近记录",style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                }
            }
            if(local.ready && !local.opening && local.error==null && recent.isEmpty()) item("recent-empty") {
                Text("今天还没有记录。直接在下方写下第一句。",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
            }
            items(recent,key={"record:"+it.id}) {entry ->
                Column(Modifier.fillMaxWidth().testTag("entry-${entry.id}").semantics {selected=entry.id==selectedEntryId}
                    .clickable(enabled=enabled && !ui.busy && ui.selection==null,onClickLabel="阅读全文") {onOpenEntry(entry.id)}
                    .padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(originalPreview(entry.originalText),style=MaterialTheme.typography.bodyLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Text(originalMetadata(entry),style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
                    if(entry.policy!=com.t1doo.aiyou.model.ProcessingPolicy.LOCAL_ONLY) PrivacyChip(entry.policy)
                    HorizontalDivider(color=AIyouColors.outlineDecorative)
                }
            }
        }
        HorizontalDivider(color=AIyouColors.outlineDecorative)
        // Scrolling belongs to the constrained composer, not an extra IME inset. At large text
        // every action remains reachable; focus editing can use the complete available height.
        Column(Modifier.fillMaxWidth().heightIn(max=if(focusEditor) availableHeight else availableHeight*.65f)
            .verticalScroll(inputScroll).padding(horizontal=16.dp,vertical=4.dp).testTag("mixed-composer"),
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(!enabled) {
                CaptureFeedback(local.error ?: ui.error ?: "正在读取加密草稿。",isError=local.error!=null || ui.error!=null)
                TextButton(onClick=controller::refresh,enabled=local.ready,modifier=Modifier.heightIn(min=48.dp)){Text("重试读取草稿")}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("记下来",Modifier.heightIn(min=48.dp).wrapContentHeight().drawBehind {
                    drawLine(AIyouColors.primary,Offset(0f,size.height),Offset(size.width,size.height),2.dp.toPx())
                },style=MaterialTheme.typography.labelLarge,color=AIyouColors.textPrimary)
                if(onAskQuestion!=null) TextButton(onClick=onAskQuestion,enabled=editable&&!ui.confirmDiscard,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-ask-question")){Text("问一问")}
                Spacer(Modifier.weight(1f))
                Text(if(ui.draft?.policy==com.t1doo.aiyou.model.ProcessingPolicy.AI_NONE) "不参与 AI" else "仅本机",style=MaterialTheme.typography.bodySmall,color=AIyouColors.textSecondary)
            }
            BasicTextField(value=fieldValue,onValueChange={ value ->
                    ui.draft?.id?.let {id -> controller.editValue(id,CaptureEditorFrame(value.text,value.selection.start,value.selection.end,
                        value.composition?.min,value.composition?.max))}
                },enabled=editable,
                minLines=if(focusEditor) 5 else 2,maxLines=if(focusEditor) 16 else 3,
                textStyle=MaterialTheme.typography.bodyLarge.copy(color=AIyouColors.textPrimary),cursorBrush=SolidColor(AIyouColors.primary),
                modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("mixed-text").onPreviewKeyEvent { event ->
                        val shortcut=(event.isCtrlPressed || event.isMetaPressed) && (event.key==Key.Z || event.key==Key.Y)
                        if(shortcut && event.type==KeyEventType.KeyDown) ui.draft?.id?.let {controller.undoInput(it,event.key==Key.Y || event.isShiftPressed)}
                        shortcut
                    }
                    .semantics {contentDescription="写下此刻，也可以只添加附件"},
                decorationBox={field->Box {if(ui.text.isEmpty()) Text("想到什么，先记下来…",style=MaterialTheme.typography.bodyLarge,color=AIyouColors.textSecondary);field()}})
            if(ui.draft?.let {it.images.isNotEmpty() || it.audio!=null}==true) TextButton(onClick={reveal("audio")},modifier=Modifier.heightIn(min=48.dp)) {
                Text("${ui.draft!!.images.size} 张图片"+if(ui.draft.audio!=null) " · 原音附件" else " · 查看附件")
            }
            if(ui.discardPending) CaptureFeedback("清空结果待核对。保留同一次意图，不另建草稿。",modifier=Modifier.testTag("mixed-discard-pending"))
            if(ui.busy || ui.pendingEdits>0) CaptureFeedback("正在保存或核对本机数据，请稍候。")
            ui.error?.let {CaptureFeedback(it,isError=true,modifier=Modifier.testTag("mixed-error"))}
            ui.notice?.let {CaptureFeedback(it,modifier=Modifier.testTag("mixed-notice"))}
            if(controller.audioCapture.busy || ui.audio.error!=null) {
                CaptureFeedback(ui.audio.error ?: "正在处理原音，尚未保存记录。",isError=ui.audio.error!=null,modifier=Modifier.testTag("mixed-audio-summary"))
                TextButton(onClick={reveal("audio")},modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-status")){Text("查看本次原音处理")}
            }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={controller.select()},enabled=editable&&ui.pendingEdits==0,
                    modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("mixed-add").semantics {contentDescription="添加图片"}) {AttachmentIcon(false,editable)}
                TextButton(onClick={controller.audioCapture.startFile(canImportAudio,{audio?.isResumed==true})},
                    enabled=editable&&audio!=null&&ui.draft?.audio==null&&(ui.draft?.images?.size ?: 20)<20,
                    modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("mixed-audio-import").semantics {contentDescription="导入原音文件，不自动转写"}) {AttachmentIcon(true,editable&&audio!=null);Text("导入原音")}
                TextButton(onClick={detailsOpen=true},modifier=Modifier.heightIn(min=48.dp).focusRequester(moreFocus).testTag("mixed-more")){Text("更多")}
                TextButton(onClick={focusEditor=!focusEditor},modifier=Modifier.heightIn(min=48.dp).testTag("mixed-focus-editor")){Text(if(focusEditor)"收起编辑" else "展开")}
                TextButton(onClick=controller::save,
                    enabled=enabled&&!ui.busy&&!controller.audioCapture.busy&&!playback.busy&&ui.selection==null&&ui.pendingEdits==0&&!ui.discardPending&&ui.policyChange==null,
                    modifier=Modifier.widthIn(min=96.dp).heightIn(min=48.dp).testTag("mixed-save")) {
                    Text(if(ui.uncertainSave) "核对保存" else if(ui.busy) "保存中" else "保存",style=MaterialTheme.typography.labelLarge)
                }
            }
        }
      }
    }
    val detailContent:@Composable ()->Unit = {
        LazyColumn(Modifier.fillMaxWidth().testTag("mixed-details"),state=detailsList,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item("heading") {
                Text("这份草稿",style=MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={ui.draft?.id?.let {controller.undoInput(it)}},enabled=editable&&editor.canUndo,
                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-undo")){Text("撤销输入")}
                    TextButton(onClick={ui.draft?.id?.let {controller.undoInput(it,true)}},enabled=editable&&editor.canRedo,
                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-redo")){Text("重做输入")}
                }
                Text("图片与原音按现有能力处理；导入不自动转写。",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(ui.retainJpegMetadata,onCheckedChange=controller::metadata,enabled=editable)
                    Text("保留 JPEG 拍摄资料",style=MaterialTheme.typography.bodySmall)
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
                ui.selection?.let { selection ->
                    if(selection.phase==AttachmentImportPhase.FAILED) TextButton(onClick=controller::retryImage,modifier=Modifier.heightIn(min=48.dp)){Text("重试确认图片")}
                    TextButton(onClick=controller::cancelSelection,enabled=selection.phase!=AttachmentImportPhase.SAVING,
                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-cancel-picture")){Text("取消本次选择")}
                }
                TextButton(onClick=controller::refresh,enabled=local.ready&&!ui.busy&&!controller.audioCapture.busy&&!playback.busy&&ui.selection==null,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-refresh")){Text("重新核对加密草稿")}
                TextButton(onClick={detailsTarget=null;dismissDetails()},modifier=Modifier.heightIn(min=48.dp)){Text("回到输入")}
            }
        }
    }
    if(detailsOpen) {
        if(motionAllowed) ModalBottomSheet(onDismissRequest={detailsTarget=null;dismissDetails()},containerColor=AIyouColors.surface) {detailContent()}
        else Dialog(onDismissRequest={detailsTarget=null;dismissDetails()}) {
            Surface(color=AIyouColors.surface) {Box(Modifier.fillMaxWidth().heightIn(max=560.dp)){detailContent()}}
        }
    }
}

/** State remains tied to its actual operation; no optimistic success or invented progress. */
@Composable
private fun CaptureFeedback(text:String,modifier:Modifier=Modifier,isError:Boolean=false) {
    Text(text,modifier.fillMaxWidth().padding(vertical=4.dp).semantics {liveRegion=LiveRegionMode.Polite},
        style=MaterialTheme.typography.bodySmall,color=if(isError)AIyouColors.error else AIyouColors.textSecondary)
}
