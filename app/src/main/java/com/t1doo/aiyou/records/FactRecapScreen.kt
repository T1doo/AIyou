package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.time.ZoneId

@Composable
fun FactRecapScreen(local: LocalRecordUiState,controller: FactRecapController,list: LazyListState,
    onSource: (FactRecapCard,Boolean)->Unit,modifier: Modifier=Modifier,onRetryRecords: ()->Unit={}) {
    val u=local.factRecap ?: return
    val ready=local.ready && !local.opening && local.error==null
    val range=runCatching { u.requestedRange() }.getOrNull()
    LazyColumn(modifier.fillMaxSize().testTag("recap-list"),state=list,contentPadding=PaddingValues(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item("intro") { Text("本机日／周回顾",style=MaterialTheme.typography.headlineSmall)
            Text("按记录保存时间整理，不代表事件发生时间。只回看原文与手工标签，不生成 AI 叙事或心理判断。")
            Text("补记、纠正或清除事件日期不会移动这里的记录分组；按事件日找原文，请在回忆中选择“事件日期”检索。") }
        item("date") { OutlinedTextField(u.date,{controller.input(it,u.zone,u.period)},label={Text("日期 YYYY-MM-DD")},
            enabled=ready,modifier=Modifier.fillMaxWidth().testTag("recap-date")) }
        item("zone") { OutlinedTextField(u.zone,{controller.input(u.date,it,u.period)},label={Text("回顾时区，例如 Asia/Shanghai")},
            enabled=ready,modifier=Modifier.fillMaxWidth().testTag("recap-zone")) }
        item("period") { Column { Text("一周从周一开始，至下周一零点前；按所选时区自然日划分。")
            OutlinedButton({controller.input(u.date,u.zone,FactRecapPeriod.DAY)},enabled=ready,
                modifier=Modifier.heightIn(min=48.dp).testTag("recap-day")){Text("单日"+if(u.period==FactRecapPeriod.DAY) " · 已选" else "")}
            OutlinedButton({controller.input(u.date,u.zone,FactRecapPeriod.WEEK)},enabled=ready,
                modifier=Modifier.heightIn(min=48.dp).testTag("recap-week")){Text("所在周"+if(u.period==FactRecapPeriod.WEEK) " · 已选" else "")}
        } }
        item("range-preview") {
            Text(if(range==null) "本次范围无效，请检查日期与时区。" else
                "本次范围：${Instant.ofEpochMilli(range.startMillis).atZone(ZoneId.of(range.zone))} 至 ${Instant.ofEpochMilli(range.endMillis).atZone(ZoneId.of(range.zone))} 前",
                modifier=Modifier.testTag("recap-range-preview"))
        }
        item("generate") { Button(controller::generate,enabled=ready && !u.loading && range!=null,
            modifier=Modifier.heightIn(min=48.dp).testTag("recap-generate")){Text(if(u.error!=null) "重试生成回顾" else "生成这次回顾")}
            TextButton(controller::cancel,modifier=Modifier.heightIn(min=48.dp).testTag("recap-cancel")){Text(if(u.loading) "取消生成" else "清除这次回顾")}
            if(u.loading) Text("正在读取本机证据…")
            if(!ready) { Text(local.error ?: "本机记录尚未可读取，请先重试记录读取。")
                TextButton(onRetryRecords,enabled=!local.opening,modifier=Modifier.heightIn(min=48.dp)){Text("重试读取本机记录")} }
            u.error?.let { Text(it,modifier=Modifier.testTag("recap-error")) }
        }
        val s=u.snapshot
        if(s!=null && ready) {
            item("totals") { Text("${s.range.firstDay} 至 ${s.range.endDay} 前 · ${s.range.zone}")
                Text("${s.recordCount} 条记录 · ${s.imageCount} 张图片来源",modifier=Modifier.testTag("recap-totals"))
                val audioCount=s.cards.sumOf { it.audios.size }
                if(audioCount>0) Text("${audioCount} 段原音来源；尚未转写，不计入图片数。")
                Text("图片数来自已保存的附件清单，不代表画面内容；打开原图时另行认证文件。")
                val tags=s.cards.flatMap { it.tags.names }.groupingBy { it }.eachCount().toSortedMap()
                Text(if(tags.isEmpty()) "此范围没有手工标签。" else "手工标签（记录数）："+tags.entries.joinToString("；"){"${it.key} · ${it.value}"})
                if(s.cards.isEmpty()) Text("这个范围没有可见记录。没有生成故事或结论。",modifier=Modifier.testTag("recap-empty"))
            }
            s.range.days.forEach { day ->
                val cards=s.cards.filter { s.range.day(it.capturedAtMillis)==day }
                item("day:$day") { Text("$day · ${cards.size} 条记录 · ${cards.sumOf { it.images.size }} 张图片来源",style=MaterialTheme.typography.titleMedium) }
                cards.forEach { card ->
                    item("source:${card.source.sourceId}") {
                        Column(Modifier.fillMaxWidth()) {
                            Text("记录时间：${Instant.ofEpochMilli(card.capturedAtMillis).atZone(ZoneId.of(s.range.zone))} · 原文 v${card.source.revision}")
                            Text(if(card.excerpt.isEmpty()) {
                                if(card.audios.isNotEmpty()) "附件记录：没有文字正文；原音尚未转写。" else "图片记录：没有文字正文。"
                            } else card.excerpt,modifier=Modifier.testTag("recap-excerpt:"+card.source.sourceId))
                            if(card.hasMore) Text("以上为原文开头摘录，全文可在原记录阅读。")
                            Text("手工标签："+card.tags.names.takeIf { it.isNotEmpty() }?.joinToString("、").orEmpty().ifEmpty { "无" })
                            card.images.forEach { image -> Text("图片来源 ${image.intent.id} · ${image.intent.mediaType} · ${image.intent.byteSize} 字节 · "+
                                if(image.state==AttachmentState.READY) "清单已保存，文件打开时核验" else "尚未完整可读，请在原记录处理") }
                            card.audios.forEach { audio -> Text("原音来源 ${audio.intent.id} · ${audio.intent.byteSize} 字节 · "+
                                if(audio.state==AttachmentState.READY) "清单已保存；当前尚未接入播放" else "尚未完整可读，请在原记录处理") }
                            OutlinedButton({onSource(card,false)},enabled=!u.loading,
                                modifier=Modifier.heightIn(min=48.dp).testTag("recap-source:"+card.source.sourceId)){Text("打开准确原文／全文")}
                            if(card.images.isNotEmpty()) TextButton({onSource(card,true)},
                                modifier=Modifier.heightIn(min=48.dp).testTag("recap-images:"+card.source.sourceId)){Text("打开此版本图片来源")}
                        }
                    }
                }
            }
        }
    }
}
