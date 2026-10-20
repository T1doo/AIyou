package com.t1doo.aiyou.records

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private class WavePickerHandle { var launcher:ActivityResultLauncher<Intent>?=null }

/** Same live VM UUID registry ownership as the existing image picker; no app SavedState/URI grant persistence. */
@Composable
fun WaveFilePicker(audio:ControlledCaptureAudio,ui:CaptureAudioUi) {
    val key=ui.filePickerKey ?: return
    val registry=checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val resolver=LocalContext.current.applicationContext.contentResolver
    val handle=remember(key) { WavePickerHandle() }
    DisposableEffect(registry,key,audio) {
        val registered=registry.register(key,ActivityResultContracts.StartActivityForResult()) { result ->
            val uri=result.data?.data?.takeIf { result.resultCode==Activity.RESULT_OK && it.scheme=="content" }
            audio.selectedFile(key,uri?.let { selected -> object:SelectedWaveSource {
                override fun open()=checkNotNull(resolver.openInputStream(selected))
                override fun toString()="SelectedWaveSource(redacted)"
            } })
        }
        handle.launcher=registered
        onDispose { handle.launcher=null;registered.unregister() }
    }
    LaunchedEffect(key) {
        if(audio.claimFile(key))try {
            checkNotNull(handle.launcher).launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
                putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("audio/wav","audio/x-wav","audio/wave","audio/vnd.wave","application/octet-stream"))
                putExtra(Intent.EXTRA_LOCAL_ONLY,true);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false)
            })
        } catch(_:Exception) { audio.selectedFile(key,null) }
    }
}

@Composable
fun OriginalAudioStatus(controller:OriginalAudioController,modifier:Modifier=Modifier) {
    val ui by controller.state.collectAsState()
    if(!ui.busy && ui.error==null)return
    Column(modifier.fillMaxWidth().padding(8.dp).testTag("audio-playback-status")
        .semantics { liveRegion=LiveRegionMode.Polite }) {
        Text(when(ui.phase) {
            OriginalAudioPhase.IDLE -> ui.error.orEmpty()
            OriginalAudioPhase.LOADING -> "正在认证原音和来源"
            OriginalAudioPhase.FOCUS -> "正在准备前台播放"
            OriginalAudioPhase.PLAYING -> "正在播放原音 · ${ui.playedMillis/1000} / ${ui.durationMillis/1000} 秒"
            OriginalAudioPhase.CLOSING -> "正在关闭播放；请等待实际释放确认"
            OriginalAudioPhase.CLOSING_UNKNOWN -> ui.error.orEmpty()
        })
        if(ui.busy) {
            Text("先停止播放，再编辑、保存或修改附件和范围。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=controller::stop,modifier=Modifier.heightIn(min=48.dp).testTag("audio-playback-stop")) {
                Text(if(ui.phase==OriginalAudioPhase.CLOSING_UNKNOWN) "核对停止同一次播放" else "停止播放")
            }
        }
    }
}
