package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.SelectedQuestionAuthorizationRepository
import com.t1doo.aiyou.domain.textConnection
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class QuestionConnectionsState(val session:Long=0,val busy:Boolean=false,
    val configurations:List<ModelConnectionConfiguration> = emptyList(),val profiles:List<ModelCapabilityProfile> = emptyList(),
    val selected:ProductionQuestionBinding?=null,val error:String?=null) {
    override fun toString()="QuestionConnectionsState(busy=$busy, redacted)"
}

/** Private binding installed from the admitted store, never from UI supplied credentials/body/gates. */
internal class ProductionQuestionBinding internal constructor(val configuration:ModelConnectionConfiguration,
    val owner:SelectedQuestionAuthorizationRepository,val clock:Clock,
    private val runtime:ProductionQuestions,private val records:EntryRepository,private val ai:ProductionRecordAi) {
    val connection=configuration.textConnection(TextPurpose.CONVERSATION)
    fun isCurrent()=runtime.current(this)
    fun unavailableNotice():String?=ai.unavailableNotice()
    fun newExchange(permit:SelectedQuestionSendPermit,executionClock:()->java.time.Instant):com.t1doo.aiyou.domain.SelectedQuestionSendExchange {
        check(isCurrent()&&unavailableNotice()==null&&permit.review.connection==connection){"Question binding unavailable"}
        check(records.selectedQuestionAuthorizations===owner){"Question owner changed"}
        return ai.openQuestion(records,permit,executionClock)
    }
    override fun toString()="ProductionQuestionBinding(redacted)"
}

/** One VM's question directory and actual controller borrowers. No data/model work occurs in a UI lock. */
class ProductionQuestions internal constructor(private val currentRecords:()->LocalRecordUiState,
    private val enqueue:(suspend (EntryRepository)->Unit)->Boolean,private val ai:ProductionRecordAi,
    private val clock:Clock) {
    private val lock=Any()
    private val ids=AtomicLong()
    @Volatile private var closed=false
    @Volatile private var active=false
    @Volatile private var records:EntryRepository?=null
    private val mutable=MutableStateFlow(QuestionConnectionsState())
    internal val state=mutable.asStateFlow()
    private val controllers=mutableSetOf<SelectedQuestionConsentController>()

    internal fun bindOwner(store:EntryRepository)=synchronized(lock) {
        check(!closed&&(records==null||records===store)){"Question records owner changed"};records=store
    }
    private fun live(session:Long):Boolean {
        val store=records?:return false;val local=currentRecords()
        return !closed&&active&&mutable.value.session==session&&local.ready&&!local.opening&&local.error==null&&
            local.modelConnectionOwner===store.modelConnections&&local.modelCapabilityOwner===store.modelCapabilities&&
            local.modelSecretOwner===store.localModelConnections?.secrets&&
            local.selectedQuestionAuthorizationOwner===store.selectedQuestionAuthorizations
    }
    internal fun unavailableNotice()=ai.unavailableNotice()
    internal fun current(binding:ProductionQuestionBinding)=mutable.value.let {
        live(it.session)&&it.selected===binding
    }
    internal fun open():Long {
        if(closed)return 0
        if(active&&live(mutable.value.session))return mutable.value.session
        active=true
        val session=ids.incrementAndGet();mutable.value=QuestionConnectionsState(session,busy=true)
        load(session)
        return session
    }
    internal fun reload(session:Long) {
        if(!live(session)||mutable.value.busy||sending())return
        retireControllers()
        mutable.value=QuestionConnectionsState(session,busy=true)
        load(session)
    }
    private fun load(session:Long) {
        if(!live(session)){mutable.value=QuestionConnectionsState(session,error="本机连接及资料尚未就绪，请重新打开。");return}
        if(!enqueue {store->
            if(!live(session)||records!==store)return@enqueue
            try {
                val configs=checkNotNull(store.modelConnections).configurations()
                val profiles=checkNotNull(store.modelCapabilities).profiles()
                if(live(session))mutable.value=QuestionConnectionsState(session,configurations=configs,profiles=profiles)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(live(session))mutable.value=QuestionConnectionsState(session,error="连接或能力档案读取失败；未创建请求，请重试读取。")}
        })if(live(session))mutable.value=QuestionConnectionsState(session,error="资料队列已关闭；未创建请求，请重新打开。")
    }
    internal fun select(session:Long,configuration:ModelConnectionConfiguration) {
        val ui=mutable.value;val store=records?:return
        if(!live(session)||ui.busy||ui.error!=null||configuration !in ui.configurations||configuration.secretRef==null||
            sending()||
            ModelConfigurationPurpose.CONVERSATION !in configuration.details.purposes||
            ui.profiles.singleOrNull{it.connection==configuration}?.observation?.state!=CapabilityState.SUPPORTED)return
        if(ui.selected?.configuration==configuration)return
        val owner=store.selectedQuestionAuthorizations?:return
        val binding=ProductionQuestionBinding(configuration,owner,clock,this,store,ai)
        retireControllers()
        if(live(session))mutable.value=ui.copy(selected=binding)
    }
    /** Registration precedes every UI action. Retiring controllers remain owned until real exit. */
    internal fun own(controller:SelectedQuestionConsentController) {
        val accepted=synchronized(lock){if(closed||controllers.size>=4)false else controllers.add(controller)}
        if(!accepted){
            controller.close()
            if(!closed)mutable.value=mutable.value.copy(selected=null,error="上个问答仍在停止，请等待后重新读取连接；未创建新请求。")
            return
        }
        controller.whenDrained{synchronized(lock){controllers.remove(controller)}}
    }
    private fun retireControllers() {
        val old=synchronized(lock){controllers.toList()}
        old.forEach{it.close()}
    }
    private fun sending()=synchronized(lock){controllers.any{it.state.value.stage in setOf(
        QuestionConsentStage.PREPARING,QuestionConsentStage.AUTHORIZING,QuestionConsentStage.RUNNING,QuestionConsentStage.WITHDRAWING)}}
    internal fun leave() {
        active=false
        mutable.value=QuestionConnectionsState(ids.incrementAndGet())
        retireControllers()
    }
    internal fun markDisposed() {
        synchronized(lock){closed=true;active=false;records=null}
        mutable.value=QuestionConnectionsState(ids.incrementAndGet())
        retireControllers()
    }
    internal suspend fun closeAndJoin() {
        markDisposed()
        val pending=synchronized(lock){controllers.toList()}
        pending.forEach{it.closeAndJoin()}
    }
    override fun toString()="ProductionQuestions(redacted)"
}
