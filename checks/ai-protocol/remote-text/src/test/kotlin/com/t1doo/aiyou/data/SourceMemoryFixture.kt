package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*

internal class SourceMemoryFixture : AutoCloseable {
    val base=LocalCandidateFixture();val db get()=base.db
    val source=SourceVersion("source",2)
    val original="合成🙂我说“开心”只是引用；没有去咖啡馆。假如以后去旅行，还没有发生。"
    var store=SqlSourceMemoryRepository(base.gateway,LocalCandidateFixture.KEY);private set
    init { base.revise(original) }
    fun content(p:SourceMemoryPreview=store.preview(source),note:String="人工合成备注",kind:SourceMemoryKind=SourceMemoryKind.EVENT,
        assertion:SourceMemoryAssertion=SourceMemoryAssertion.UNCERTAIN)=SourceMemoryContent(SourceMemoryRules.reference(p,p.original),kind,assertion,note)
    fun request(id:String="memory",p:SourceMemoryPreview=store.preview(source),note:String="人工合成备注",mutation:String="create-memory",at:Long=40)=SourceMemoryCreate(id,p,content(p,note),mutation,at)
    fun create(id:String="memory",note:String="人工合成备注")=store.create(request(id=id,note=note,mutation="create-"+id))
    fun change(s:SourceMemorySnapshot,action:SourceMemoryAction,content:SourceMemoryContent?=null,mutation:String="review-"+action.name,at:Long=50)=SourceMemoryMutation(s.id,s.source,s.sourceHash,s.generation,s.version,
        s.current!!.revision,s.current!!.review,action,content,mutation,at)
    fun bytes(id:String="memory")=db.read("SELECT payload FROM local_candidate_batches WHERE id=?",id).single().single() as ByteArray
    fun reopen() { base.gateway.stopAndAwaitIdle();store.wipeKey();base.reopen();store=SqlSourceMemoryRepository(base.gateway,LocalCandidateFixture.KEY) }
    override fun close() { base.gateway.stopAndAwaitIdle();store.wipeKey();base.close() }
}
