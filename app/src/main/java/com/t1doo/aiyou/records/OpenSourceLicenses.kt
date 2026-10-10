package com.t1doo.aiyou.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.t1doo.aiyou.designsystem.AIyouDimensions

/** Complete bundled license text remains available without network access. */
@Composable
fun OpenSourceLicenses(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val documents = remember(context) {
        context.assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted().map { name ->
            name.removeSuffix(".txt") to context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(AIyouDimensions.pageNarrow),
        verticalArrangement = Arrangement.spacedBy(AIyouDimensions.sectionGap)) {
        item { Text("开源许可与声明 · 离线文本", style = MaterialTheme.typography.titleLarge) }
        items(documents, key = { it.first }) { (name, text) ->
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
