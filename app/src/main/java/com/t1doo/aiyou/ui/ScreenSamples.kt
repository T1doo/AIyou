package com.t1doo.aiyou.ui

import androidx.compose.runtime.Immutable

@Immutable
data class MemorySample(val id: String, val date: String, val text: String, val annotation: String? = null)

/** Synthetic visual fixtures. No repository, model, user facts or scheduled work. */
object ScreenSamples {
    val memories = listOf(
        MemorySample("leaf", "2026-10-10 16:20", "散步时看见一片新叶子，想把这个小小的发现记下来。", "示例摘要：散步中的小发现。"),
        MemorySample("book", "2026-10-09 19:00", "想把旅行照片做成一本小册子。现在还是个想法，不是必须完成的任务。"),
    )
    val longText = "这是一条虚构的长记录，包含中文、标点和 emoji 🌿。原文应完整保留，不能被示例摘要覆盖。".repeat(10)
}
