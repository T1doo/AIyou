package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class FactRecapUiState(val date: String="",val zone: String="UTC",val period: FactRecapPeriod=FactRecapPeriod.DAY,
    val loading: Boolean=false,val snapshot: FactRecapSnapshot?=null,val error: String?=null) {
    override fun toString()="FactRecapUiState(loading=$loading, redacted)"
}

/** Explicit user requests on the existing records IO actor; nothing scheduled or persisted. */
class FactRecapController internal constructor(private val current: ()->LocalRecordUiState,
    private val publish: ((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue: (suspend(EntryRepository)->Unit)->Boolean,private val clock: Clock=Clock.systemDefaultZone()) {
    private val generation=AtomicLong()
    private val ownershipGate=Any()
    @Volatile private var disposed=false
    private fun ui()=current().factRecap
    private fun available()=!disposed && current().let { it.ready && !it.opening && it.error==null && it.supportsFactRecaps }
    private fun change(action:(FactRecapUiState)->FactRecapUiState)=publish { if(disposed) it else it.copy(factRecap=action(it.factRecap ?: FactRecapUiState())) }
    fun open():Unit=synchronized(ownershipGate) { if(!available()) return;generation.incrementAndGet();change { FactRecapUiState(LocalDate.now(clock).toString(),clock.zone.id) } }
    fun input(date: String,zone: String,period: FactRecapPeriod):Unit=synchronized(ownershipGate) {
        if(ui()==null || date.length>10 || zone.length>64) return
        generation.incrementAndGet();change { it.copy(date=date,zone=zone,period=period,loading=false,snapshot=null,error=null) }
    }
    fun cancel():Unit=synchronized(ownershipGate) { if(ui()==null) return;generation.incrementAndGet();change { it.copy(loading=false,snapshot=null,error=null) } }
    fun close():Unit=synchronized(ownershipGate) { generation.incrementAndGet();publish { it.copy(factRecap=null) } }
    fun invalidate():Unit=synchronized(ownershipGate) {
        if(ui()?.snapshot==null && ui()?.loading!=true) return
        generation.incrementAndGet();change { it.copy(loading=false,snapshot=null,error="记录或来源已变化，请重新生成回顾。") }
    }
    private fun changeOwned(token:Long,action:(FactRecapUiState)->FactRecapUiState):Unit=synchronized(ownershipGate) {
        // Recheck inside the StateFlow CAS as readiness can also change independently of this controller.
        if(owns(token)) publish { local -> if(disposed || generation.get()!=token || !local.ready || local.opening ||
            local.error!=null || !local.supportsFactRecaps || local.factRecap==null) local
            else local.copy(factRecap=action(local.factRecap)) }
    }
    private fun retireOwned(token:Long,snapshot:FactRecapSnapshot?=null):Unit=synchronized(ownershipGate) {
        if(owns(token) && (snapshot==null || ui()?.snapshot===snapshot)) invalidate()
    }
    fun generate() {
        val (token,range)=synchronized(ownershipGate) {
            val u=ui() ?: return;if(!available() || u.loading) return
            val range=try { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(u.date));FactRecapRange(LocalDate.parse(u.date),u.period,u.zone) }
                catch(_:Exception){change { it.copy(snapshot=null,error="请填写 1900–2100 年的有效日期和时区，例如 Asia/Shanghai 或 UTC。") };return}
            val token=generation.incrementAndGet();change { it.copy(loading=true,snapshot=null,error=null) };token to range
        }
        if(!enqueue { store ->
            if(!owns(token)) return@enqueue
            try {
                val owner=checkNotNull(store.factRecaps);val snapshot=owner.capture(range)
                if(!owns(token)) return@enqueue
                val accepted=owner.publishCurrent(snapshot) { changeOwned(token) { it.copy(loading=false,snapshot=snapshot,error=null) } }
                if(!accepted) retireOwned(token)
            } catch(e:CancellationException){throw e}
            catch(e:Exception){changeOwned(token) { it.copy(loading=false,snapshot=null,error=if(e is FactRecapTooBroad)
                "该范围超过完整回顾上限（1000 条、200 万字、20000 张图片）；请缩小到单日。没有显示部分结果。" else "本机回顾暂不可读取，未生成不完整结果。请重试。") }}
        }) changeOwned(token) { it.copy(loading=false,snapshot=null,error="本机队列暂不可用，请重试。") }
    }
    fun source(card: FactRecapCard,onOpen: ()->Unit) {
        val (token,snapshot)=synchronized(ownershipGate) {
            val s=ui()?.snapshot ?: return
            if(!available() || s.cards.none { it===card }) return
            generation.get() to s
        }
        if(!enqueue { store ->
            if(!owns(token) || ui()?.snapshot!==snapshot) return@enqueue
            try { if(!checkNotNull(store.factRecaps).resolve(snapshot,card.source) {
                synchronized(ownershipGate) { if(owns(token) && ui()?.snapshot===snapshot) onOpen() }
            }) retireOwned(token,snapshot) }
            catch(e:CancellationException){throw e} catch(_:Exception){retireOwned(token,snapshot)}
        }) retireOwned(token,snapshot)
    }
    /** Recheck on reader return without replacing the list or its scroll position. */
    fun validate() {
        val (token,snapshot)=synchronized(ownershipGate) { generation.get() to (ui()?.snapshot ?: return) }
        if(!enqueue { store -> if(owns(token)) try {
            if(!checkNotNull(store.factRecaps).publishCurrent(snapshot) {}) retireOwned(token,snapshot)
        } catch(e:CancellationException){throw e} catch(_:Exception){retireOwned(token,snapshot)} }) retireOwned(token,snapshot)
    }
    /** Called on the existing IO actor after attachment IO releases its media lock. Pure reads retain the view. */
    fun checkCurrent(store: EntryRepository) {
        val (token,snapshot)=synchronized(ownershipGate) { generation.get() to (ui()?.snapshot ?: return) }
        try { if(!checkNotNull(store.factRecaps).publishCurrent(snapshot){}) retireOwned(token,snapshot) }
        catch(e:CancellationException){throw e} catch(_:Exception){retireOwned(token,snapshot)}
    }
    private fun owns(token: Long)=available() && generation.get()==token && ui()!=null
    fun dispose():Unit=synchronized(ownershipGate) { disposed=true;generation.incrementAndGet();publish { it.copy(factRecap=null) } }
}

/** Synchronously retire visible private excerpts when the records owner's public source changes. */
internal fun reconcileFactRecap(before: LocalRecordUiState,after: LocalRecordUiState): LocalRecordUiState {
    val u=after.factRecap ?: return after;val s=u.snapshot ?: return after
    if(before.entries===after.entries && before.manualTags===after.manualTags && before.ready==after.ready &&
        before.opening==after.opening && before.error==after.error && before.manualTagsAvailable==after.manualTagsAvailable &&
        before.supportsFactRecaps==after.supportsFactRecaps) return after
    val ids=after.entries.filter { it.capturedAtMillis>=s.range.startMillis && it.capturedAtMillis<s.range.endMillis }
    val valid=after.supportsFactRecaps && after.ready && !after.opening && after.error==null &&
        after.manualTagsAvailable && ids.size==s.cards.size && s.cards.all { card ->
        ids.any { it.id==card.source.sourceId && it.currentRevision.toLong()==card.source.revision &&
            it.capturedAtMillis==card.capturedAtMillis && it.captureTimeZone==card.captureZone && it.policy==card.policy && OriginalExports.hash(it.originalText)==card.contentHash } &&
            after.manualTagsAvailable && after.manualTags[card.source.sourceId]?.let { it.version==card.tags.version && it.names==card.tags.names }
                .let { it==true || card.tags.version==0L && !after.manualTags.containsKey(card.source.sourceId) }
    }
    return if(valid) after else after.copy(factRecap=u.copy(snapshot=null,loading=false,error="记录或来源已变化，请重新生成回顾。"))
}
