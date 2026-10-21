package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID

/** Fixed logical columns, never executable SQL or filesystem paths supplied by a document. */
internal data class BackupTableSpec(val name:String,val columns:String,val maximum:Int) {
    val names=columns.split(',')
}
internal object FullBackupTables {
    // Order matters: all final source/access values precede candidate inserts (source invalidation triggers).
    val specs=listOf(
        BackupTableSpec("entries","id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy,isFavorite,favoriteVersion,lastFavoriteMutationId,lastFavoriteSourceRevision",256),
        BackupTableSpec("entry_revisions","entryId,revision,originalText,contentHash,createdAtMillis",4096),
        BackupTableSpec("remote_source_access","entryId,generation,deleted,connection,consent",256),
        BackupTableSpec("attachments","id,entryId,revision,mediaType,sha256,byteSize,state",256),
        BackupTableSpec("entry_manual_tags","entryId,version,lastMutationId,ciphertext",256),
        BackupTableSpec("entry_manual_people","entryId,version,lastMutationId,ciphertext",256),
        BackupTableSpec("entry_event_dates","entryId,version,lastMutationId,lastSourceRevision,localDate,timeZone",256),
        BackupTableSpec("manual_tasks","id,version,lastMutationId,ciphertext",4096),
        BackupTableSpec("local_candidate_batches","id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload",4096),
        BackupTableSpec("capture_draft","slot,id,version,text",1),
        BackupTableSpec("local_jobs","id,entryId,sourceRevision,operation,state",16384),
        BackupTableSpec("permanent_erasure_receipts","entryId,sourceRevision,generation,mutationId,phase,plan,packageProof",4096),
        BackupTableSpec("record_package_receipts","id,entryId,sourceRevision,operation,state",16384),
    )
    // s=text, l=integer, b=portable bytes, ?=nullable text, n=required NULL (never remote authority).
    val kinds=listOf("sllssll?l","slssl","sllnn","sslssls","slsb","slsb","slsl??","slsb","sslslllsslb","lsls","sslss","sllssss","sslss")
    val excluded=setOf("remote_text_jobs","text_annotations","local_search_fts","local_search_documents","local_search_epoch","owned_file_creations","model_configurations","model_capability_profiles","reminder_runtime_meta")
    val receipts=setOf("PACKAGE_ORIGIN","PACKAGE_RESTORE","PACKAGE_TASK_RESTORE","PACKAGE_MEDIA_INTENT",
        "CAPTURE_CURRENT","CAPTURE_SAVED","CAPTURE_DISCARDED","CAPTURE_REMOVED","FULL_BACKUP_IMPORT_V8","FULL_BACKUP_IMPORT_V9","FULL_BACKUP_IMPORT_V10","FULL_BACKUP_IMPORT_V11","FULL_BACKUP_IMPORT_V12")
    val retainedJobs=setOf("ORIGINAL_EDIT_COMMIT","PACKAGE_ORIGIN","PACKAGE_RESTORE")
    val excludedJobs=setOf("LOCAL_INDEX","LOCAL_TAG_HINTS","LOCAL_SUMMARY","LOCAL_EXCERPTS","RECORD_AUTHORIZATION_V1","SELECTED_QUESTION_SEND_V1")
}
internal data class FullBackupSourceProof(val source:SourceVersion,val hash:String,val generation:Long,val original:String,
    val captured:Long,val zone:String,val policy:ProcessingPolicy,val deleted:Boolean)
internal class FullBackupData(val id:String,val created:Long,val tables:List<List<List<Any?>>>,
    val images:List<RecordPackageImage>,val edits:List<OriginalEditDraft>,val capture:CaptureDraft?,
    val audios:List<RecordPackageAudio> = emptyList()) : FullBackupSnapshot {
    val media:List<RecordPackageAttachment> get()=images+audios
    val version:Int get()=if(tables[8].any {it[5]==5L} || tables[12].any {it[3]=="FULL_BACKUP_IMPORT_V12"}) 12 else if(tables[8].any {it[5]==4L} || tables[12].any {it[3]=="FULL_BACKUP_IMPORT_V11"}) 11 else if(tables[8].any { it[5]==3L } || tables[12].any { it[3]=="FULL_BACKUP_IMPORT_V10" }) 10 else if(audios.isNotEmpty() || capture?.audio!=null || tables[12].any { it[3]=="FULL_BACKUP_IMPORT_V9" }) 9 else 8
    private var released=false
    override val summary:FullBackupSummary
        get() { check(!released)
            return FullBackupSummary(id,created,tables[0].size,tables[2].count { it[2]==1L },tables[1].size,
                images.size,images.sumOf { it.byteSize },edits.count { it.state==OriginalEditDraftState.ACTIVE },
                tables[7].size,tables[8].size,(if(capture!=null) 1 else 0)+tables[9].size,tables[11].size,audios.size,audios.sumOf { it.byteSize })
        }
    fun checkLive() { check(!released) }
    override fun release() { if(!released) { released=true;tables.flatten().flatten().filterIsInstance<ByteArray>().forEach { it.fill(0) } } }
    override fun toString()="FullBackupData(redacted)"
}
internal class AuthenticatedFullBackup(internal val data:FullBackupData,override val digest:String,
    internal val header:ByteArray,private val key:ByteArray,internal val source:()->java.io.InputStream,
    internal val cipher:RecordMediaPackageCipher):VerifiedFullBackup {
    private var released=false
    override val summary get()=data.summary
    internal suspend fun <T> withKey(block:suspend (ByteArray)->T):T {
        val borrowed=synchronized(this) { check(!released);key.copyOf() }
        return try { block(borrowed) } finally { borrowed.fill(0) }
    }
    @Synchronized override fun release() { released=true;key.fill(0);data.release() }
    override fun toString()="VerifiedFullBackup(redacted)"
}
internal object FullBackupCodec {
    const val MAX_METADATA=16*1024*1024
    const val MAX_FILE_BYTES=MAX_METADATA.toLong()+RecordMediaPackages.MAX_IMAGE_TOTAL+16384
    fun encode(data:FullBackupData):ByteArray {
        data.checkLive();validate(data)
        return PackageWriter(MAX_METADATA).use { w ->
            w.string("AIyou-logical-full-v"+data.version,32);w.i32(data.version);w.i32(1)
            w.string(data.id,36);w.i64(data.created);w.i32(FullBackupTables.specs.size)
            FullBackupTables.specs.forEachIndexed { index,spec ->
                w.string(spec.name,64);w.string(spec.columns,512)
                val rows=data.tables[index];w.i32(rows.size)
                rows.forEach { row -> row.forEach { cell -> when(cell) {
                    null -> w.i32(0)
                    is Long -> { w.i32(1);w.i64(cell) }
                    is String -> { w.i32(2);text(w,cell) }
                    is ByteArray -> { w.i32(3);w.i32(cell.size);w.raw(cell) }
                    else -> error("Unsupported logical cell")
                } } }
            }
            w.i32(data.images.size);data.images.forEach { i ->
                w.string(i.sourceId,512);w.i32(i.sourceRevision);w.string(i.attachmentId,36);w.string(i.mediaType,32)
                w.i64(i.byteSize);w.raw(packageUnhex(i.sha256))
            }
            if(data.version>=9) { w.i32(data.audios.size);data.audios.forEach { a ->
                w.string(a.sourceId,512);w.i32(a.sourceRevision);w.string(a.attachmentId,36);w.string(a.mediaType,32);w.i64(a.byteSize);w.raw(packageUnhex(a.sha256))
            } }
            w.i32(data.edits.size);data.edits.forEach { RecordEditDraftPackageCodec.write(w,it) }
            w.bytes()
        }
    }
    fun decode(bytes:ByteArray):FullBackupData {
        require(bytes.size<=MAX_METADATA)
        val allocated=mutableListOf<ByteArray>()
        try {
            val r=PackageReader(bytes)
            val name=r.string(32);val version=r.i32();require(version in setOf(8,9,10,11,12) && name=="AIyou-logical-full-v"+version && r.i32()==1)
            val id=r.string(36);val at=r.i64();require(r.i32()==FullBackupTables.specs.size)
            val tables=FullBackupTables.specs.map { spec ->
                require(r.string(64)==spec.name && r.string(512)==spec.columns)
                List(r.count(spec.maximum)) { List(spec.names.size) { when(r.i32()) {
                    0 -> null
                    1 -> r.i64()
                    2 -> text(r)
                    3 -> r.raw(r.i32(),65536).also { allocated+=it }
                    else -> error("Unknown logical cell")
                } } }
            }
            val images=List(r.count(256)) { RecordPackageImage(r.string(512),r.i32(),r.string(36),r.string(32),r.i64(),packageHex(r.raw(32))) }
            val audios=if(version>=9) List(r.count(256)) { RecordPackageAudio(r.string(512),r.i32(),r.string(36),r.string(32),r.i64(),packageHex(r.raw(32))) } else emptyList()
            val edits=List(r.count(256)) { RecordEditDraftPackageCodec.read(r) };r.end()
            val capture=decodeCapture(tables[12])
            return FullBackupData(id,at,tables,images,edits,capture,audios).also { require(it.version==version);validate(it);allocated.clear() }
        } finally { allocated.forEach { it.fill(0) } }
    }
    fun decodeCapture(receipts:List<List<Any?>>):CaptureDraft? {
        val current=receipts.filter { it[3]=="CAPTURE_CURRENT" }
        require(current.size<=1)
        return current.singleOrNull()?.let { row ->
            require(row[0]=="CAPTURE_CURRENT" && row[2]==1L)
            CaptureDraftCodec.decode(row[4] as String).also { require(it.id==row[1]) }
        }
    }
    fun validate(d:FullBackupData) {
        require(UUID.fromString(d.id).toString()==d.id && d.created>=0 && d.tables.size==FullBackupTables.specs.size)
        d.tables.forEachIndexed { index,rows ->
            val spec=FullBackupTables.specs[index]
            require(rows.size<=spec.maximum && rows.all { it.size==spec.names.size })
            val kinds=FullBackupTables.kinds[index];require(kinds.length==spec.names.size)
            rows.forEach { row -> row.forEachIndexed { cell,value -> require(when(kinds[cell]) {
                's' -> value is String
                'l' -> value is Long
                'b' -> value is ByteArray
                '?' -> value==null || value is String
                'n' -> value==null
                else -> false
            }) } }
            val keys=rows.map { if(index==1) it.take(2) else it.take(1) }
            require(keys.distinct().size==keys.size)
        }
        val entries=d.tables[0].associateBy { it[0] as String }
        entries.values.forEach { e ->
            ManualTags.identifier(e[0] as String);require(e[4] in setOf("LOCAL_ONLY","AI_NONE"))
            require(e[2] is Long && (e[2] as Long)>=0 && e[5] in setOf(0L,1L) && (e[6] as Long)>=0)
            java.time.ZoneId.of(e[3] as String)
            RecordFavoriteSnapshot(e[0] as String,e[6] as Long,e[5]==1L)
            if(e[6]==0L) require(e[7]==null && e[8]==0L)
            else { RecordFavorites.identifier(e[7] as String);require((e[8] as Long) in 1..(e[1] as Long)) }
            val revisions=d.tables[1].filter { it[0]==e[0] }.sortedBy { it[1] as Long }
            require((e[1] as Long) in 1..4096 && revisions.map { it[1] }==(1..(e[1] as Long)).toList())
            revisions.forEach { r -> require((r[2] as String).length<=100000 && OriginalExports.hash(r[2] as String)==r[3] && (r[4] as Long)>=0) }
        }
        require(d.tables[1].all { it[0] in entries })
        d.tables[2].forEach { require(it[0] in entries && (it[1] as Long)>=0 && it[2] in setOf(0L,1L) && it[3]==null && it[4]==null) }
        val capture=decodeCapture(d.tables[12]);require(sameCapture(capture,d.capture))
        require(capture==null || !capture.discarding && capture.images.all { it.state==CaptureImageState.READY } && (capture.audio?.state ?: CaptureAudioState.READY)==CaptureAudioState.READY)
        require(capture==null || d.tables[9].isEmpty())
        require(capture==null || capture.id !in entries)
        d.tables[9].forEach { require(it[0]==1L && (it[2] as Long)>0 && (it[3] as String).length<=100000);RecordPackages.uuid(it[1] as String) }
        val expected=d.tables[3].map { row ->
            require(row[6]=="READY" && row[1] in entries && d.tables[1].any { it[0]==row[1] && it[1]==row[2] })
            RecordPackageAttachments.descriptor(row[1] as String,Math.toIntExact(row[2] as Long),row[0] as String,row[3] as String,row[5] as Long,row[4] as String)
        }+capture?.images.orEmpty().map { i -> i.intent.let { RecordPackageImage(it.entryId,it.revision,it.id,it.mediaType,it.byteSize,it.sha256) } }+
            listOfNotNull(capture?.audio?.intent).map { RecordPackageAudio(it.entryId,it.revision,it.id,it.mediaType,it.byteSize,it.sha256) }
        require(d.images==expected.filterIsInstance<RecordPackageImage>() && d.audios==expected.filterIsInstance<RecordPackageAudio>() && d.media.size<=256 && d.media.map { it.attachmentId }.distinct().size==d.media.size &&
            d.media.sumOf { it.byteSize }<=RecordMediaPackages.MAX_IMAGE_TOTAL)
        if(d.version>=9) d.tables[1].filter { (it[2] as String).isBlank() }.forEach { revision ->
            require(expected.any { it.sourceId==revision[0] && it.sourceRevision.toLong()==revision[1] })
        }
        d.edits.forEach { e ->
            EncryptedOriginalEditStore.validateFullDraft(e)
            require(e.entryId in entries && e.baseRevision.toLong()<=(entries.getValue(e.entryId)[1] as Long))
            if(e.state==OriginalEditDraftState.ACTIVE) require(d.tables[1].any { it[0]==e.entryId && it[1]==e.sourceRevision.toLong() && it[2]==e.originalText })
        }
        require(d.edits.size<=256 && d.edits.map { it.entryId }.distinct().size==d.edits.size)
        d.tables[10].forEach { row ->
            val operation=row[3] as String
            require(row[1] in entries && d.tables[1].any { it[0]==row[1] && it[1]==row[2] })
            when {
                operation in FullBackupTables.retainedJobs -> Unit
                operation.matches(Regex("RECYCLE_(MOVE|RESTORE):[0-9]+")) -> {
                    val generation=operation.substringAfter(':').toLong()
                    require(generation in 0 until Long.MAX_VALUE && row[4]=="CONFIRMED:"+(generation+1) && (row[0] as String).startsWith("RECYCLE:"))
                }
                operation.matches(Regex("LOCAL_POLICY:(LOCAL_ONLY|AI_NONE|REMOTE_ALLOWED):(LOCAL_ONLY|AI_NONE):[0-9]+")) -> {
                    val generation=operation.substringAfterLast(':').toLong()
                    require(generation in 0 until Long.MAX_VALUE && (row[4] as String).matches(Regex("CONFIRMED:"+(generation+1)+":[0-9a-f]{64}")) && (row[0] as String).startsWith("LOCAL_POLICY:"))
                }
                else -> error("Unknown local receipt")
            }
        }
        d.tables[11].forEach {
            ManualTags.identifier(it[0] as String);ManualTags.identifier(it[3] as String)
            require((it[1] as Long)>0 && (it[2] as Long)>=0 && it[4]=="DONE" && it[5]=="" && it[0] !in entries)
        }
        require(d.tables[11].map { it[3] }.distinct().size==d.tables[11].size)
        d.tables[12].forEach {
            ManualTags.identifier(it[0] as String);ManualTags.identifier(it[1] as String)
            require((it[2] as Long)>0 && it[3] in FullBackupTables.receipts)
        }
        for(index in listOf(4,5,7)) d.tables[index].forEach { row ->
            val id=row[0] as String
            ManualTags.identifier(if(index==7 && id.startsWith(TaskProposals.PREFIX)) id.removePrefix(TaskProposals.PREFIX) else id)
            ManualTags.identifier(row[2] as String);require((row[1] as Long)>0)
        }
        d.tables[6].forEach { row ->
            RecordFavorites.identifier(row[2] as String)
            require((row[1] as Long)>0 && (row[3] as Long) in 1..(entries.getValue(row[0] as String)[1] as Long))
            val date=if(row[4]==null) { require(row[5]==null);null } else RecordEventDate(row[4] as String,row[5] as String)
            RecordEventDateSnapshot(row[0] as String,row[1] as Long,date)
        }
        for(index in listOf(4,5,6,8)) require(d.tables[index].all { it[if(index==8) 1 else 0] in entries })
        d.tables[8].forEach { require(it[5] in (if(d.version==12) setOf(1L,2L,3L,4L,5L) else if(d.version==11) setOf(1L,2L,3L,4L) else if(d.version==10) setOf(1L,2L,3L) else setOf(1L,2L)) && it[7] in setOf("READY","CANCELLED","INVALIDATED")) }
    }
    fun sameCapture(a:CaptureDraft?,b:CaptureDraft?):Boolean = if(a==null || b==null) a==null && b==null else
        a.sameSnapshot(b)
    // Drafts can contain unfinished UTF-16. No implicit replacement by a UTF-8 encoder.
    private fun text(w:PackageWriter,value:String) {
        require(value.length<=MAX_METADATA/2);w.i32(value.length)
        val bytes=ByteArray(value.length*2)
        try { value.forEachIndexed { i,c -> bytes[2*i]=(c.code ushr 8).toByte();bytes[2*i+1]=c.code.toByte() };w.raw(bytes) }
        finally { bytes.fill(0) }
    }
    private fun text(r:PackageReader):String {
        val count=r.count(MAX_METADATA/2);val bytes=r.raw(count*2);val chars=CharArray(count)
        return try { chars.indices.forEach { i -> chars[i]=(((bytes[2*i].toInt() and 255) shl 8) or (bytes[2*i+1].toInt() and 255)).toChar() };String(chars) }
        finally { bytes.fill(0);chars.fill('\u0000') }
    }
}
