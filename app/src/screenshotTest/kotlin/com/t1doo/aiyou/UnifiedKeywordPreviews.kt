package com.t1doo.aiyou

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*

/** Synthetic display data, never a functioning store, inference port, or navigation authority. */
@Composable private fun UnifiedKeywordSpecimen(firstItem:Int){
    val entry=StoredEntry("00000000-0000-4000-8000-000000000001",1,"合成：我偏好淡茶，散步后也喜欢清水。",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    val source=SourceVersion(entry.id,1);val hash=OriginalExports.hash(entry.originalText)
    val binding=UnifiedKeywordBinding(source,hash,0,55,"UTC",entry.policy)
    val audio=AttachmentIntent("00000000-0000-4000-8000-000000000002",entry.id,1,"audio/wav","0".repeat(64),108)
    val image=AttachmentIntent("00000000-0000-4000-8000-000000000003",entry.id,1,"image/png","0".repeat(64),68)
    val preview=SourceMemoryPreview(source,hash,0,entry.originalText,55,"UTC",entry.policy,1)
    val preference=SourcePreferenceSnapshot("00000000-0000-4000-8000-000000000004",source,hash,0,2,PreferenceAvailability.AVAILABLE,55,55,"UTC",entry.policy,
        "00000000-0000-4000-8000-000000000005","合成口味",listOf(PreferenceContentVersion(1,PreferenceContent(SourceMemoryRules.reference(preview,"我偏好淡茶")),55)),listOf(PreferenceCheckpoint(1,PreferenceReview.ACCEPTED,null,55)))
    val evidence=listOf(UnifiedKeywordEvidence.Original(binding,entry),
        UnifiedKeywordEvidence.Audio(binding,AudioTranscriptSnapshot("00000000-0000-4000-8000-000000000006",AudioTranscriptSource(audio,hash,0,55,"UTC",entry.policy,32),1,false,listOf(AudioTranscriptVersion(1,"合成人工转写：散步后喝淡茶。",55)))),
        UnifiedKeywordEvidence.Image(binding,ImageTextSnapshot("00000000-0000-4000-8000-000000000007",ImageTextSource(image,hash,0,55,"UTC",entry.policy),ImageTextEngine("preview-synthetic","1",true),listOf(ImageTextVersion(1,"合成端口展示：淡茶",false,55)))),
        UnifiedKeywordEvidence.Preference(binding,preference))
    val snapshot=object:UnifiedKeywordSnapshot{override val keyword="淡茶";override val scope=UnifiedKeywordScope.ALL
        override val bindings=listOf(binding);override val groups=listOf(UnifiedKeywordGroup(binding,evidence.sortedBy{it.key}))}
    val owner=object:UnifiedKeywordRepository{
        override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot=error("Render-only fixture")
        override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit):Boolean=error("Render-only fixture")
    }
    val ui=UnifiedKeywordUi(1,owner,keyword="淡茶",snapshot=snapshot)
    val controller=UnifiedKeywordController({LocalRecordUiState()},{},{false})
    AIyouTheme{UnifiedKeywordContent(ui,controller,{false},{_,_->},rememberLazyListState(initialFirstVisibleItemIndex=firstItem))}
}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordHeaderRender(){UnifiedKeywordSpecimen(0)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordResultsRender(){UnifiedKeywordSpecimen(1)}
