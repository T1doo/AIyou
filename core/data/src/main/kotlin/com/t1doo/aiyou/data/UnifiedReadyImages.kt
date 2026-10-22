package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.ZoneId

/** Complete streamed receipt; no full-vault ID/body/media map and no implicit recovery writes. */
internal class UnifiedReadyImages(private val database:RecordsSqlDatabase,private val media:AttachmentStore?) {
    internal inner class Receipt(val epoch:Long,val sources:Long,val attachments:Long,val digest:String) {
        val owner=this@UnifiedReadyImages
        override fun toString()="UnifiedReadyImageReceipt(redacted)"
    }
    private val live="COALESCE(a.deleted,0)=0 AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"
    private fun epoch(sql:RecordsSqlConnection):Long {
        val rows=sql.query("SELECT slot,epoch FROM local_search_epoch");require(rows.size==1&&rows[0][0]==1L)
        return (rows.single()[1] as Long).also{require(it>=0&&it<Long.MAX_VALUE)}
    }
    // Bound malformed metadata in SQLite before the transport/native cursor materializes it.
    private fun text(column:String,max:Int)="CASE WHEN length(CAST($column AS BLOB))<=$max THEN $column END"
    private fun integer(column:String)="CASE WHEN typeof($column)='integer' THEN $column END"
    private val columns=listOf(text("id",512),text("entryId",512),integer("revision"),text("mediaType",32),
        text("sha256",64),integer("byteSize"),text("state",32)).joinToString(",")
    private fun sources(sql:RecordsSqlConnection,after:String?):List<Pair<String,Int>> = sql.query(
        "SELECT "+text("e.id",512)+","+integer("e.currentRevision")+" FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE "+(if(after==null)""else"e.id>? AND ")+live+" ORDER BY e.id LIMIT 20",if(after==null)emptyList()else listOf(after)).map{
            ManualTags.identifier(it[0] as String);(it[0] as String) to Math.toIntExact(it[1] as Long).also{r->require(r>0)}
        }
    private fun row(r:List<Any?>):LocalAttachment {
        val intent=AttachmentIntent(r[0] as String,r[1] as String,Math.toIntExact(r[2] as Long),r[3] as String,r[4] as String,r[5] as Long)
        EncryptedAttachmentStore.validate(intent)
        return LocalAttachment(intent,AttachmentState.valueOf(r[6] as String))
    }
    /** Rowid is a transaction-local cursor only. It is never an identity, IO ticket, or receipt field.
     * The existing (entryId,revision) index is ordered by rowid, avoiding a per-page whole-source sort.
     * Both complete receipt passes run wholly under a single SQL lease, so VACUUM cannot move this cursor. */
    private fun metadata(sql:RecordsSqlConnection,id:String,revision:Int,after:Long?):List<Pair<Long,LocalAttachment>> = sql.query(
        "SELECT rowid,"+columns+" FROM attachments WHERE entryId=? AND revision=?"+(if(after==null)""else" AND rowid>?")+" ORDER BY rowid LIMIT 20",
        if(after==null)listOf(id,revision)else listOf(id,revision,after)).map{r->
            val value=row(r.drop(1));require(value.intent.entryId==id&&value.intent.revision==revision);(r[0] as Long) to value
        }
    /** At most64 images/source already proved by the full metadata pass. Stable IDs survive IO/VACUUM. */
    private fun imagePage(sql:RecordsSqlConnection,id:String,revision:Int,after:String?):List<LocalAttachment> = sql.query(
        "SELECT "+columns+" FROM attachments WHERE entryId=? AND revision=? AND mediaType IN ('image/png','image/jpeg')"+(if(after==null)""else" AND id>?")+" ORDER BY id LIMIT 20",if(after==null)listOf(id,revision)else listOf(id,revision,after)).map{r->
            row(r).also{require(it.intent.entryId==id&&it.intent.revision==revision)}
        }
    private fun same(before:Receipt,now:Receipt) = before.owner===this&&now.owner===this&&before.epoch==now.epoch&&before.sources==now.sources&&before.attachments==now.attachments&&before.digest==now.digest
    fun validate(sql:RecordsSqlConnection,before:Receipt,guard:()->Unit) {if(!same(before,scan(sql,guard)))throw UnifiedKeywordConflict()}
    fun matches(sql:RecordsSqlConnection,source:SourceVersion):Boolean = sql.query(
        "SELECT p.id FROM entries e JOIN attachments p ON p.entryId=e.id AND p.revision=e.currentRevision WHERE e.id=? AND e.currentRevision=? AND p.mediaType IN ('image/png','image/jpeg') AND p.state='READY' LIMIT 1",listOf(source.sourceId,source.revision)).isNotEmpty()
    fun scan(sql:RecordsSqlConnection,guard:()->Unit):Receipt {
        guard();val stamp=epoch(sql);var sourceCount=0L;var attachmentCount=0L;val digest=MessageDigest.getInstance("SHA-256")
        fun append(write:(PackageWriter)->Unit){val b=PackageWriter(4096).use{write(it);it.bytes()};try{digest.update(b)}finally{b.fill(0)}}
        append{it.string("AIyou/unified/ready-images/1",64);it.i64(stamp)}
        var after:String?=null
        while(true){guard();val page=sources(sql,after);if(page.isEmpty())break
            for((id,revision)in page){guard()
                val size=sql.query("SELECT length(CAST(originalText AS BLOB)) FROM entry_revisions WHERE entryId=? AND revision=?",listOf(id,revision)).single().single() as Long;require(size in 0..400000)
                val r=sql.query("SELECT r.originalText,"+text("r.contentHash",64)+","+integer("COALESCE(a.generation,0)")+","+integer("e.capturedAtMillis")+","+text("e.captureTimeZone",128)+","+text("e.privacyPolicy",32)+" FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=? AND e.currentRevision=? AND "+live,listOf(id,revision)).single()
                val body=r[0] as String;require(body.length<=100000&&OriginalExports.hash(body)==r[1]&&(r[2] as Long)>=0);ZoneId.of(r[4] as String);ProcessingPolicy.valueOf(r[5] as String)
                append{it.i32(1);it.string(id,512);it.i32(revision);it.string(r[1] as String,64);it.i64(r[2] as Long);it.i64(r[3] as Long);it.string(r[4] as String,128);it.string(r[5] as String,32)}
                var next:Long?=null;var imageCount=0;var count=0L
                while(true){guard();val rows=metadata(sql,id,revision,next);if(rows.isEmpty())break
                    for((cursor,row) in rows){guard();val i=row.intent;if(i.mediaType in setOf("image/png","image/jpeg")&&++imageCount>64)throw UnifiedReadyImageLimit()
                        append{it.i32(2);it.string(i.id,512);it.string(i.entryId,512);it.i32(i.revision);it.string(i.mediaType,32);it.string(i.sha256,64);it.i64(i.byteSize);it.string(row.state.name,32)}
                        count=Math.addExact(count,1);next=cursor
                    };if(rows.size<20)break
                }
                require(body.isNotBlank()||count>0)
                append{it.i32(3);it.i64(count)};sourceCount=Math.addExact(sourceCount,1);attachmentCount=Math.addExact(attachmentCount,count);after=id;guard()
            };if(page.size<20)break
        }
        append{it.i32(4);it.i64(sourceCount);it.i64(attachmentCount)};guard();return Receipt(stamp,sourceCount,attachmentCount,packageHex(digest.digest()))
    }
    /** Each read is pinned to this run's immutable intent, not an ID authenticated by an old run. */
    suspend fun authenticate(before:Receipt,guard:()->Unit) {
        if(before.owner!==this)throw UnifiedKeywordConflict();val reader=media?:throw UnifiedReadyImagesUnsupported()
        fun current(sql:RecordsSqlConnection){guard();if(epoch(sql)!=before.epoch)throw UnifiedKeywordConflict()}
        var after:String?=null
        while(true){val page=database.transaction{sql->current(sql);sources(sql,after)};if(page.isEmpty())break
            for((id,revision)in page){var next:String?=null
                while(true){val rows=database.transaction{sql->current(sql);imagePage(sql,id,revision,next)};if(rows.isEmpty())break
                    for(row in rows){guard();val i=row.intent
                        if(row.state==AttachmentState.READY&&i.mediaType in setOf("image/png","image/jpeg")){
                            // Initial intent survives the actual IO. Reusing the same ID with other bytes cannot pass.
                            val bytes=reader.read(i.id)
                            try{guard();RecordMediaPackageCipher.checkImage(RecordPackageImage(i.entryId,i.revision,i.id,i.mediaType,i.byteSize,i.sha256),bytes)
                                database.transaction{sql->current(sql);val now=sql.query("SELECT "+columns+" FROM attachments WHERE id=?",listOf(i.id)).singleOrNull()?.let(::row);if(now!=row)throw UnifiedKeywordConflict()}
                            }finally{bytes.fill(0)}
                        };next=i.id;guard()
                    };if(rows.size<20)break
                };after=id
            };if(page.size<20)break
        }
        database.transaction{validate(it,before,guard)}
    }
}
