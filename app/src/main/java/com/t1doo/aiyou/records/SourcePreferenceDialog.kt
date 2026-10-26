package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.model.*

internal fun PreferenceReview.label()=when(this) {
    PreferenceReview.PROPOSED -> "待确认自述";PreferenceReview.ACCEPTED -> "用户已采纳自述";PreferenceReview.REJECTED -> "已拒绝"
    PreferenceReview.SUPERSEDED -> "已被取代";PreferenceReview.REVOKED -> "已撤回采纳"
}
private fun PreferenceInterval.label()=if(zone==null)"有效区间：未说明" else "有效区间：${fromDate ?: "起点未说明"} 至 ${untilExclusive ?: "终点未说明"}（终点不含）· $zone"

@Composable
internal fun SourcePreferenceDialog(ui:PreferenceUiState,controller:SourcePreferenceController,canUse:()->Boolean,onLocate:(OriginalReadingLocation,UnifiedKeywordHandoff)->Unit) {
    val height=with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp()*0.9f }
    DisposableEffect(controller,ui.session) { onDispose { controller.close(ui.session) } }
    val editable=!ui.busy && ui.pending==null && ui.confirmation==null
    var chooseTopic by remember(ui.session) { mutableStateOf(false) }
    fun edit(quote:String=ui.quote,topic:String=ui.topicLabel,from:String=ui.from,until:String=ui.until,zone:String=ui.zone,affirmed:Boolean=ui.affirmed) {
        if(canUse())controller.edit(ui.session,quote,topic,from,until,zone,affirmed)
    }
    Dialog(onDismissRequest={if(canUse())controller.close(ui.session)},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max=height),shape=MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("preferences-dialog"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("普通偏好自述",style=MaterialTheme.typography.titleLarge)
                    Text("只保留你逐字选择并明确确认的普通本人自述，不自动抽取、事实核验或推断人格、诊断和身份关系。不会联网。")
                    Text("采纳不代表永久成立；记录、采纳时间都不是生效时间。未知区间保持未说明。",style=MaterialTheme.typography.bodySmall)
                    if(ui.busy)Text("正在核对本机来源与审阅。")
                    ui.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("preferences-error")) }
                    ui.notice?.let { Text(it,modifier=Modifier.testTag("preferences-notice")) }
                    val confirmation=ui.confirmation
                    if(confirmation!=null) {
                        val create=(confirmation.intent as? PreferenceIntent.Create)?.request
                        val mutation=(confirmation.intent as? PreferenceIntent.Mutate)?.request
                        Text(when { create!=null -> "确认仅存为待确认候选？";mutation?.action==PreferenceAction.CORRECT -> "确认纠正并重新待确认？";mutation?.replacing!=null -> "确认采纳此条并取代下面明确展示的旧项？";mutation?.action==PreferenceAction.ACCEPT -> "单独确认采纳这条普通自述？";mutation?.action==PreferenceAction.REJECT -> "确认拒绝？";else -> "确认撤回采纳？" })
                        val content=create?.content ?: mutation?.content ?: mutation?.expected?.current?.content
                        content?.let { Text(it.reference.quote);Text(it.interval.label()) }
                        Text("主题："+(create?.topicLabel ?: mutation?.expected?.topicLabel).orEmpty())
                        Text("本次出处版本 ${create?.preview?.source?.revision ?: mutation?.expected?.source?.revision}；记录时区 ${create?.preview?.captureTimeZone ?: mutation?.expected?.captureTimeZone}")
                        Text("本次原审阅："+(mutation?.expected?.review?.review?.label() ?: "尚未创建；只存待确认"))
                        mutation?.replacing?.let { old ->
                            Text("将取代的旧项 · ${old.review?.review?.label()}");Text(checkNotNull(old.current).content.reference.quote)
                            Text(old.current!!.content.interval.label());Text("出处版本 ${old.source.revision}；记录时区 ${old.captureTimeZone}")
                            Text("旧话不是错误记录，取代不会补造其结束日期；撤回新项也不会复活旧项。")
                        }
                        TextButton(onClick={if(canUse())controller.confirm(ui.session,confirmation)},enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("preference-confirm")) { Text("确认这次操作") }
                        TextButton(onClick={if(canUse())controller.cancel(ui.session,confirmation)},enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("preference-cancel")) { Text("取消，保持原样") }
                    } else {
                        if(ui.source==null) {
                            Column { listOf(PreferenceReview.PROPOSED,PreferenceReview.ACCEPTED).forEach { section ->
                                TextButton(onClick={if(canUse())controller.section(ui.session,section)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-section-"+section.name)) { Text(section.label()) }
                            } }
                            if(ui.section==PreferenceReview.ACCEPTED) {
                                Text("按主题或当前引用逐字包含查找；区分大小写，不是语义检索或 AI 问答。留空可看全部已采纳自述。")
                                OutlinedTextField(ui.keyword,{if(canUse())controller.keyword(ui.session,it)},label={Text("关键词（最多64字）")},enabled=ui.pending==null && ui.confirmation==null,modifier=Modifier.fillMaxWidth().testTag("preference-keyword"))
                                TextButton(onClick={if(canUse())controller.search(ui.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-search")) { Text("查找已采纳自述") }
                                if(ui.page!=null && ui.items.isEmpty())Text("没有找到逐字包含此关键词的已采纳普通自述。",Modifier.testTag("preference-no-match"))
                            }
                            if(ui.page!=null)Text(if(ui.section==PreferenceReview.ACCEPTED)"当前匹配页 ${ui.items.size} 条；不是全库总数。" else "当前页 ${ui.items.size} 条；空页仍可能有更早内容，不是全库总数。",Modifier.testTag("preferences-count"))
                        }
                        if(ui.sourcePage!=null) {
                            val original=ui.sourcePage.preview.original
                            val end=original.offsetByCodePoints(0,minOf(320,original.codePointCount(0,original.length)))
                            Text("原文开头提示（最多320字；其他位置请先在原文选择复制）")
                            SelectionContainer { Text(original.substring(0,end)) }
                            OutlinedTextField(ui.quote,{edit(quote=it)},label={Text("逐字原文片段（最多320字）")},enabled=editable,modifier=Modifier.fillMaxWidth().testTag("preference-quote"))
                            OutlinedTextField(ui.topicLabel,{edit(topic=it)},label={Text("新建普通偏好主题/上下文（最多40字）")},enabled=editable && ui.topicExpected==null && ui.editing==null,modifier=Modifier.fillMaxWidth().testTag("preference-topic-label"))
                            Text("主题由你划分；跨主题不会自动识别同义或冲突。本片不改主题名，也不跨主题搬移旧项。",style=MaterialTheme.typography.bodySmall)
                            if(ui.editing==null) {
                                TextButton(onClick={chooseTopic=!chooseTopic},enabled=editable,modifier=Modifier.heightIn(min=48.dp)) { Text("选择现有主题") }
                                if(chooseTopic)ui.sourcePage.topics.forEach { topic ->
                                    TextButton(onClick={if(canUse()) {controller.topic(ui.session,topic);chooseTopic=false}},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-topic:"+topic.topicId)) { Text(checkNotNull(topic.topicLabel)) }
                                }
                                if(ui.topicExpected!=null)TextButton(onClick={if(canUse())controller.topic(ui.session,null)},enabled=editable,modifier=Modifier.heightIn(min=48.dp)) { Text("改为新主题") }
                            }
                            OutlinedTextField(ui.from,{edit(from=it)},label={Text("生效起始日（YYYY-MM-DD，未知留空）")},enabled=editable,modifier=Modifier.fillMaxWidth().testTag("preference-from"))
                            OutlinedTextField(ui.until,{edit(until=it)},label={Text("结束日（不含此日，未知留空）")},enabled=editable,modifier=Modifier.fillMaxWidth().testTag("preference-until"))
                            OutlinedTextField(ui.zone,{edit(zone=it)},label={Text("日期时区（IANA，如 Asia/Shanghai；全未知留空）")},enabled=editable,modifier=Modifier.fillMaxWidth().testTag("preference-zone"))
                            Row { Checkbox(ui.affirmed,{edit(affirmed=it)},enabled=editable,modifier=Modifier.testTag("preference-affirm"));Text("我确认这是本人明确的普通偏好自述，不是引用、假设、计划或敏感身份/健康/关系推断。") }
                            Button(onClick={if(canUse())controller.save(ui.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-save")) { Text(if(ui.editing==null)"仅保存为待确认候选" else "纠正并重新待确认") }
                            if(ui.editing!=null)TextButton(onClick={if(canUse())controller.cancelCorrection(ui.session)},enabled=editable,modifier=Modifier.heightIn(min=48.dp)) { Text("取消纠正") }
                        }
                        ui.items.forEach { item ->
                            val content=checkNotNull(item.current).content;val review=checkNotNull(item.review)
                            HorizontalDivider();Text(checkNotNull(item.topicLabel),style=MaterialTheme.typography.titleMedium)
                            Text(review.review.label(),Modifier.testTag("preference-state:"+item.id));Text(content.reference.quote);Text(content.interval.label())
                            Text("出处原文版本 ${item.source.revision} · 记录时区 ${item.captureTimeZone}")
                            TextButton(onClick={if(canUse())controller.locate(ui.session,item,onLocate)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-source:"+item.id)) { Text("查回逐字出处") }
                            if(review.review==PreferenceReview.PROPOSED) {
                                TextButton(onClick={if(canUse())controller.action(ui.session,item,PreferenceAction.ACCEPT)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-accept:"+item.id)) { Text("审阅并单独采纳") }
                                TextButton(onClick={if(canUse())controller.action(ui.session,item,PreferenceAction.REJECT)},enabled=editable,modifier=Modifier.heightIn(min=48.dp)) { Text("拒绝候选") }
                            }
                            if(review.review==PreferenceReview.ACCEPTED)TextButton(onClick={if(canUse())controller.action(ui.session,item,PreferenceAction.REVOKE)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-revoke:"+item.id)) { Text("撤回采纳") }
                            if(ui.source!=null && review.review!=PreferenceReview.SUPERSEDED && item.contents.size<8)TextButton(onClick={if(canUse())controller.correct(ui.session,item)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("preference-correct:"+item.id)) { Text("手工纠正") }
                            TextButton(onClick={if(canUse())controller.history(ui.session,item.id)},enabled=editable,modifier=Modifier.heightIn(min=48.dp)) { Text("查看内容与取代历史") }
                            if(ui.historyId==item.id)item.contents.forEachIndexed { index,version ->
                                val checkpoint=item.reviews[index];Text("内容 ${version.revision} · ${checkpoint.review.label()}"+(checkpoint.priorReview?.let { "（此前${it.label()}）" } ?: ""))
                                Text(version.content.reference.quote);Text(version.content.interval.label())
                                listOf("取代前项" to checkpoint.predecessor,"取代后项" to checkpoint.successor).forEach { (label,edge) ->
                                    if(edge!=null)TextButton(onClick={if(canUse())controller.endpoint(ui.session,item,edge,onLocate)},enabled=editable,modifier=Modifier.heightIn(min=48.dp)) { Text("核对${label}逐字出处") }
                                }
                            }
                        }
                        if(ui.source==null) {
                            TextButton(onClick={if(canUse())controller.next(ui.session,true)},enabled=editable && ui.canPrevious,modifier=Modifier.heightIn(min=48.dp)) { Text("上一页") }
                            TextButton(onClick={if(canUse())controller.next(ui.session)},enabled=editable && ui.page?.next!=null,modifier=Modifier.heightIn(min=48.dp)) { Text("下一页") }
                        }
                    }
                    if(ui.error!=null && ui.pending!=null)TextButton(onClick={if(canUse())controller.retry(ui.session)},enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp).testTag("preference-retry")) { Text("核对同一次请求") }
                    TextButton(onClick={if(canUse())controller.refresh(ui.session)},enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp)) { Text("仅刷新实际状态，不重复原请求") }
                    TextButton(onClick={if(canUse())controller.close(ui.session)},modifier=Modifier.heightIn(min=48.dp).testTag("preference-close")) { Text("关闭偏好自述") }
                }
            }
        }
    }
}
