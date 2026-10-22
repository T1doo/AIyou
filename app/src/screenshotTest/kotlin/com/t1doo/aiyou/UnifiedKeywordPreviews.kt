package com.t1doo.aiyou

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*

/** Synthetic display data, never a functioning store, inference port, or navigation authority. */
@Composable private fun UnifiedKeywordSpecimen(firstItem:Int,memoryOnly:Boolean=false,dateRange:Boolean=false,tags:Boolean=false,people:Boolean=false,favorites:Boolean=false){
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
    val shown=if(memoryOnly)listOf(UnifiedKeywordEvidence.AcceptedMemory(AcceptedMemoryKeywordHit("00000000-0000-4000-8000-000000000008",binding,2,1,
        SourceMemoryContent(SourceMemoryRules.reference(preview,"我偏好淡茶"),SourceMemoryKind.OCCASION_EMOTION,SourceMemoryAssertion.UNCERTAIN,"合成人工备注：散步后更清醒。"),false,true)))else evidence
    val word=if(memoryOnly)"散步"else"淡茶"
    val range=if(dateRange)UnifiedCaptureRange.parse("1970-01-01","1970-01-01")else UnifiedCaptureRange()
    val snapshot=object:UnifiedKeywordSnapshot{override val keyword=word;override val scope=UnifiedKeywordScope.ALL;override val captureRange=range;override val onlyFavorites=favorites
        override val bindings=listOf(binding);override val groups=listOf(UnifiedKeywordGroup(binding,shown.sortedBy{it.key}))}
    val owner=object:UnifiedKeywordRepository{
        override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot=error("Render-only fixture")
        override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit):Boolean=error("Render-only fixture")
    }
    val tagPage=object:UnifiedTagPage{override val hasNext=true;override val choices=listOf("合成：饮食与散步","合成人工标签"+"茶".repeat(34)).mapIndexed{n,text->object:UnifiedTagChoice{override val id="synthetic-tag-$n";override val name=text}}}
    val peoplePage=object:UnifiedPersonPage{override val hasNext=true;override val choices=listOf("合成人物标记：阿岚","合成人物标记"+"人".repeat(34)).mapIndexed{n,text->object:UnifiedPersonChoice{override val id="synthetic-person-$n";override val name=text}}}
    val ui=UnifiedKeywordUi(1,owner,keyword=word,snapshot=snapshot,from=range.from?.toString().orEmpty(),through=range.through?.toString().orEmpty(),tagPicker=tags,tagPage=if(tags)tagPage else null,personPicker=people,personPage=if(people)peoplePage else null,onlyFavorites=favorites)
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
@Composable fun UnifiedKeywordResultsRender(){UnifiedKeywordSpecimen(3)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordAcceptedMemoryRender(){UnifiedKeywordSpecimen(3,true)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordCaptureDateRender(){UnifiedKeywordSpecimen(1,dateRange=true)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordManualTagsRender(){UnifiedKeywordSpecimen(0,tags=true)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordManualPeopleRender(){UnifiedKeywordSpecimen(0,people=true)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordFavoritesRender(){UnifiedKeywordSpecimen(1,favorites=true)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun UnifiedKeywordReadyImagesRender(){
    AIyouTheme{Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){
        Surface(Modifier.padding(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium){
            Column(Modifier.padding(12.dp)){UnifiedReadyImageFilter(true){}}
        }
    }}
}
