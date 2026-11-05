package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.t1doo.aiyou.model.RecordDateBasis
import com.t1doo.aiyou.model.ManualTags
import java.time.LocalDate

/** Ephemeral panel state, never a query owner, stored preference or diagnostic payload. */
data class OriginalFilterDraft(val from: String = "", val through: String = "",
    val basis: RecordDateBasis = RecordDateBasis.CAPTURED, val tag: String? = null, val person: String? = null) {
    override fun toString() = "OriginalFilterDraft(redacted)"
    fun validated(): OriginalFilterDraft {
        fun date(value: String): LocalDate? {
            if(value.isEmpty()) return null
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
            return LocalDate.parse(value)
        }
        val start=date(from);val end=date(through)
        require(start==null || end==null || !end.isBefore(start))
        return copy(person=person?.takeIf { it.isNotBlank() }?.let { ManualTags.canonical(listOf(it)).single() })
    }
}

@Composable
internal fun OriginalFiltersDialog(initial: OriginalFilterDraft, enabled: Boolean, tags: List<String>,
    tagsAvailable: Boolean, peopleSupported: Boolean, peopleAvailable: Boolean,
    eventDatesAvailable: Boolean, onDismiss: () -> Unit, onApply: (OriginalFilterDraft) -> Boolean,
    onRefreshPeople: () -> Unit, onRefreshEventDates: () -> Unit,
    indexSupported: Boolean, indexBusy: Boolean, onRebuild: () -> Unit, onClearAll: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosingTag by remember { mutableStateOf(false) }
    var maintenance by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),contentAlignment=Alignment.BottomCenter) {
                Surface(shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).verticalScroll(rememberScrollState())
                        .padding(16.dp).testTag("original-search-filter-page"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onDismiss,modifier=Modifier.heightIn(min=48.dp).testTag("original-filter-cancel")){Text("取消")}
                            Text("筛选回忆",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
                            Button(onClick={
                                try {
                                    val value=draft.validated()
                                    require(value.person==null || peopleAvailable)
                                    require(value.tag==null || tagsAvailable && value.tag in tags)
                                    require(value.basis!=RecordDateBasis.EVENT || eventDatesAvailable)
                                    if(onApply(value)) onDismiss() else error="页面状态已变化，请返回后重新打开筛选。"
                                } catch(_: IllegalArgumentException) {error="请核对日期、人物及当前可用的标签。"}
                                catch(_: java.time.DateTimeException) {error="请填写有效日期，结束日期不能早于开始日期。"}
                            },enabled=enabled,shape=RoundedCornerShape(10.dp),modifier=Modifier.heightIn(min=48.dp).testTag("original-search-filter-done")){Text("应用")}
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            RecordDateBasis.entries.forEach { basis ->
                                FilterChip(selected=draft.basis==basis,onClick={draft=draft.copy(basis=basis)},enabled=enabled,
                                    label={Text(if(basis==RecordDateBasis.CAPTURED)"记录日期" else "事件日期")})
                            }
                        }
                        OutlinedTextField(value=draft.from,onValueChange={if(it.length<=10)draft=draft.copy(from=it)},enabled=enabled,
                            label={Text("开始日期")},placeholder={Text("YYYY-MM-DD")},singleLine=true,shape=MaterialTheme.shapes.medium,
                            modifier=Modifier.fillMaxWidth().testTag("original-search-from"))
                        OutlinedTextField(value=draft.through,onValueChange={if(it.length<=10)draft=draft.copy(through=it)},enabled=enabled,
                            label={Text("结束日期")},placeholder={Text("YYYY-MM-DD")},singleLine=true,shape=MaterialTheme.shapes.medium,
                            modifier=Modifier.fillMaxWidth().testTag("original-search-through"))
                        Box {
                            TextButton(onClick={choosingTag=true},enabled=enabled&&tagsAvailable,
                                modifier=Modifier.heightIn(min=48.dp).testTag("original-search-tag")){Text(draft.tag ?: "全部标签")}
                            DropdownMenu(expanded=choosingTag,onDismissRequest={choosingTag=false}) {
                                DropdownMenuItem(text={Text("全部标签")},onClick={draft=draft.copy(tag=null);choosingTag=false})
                                tags.forEachIndexed { index, tag -> DropdownMenuItem(text={Text(tag)},onClick={draft=draft.copy(tag=tag);choosingTag=false},
                                    modifier=Modifier.testTag("original-search-tag-option-$index")) }
                            }
                        }
                        if(peopleSupported) OutlinedTextField(value=draft.person.orEmpty(),onValueChange={if(it.length<=160)draft=draft.copy(person=it)},
                            enabled=enabled&&peopleAvailable,label={Text("人物称呼")},singleLine=true,shape=MaterialTheme.shapes.medium,
                            modifier=Modifier.fillMaxWidth().testTag("original-search-person-input"))
                        if(peopleSupported&&!peopleAvailable) TextButton(onRefreshPeople,enabled=enabled){Text("重试读取人物")}
                        if(draft.basis==RecordDateBasis.EVENT&&!eventDatesAvailable) TextButton(onRefreshEventDates,enabled=enabled){Text("重试读取事件日期")}
                        error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("original-filter-error")) }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={draft=OriginalFilterDraft();error=null},enabled=enabled,
                                modifier=Modifier.heightIn(min=48.dp).testTag("original-filter-reset")){Text("重置筛选")}
                            TextButton(onClick={maintenance=!maintenance},modifier=Modifier.heightIn(min=48.dp)){Text("更多查找设置")}
                        }
                        if(maintenance) {
                            TextButton(onClick={onClearAll();onDismiss()},enabled=enabled,
                                modifier=Modifier.heightIn(min=48.dp).testTag("original-search-clear")){Text("清空全部查找，包括搜索词")}
                            if(indexSupported) TextButton(onRebuild,enabled=enabled&&!indexBusy,
                                modifier=Modifier.heightIn(min=48.dp).testTag("original-search-rebuild")){Text("重新整理本机查找")}
                        }
                    }
                }
            }
        }
    }
}
