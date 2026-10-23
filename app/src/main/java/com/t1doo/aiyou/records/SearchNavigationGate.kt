package com.t1doo.aiyou.records

/** The navigation arbitration used by the real UI and host concurrency regressions. */
internal class SearchNavigationGate(private val changed:(Long)->Unit={}) {
    private val lock=Any()
    private var value=0L
    private var alive=true
    val generation get()=synchronized(lock){value}
    var active:Boolean
        get()=synchronized(lock){alive}
        set(v)=synchronized(lock){alive=v}
    fun advance()=synchronized(lock){value=Math.addExact(value,1);changed(value)}
    fun tryAdvance(expected:Long):Boolean=synchronized(lock){
        if(!alive||value!=expected)false else{value=Math.addExact(value,1);changed(value);true}
    }
    fun publish(expected:Long,action:()->Unit)=synchronized(lock){if(alive&&value==expected)action()}
    /** Always enters navigation before a search lock, including failed-location rollback. */
    fun arbitrate(action:()->Unit)=synchronized(lock){action()}
    /** Reject publication reentry BEFORE acquiring navigation, not later inside a search lock. */
    fun inputAction(expected:Long,search:LocalOriginalSearchController,allowed:()->Boolean,action:()->Unit){
        if(search.inputCallbackReentrant())return
        publish(expected){if(allowed())action()}
    }
    fun inputDecision(expected:Long,search:LocalOriginalSearchController,allowed:()->Boolean,action:()->Boolean):Boolean{
        if(search.inputCallbackReentrant())return false
        var result=false;publish(expected){if(allowed())result=action()};return result
    }
}
