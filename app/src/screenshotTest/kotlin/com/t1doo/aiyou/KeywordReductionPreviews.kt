package com.t1doo.aiyou

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.records.*

@Composable private fun ReductionSpecimen(ready:Boolean){
    val input=OriginalSearchInput("合成蓝色长绳 尚未完成的悬挂安排",from="2026-10-01",through="2026-10-23",tag="合成人工标签",person="合成人物称呼",onlyFavorites=true,onlyImages=true)
    val binding=QuestionSearchBinding(0,LocalRecordUiState(),input)
    val initial=checkNotNull(KeywordReductionDraft.from(input.query))
    val draft=if(ready)checkNotNull(initial.toggle(1))else initial
    val ui=QuestionKeywordUi(1,0,binding,dateChoice=QuestionDateChoice.CURRENT,reduction=draft)
    AIyouTheme{KeywordReductionContent(ui,{_,_->},{},{},rememberScrollState(if(ready)Int.MAX_VALUE else 0))}
}
@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun KeywordReductionInputRender(){ReductionSpecimen(false)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun KeywordReductionConfirmRender(){ReductionSpecimen(true)}
