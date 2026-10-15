package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*

internal class SourceTagFixture : AutoCloseable {
    val base=LocalCandidateFixture()
    val db get()=base.db
    val source=SourceVersion("source",2)
    val original="合成🙂原文，不推断偏好。\n标签：#旅行 #生活"
    var tags=SqlManualTagRepository(base.gateway,LocalCandidateFixture.KEY);private set
    var store=SqlSourceTagRepository(base.gateway,tags);private set
    init { base.revise(original) }
    fun preview()=store.preview(source)
    fun request(p:SourceTagPreview=preview(),index:Int=0,mutation:String="synthetic-apply",at:Long=40)=SourceTagRequest(p,p.proposals[index],mutation,at)
    fun bytes()=db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId='source'").single().single() as ByteArray
    fun epoch()=db.read("SELECT epoch FROM local_search_epoch WHERE slot=1").single().single() as Long
    fun reopen() { base.gateway.stopAndAwaitIdle();tags.wipeKey();base.reopen();tags=SqlManualTagRepository(base.gateway,LocalCandidateFixture.KEY);store=SqlSourceTagRepository(base.gateway,tags) }
    override fun close() { base.gateway.stopAndAwaitIdle();tags.wipeKey();base.close() }
}
