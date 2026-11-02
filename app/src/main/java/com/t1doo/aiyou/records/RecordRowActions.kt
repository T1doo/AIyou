package com.t1doo.aiyou.records

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Horizontal reveal never commits an operation. The ordinary button exposes the same actions. */
@Composable
internal fun RecordRowActions(id:String,expanded:Boolean,enabled:Boolean,favorite:Boolean?,favoriteBusy:Boolean,
    onReveal:()->Unit,onClose:()->Unit,returnFocus:Boolean,onAction:(String)->Unit,content:@Composable ()->Unit) {
    val threshold=with(LocalDensity.current) {56.dp.toPx()}
    var drag by remember(id,enabled) {mutableFloatStateOf(0f)}
    val focus=remember(id) {FocusRequester()}
    var wasExpanded by remember(id) {mutableStateOf(false)}
    val reveal by rememberUpdatedState(onReveal)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(expanded,returnFocus,enabled) {
        val closed=wasExpanded&&!expanded;wasExpanded=expanded
        if(closed&&returnFocus&&enabled) {withFrameNanos{};focus.requestFocus()}
    }
    Column(Modifier.fillMaxWidth().pointerInput(id,enabled,threshold) {
        if(enabled) detectHorizontalDragGestures(onDragCancel={drag=0f},onDragEnd={
            if(drag<=-threshold) reveal() else if(drag>=threshold) close()
            drag=0f
        }) {change,delta->change.consume();drag=(drag+delta).coerceIn(-threshold*1.5f,threshold*1.5f)}
    }) {
        Box(Modifier.fillMaxWidth().graphicsLayer {translationX=drag.coerceIn(-threshold,0f)}) {content()}
        TextButton(onClick={if(expanded){onClose()}else onReveal()},enabled=enabled,
            modifier=Modifier.heightIn(min=48.dp).focusRequester(focus).testTag("record-actions-"+id)
                .semantics {stateDescription=if(expanded)"已展开" else "已收起"}) {Text(if(expanded)"收起操作" else "操作")}
        if(expanded) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(favorite!=null) TextButton(onClick={onAction("favorite")},enabled=enabled&&!favoriteBusy,
                modifier=Modifier.heightIn(min=48.dp).testTag("record-favorite-"+id)) {Text(if(favoriteBusy)"收藏状态待确认" else if(favorite)"取消收藏" else "收藏")}
            TextButton(onClick={onAction("edit")},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("record-edit-"+id)) {Text("编辑原文")}
            TextButton(onClick={onAction("delete")},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("record-delete-"+id)) {Text("移入回收站…")}
        }
    }
}
