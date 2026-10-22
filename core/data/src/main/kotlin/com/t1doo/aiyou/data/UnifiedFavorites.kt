package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.ZoneId

/** Complete source/favorite receipt, never a full-vault plaintext map or a UI snapshot. */
internal class UnifiedFavorites(private val storage:SqlRecordFavoriteRepository) {
    internal inner class Receipt(val epoch:Long,val count:Long,val digest:String) {
        val owner=this@UnifiedFavorites
        override fun toString()="UnifiedFavoriteReceipt(redacted)"
    }
    fun validate(sql:RecordsSqlConnection,before:Receipt,guard:()->Unit) {
        if(before.owner!==this)throw UnifiedKeywordConflict()
        val now=scan(sql,guard)
        if(now.epoch!=before.epoch||now.count!=before.count||now.digest!=before.digest)throw UnifiedKeywordConflict()
    }
    fun matches(sql:RecordsSqlConnection,source:SourceVersion):Boolean {
        val row=storage.metadataWithin(sql,source.sourceId)
        if(row.currentRevision.toLong()!=source.revision)throw UnifiedKeywordConflict()
        return row.favorite
    }
    fun scan(sql:RecordsSqlConnection,guard:()->Unit):Receipt {
        guard()
        val epochs=sql.query("SELECT slot,epoch FROM local_search_epoch")
        require(epochs.size==1&&epochs[0][0]==1L)
        val epoch=epochs.single()[1] as Long;require(epoch>=0&&epoch<Long.MAX_VALUE)
        var count=0L;val digest=MessageDigest.getInstance("SHA-256")
        fun append(write:(PackageWriter)->Unit){val b=PackageWriter(2048).use{write(it);it.bytes()};try{digest.update(b)}finally{b.fill(0)}}
        append{it.string("AIyou/unified/favorites/1",64);it.i64(epoch)}
        storage.visitWithin(sql,guard){favorite->
            guard();val id=favorite.id
            val size=sql.query("SELECT length(CAST(originalText AS BLOB)) FROM entry_revisions WHERE entryId=? AND revision=?",listOf(id,favorite.currentRevision)).single().single() as Long
            require(size in 0..400000)
            val s=sql.query("SELECT r.originalText,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(id)).single()
            val body=s[0] as String;require(body.length<=100000&&OriginalExports.hash(body)==s[1])
            require((s[2] as Long)>=0);ZoneId.of(s[4] as String);ProcessingPolicy.valueOf(s[5] as String)
            append{w->w.string(id,512);w.i32(favorite.currentRevision);w.i32(if(favorite.favorite)1 else 0);w.i64(favorite.version)
                w.i32(if(favorite.mutation==null)0 else 1);favorite.mutation?.let{w.string(it,512)};w.i32(favorite.sourceRevision)
                w.string(s[1] as String,64);w.i64(s[2] as Long);w.i64(s[3] as Long);w.string(s[4] as String,128);w.string(s[5] as String,32)}
            count=Math.addExact(count,1);guard()
        }
        append{it.i64(count)};guard();return Receipt(epoch,count,packageHex(digest.digest()))
    }
}
