package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.LocalCandidates

/** Source fragments only. No dictionary, stopword deletion, inference or synonym expansion. */
object QuestionKeywordFragments {
    class Fragment internal constructor(val start:Int,val end:Int,val text:String) {
        override fun toString()="QuestionFragment(redacted)"
    }
    class Proposals internal constructor(val values:List<Fragment>,val truncated:Boolean)
    fun propose(question:String):Proposals {
        // A hostile paste must be bounded before Unicode scans or code-point allocation.
        require(question.length<=1024)
        require(LocalCandidates.validUnicode(question)&&question.codePointCount(0,question.length)<=512)
        val cp=question.codePoints().toArray()
        require(cp.none{Character.isISOControl(it)&&it !in listOf(9,10,13)})
        val values=LinkedHashMap<String,Fragment>();var truncated=false
        fun emit(start:Int,end:Int){
            val text=String(cp,start,end-start)
            if(text in values)return
            if(values.size==64){truncated=true;return}
            values[text]=Fragment(start,end,text)
        }
        fun han(p:Int)=Character.UnicodeScript.of(p)==Character.UnicodeScript.HAN
        var at=0
        while(at<cp.size){
            if(!Character.isLetterOrDigit(cp[at])){at++;continue}
            val start=at;val chinese=han(cp[at]);at++
            while(at<cp.size&&Character.isLetterOrDigit(cp[at])&&han(cp[at])==chinese)at++
            if(at-start<=16)emit(start,at)else truncated=true
            if(chinese)for(i in start until at)for(width in listOf(4,3,2,1))if(i+width<=at)emit(i,i+width)
        }
        return Proposals(java.util.Collections.unmodifiableList(values.values.toList()),truncated)
    }
    fun confirmedQuery(terms:List<String>):String {
        require(terms.size in 1..8)
        var points=terms.size-1
        for(term in terms){
            require(term.length in 1..128)
            require(term.none(Char::isWhitespace)&&LocalCandidates.validUnicode(term))
            points+=term.codePointCount(0,term.length);require(points<=64)
            require(term.codePoints().noneMatch(Character::isISOControl))
        }
        require(terms.distinct().size==terms.size)
        return terms.joinToString(" ")
    }
}
