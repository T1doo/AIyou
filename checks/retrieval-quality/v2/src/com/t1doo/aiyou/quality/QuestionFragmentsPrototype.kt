package com.t1doo.aiyou.quality

import com.t1doo.aiyou.model.LocalCandidates

/** Nonshipping design probe. No stopword list, dictionary, source content, model or synonym rule. */
internal object QuestionFragmentsPrototype {
    data class Fragment(val start:Int,val end:Int,val text:String)
    data class Proposals(val values:List<Fragment>,val truncated:Boolean)
    fun propose(question:String):Proposals {
        require(LocalCandidates.validUnicode(question))
        val cp=question.codePoints().toArray();require(cp.size<=512)
        require(cp.none{Character.isISOControl(it)&&it !in listOf(9,10,13)})
        val chosen=LinkedHashMap<String,Fragment>();var truncated=false
        fun emit(start:Int,end:Int){
            val text=String(cp,start,end-start)
            if(text in chosen)return
            if(chosen.size==64){truncated=true;return}
            chosen[text]=Fragment(start,end,text)
        }
        fun han(p:Int)=Character.UnicodeScript.of(p)==Character.UnicodeScript.HAN
        var at=0
        while(at<cp.size){
            if(!Character.isLetterOrDigit(cp[at])){at++;continue}
            val start=at;val chinese=han(cp[at]);at++
            while(at<cp.size&&Character.isLetterOrDigit(cp[at])&&han(cp[at])==chinese)at++
            // A long question must not become one huge or unconfirmable candidate chip.
            if(at-start<=16)emit(start,at)
            if(chinese)for(i in start until at)for(width in listOf(4,3,2,1))if(i+width<=at)emit(i,i+width)
        }
        return Proposals(chosen.values.toList(),truncated)
    }
    /** This is an explicit synthetic user choice in probes, not automated term selection. */
    fun confirmedQuery(terms:List<String>):String {
        require(terms.size in 1..8&&terms.distinct().size==terms.size)
        require(terms.all{it.isNotBlank()&&it.none(Char::isWhitespace)&&LocalCandidates.validUnicode(it)&&
            it.codePoints().noneMatch(Character::isISOControl)})
        val query=terms.joinToString(" ");require(query.codePointCount(0,query.length)<=64)
        return query
    }
}
