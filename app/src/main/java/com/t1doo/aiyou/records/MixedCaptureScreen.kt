package com.t1doo.aiyou.records

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.model.CaptureImageState

@Composable
fun MixedCaptureScreen(local: LocalRecordUiState,controller: MixedCaptureController,modifier: Modifier=Modifier) {
    val ui=local.mixedCapture ?: MixedCaptureUiState()
    val enabled=local.ready && !local.opening && local.error==null && ui.draft!=null
    val editable=enabled && !ui.busy && ui.selection==null && !ui.uncertainSave && ui.draft?.discarding!=true
    DisposableEffect(controller) { onDispose { controller.cancelSelection() } }
    if(ui.confirmDiscard) AlertDialog(onDismissRequest=controller::cancelDiscard,title={Text("清空这份图文草稿？")},
        text={Text("将清理这份草稿的文字和加密暂存图片。已保存的记录保持不变。")},
        confirmButton={TextButton(onClick=controller::confirmDiscard,enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("mixed-discard-confirm")){Text("确认清空草稿")}},
        dismissButton={TextButton(onClick=controller::cancelDiscard,enabled=!ui.busy){Text("继续编辑")}})
    LazyColumn(modifier.fillMaxSize().padding(horizontal=AIyouDimensions.pageNarrow).testTag("mixed-list"),
        contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("此刻",style=MaterialTheme.typography.headlineMedium)
            Text("文字、图文，或只用图片记下来。仅保存在本机，不参与 AI。") }
        if(!enabled) item { Text(local.error ?: ui.error ?: "正在读取加密草稿。")
            TextButton(onClick=controller::refresh,enabled=local.ready){Text("重试读取草稿")} }
        else {
            item { OutlinedTextField(value=ui.text,onValueChange=controller::edit,enabled=editable,
                label={Text("写下此刻（也可以只添加图片）")},minLines=4,maxLines=8,
                modifier=Modifier.fillMaxWidth().testTag("mixed-text")) }
            item { Text("已选 ${ui.draft!!.images.size} / 20 张 · ${ui.draft.images.sumOf { it.intent.byteSize }} 字节",modifier=Modifier.testTag("mixed-count"))
                Text("JPEG / PNG；单张最多 16 MiB，合计最多 64 MiB。原图可能含拍摄资料。无 OCR，仅按文字与手工标签查找。") }
            items(ui.draft!!.images,key={it.intent.id}) { item ->
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
            item { Row { Checkbox(ui.retainJpegMetadata,onCheckedChange=controller::metadata,enabled=editable)
                Text("保留 JPEG 拍摄资料；取消勾选将移除拍摄资料与注释。") } }
            item { OutlinedButton(onClick={controller.select()},enabled=editable && ui.pendingEdits==0,
                modifier=Modifier.heightIn(min=48.dp).testTag("mixed-add")){Text("添加图片")}}
            item { Button(onClick=controller::save,enabled=enabled && !ui.busy && ui.selection==null && ui.pendingEdits==0,
                modifier=Modifier.heightIn(min=48.dp).testTag("mixed-save")){Text(if(ui.uncertainSave) "核对同一次保存" else "保存这条记录")}}
            item { TextButton(onClick=controller::requestDiscard,enabled=editable && ui.pendingEdits==0,
                modifier=Modifier.heightIn(min=48.dp).testTag("mixed-clear")){Text("清空草稿")}}
        }
        item {
            if(ui.busy || ui.pendingEdits>0 || ui.selection?.phase in setOf(AttachmentImportPhase.PREPARING,AttachmentImportPhase.SAVING)) Text("正在保存或核对本机数据，请稍候。")
            ui.error?.let { Text(it,modifier=Modifier.testTag("mixed-error")) }
            ui.notice?.let { Text(it,modifier=Modifier.testTag("mixed-notice")) }
            ui.selection?.let { selection ->
                if(selection.phase==AttachmentImportPhase.FAILED) TextButton(onClick=controller::retryImage,modifier=Modifier.heightIn(min=48.dp)){Text("重试确认图片")}
                TextButton(onClick=controller::cancelSelection,enabled=selection.phase!=AttachmentImportPhase.SAVING,
                    modifier=Modifier.heightIn(min=48.dp).testTag("mixed-cancel-picture")){Text("取消本次选择")}
            }
            TextButton(onClick=controller::refresh,enabled=local.ready && !ui.busy && ui.selection==null,
                modifier=Modifier.heightIn(min=48.dp).testTag("mixed-refresh")){Text("重新核对加密草稿")}
        }
    }
}
