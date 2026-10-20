package com.t1doo.aiyou.records

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.MemoryCard
import com.t1doo.aiyou.model.CaptureImageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MixedCaptureScreen(local: LocalRecordUiState,controller: MixedCaptureController,modifier: Modifier=Modifier,
    listState: LazyListState = rememberLazyListState(), onOpenEntry: (String) -> Unit = {}, selectedEntryId: String? = null,
    defaultPolicyUnavailable: Boolean = false,audio:OriginalAudioController?=null,canImportAudio:()->Boolean={true}) {
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
    val atTop by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex==0 && listState.firstVisibleItemScrollOffset==0 } }
    val scope=rememberCoroutineScope()
    LaunchedEffect(recentIds,atTop) { if(atTop) seenIds=recentIds }
    DisposableEffect(controller) { onDispose { controller.cancelSelection() } }
    WaveFilePicker(controller.audioCapture,ui.audio,foreground)
    if(ui.confirmDiscard) AlertDialog(onDismissRequest=controller::cancelDiscard,title={Text(if(ui.discardPending) "清空结果待核对" else "清空这份草稿？")},
        text={Text(if(ui.discardPending) "清空可能已经完成。先核对这次清空，再继续记录；已清理的图片不会自动恢复。"
            else "将清理这份草稿的文字、图片和原音。已保存的记录保持不变。")},
        confirmButton={TextButton(onClick=controller::confirmDiscard,enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mixed-discard-confirm")){Text(if(ui.discardPending) "核对同一次清空" else "确认清空草稿")}},
        dismissButton={TextButton(onClick=controller::cancelDiscard,enabled=!ui.busy){Text(if(ui.discardPending) "稍后核对" else "继续编辑")}})
    Column(modifier.fillMaxSize()) {
        if(recentIds.any { it !in seenIds }) TextButton(onClick={scope.launch { listState.animateScrollToItem(0) }},
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mixed-new-records")) { Text("有新内容 · 查看今天最近记录") }
        LazyColumn(Modifier.weight(1f).padding(horizontal=AIyouDimensions.pageNarrow).testTag("mixed-list"),state=listState,
            contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item("heading") { Text("今天 · ${day.date}",style=MaterialTheme.typography.headlineMedium)
                Text("用文字、图片或原音文件记下来。不会发送到远端；本次范围以草稿中的选择为准。")
                if(defaultPolicyUnavailable) Text("设置暂不可读取；以后新建的草稿暂用不参与 AI。当前草稿范围保持不变，仍可逐稿选择。",
                    modifier=Modifier.testTag("mixed-default-policy-unavailable")) }
            item("recent") { Text("今天最近记录",style=MaterialTheme.typography.titleLarge)
                Text("按记录时间显示最近 5 条；更多记录可到回忆查看。") }
            if(local.ready && !local.opening && local.error==null && recent.isEmpty()) item("recent-empty") {
                Text("今天还没有记录，从下面记下此刻。") }
            items(recent,key={"record:"+it.id}) { entry ->
                MemoryCard(originalPreview(entry.originalText),originalMetadata(entry),policy=entry.policy,
                    modifier=Modifier.testTag("entry-${entry.id}").semantics { selected=entry.id==selectedEntryId },
                    onOpen={if(enabled && !ui.busy && ui.selection==null) onOpenEntry(entry.id)})
            }
            if(!enabled) item("unavailable") { Text(local.error ?: ui.error ?: "正在读取加密草稿。")
                TextButton(onClick=controller::refresh,enabled=local.ready){Text("重试读取草稿")} }
            else {
                item("composer") { OutlinedTextField(value=ui.text,onValueChange=controller::edit,enabled=editable,
                    label={Text("写下此刻（也可以只添加附件）")},minLines=4,maxLines=8,
                    modifier=Modifier.fillMaxWidth().testTag("mixed-text")) }
                if(ui.supportsProcessingChoice) item("processing-policy") { CaptureProcessingChoice(ui,controller) }
                if(!ui.discardPending) {
                    item("count") { Text("已选 ${ui.draft!!.images.size+(if(ui.draft.audio==null)0 else 1)} / 20 个附件 · ${ui.draft.images.sumOf { it.intent.byteSize }+(ui.draft.audio?.intent?.byteSize ?: 0)} 字节",modifier=Modifier.testTag("mixed-count"))
                        Text("JPEG / PNG；单张最多 16 MiB，合计最多 64 MiB。原图可能含拍摄资料。无 OCR，仅按文字与手工标签查找。") }
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
                    item("metadata") { Row { Checkbox(ui.retainJpegMetadata,onCheckedChange=controller::metadata,enabled=editable)
                        Text("保留 JPEG 拍摄资料；取消勾选将移除拍摄资料与注释。") } }
                    item("add") { OutlinedButton(onClick={controller.select()},enabled=editable && ui.pendingEdits==0,
                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-add")){Text("添加图片")}}
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
                            } else OutlinedButton(onClick={controller.audioCapture.startFile(canImportAudio,{audio?.isResumed==true})},
                                enabled=editable && audio!=null && draft.images.size<20,
                                modifier=Modifier.heightIn(min=48.dp).testTag("mixed-audio-import")) { Text("导入原音文件") }
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
                item("save") { Button(onClick=controller::save,enabled=enabled && !ui.busy && !controller.audioCapture.busy && !playback.busy && ui.selection==null && ui.pendingEdits==0 && !ui.discardPending && ui.policyChange==null,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-save")){Text(if(ui.uncertainSave) "核对同一次保存" else "保存这条记录")}}
                item("clear") { TextButton(onClick={if(ui.discardPending) controller.confirmDiscard() else controller.requestDiscard()},
                    enabled=(editable || enabled && ui.discardPending && !ui.busy) && ui.pendingEdits==0,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-clear")){Text(if(ui.discardPending) "核对同一次清空" else "清空草稿")}}
            }
            item("status") {
                if(ui.discardPending) Text("清空前内容仅供核对。请核对同一次清空后继续记录；也可稍后回来核对。",modifier=Modifier.testTag("mixed-discard-pending"))
                if(ui.busy || ui.pendingEdits>0 || ui.selection?.phase in setOf(AttachmentImportPhase.PREPARING,AttachmentImportPhase.SAVING)) Text("正在保存或核对本机数据，请稍候。")
                ui.error?.let { Text(it,modifier=Modifier.testTag("mixed-error")) }
                ui.notice?.let { Text(it,modifier=Modifier.testTag("mixed-notice")) }
                ui.selection?.let { selection ->
                    if(selection.phase==AttachmentImportPhase.FAILED) TextButton(onClick=controller::retryImage,modifier=Modifier.heightIn(min=48.dp)){Text("重试确认图片")}
                    TextButton(onClick=controller::cancelSelection,enabled=selection.phase!=AttachmentImportPhase.SAVING,
                        modifier=Modifier.heightIn(min=48.dp).testTag("mixed-cancel-picture")){Text("取消本次选择")}
                }
                TextButton(onClick=controller::refresh,enabled=local.ready && !ui.busy && !controller.audioCapture.busy && !playback.busy && ui.selection==null,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-refresh")){Text("重新核对加密草稿")}
            }
        }
        if(enabled && recent.isNotEmpty()) TextButton(onClick={scope.launch { listState.animateScrollToItem(2+recent.size) }},
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mixed-jump-composer")) { Text(if(ui.discardPending) "查看清空核对" else "继续记录") }
    }
}
