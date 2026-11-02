package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.SelectedQuestionReply

/** Formatting only, never an authorization. Callers must recheck the actual owner immediately before use. */
internal fun answerCopyText(reply:SelectedQuestionReply,heading:String="模型回答 · 内容待核对"):String {
    require(!reply.noEvidence && reply.paragraphs.isNotEmpty())
    val references=reply.paragraphs.flatMap {it.references}.distinct()
    return buildString {
        append(heading);append("\n引用有效不等于论断得到支持。\n\n")
        for(paragraph in reply.paragraphs) {
            append(paragraph.text)
            for(reference in paragraph.references) append(" [${references.indexOf(reference)+1}]")
            append("\n\n")
        }
        for((index,reference) in references.withIndex()) {
            append("[${index+1}] 原文版本 ${reference.source.revision}\n")
            append(reference.quote);append("\n\n")
        }
    }.trimEnd()
}
