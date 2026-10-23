package com.t1doo.aiyou

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.records.*

/** Actual production body, synthetic display only; no repository, query or model runs here. */
@Composable private fun QuestionKeywordSpecimen(review:Boolean){
    val question="我没有种薄荷吗？小舟说“种了薄荷”，我该查哪条记录？"
    val binding=QuestionSearchBinding(0,LocalRecordUiState(),OriginalSearchInput(tag="合成人工标签",person="合成人物标记"))
    val ui=QuestionKeywordUi(1,1,binding,question,QuestionKeywordFragments.propose(question),"薄荷",QuestionDateChoice.UNDECIDED,
        error=if(review)"请明确日期依据及沿用、不限或手工范围；没有开始查找。"else null)
    AIyouTheme{QuestionKeywordContent(ui,{_,_->},{_,_->},{_,_->},{_,_->},{_,_->},{_,_->},{_,_->},{},{},rememberScrollState(if(review)Int.MAX_VALUE else 0))}
}
@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun QuestionKeywordInputRender(){QuestionKeywordSpecimen(false)}

@PreviewTest
@Preview(name="portrait200",widthDp=320,heightDp=640,fontScale=2f,locale="zh-rCN")
@Preview(name="landscape200",widthDp=640,heightDp=320,fontScale=2f,locale="zh-rCN")
@Composable fun QuestionKeywordReviewRender(){QuestionKeywordSpecimen(true)}
