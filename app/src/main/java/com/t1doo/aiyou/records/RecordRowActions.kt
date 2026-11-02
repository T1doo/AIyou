package com.t1doo.aiyou.records

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouColors

/** A narrow side band is revealed; crossing a drag threshold never commits an operation. */
@Composable
internal fun RecordRowActions(id:String,expanded:Boolean,enabled:Boolean,favorite:Boolean?,favoriteBusy:Boolean,
    onReveal:()->Unit,onClose:()->Unit,returnFocus:Boolean,onAction:(String)->Unit,content:@Composable ()->Unit) {
    val threshold=with(LocalDensity.current) {56.dp.toPx()}
    val band=112.dp
    val bandPx=with(LocalDensity.current) {band.toPx()}
    var drag by remember(id,enabled) {mutableFloatStateOf(0f)}
    val focus=remember(id) {FocusRequester()}
    var wasExpanded by remember(id) {mutableStateOf(false)}
    val reveal by rememberUpdatedState(onReveal)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(expanded,returnFocus,enabled) {
        val closed=wasExpanded&&!expanded;wasExpanded=expanded
        if(closed&&returnFocus&&enabled) {withFrameNanos{};focus.requestFocus()}
    }
    Box(Modifier.fillMaxWidth().heightIn(min=if(expanded)144.dp else 0.dp).clipToBounds()
        .pointerInput(id,enabled,expanded,threshold) {
            if(enabled) detectHorizontalDragGestures(onDragCancel={drag=0f},onDragEnd={
                if(drag<=-threshold) reveal() else if(drag>=threshold) close()
                drag=0f
            }) {change,delta->change.consume();drag=(drag+delta).coerceIn(-bandPx,bandPx)}
        }) {
        if(expanded||drag<0f) Row(Modifier.matchParentSize(),horizontalArrangement=Arrangement.End) {
            Column(Modifier.width(band).fillMaxHeight().background(AIyouColors.surfaceMuted).verticalScroll(rememberScrollState())
                .testTag("record-action-band-"+id),horizontalAlignment=Alignment.CenterHorizontally) {
                if(favorite!=null) TextButton(onClick={onAction("favorite")},enabled=expanded&&enabled&&!favoriteBusy,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("record-favorite-"+id)) {Text(if(favoriteBusy)"待确认" else if(favorite)"取消收藏" else "收藏")}
                TextButton(onClick={onAction("edit")},enabled=expanded&&enabled,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("record-edit-"+id)) {Text("编辑原文")}
                TextButton(onClick={onAction("delete")},enabled=expanded&&enabled,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("record-delete-"+id)
                        .semantics {contentDescription="移入回收站，下一步核对对象"}) {Text("回收站…")}
            }
        }
        Column(Modifier.fillMaxWidth().graphicsLayer {translationX=((if(expanded)-bandPx else 0f)+drag).coerceIn(-bandPx,0f)}
            .background(AIyouColors.surface)) {
            content()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick={if(expanded)onClose() else onReveal()},enabled=enabled,
                    modifier=Modifier.heightIn(min=48.dp).focusRequester(focus).testTag("record-actions-"+id)
                        .semantics {stateDescription=if(expanded)"侧边操作已展开" else "侧边操作已收起"}) {Text(if(expanded)"收起操作" else "操作")}
            }
        }
    }
}
