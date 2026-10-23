package com.t1doo.aiyou.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Presentation states only. A NORMAL component is not proof of persistence. */
enum class ComponentState(val description: String) {
    NORMAL("正常"),
    DISABLED("暂不可用"),
    LOADING("处理中"),
    ERROR("操作失败"),
    LONG_TEXT("长文本"),
    EMPTY("暂无内容"),
    OFFLINE("当前离线"),
}

enum class MascotState(val description: String) {
    Idle("等你记录"), Listening("正在聆听"), Saving("正在保存"),
    Saved("保存完成"), Thinking("正在整理"), NeedsAttention("需要留意"),
}

/** Resource boundary: no mascot owns permissions, persistence or task behavior. */
fun interface MascotAssetProvider {
    fun labelFor(state: MascotState): String?
    @Composable
    fun Artwork(state: MascotState, modifier: Modifier) { NeutralCapybara(state, modifier) }

    /** False means the requested pose is unavailable and a static fallback was drawn. */
    @Composable
    fun interactionArtwork(pose: CompanionPose, modifier: Modifier, actionModifier: Modifier = Modifier): Boolean {
        Artwork(MascotState.Idle, modifier)
        return false
    }
}

object NeutralMascotAssets : MascotAssetProvider {
    override fun labelFor(state: MascotState): String = state.description
}
