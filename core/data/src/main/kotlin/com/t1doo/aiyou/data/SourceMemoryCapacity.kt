package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*

/** Existing per-source write contract, shared by all portable/SQL readers. No global scan cap. */
internal object SourceMemoryCapacity {
    fun count(value:Long){if(value !in 0..SourceMemoryRules.MAX_MEMORIES.toLong())throw SourceMemoryLimit()}
    fun portable(rows:List<List<Any?>>){
        val counts=HashMap<SourceVersion,Long>()
        rows.forEach {r->if(r[5]==2L&&r[7]=="READY"){
            val source=SourceVersion(r[1] as String,r[2] as Long)
            val next=Math.addExact(counts[source]?:0L,1);count(next);counts[source]=next
        }}
    }
}
