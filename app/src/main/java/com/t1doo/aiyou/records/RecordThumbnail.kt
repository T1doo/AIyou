package com.t1doo.aiyou.records

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Only supplied by the existing authenticated current-image search proof and visible-set budget. */
@Composable
internal fun RecordThumbnail(tile: ImageGridTile?) {
    Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        val picture = tile?.picture as? PlatformAttachmentPicture
        if (picture != null) Image(picture.image, "记录图片预览，打开原文查看原件", Modifier.size(64.dp), contentScale = ContentScale.Crop)
        else Text(if (tile?.failed == true) "图片暂不可读" else "读取图片…", style = MaterialTheme.typography.bodySmall)
    }
}
