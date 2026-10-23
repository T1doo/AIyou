package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.RecordDateBasis
import com.t1doo.aiyou.domain.UnifiedCaptureRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A memory-only shallow binding; never a SQL proof or authority to cite an original. */
class QuestionSearchBinding(val navigation:Long,val source:LocalRecordUiState,val input:OriginalSearchInput) {
    internal fun same(other:QuestionSearchBinding):Boolean = navigation==other.navigation&&
        source.localSearch===other.source.localSearch&&source.entries===other.source.entries&&
        source.localSearchEpoch==other.source.localSearchEpoch&&
        source.localProcessingVersions===other.source.localProcessingVersions&&
        source.manualTags===other.source.manualTags&&source.manualTagsAvailable==other.source.manualTagsAvailable&&
        source.manualPeople===other.source.manualPeople&&source.manualPeopleAvailable==other.source.manualPeopleAvailable&&
        source.favorites===other.source.favorites&&source.favoritesAvailable==other.source.favoritesAvailable&&
        source.favoritesLoading==other.source.favoritesLoading&&
        source.eventDates===other.source.eventDates&&source.eventDatesAvailable==other.source.eventDatesAvailable&&
        input.ownership==other.input.ownership&&input.query==other.input.query&&input.mode==other.input.mode&&
        input.from==other.input.from&&input.through==other.input.through&&input.dateBasis==other.input.dateBasis&&
        input.tag==other.input.tag&&input.person==other.input.person&&input.onlyFavorites==other.input.onlyFavorites&&
        input.onlyImages==other.input.onlyImages
    override fun toString()="QuestionSearchBinding(redacted)"
}
enum class QuestionDateChoice { UNDECIDED, CURRENT, UNLIMITED, RANGE }
class QuestionKeywordUi(val session:Long,val revision:Long,val binding:QuestionSearchBinding,
    val question:String="",val proposals:QuestionKeywordFragments.Proposals=QuestionKeywordFragments.propose(""),
    val terms:String="",val dateChoice:QuestionDateChoice=QuestionDateChoice.UNDECIDED,
    val from:String=binding.input.from,val through:String=binding.input.through,
    val dateBasis:RecordDateBasis=binding.input.dateBasis,val error:String?=null,
    val rejectedQuestion:Boolean=false,val rejectedTerms:Boolean=false,
    val rejectedFrom:Boolean=false,val rejectedThrough:Boolean=false,internal val lease:Any=Any()) {
    internal fun next(question:String=this.question,proposals:QuestionKeywordFragments.Proposals=this.proposals,
        terms:String=this.terms,dateChoice:QuestionDateChoice=this.dateChoice,from:String=this.from,
        through:String=this.through,dateBasis:RecordDateBasis=this.dateBasis,error:String?=null,
        rejectedQuestion:Boolean=this.rejectedQuestion,rejectedTerms:Boolean=this.rejectedTerms,
        rejectedFrom:Boolean=this.rejectedFrom,rejectedThrough:Boolean=this.rejectedThrough)=
        QuestionKeywordUi(session,Math.addExact(revision,1),binding,question,proposals,terms,dateChoice,from,through,dateBasis,error,rejectedQuestion,rejectedTerms,rejectedFrom,rejectedThrough,lease)
    override fun toString()="QuestionKeywordUi(redacted)"
}
data class QuestionKeywordCommit(val query:String,val from:String,val through:String,val basis:RecordDateBasis) {
    override fun toString()="QuestionKeywordCommit(redacted)"
}

/** All entry points arbitrate navigation -> helper -> search; no deferred handoff after UI-null. */
internal class QuestionKeywordController(private val navigation:SearchNavigationGate,
    private val currentBinding:()->QuestionSearchBinding?,private val retireSearch:()->Unit,
    private val commit:(QuestionKeywordCommit)->Boolean) {
    private val lock=Any()
    private val mutable=MutableStateFlow<QuestionKeywordUi?>(null)
    val state=mutable.asStateFlow()
    private var serial=0L
    private var disposed=false
    private var foreground=false
    private var confirming=false
    private fun work(action:()->Unit){navigation.arbitrate{
        if(!Thread.holdsLock(lock))synchronized(lock){action()}
    }}
    private fun current(u:QuestionKeywordUi):Boolean = !disposed&&foreground&&!confirming&&mutable.value===u&&
        navigation.active&&navigation.generation==u.binding.navigation&&currentBinding()?.let(u.binding::same)==true
    private fun retireLocked(){serial=Math.addExact(serial,1);if(mutable.value!=null){retireSearch();mutable.value=null}}
    fun open()=work{
        if(disposed||!foreground||confirming||mutable.value!=null)return@work
        val binding=currentBinding()?:return@work
        if(!navigation.active||navigation.generation!=binding.navigation)return@work
        retireSearch();serial=Math.addExact(serial,1);mutable.value=QuestionKeywordUi(serial,0,binding)
    }
    fun foreground(value:Boolean)=work{foreground=value;if(!value)retireLocked()}
    fun retire()=navigation.arbitrate{
        // A source-location publication already holds search while advancing navigation.
        // An open helper has synchronously invalidated that search, so this path is UI-null.
        // Do not acquire a helper mutex after search when there is no helper to retire.
        if(mutable.value!=null)work{retireLocked()}
    }
    fun dispose()=work{disposed=true;foreground=false;retireLocked()}
    fun sync()=work{mutable.value?.let{if(!current(it))retireLocked()}}
    fun cancel(u:QuestionKeywordUi)=work{if(mutable.value?.let{it.session==u.session&&it.lease===u.lease}==true)retireLocked()}
    private fun change(u:QuestionKeywordUi,action:()->QuestionKeywordUi)=work{
        if(!current(u)){if(mutable.value===u)retireLocked();return@work}
        mutable.value=action()
    }
    fun question(u:QuestionKeywordUi,value:String)=change(u){
        try{u.next(question=value,proposals=QuestionKeywordFragments.propose(value),terms="",dateChoice=QuestionDateChoice.UNDECIDED,rejectedQuestion=false,rejectedTerms=false)}
        catch(_:IllegalArgumentException){u.next(error="问题最多512个字符，不能含控制字符或不完整字符；没有截断问题。请重新编辑问题。",rejectedQuestion=true)}
    }
    fun terms(u:QuestionKeywordUi,value:String)=change(u){
        if(value.length>128||!com.t1doo.aiyou.model.LocalCandidates.validUnicode(value)||value.codePoints().anyMatch(Character::isISOControl))
            u.next(error="关键词最多64字、8词，不能含控制字符；没有截断输入。请重新编辑关键词。",rejectedTerms=true)
        else u.next(terms=value,rejectedTerms=false)
    }
    fun choose(u:QuestionKeywordUi,fragment:QuestionKeywordFragments.Fragment)=change(u){
        if(u.proposals.values.none{it===fragment})return@change u
        val selected=u.terms.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}.toMutableList()
        if(fragment.text in selected)selected.remove(fragment.text)else selected.add(fragment.text)
        try{if(selected.isNotEmpty())QuestionKeywordFragments.confirmedQuery(selected);u.next(terms=selected.joinToString(" "),rejectedTerms=false)}
        catch(_:IllegalArgumentException){u.next(error="最多选择8词，含空格总计64个字符；可以直接编辑关键词。")}
    }
    fun dateChoice(u:QuestionKeywordUi,value:QuestionDateChoice)=change(u){when(value){
        QuestionDateChoice.CURRENT->u.next(dateChoice=value,from=u.binding.input.from,through=u.binding.input.through,dateBasis=u.binding.input.dateBasis,rejectedFrom=false,rejectedThrough=false)
        QuestionDateChoice.UNLIMITED->u.next(dateChoice=value,from="",through="",rejectedFrom=false,rejectedThrough=false)
        else->u.next(dateChoice=value)
    }}
    fun from(u:QuestionKeywordUi,value:String)=change(u){if(value.length>10)u.next(error="日期按YYYY-MM-DD输入；没有截断输入。",rejectedFrom=true)else u.next(from=value,dateChoice=QuestionDateChoice.RANGE,rejectedFrom=false)}
    fun through(u:QuestionKeywordUi,value:String)=change(u){if(value.length>10)u.next(error="日期按YYYY-MM-DD输入；没有截断输入。",rejectedThrough=true)else u.next(through=value,dateChoice=QuestionDateChoice.RANGE,rejectedThrough=false)}
    fun basis(u:QuestionKeywordUi,value:RecordDateBasis)=change(u){u.next(dateBasis=value,dateChoice=QuestionDateChoice.UNDECIDED)}
    fun confirm(u:QuestionKeywordUi)=work{
        if(!current(u)){if(mutable.value===u)retireLocked();return@work}
        if(u.dateBasis==RecordDateBasis.EVENT&&!u.binding.source.eventDatesAvailable){
            mutable.value=u.next(error="人工事件日期尚未完整读取，未发起查找。请切换为捕获日期，或先重试事件日期读取。");return@work
        }
        val request=try{
            require(u.error==null)
            require(!u.rejectedQuestion&&!u.rejectedTerms)
            require(!u.rejectedFrom&&!u.rejectedThrough)
            require(u.question.isNotBlank())
            val terms=u.terms.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
            val query=QuestionKeywordFragments.confirmedQuery(terms)
            require(u.dateChoice!=QuestionDateChoice.UNDECIDED)
            require(u.dateChoice!=QuestionDateChoice.RANGE||u.from.isNotEmpty()||u.through.isNotEmpty())
            UnifiedCaptureRange.parse(u.from,u.through)
            require(u.dateBasis!=RecordDateBasis.EVENT||u.binding.source.eventDatesAvailable)
            QuestionKeywordCommit(query,u.from,u.through,u.dateBasis)
        }catch(_:IllegalArgumentException){mutable.value=u.next(error="请核对关键词，并明确日期依据及沿用、不限或手工范围；无效条件没有发起查找。");return@work}
        confirming=true
        // Keep the modal blocking search until the one atomic canonical-input callback is done.
        try{
            val applied=try{commit(request)}catch(_:Exception){false}
            mutable.value=if(applied)null else u.next(error="检索条件未能一次应用，未开始新查找。请取消后重新打开选词；原记录未改。")
        }finally{serial=Math.addExact(serial,1);confirming=false}
    }
}
