package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.t1doo.aiyou.model.ProcessingPolicy

@Composable
private fun ComponentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = AIyouColors.surface),
    ) {
        Column(
            Modifier.padding(AIyouDimensions.spaceLg),
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
        shape = CircleShape,
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
    ComponentCard(modifier) {
        Text(capturedAt, style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
        Text("原文", style = MaterialTheme.typography.labelLarge)
        Text(originalText, style = MaterialTheme.typography.bodyLarge)
        attachmentLabel?.let {
            Surface(color = AIyouColors.surfaceMuted, shape = MaterialTheme.shapes.small) {
                Text(it, Modifier.fillMaxWidth().padding(AIyouDimensions.spaceLg), style = MaterialTheme.typography.bodySmall)
            }
        }
        PrivacyChip(policy)
        annotation?.let {
            Text("AI 派生内容", style = MaterialTheme.typography.labelLarge)
            Text(it, style = MaterialTheme.typography.bodyMedium)
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
    onSubmit: (() -> Unit)? = null,
    onImage: (() -> Unit)? = null,
    onAudio: (() -> Unit)? = null,
    state: ComponentState = ComponentState.NORMAL,
    availability: String = "记录保存尚未开放；输入仅用于界面演示",
) {
    val canEdit = state != ComponentState.DISABLED && state != ComponentState.LOADING
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = AIyouColors.surfaceMuted) {
        Column(
            Modifier.padding(AIyouDimensions.spaceLg),
            verticalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                OutlinedButton(onClick = { onModeChange(false) }, enabled = canEdit) {
                    Text(if (!askMode) "记下来 · 已选" else "记下来")
                }
                OutlinedButton(onClick = { onModeChange(true) }, enabled = canEdit) {
                    Text(if (askMode) "问一问 · 已选" else "问一问")
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = canEdit,
                label = { Text(if (askMode) "想问些什么" else "此刻，想记下什么") },
                minLines = 3,
                maxLines = 6,
                isError = state == ComponentState.ERROR,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AIyouDimensions.spaceSm)) {
                OutlinedButton(onClick = { onImage?.invoke() }, enabled = canEdit && onImage != null) { Text("图片") }
                OutlinedButton(onClick = { onAudio?.invoke() }, enabled = canEdit && onAudio != null) { Text("录音") }
                Button(
                    onClick = { onSubmit?.invoke() },
                    enabled = canEdit && text.isNotBlank() && onSubmit != null,
                ) { Text(if (askMode) "提问" else "保存") }
            }
            Text(availability, style = MaterialTheme.typography.bodySmall, color = AIyouColors.textSecondary)
            if (askMode) Text("提问不会自动成为永久记忆", style = MaterialTheme.typography.bodySmall)
            StateFeedback(state)
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
    mascotState: MascotState = MascotState.Idle,
    modifier: Modifier = Modifier,
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
