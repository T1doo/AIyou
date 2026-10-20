package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.ReminderEnvelope
import com.t1doo.aiyou.ui.AppAccessPhase
import com.t1doo.aiyou.ui.AppAccessState

/** One in-memory navigation request. It is never an alarm ticket or a publication permit. */
internal class ReminderClickNavigation {
    class Request internal constructor(val envelope:ReminderEnvelope,val restoreGeneration:Long,
        internal val initialSession:Long?) {
        internal var authenticationStarted=false
        internal var readingSession:Long?=null
        internal var routeGeneration:Long?=null
    }
    private val guard=Any()
    private var pending:Request?=null
    fun replace(envelope:ReminderEnvelope?,access:AppAccessState,restoreGeneration:Long)=synchronized(guard){
        pending=envelope?.let{Request(it,restoreGeneration,access.session.takeIf{access.canReadRecords})}
    }
    fun clear()=synchronized(guard){pending=null}
    fun currentForRead(access:AppAccessState,routeGeneration:Long?=0):Request?=synchronized(guard){
        val request=pending?:return@synchronized null
        val bound=request.readingSession?:request.initialSession
        if(bound!=null&&(!access.canReadRecords||access.session!=bound) || access.phase==AppAccessPhase.FAILED ||
            access.phase==AppAccessPhase.LOCKED&&request.authenticationStarted){pending=null;return@synchronized null}
        if(access.phase==AppAccessPhase.AUTHENTICATING)request.authenticationStarted=true
        if(!access.canReadRecords||routeGeneration==null)return@synchronized null
        if(request.routeGeneration?.let{it!=routeGeneration}==true){pending=null;return@synchronized null}
        request.routeGeneration=routeGeneration
        request.readingSession=access.session
        request
    }
    fun finish(request:Request,session:Long,access:AppAccessState,routeGeneration:Long?=0):Boolean=synchronized(guard){
        if(pending!==request || request.readingSession!=session || routeGeneration==null || request.routeGeneration!=routeGeneration || !access.canReadRecords || access.session!=session)return@synchronized false
        pending=null;true
    }
}
