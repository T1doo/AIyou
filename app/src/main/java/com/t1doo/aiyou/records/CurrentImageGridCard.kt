package com.t1doo.aiyou.records

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.t1doo.aiyou.designsystem.AIyouDimensions

@Composable
internal fun CurrentImageGridCard(hit: OriginalSearchHit, tile: ImageGridTile?, modifier: Modifier = Modifier,
    onOpen: () -> Unit) {
    Surface(onClick = onOpen, modifier = modifier.heightIn(min = AIyouDimensions.minTouchTarget)
        .testTag("image-grid-entry-${hit.entry.id}"), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(AIyouDimensions.spaceSm), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                val picture = tile?.picture as? PlatformAttachmentPicture
                if (picture != null) Image(picture.image, "当前版本图片", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().testTag("image-grid-picture-${hit.entry.id}"))
                else Text(if (tile?.failed == true) "图片暂不可查看\n原文件已保留" else "正在读取图片…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(if (tile?.failed == true) "image-grid-failed-${hit.entry.id}" else "image-grid-loading-${hit.entry.id}"))
            }
            OriginalSearchTagMatch(hit)
            if (hit.excerpt.isNotEmpty()) Text(hit.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(originalMetadata(hit.entry), style = MaterialTheme.typography.bodySmall)
            Text("打开原文 · 版本 ${hit.entry.currentRevision}", style = MaterialTheme.typography.labelLarge)
        }
    }
}
