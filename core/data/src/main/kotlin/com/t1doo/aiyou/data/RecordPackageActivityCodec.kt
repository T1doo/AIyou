package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*

/** Versioned bounded metadata inside the existing authenticated package frames. */
internal object RecordPackageActivityCodec {
    fun encode(a: RecordPackageActivities): ByteArray = PackageWriter(RecordMediaPackages.MAX_METADATA).use { w ->
        w.i32(1); w.i32(a.sourceGenerations.size); a.sourceGenerations.forEach(w::i64)
        w.i32(a.tasks.size)
        a.tasks.forEach { item ->
            val t=item.task; w.i32(item.sourceRecord); w.string(t.id,512); w.i64(t.version); w.string(t.title,3840)
            w.string(t.status.name,16); w.i64(t.createdAtMillis); w.string(t.creationTimeZone,128); w.i64(t.updatedAtMillis)
            date(w,ManualTaskDateInput.from(t.schedule,t.creationTimeZone));w.i32(t.schedule?.offsetSeconds ?: Int.MIN_VALUE)
            w.i32(if(t.sourceReceipt==null) 0 else 1)
            t.sourceReceipt?.let { binding(w,it.binding); w.string(it.requestHash,64) }
        }
        w.i32(a.drafts.size)
        a.drafts.forEach { item ->
            val p=item.proposal; w.i32(item.sourceRecord); w.string(p.id,512); w.i64(p.version); binding(w,p.binding)
            w.string(p.titleInput,3840); date(w,p.dateInput); w.i64(p.createdAtMillis); w.string(p.creationTimeZone,128); w.i64(p.updatedAtMillis)
        }
        w.bytes()
    }
    fun decode(bytes: ByteArray): RecordPackageActivities {
        val r=PackageReader(bytes); require(r.i32()==1)
        val generations=List(r.count(20)) { r.i64().also { require(it>=0) } }
        val tasks=List(r.count(RecordPackageActivities.MAX_ITEMS)) {
            val index=r.i32(); val id=r.string(512); val version=r.i64(); val title=r.string(3840); val status=ManualTaskStatus.valueOf(r.string(16))
            val created=r.i64(); val zone=r.string(128); val updated=r.i64(); val date=date(r);val offset=r.i32()
            val schedule=date.parse()
            require(schedule?.offsetSeconds==(if(offset==Int.MIN_VALUE) null else offset)) // Never reinterpret saved time with different device tzdata.
            val has=r.i32(); require(has in 0..1)
            val receipt=if(has==0) null else SourceTaskReceipt(binding(r),r.string(64))
            RecordPackageTask(ManualTaskSnapshot(id,version,title,status,created,zone,updated,schedule,receipt),index)
        }
        val drafts=List(r.count(RecordPackageActivities.MAX_ITEMS)) {
            val index=r.i32(); val id=r.string(512); val version=r.i64(); val binding=binding(r); val title=r.string(3840); val date=date(r)
            RecordPackageDraft(TaskProposal(id,version,binding,title,date,r.i64(),r.string(128),r.i64()),index)
        }
        r.end(); return RecordPackageActivities(tasks,drafts,generations)
    }
    fun validate(a: RecordPackageActivities, records: List<RecordPackageOriginal>) {
        require(a.sourceGenerations.size==records.size)
        require(records.isNotEmpty() || a.tasks.isNotEmpty() || a.drafts.isNotEmpty())
        fun check(index:Int,b:TaskSourceBinding?) {
            if(index<0) return
            val p=records[index]; require(b!=null && p.sourceId==b.source.sourceId && p.sourceRevision.toLong()==b.source.revision &&
                p.contentHash==b.sourceHash && a.sourceGenerations[index]==b.generation && b.end<=p.originalText.codePointCount(0,p.originalText.length))
        }
        a.tasks.forEach { check(it.sourceRecord,it.task.sourceReceipt?.binding) }; a.drafts.forEach { check(it.sourceRecord,it.proposal.binding) }
    }
    private fun binding(w:PackageWriter,b:TaskSourceBinding) {
        w.string(b.source.sourceId,512);w.i64(b.source.revision);w.string(b.sourceHash,64);w.i64(b.generation);w.i32(b.start);w.i32(b.end)
    }
    private fun binding(r:PackageReader)=TaskSourceBinding(SourceVersion(r.string(512),r.i64()),r.string(64),r.i64(),r.i32(),r.i32())
    private fun date(w:PackageWriter,d:ManualTaskDateInput) {w.string(d.date,40);w.string(d.time,20);w.string(d.timeZone,512);w.string(d.occurrence?.name.orEmpty(),16)}
    private fun date(r:PackageReader)=ManualTaskDateInput(r.string(40),r.string(20),r.string(512),r.string(16).takeIf(String::isNotEmpty)?.let(ManualTaskOccurrence::valueOf))
}
