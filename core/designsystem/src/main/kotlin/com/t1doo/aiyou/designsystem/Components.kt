package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import com.t1doo.aiyou.model.ProcessingPolicy

@Composable
private fun ComponentCard(modifier: Modifier = Modifier, padding: Dp = AIyouDimensions.spaceLg, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = AIyouColors.surface),
        border = BorderStroke(AIyouDimensions.spaceXs / 4, AIyouColors.outlineDecorative),
    ) {
        Column(
            Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm),
        ) { content() }
    }
}

@Composable
private fun StateFeedback(state: ComponentState) {
    when (state) {
        ComponentState.NORMAL, ComponentState.LONG_TEXT -> Unit
        ComponentState.LOADING -> {
            CircularProgressIndicator(Modifier.size(AIyouDimensions.spaceLg))
            Text(state.description, style = MaterialTheme.typography.bodySmall)
        }
        else -> Text(
            state.description,
            color = if (state == ComponentState.ERROR) AIyouColors.error else AIyouColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun PrivacyChip(
    policy: ProcessingPolicy,
    modifier: Modifier = Modifier,
    state: ComponentState = ComponentState.NORMAL,
    description: String? = null,
) {
    Surface(
        modifier = modifier,
        color = AIyouColors.surfaceMuted,
        shape = privacyContentShape,
        border = BorderStroke(AIyouDimensions.spaceXs / 4, AIyouColors.outlineFunctional),
    ) {
        Column(Modifier.padding(horizontal = AIyouDimensions.spaceMd, vertical = AIyouDimensions.spaceSm)) {
            Text(
                when (policy) {
                    ProcessingPolicy.AI_NONE -> "不参与 AI"
                    ProcessingPolicy.LOCAL_ONLY -> "仅本机处理"
                    ProcessingPolicy.REMOTE_ALLOWED -> "远端处理需有效授权"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            StateFeedback(state)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun MemoryCard(
    originalText: String,
    capturedAt: String,
    modifier: Modifier = Modifier,
    policy: ProcessingPolicy = ProcessingPolicy.LOCAL_ONLY,
    annotation: String? = null,
    attachmentLabel: String? = null,
    state: ComponentState = ComponentState.NORMAL,
) {
    ComponentCard(modifier, padding = AIyouDimensions.spaceMd) {
        if (state == ComponentState.EMPTY) {
            Text("还没有记录", style = MaterialTheme.typography.titleMedium)
            Text("保存记录后，原文、附件和 AI 派生内容会分别显示。", style = MaterialTheme.typography.bodyMedium)
            StateFeedback(state)
            return@ComponentCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
            Text(capturedAt, style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
            Text(if (policy == ProcessingPolicy.LOCAL_ONLY) "原文 · 仅本机" else "原文", style = MaterialTheme.typography.bodySmall, color = AIyouColors.primary)
        }
        Text(originalText, style = MaterialTheme.typography.bodyLarge)
        attachmentLabel?.let {
            Surface(modifier = Modifier.fillMaxWidth(), color = AIyouColors.surfaceMuted, shape = MaterialTheme.shapes.small) {
                Text(it, Modifier.fillMaxWidth().padding(AIyouDimensions.spaceLg), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (policy != ProcessingPolicy.LOCAL_ONLY) PrivacyChip(policy)
        annotation?.let {
            Text("AI 派生 · $it", Modifier.fillMaxWidth().drawBehind {
                drawLine(AIyouColors.accentDecorative, Offset.Zero, Offset(0f, size.height), AIyouDimensions.iconStroke.toPx())
            }.padding(start = AIyouDimensions.spaceMd),
                style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        }
        StateFeedback(state)
    }
}

@Composable
fun CaptureComposer(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    askMode: Boolean = false,
    onModeChange: (Boolean) -> Unit = {},
    askAvailable: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    onImage: (() -> Unit)? = null,
    onAudio: (() -> Unit)? = null,
    state: ComponentState = ComponentState.NORMAL,
    availability: String = "记录保存尚未开放；输入仅用于界面演示",
) {
    val canEdit = state != ComponentState.DISABLED && state != ComponentState.LOADING
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = AIyouColors.surfaceMuted) {
            Column(
                Modifier.padding(AIyouDimensions.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stackControls = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
                    if (stackControls) {
                        Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            ComposerMode("记下来", !askMode, canEdit, { onModeChange(false) }, Modifier.fillMaxWidth())
                            ComposerMode(if (askAvailable) "问一问" else "问一问 · 未开放", askMode, canEdit && askAvailable, { onModeChange(true) }, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            ComposerMode("记下来", !askMode, canEdit, { onModeChange(false) }, Modifier.weight(1f))
                            ComposerMode(if (askAvailable) "问一问" else "问一问 · 未开放", askMode, canEdit && askAvailable, { onModeChange(true) }, Modifier.weight(1f))
                        }
                    }
                }
                Surface(Modifier.fillMaxWidth(), color = AIyouColors.surface, shape = MaterialTheme.shapes.small,
                    border = BorderStroke(AIyouDimensions.spaceXs / 4, AIyouColors.outlineFunctional)) {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.composerTextMinHeight)
                            .padding(AIyouDimensions.spaceSm)
                            .semantics {
                                contentDescription = if (askMode) "想问些什么" else "此刻，想记下什么"
                                if (state == ComponentState.ERROR) error("输入需要处理，请检查后重试。")
                            },
                        enabled = canEdit,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = AIyouColors.textPrimary),
                        minLines = 2,
                        maxLines = 6,
                        decorationBox = { field ->
                            Box {
                                if (text.isEmpty()) Text(if (askMode) "想问些什么？" else "今天有什么想记住的？", color = AIyouColors.textSecondary, style = MaterialTheme.typography.bodyLarge)
                                field()
                            }
                        },
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stackControls = maxWidth <= AIyouDimensions.narrowBreakpoint && LocalDensity.current.fontScale > 1.3f
                    if (stackControls) {
                        Column(verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                                ComposerAttachment(false, canEdit && onImage != null, onImage == null, { onImage?.invoke() }, Modifier.weight(1f))
                                ComposerAttachment(true, canEdit && onAudio != null, onAudio == null, { onAudio?.invoke() }, Modifier.weight(1f))
                            }
                            Button(onClick = { onSubmit?.invoke() }, enabled = canEdit && text.isNotBlank() && onSubmit != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget),
                                shape = MaterialTheme.shapes.small) { Text(if (askMode) "提问" else "保存", style = MaterialTheme.typography.labelLarge) }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
                                ComposerAttachment(false, canEdit && onImage != null, onImage == null, { onImage?.invoke() })
                                ComposerAttachment(true, canEdit && onAudio != null, onAudio == null, { onAudio?.invoke() })
                            }
                            Button(onClick = { onSubmit?.invoke() }, enabled = canEdit && text.isNotBlank() && onSubmit != null,
                                modifier = Modifier.heightIn(min = AIyouDimensions.minTouchTarget), shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = AIyouDimensions.spaceLg, vertical = AIyouDimensions.spaceSm)) {
                                Text(if (askMode) "提问" else "保存", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        Text(availability, Modifier.padding(horizontal = AIyouDimensions.spaceXs), style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        if (askMode) Text("提问不会自动成为永久记忆", style = MaterialTheme.typography.bodySmall)
        StateFeedback(composerFeedbackState(state, text))
    }
}

@Composable
private fun ComposerMode(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier, color = if (selected) AIyouColors.surface else AIyouColors.surfaceMuted,
        shape = MaterialTheme.shapes.small) {
        TextButton(onClick = onClick, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(disabledContentColor = AIyouColors.textSecondary),
            modifier = Modifier.fillMaxWidth().heightIn(min = AIyouDimensions.minTouchTarget).semantics {
                this.selected = selected
                if (!enabled) stateDescription = "当前不可用"
            }) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun ComposerAttachment(audio: Boolean, enabled: Boolean, unavailable: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = AIyouDimensions.minTouchTarget)
        .semantics { if (!enabled) stateDescription = if (unavailable) "尚未开放" else "当前不可用" },
        contentPadding = PaddingValues(horizontal = AIyouDimensions.spaceXs * 1.5f, vertical = AIyouDimensions.spaceSm),
        colors = ButtonDefaults.textButtonColors(disabledContentColor = AIyouColors.textSecondary)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
            AttachmentIcon(audio, enabled)
            val label = if (audio) "录音" else "图片"
            Text(if (unavailable) "${label}未开放" else label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SourceCitationCard(
    date: String,
    quote: String,
    revisionLabel: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    state: ComponentState = ComponentState.NORMAL,
) {
    ComponentCard(modifier) {
        Text("来源 · $date", style = MaterialTheme.typography.titleMedium)
        Text(revisionLabel, style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        Text(quote, style = MaterialTheme.typography.bodyLarge)
        TextButton(
            onClick = { onOpen?.invoke() },
            enabled = onOpen != null && state != ComponentState.DISABLED && state != ComponentState.LOADING,
        ) { Text("查看原文") }
        StateFeedback(state)
    }
}

@Composable
fun TaskProposalCard(
    title: String,
    temporalLabel: String,
    sourceLabel: String,
    modifier: Modifier = Modifier,
    onReview: (() -> Unit)? = null,
    state: ComponentState = ComponentState.NORMAL,
) {
    ComponentCard(modifier) {
        if (state == ComponentState.EMPTY) {
            Text("没有待确认建议", style = MaterialTheme.typography.titleMedium)
            Text("建议经过用户确认后，才可成为正式任务。", style = MaterialTheme.typography.bodyMedium)
            StateFeedback(state)
            return@ComponentCard
        }
        Text("待确认建议", style = MaterialTheme.typography.labelLarge, color = AIyouColors.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(temporalLabel, style = MaterialTheme.typography.bodyMedium)
        Text("来源：$sourceLabel", style = MaterialTheme.typography.bodySmall)
        Text("尚未创建任务或安排提醒", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onReview?.invoke() },
            enabled = onReview != null && state != ComponentState.DISABLED && state != ComponentState.LOADING,
        ) { Text("查看并确认") }
        StateFeedback(state)
    }
}

@Composable
fun ProviderConnectionCard(
    name: String,
    capabilityLabel: String,
    modifier: Modifier = Modifier,
    onConfigure: (() -> Unit)? = null,
    state: ComponentState = ComponentState.NORMAL,
) {
    ComponentCard(modifier) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(capabilityLabel, style = MaterialTheme.typography.bodyLarge)
        Text("配置连接不等于授权发送记录", style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = { onConfigure?.invoke() },
            enabled = onConfigure != null && state != ComponentState.DISABLED && state != ComponentState.LOADING,
        ) { Text("配置连接") }
        StateFeedback(state)
    }
}

@Composable
fun StatusNotice(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    state: ComponentState = ComponentState.NORMAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val color = if (state == ComponentState.ERROR) AIyouColors.error else AIyouColors.textPrimary
    ComponentCard(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(title, color = color, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        StateFeedback(state)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, enabled = state != ComponentState.DISABLED && state != ComponentState.LOADING) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun MascotBadge(
    modifier: Modifier = Modifier,
    mascotState: MascotState = MascotState.Idle,
    assets: MascotAssetProvider = NeutralMascotAssets,
    state: ComponentState = ComponentState.NORMAL,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceXs)) {
        Surface(
            modifier = Modifier.sizeIn(minWidth = AIyouDimensions.badgeSize, minHeight = AIyouDimensions.badgeSize),
            color = AIyouColors.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("忆", modifier = Modifier.padding(AIyouDimensions.spaceMd), style = MaterialTheme.typography.titleMedium)
        }
        Text(assets.labelFor(mascotState) ?: mascotState.description, style = MaterialTheme.typography.bodySmall)
        StateFeedback(state)
    }
}
