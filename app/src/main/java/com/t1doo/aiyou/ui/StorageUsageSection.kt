package com.t1doo.aiyou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.designsystem.AIyouDimensions
import java.util.Locale

@Composable
internal fun StorageUsageSection(controller: StorageUsageController) {
    DisposableEffect(controller) {
        val owner = controller.enter()
        onDispose { controller.leave(owner) }
    }
    val state by controller.state.collectAsState()
    val owner = state.session
    Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Text("本机记录占用", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text("按文件大小统计，包含历史版本、回收站和未完成附件。查询期间的写入可能让大小变化。",
            style = MaterialTheme.typography.bodySmall)
        if (state.busy) Text("正在查询本机记录占用…", modifier = Modifier.testTag("storage-usage-loading"))
        state.usage?.let { usage ->
            Text("文件大小合计：${storageBytes(usage.totalBytes)}", modifier = Modifier.testTag("storage-usage-total"))
            Text("数据库与写入日志：${storageBytes(usage.databaseBytes)}")
            Text("加密附件：${storageBytes(usage.attachmentBytes)}")
            Text("加密编辑草稿：${storageBytes(usage.editDraftBytes)}")
            Text("其他记录文件：${storageBytes(usage.otherBytes)}")
        }
        if (state.error) Text("暂时无法完整读取占用，请稍后重试。", modifier = Modifier.testTag("storage-usage-error"))
        Text("不含应用安装包、密钥和你另存的导出文件；不代表设备剩余空间。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { controller.query(owner) }, enabled = state.active && !state.busy,
            modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget).testTag("storage-usage-query")) {
            Text(when { state.error -> "重试查询占用"; state.usage != null -> "刷新占用"; else -> "查询占用" })
        }
    }
}

private fun storageBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
}
