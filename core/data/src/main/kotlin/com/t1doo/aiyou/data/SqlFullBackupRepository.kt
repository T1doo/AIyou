package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** A single logical snapshot and a single final commit. No second database, schema, file cache or key export.
 * Failure keeps the authenticated document, PENDING receipt and owned ciphertext for the same-backup retry.
 * The gate intentionally refuses every nonempty/unknown target; there is no destructive overwrite mode. */
internal class SqlFullBackupRepository(private val database:RecordsSqlDatabase,private val key:ByteArray,
    private val media:EncryptedAttachmentStore,private val edits:EncryptedOriginalEditStore,
    private val mediaPackages:SqlRecordMediaPackageRepository,
    private val captureGate:CaptureDraftGate?=null,
    private val checkpoint:(String)->Unit={}):FullBackupRepository {
    private val cipher=RecordMediaPackageCipher()
    override suspend fun capture(backupId:String,createdAtMillis:Long):FullBackupSnapshot = captureData(backupId,createdAtMillis,true)
    private suspend fun captureData(id:String,at:Long,verifyImages:Boolean):FullBackupData = media.fullFiles { files ->
        edits.packageFiles { editLease ->
            val data=database.transaction { sql -> snapshot(sql,id,at,editLease.fullDrafts()) }
            try {
                files.inventory(data.media.map(::intent))
                if(verifyImages) data.media.forEach { image ->
                    val bytes=files.read(intent(image))
                    try { RecordMediaPackageCipher.checkImage(image,bytes) } finally { bytes.fill(0) }
                }
                val after=database.transaction { sql -> snapshot(sql,id,at,data.edits) }
                try { require(digest(data)==digest(after)) } finally { after.release() }
                data
            } catch(f:Throwable) { data.release();throw f }
        }
    }
    private suspend fun current(data:FullBackupData):Boolean = try {
        val now=captureData(data.id,data.created,false)
        try { digest(data)==digest(now) } finally { now.release() }
    } catch(c:CancellationException) { throw c } catch(_:Exception) { false }
    override suspend fun seal(snapshot:FullBackupSnapshot,password:CharArray):PreparedRecordMediaPackage {
        try {
            val data=snapshot as? FullBackupData ?: throw RecordPackageInvalid()
            require(current(data))
            return cipher.sealFull(data,password,{ image -> media.fullFiles { it.read(intent(image)) } },{ current(data) })
        } finally { password.fill('\u0000') }
    }
    override suspend fun open(source:()->InputStream,password:CharArray):VerifiedFullBackup {
        val pack=cipher.openFull(source,password)
        try { validateReceipts(pack.data);cells { it.validatePortable(pack.data) };return pack }
        catch(f:Throwable) { pack.release();throw f }
    }

    override fun completed(backup:VerifiedFullBackup):Boolean {
        val pack=backup as? AuthenticatedFullBackup ?: throw RecordPackageInvalid()
        pack.data.checkLive()
        return database.transaction { sql ->
            sql.query("SELECT entryId,sourceRevision,operation,state FROM record_package_receipts WHERE id=?",listOf(PREFIX+pack.data.id))
                .singleOrNull()==listOf(pack.data.id,pack.data.version.toLong(),operation(pack.data),"DONE:"+pack.digest)
        }
    }
    override suspend fun restore(backup:VerifiedFullBackup,active:()->Boolean):FullBackupRestored =
        captureGate?.mutation { restoreOwned(backup,active) } ?: restoreOwned(backup,active)
    private suspend fun restoreOwned(backup:VerifiedFullBackup,active:()->Boolean):FullBackupRestored {
        val pack=backup as? AuthenticatedFullBackup ?: throw RecordPackageInvalid()
        val data=pack.data;data.checkLive();require(active());validateReceipts(data);cells { it.validatePortable(data) }
        val journalId=PREFIX+data.id
        require(data.tables[12].none { it[0]==journalId })
        fun receipt(sql:RecordsSqlConnection):String? = sql.query("SELECT entryId,sourceRevision,operation,state FROM record_package_receipts WHERE id=?",listOf(journalId))
            .singleOrNull()?.let { require(it.take(3)==listOf(data.id,data.version.toLong(),operation(data)));it[3] as String }
        if(database.transaction { sql -> receipt(sql)?.let { state ->
                require(state in listOf("PENDING:"+pack.digest,"DONE:"+pack.digest));state.startsWith("DONE:")
            } ?: false }) return FullBackupRestored(data.summary,true)
        val intents=data.media.map(::intent)
        return media.importPackage(intents,consume={ consume -> pack.cipher.consumeFull(pack) { index,bytes ->
            require(active());consume(index,bytes)
        } },maximumImages=256,before={
            require(active());media.fullImportInventory(intents)
            edits.packageFiles { lease ->
                val prior=database.transaction { receipt(it) }
                val existingEdits=lease.fullDrafts(if(prior!=null) data.edits else emptyList())
                database.transaction { sql ->
                    val old=receipt(sql)
                    require(old==null || old=="PENDING:"+pack.digest)
                    emptyTarget(sql,pack,old!=null)
                    if(old==null) {
                        require(existingEdits.isEmpty())
                        // Inventory was empty at the first durable intent, never inferred from a retry.
                        media.fullImportInventory(emptyList())
                        sql.execute("INSERT INTO record_package_receipts VALUES(?,?,?,?,?)",listOf(journalId,data.id,data.version.toLong(),operation(data),"PENDING:"+pack.digest))
                    } else require(existingEdits.all { it in data.edits })
                }
            }
            checkpoint("full-intent")
        }) {
            edits.packageFiles { lease ->
                data.edits.forEach { require(active());lease.installFull(it) }
                require(lease.fullDrafts().associateBy { it.entryId }==data.edits.associateBy { it.entryId })
                data.capture?.let { media.registerFullCapture(it.images.map { image -> image.intent }+listOfNotNull(it.audio?.intent)) }
                checkpoint("full-files")
                database.transaction { sql ->
                    require(active() && receipt(sql)=="PENDING:"+pack.digest)
                    emptyTarget(sql,pack,true)
                    // Execution progress never migrates with user records. Older test/backup schemas lack this table.
                    if(sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='reminder_runtime_meta'").isNotEmpty())
                        sql.execute("DELETE FROM reminder_runtime_meta")
                    // This is the only deleted row: an exactly verified, default empty initialized slot.
                    sql.execute("DELETE FROM record_package_receipts WHERE id='CAPTURE_CURRENT' AND operation='CAPTURE_CURRENT'")
                    cells { owners ->
                        FullBackupTables.specs.forEachIndexed { index,spec ->
                            data.tables[index].forEach { row ->
                                val imported=owners.transform(spec.name,row,true)
                                try { sql.execute("INSERT INTO "+spec.name+"("+spec.columns+") VALUES("+spec.names.joinToString(",") { "?" }+")",imported) }
                                finally { if(imported!==row) imported.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
                            }
                        }
                    }
                    // Local indexes are rebuilt, never resumed remote/model work.
                    data.tables[0].forEach { e ->
                        sql.execute("INSERT INTO local_jobs VALUES(?,?,?,'LOCAL_INDEX','PENDING')",listOf("FULL_INDEX:"+data.id+":"+e[0],e[0],e[1]))
                    }
                    require(sql.query("PRAGMA foreign_key_check").isEmpty())
                    // Verify all logical columns/payloads under the new key before acknowledging the commit.
                    val imported=snapshot(sql,data.id,data.created,data.edits,ignoreFullJournal=journalId)
                    try { verifyRestored(data,imported) } finally { imported.release() }
                    require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",listOf("DONE:"+pack.digest,journalId,"PENDING:"+pack.digest))==1)
                    checkpoint("full-before-commit")
                }
                checkpoint("full-committed")
                FullBackupRestored(data.summary,false)
            }
        }
    }
    private fun operation(data:FullBackupData)=when(data.version) { 8 -> OP;9 -> "FULL_BACKUP_IMPORT_V9";10 -> "FULL_BACKUP_IMPORT_V10";else -> error("Unknown full version") }
    private fun verifyRestored(expected:FullBackupData,actual:FullBackupData) {
        // Logical rows are keyed sets. SQL BINARY and the edit store have different sort orders,
        // while authenticated image frames retain their explicit document order throughout IO.
        require(expected.media.associateBy { it.attachmentId }==actual.media.associateBy { it.attachmentId } &&
            expected.edits.associateBy { it.entryId }==actual.edits.associateBy { it.entryId } && FullBackupCodec.sameCapture(expected.capture,actual.capture))
        cells { owners -> expected.tables.forEachIndexed { index,rows ->
            val after=actual.tables[index];require(rows.size==after.size)
            fun rowKey(row:List<Any?>)=row.take(if(index==1) 2 else 1)
            val byKey=after.associateBy(::rowKey)
            rows.forEach { a ->
                val b=byKey.getValue(rowKey(a))
                val spec=FullBackupTables.specs[index]
                val encrypted=owners.transform(spec.name,a,true)
                val normalized=try { owners.transform(spec.name,encrypted,false) }
                    finally { if(encrypted!==a) encrypted.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
                try { normalized.indices.forEach { cell ->
                    val x=normalized[cell];val y=b[cell]
                    require(if(x is ByteArray && y is ByteArray) MessageDigest.isEqual(x,y) else x==y)
                } } finally { if(normalized!==a) normalized.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
            }
        } }
    }
    private fun snapshot(sql:RecordsSqlConnection,id:String,at:Long,editDrafts:List<OriginalEditDraft>,ignoreFullJournal:String?=null):FullBackupData {
        schema(sql);require(sql.query("PRAGMA foreign_key_check").isEmpty())
        // Check every table's count/bytes before any payload validation or bulk row materialization.
        var estimated=0L
        FullBackupTables.specs.forEach { spec ->
            val filter=when(spec.name) {
                "local_jobs" -> " WHERE operation NOT IN ('LOCAL_INDEX','SELECTED_QUESTION_SEND_V1') AND operation NOT LIKE 'RECORD_AUTH:%'"
                "record_package_receipts" -> if(ignoreFullJournal!=null) " WHERE id!=?" else ""
                else -> ""
            }
            val args=if(spec.name=="record_package_receipts" && ignoreFullJournal!=null) listOf(ignoreFullJournal) else emptyList()
            val size=sql.query("SELECT count(*),COALESCE(SUM("+spec.names.joinToString("+") { "COALESCE(length(CAST($it AS BLOB)),0)" }+"),0) FROM "+spec.name+filter,args).single()
            require((size[0] as Long)<=spec.maximum)
            estimated=Math.addExact(estimated,size[1] as Long);require(estimated<=FullBackupCodec.MAX_METADATA)
        }
        val tables=cells { owners ->
            owners.validateWithin(sql)
            mediaPackages.validateLogicalForFullBackup()
            FullBackupTables.specs.map { spec ->
            val filter=when(spec.name) {
                "local_jobs" -> " WHERE operation NOT IN ('LOCAL_INDEX','SELECTED_QUESTION_SEND_V1') AND operation NOT LIKE 'RECORD_AUTH:%'"
                "record_package_receipts" -> if(ignoreFullJournal!=null) " WHERE id!=?" else ""
                else -> ""
            }
            val args=if(spec.name=="record_package_receipts" && ignoreFullJournal!=null) listOf(ignoreFullJournal) else emptyList()
            val count=sql.query("SELECT count(*),COALESCE(SUM("+spec.names.joinToString("+") { "COALESCE(length(CAST($it AS BLOB)),0)" }+"),0) FROM "+spec.name+filter,args).single()
            require((count[0] as Long)<=spec.maximum)
            sql.query("SELECT "+spec.columns+" FROM "+spec.name+filter+" ORDER BY "+(if(spec.name=="entry_revisions") "entryId,revision" else spec.names.first())+" LIMIT "+(spec.maximum+1),args)
                .also { require(it.size<=spec.maximum) }.map { raw ->
                    val normalized=raw.toMutableList()
                    if(spec.name=="entries") {
                        require(normalized[4] in setOf("LOCAL_ONLY","REMOTE_ALLOWED","AI_NONE"))
                        if(normalized[4]!="AI_NONE") normalized[4]="LOCAL_ONLY"
                    }
                    if(spec.name=="remote_source_access") { normalized[3]=null;normalized[4]=null }
                    owners.transform(spec.name,normalized,false)
                }
        } }
        val capture=FullBackupCodec.decodeCapture(tables[12])
        val images=tables[3].map { RecordPackageAttachments.descriptor(it[1] as String,Math.toIntExact(it[2] as Long),it[0] as String,it[3] as String,it[5] as Long,it[4] as String) }+
            capture?.images.orEmpty().map { it.intent.let { i -> RecordPackageImage(i.entryId,1,i.id,i.mediaType,i.byteSize,i.sha256) } }+
            listOfNotNull(capture?.audio?.intent).map { RecordPackageAudio(it.entryId,it.revision,it.id,it.mediaType,it.byteSize,it.sha256) }
        val data=FullBackupData(id,at,tables,images.filterIsInstance<RecordPackageImage>(),editDrafts,capture,images.filterIsInstance<RecordPackageAudio>())
        try { FullBackupCodec.validate(data);validateReceipts(data);return data } catch(f:Throwable) { data.release();throw f }
    }
    private fun validateReceipts(data:FullBackupData) {
        require(data.tables[12].none { it[0]==PREFIX+data.id })
        mediaPackages.validatePortableLedger(data)
        data.tables[12].forEach { row -> when(row[3]) {
            "PACKAGE_MEDIA_INTENT" -> mediaPackages.validateCompletedForFullBackup(row)
            OP,"FULL_BACKUP_IMPORT_V9","FULL_BACKUP_IMPORT_V10" -> { RecordPackages.uuid(row[1] as String);require(row[0]==PREFIX+row[1] && row[2]==(if(row[3]==OP) 8L else if(row[3]=="FULL_BACKUP_IMPORT_V9") 9L else 10L) && (row[4] as String).matches(Regex("DONE:[0-9a-f]{64}"))) }
            "CAPTURE_SAVED","CAPTURE_DISCARDED" -> {
                RecordPackages.uuid(row[1] as String)
                require(row[0]==(row[3] as String)+":"+row[1] && row[2]==1L)
                RecordPackages.digest(row[4] as String);require(data.capture?.id!=row[1])
            }
            "CAPTURE_REMOVED" -> {
                RecordPackages.uuid(row[1] as String);require((row[0] as String).startsWith("CAPTURE_REMOVED:") && row[2]==1L)
                val image=(row[0] as String).removePrefix("CAPTURE_REMOVED:");RecordPackages.uuid(image);RecordPackages.digest(row[4] as String)
                require((data.capture?.images.orEmpty().map { it.intent }+listOfNotNull(data.capture?.audio?.intent)).none { it.id==image })
            }
            else -> require(row[3] in FullBackupTables.receipts)
        } }
    }
    private fun emptyTarget(sql:RecordsSqlConnection,pack:AuthenticatedFullBackup,resuming:Boolean) {
        schema(sql)
        FullBackupTables.specs.forEach { spec ->
            val rows=sql.query("SELECT "+spec.columns+" FROM "+spec.name+" LIMIT 2")
            if(spec.name=="record_package_receipts") {
                // Read all to distinguish our journal plus the default slot from an unknown third row.
                val all=sql.query("SELECT "+spec.columns+" FROM record_package_receipts LIMIT 4")
                require(all.all { row ->
                    if(row[0]==PREFIX+pack.data.id) resuming && row==listOf(PREFIX+pack.data.id,pack.data.id,pack.data.version.toLong(),operation(pack.data),"PENDING:"+pack.digest)
                    else if(row[0]=="CAPTURE_CURRENT" && row[3]=="CAPTURE_CURRENT" && row[2]==1L) {
                        val draft=SqlCaptureRepository.decodeForBackup(row[4] as String)
                        row[1]==draft.id && draft.version==1L && draft.text.isEmpty() && draft.images.isEmpty() && draft.audio==null && !draft.discarding &&
                            draft.policy in setOf(ProcessingPolicy.LOCAL_ONLY,ProcessingPolicy.AI_NONE)
                    } else false
                })
                require(all.size<=if(resuming) 2 else 1)
            } else require(rows.isEmpty())
        }
        for(table in listOf("remote_text_jobs","text_annotations","local_search_documents","local_search_fts"))
            require(sql.query("SELECT 1 FROM "+table+" LIMIT 1").isEmpty())
        // Only newly generated ownership proofs for this exact authenticated plan can accompany PENDING.
        val owned=sql.query("SELECT bucket,name,entryId,state,identity FROM owned_file_creations LIMIT 1025")
        require(owned.size<=1024 && (resuming || owned.isEmpty()))
        owned.forEach { row ->
            val known=when(row[0]) {
                "ATTACHMENTS" -> pack.data.media.any { it.sourceId==row[2] && row[1] in listOf(it.attachmentId+".tmp",it.attachmentId+".aead") }
                "EDITS" -> pack.data.edits.any { it.entryId==row[2] && row[1]==packageHex(packageHash(it.entryId.toByteArray(Charsets.UTF_8)))+".edit.partial."+it.id }
                else -> false
            }
            require(known && row[3] in setOf("RESERVED","CREATED"))
        }
    }
    private fun schema(sql:RecordsSqlConnection) {
        val known=FullBackupTables.specs.map { it.name }.toSet()+FullBackupTables.excluded+setOf("room_master_table","android_metadata")
        sql.query("SELECT name FROM sqlite_master WHERE type='table'").forEach { row ->
            val name=row[0] as String
            require(name in known || name.startsWith("sqlite_") || name in setOf("local_search_fts_content","local_search_fts_segments","local_search_fts_segdir","local_search_fts_docsize","local_search_fts_stat"))
        }
        FullBackupTables.specs.forEach { spec -> require(sql.query("PRAGMA table_info("+spec.name+")").map { it[1] }==spec.names) }
    }
    private fun digest(data:FullBackupData):String {
        val bytes=FullBackupCodec.encode(data)
        return try { packageHex(packageHash(bytes)) } finally { bytes.fill(0) }
    }
    private inline fun <T> cells(block:(FullBackupCells)->T):T = FullBackupCells(database,key).use(block)
    private fun intent(image:RecordPackageAttachment)=AttachmentIntent(image.attachmentId,image.sourceId,image.sourceRevision,image.mediaType,image.sha256,image.byteSize)
    companion object { private const val PREFIX="FULL_BACKUP:";private const val OP="FULL_BACKUP_IMPORT_V8" }
}

/** Purpose-specific existing codecs authenticate before a portable payload is included or installed. */
private class FullBackupCells(database:RecordsSqlDatabase,key:ByteArray):AutoCloseable {
    private val tags=ManualTagCipher(key)
    private val people=ManualTagCipher(key,ManualLabelPurpose.PEOPLE)
    private val tasks=ManualTaskCipher(key)
    private val proposals=TaskProposalCipher(key)
    private val candidates=SqlLocalCandidateRepository(database,key)
    private val memories=SqlSourceMemoryRepository(database,key)
    private val preferences=SqlSourcePreferenceRepository(database,key)
    /** Parse and bind portable cells entirely in memory before exposing a restore preview or writing an intent. */
    fun validatePortable(data:FullBackupData) {
        val retained=mutableListOf<Pair<List<Any?>,FullBackupSourceProof?>>()
        try { data.tables.forEachIndexed { index,rows ->
            val spec=FullBackupTables.specs[index]
            rows.forEach { row ->
                val sealed=transform(spec.name,row,true)
                try {
                    if(spec.name=="local_candidate_batches") {
                        val state=row[7] as String
                        val proof=if(state=="INVALIDATED") null else {
                            val entry=data.tables[0].single { it[0]==row[1] }
                            val version=if(state=="CANCELLED") row[2] else entry[1]
                            val original=data.tables[1].single { it[0]==row[1] && it[1]==version }
                            val access=data.tables[2].singleOrNull { it[0]==row[1] }
                            FullBackupSourceProof(SourceVersion(row[1] as String,version as Long),original[3] as String,
                                access?.get(1) as? Long ?: 0,original[2] as String,entry[2] as Long,entry[3] as String,
                                ProcessingPolicy.valueOf(entry[4] as String),access?.get(2)==1L)
                        }
                        when(row[5]) { 1L -> candidates.validateFullPortableRow(sealed,proof);2L -> memories.validateFullPortableRow(sealed,proof);3L -> { preferences.validateFullPortableRow(sealed,proof);retained+=sealed to proof };else -> error("Unknown memory profile") }
                    }
                } finally { if(sealed!==row && retained.none { it.first===sealed }) sealed.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
            }
        }
        preferences.validatePortableGraph(retained)
        } finally { retained.forEach { it.first.filterIsInstance<ByteArray>().forEach { bytes -> bytes.fill(0) } } }
    }
    fun validateWithin(sql:RecordsSqlConnection) { candidates.validateFullWithin(sql);memories.validateFullWithin(sql);preferences.validateFullWithin(sql) }
    fun transform(table:String,row:List<Any?>,restoring:Boolean):List<Any?> {
        val index=when(table) { "entry_manual_tags","entry_manual_people","manual_tasks" -> 3;"local_candidate_batches" -> 10;else -> return row }
        val bytes=row[index] as ByteArray
        val value=when(table) {
            "entry_manual_tags" -> tags.portable(row[0] as String,row[1] as Long,row[2] as String,bytes,restoring)
            "entry_manual_people" -> people.portable(row[0] as String,row[1] as Long,row[2] as String,bytes,restoring)
            "manual_tasks" -> if((row[0] as String).startsWith(TaskProposals.PREFIX)) proposals.portable((row[0] as String).removePrefix(TaskProposals.PREFIX),row[1] as Long,row[2] as String,bytes,restoring)
                else tasks.portable(row[0] as String,row[1] as Long,row[2] as String,bytes,restoring)
            else -> when(row[5]) { 1L -> candidates.portable(row,restoring);2L -> memories.portable(row,restoring);3L -> preferences.portable(row,restoring);else -> error("Unknown memory profile") }
        }
        return row.toMutableList().also { it[index]=value }
    }
    override fun close() { tags.wipe();people.wipe();tasks.wipe();proposals.wipe();candidates.wipeKey();memories.wipeKey();preferences.wipeKey() }
}
