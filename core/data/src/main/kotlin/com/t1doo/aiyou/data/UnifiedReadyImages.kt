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
    private fun sources(sql:RecordsSqlConnection,after:String?):List<Pair<String,Int>> = sql.query(
        "SELECT e.id,e.currentRevision FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE (? IS NULL OR e.id>?) AND "+live+" ORDER BY e.id LIMIT 20",listOf(after,after)).map{
            ManualTags.identifier(it[0] as String);(it[0] as String) to Math.toIntExact(it[1] as Long).also{r->require(r>0)}
        }
    private fun attachments(sql:RecordsSqlConnection,id:String,revision:Int,after:String?):List<LocalAttachment> = sql.query(
        "SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments WHERE entryId=? AND revision=? AND (? IS NULL OR id>?) ORDER BY id LIMIT 20",listOf(id,revision,after,after)).map{r->
            val intent=AttachmentIntent(r[0] as String,r[1] as String,Math.toIntExact(r[2] as Long),r[3] as String,r[4] as String,r[5] as Long)
            EncryptedAttachmentStore.validate(intent);require(intent.entryId==id&&intent.revision==revision)
            LocalAttachment(intent,AttachmentState.valueOf(r[6] as String))
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
                val r=sql.query("SELECT r.originalText,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=? AND e.currentRevision=? AND "+live,listOf(id,revision)).single()
                val body=r[0] as String;require(body.length<=100000&&OriginalExports.hash(body)==r[1]&&(r[2] as Long)>=0);ZoneId.of(r[4] as String);ProcessingPolicy.valueOf(r[5] as String)
                append{it.string(id,512);it.i32(revision);it.string(r[1] as String,64);it.i64(r[2] as Long);it.i64(r[3] as Long);it.string(r[4] as String,128);it.string(r[5] as String,32)}
                var next:String?=null;var imageCount=0;var count=0L
                while(true){guard();val rows=attachments(sql,id,revision,next);if(rows.isEmpty())break
                    for(row in rows){guard();val i=row.intent;if(i.mediaType in setOf("image/png","image/jpeg")&&++imageCount>64)throw UnifiedReadyImageLimit()
                        append{it.string(i.id,512);it.string(i.entryId,512);it.i32(i.revision);it.string(i.mediaType,32);it.string(i.sha256,64);it.i64(i.byteSize);it.string(row.state.name,32)}
                        count=Math.addExact(count,1);next=i.id
                    };if(rows.size<20)break
                }
                require(body.isNotBlank()||count>0)
                append{it.i64(count)};sourceCount=Math.addExact(sourceCount,1);attachmentCount=Math.addExact(attachmentCount,count);after=id;guard()
            };if(page.size<20)break
        }
        append{it.i64(sourceCount);it.i64(attachmentCount)};guard();return Receipt(stamp,sourceCount,attachmentCount,packageHex(digest.digest()))
    }
    /** Each read is pinned to this run's immutable intent, not an ID authenticated by an old run. */
    suspend fun authenticate(before:Receipt,guard:()->Unit) {
        if(before.owner!==this)throw UnifiedKeywordConflict();val reader=media?:throw UnifiedReadyImagesUnsupported()
        fun current(sql:RecordsSqlConnection){guard();if(epoch(sql)!=before.epoch)throw UnifiedKeywordConflict()}
        var after:String?=null
        while(true){val page=database.transaction{sql->current(sql);sources(sql,after)};if(page.isEmpty())break
            for((id,revision)in page){var next:String?=null
                while(true){val rows=database.transaction{sql->current(sql);attachments(sql,id,revision,next)};if(rows.isEmpty())break
                    for(row in rows){guard();val i=row.intent
                        if(row.state==AttachmentState.READY&&i.mediaType in setOf("image/png","image/jpeg")){
                            // Initial intent survives the actual IO. Reusing the same ID with other bytes cannot pass.
                            val bytes=reader.read(i.id)
                            try{guard();RecordMediaPackageCipher.checkImage(RecordPackageImage(i.entryId,i.revision,i.id,i.mediaType,i.byteSize,i.sha256),bytes)
                                database.transaction{sql->current(sql);val now=attachments(sql,id,revision,next).firstOrNull{it.intent.id==i.id};if(now!=row)throw UnifiedKeywordConflict()}
                            }finally{bytes.fill(0)}
                        };next=i.id;guard()
                    };if(rows.size<20)break
                };after=id
            };if(page.size<20)break
        }
        database.transaction{validate(it,before,guard)}
    }
}
