package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Independent HKDF-Expand purpose from the already random 32-byte database key. */
internal class ManualTagValueIds(databaseKey:ByteArray) {
    init {require(databaseKey.size==32)}
    private val key=mac(databaseKey,"AIyou/manual-tag-selector/key/1".toByteArray(Charsets.UTF_8)+byteArrayOf(1))
    private var wiped=false
    fun id(name:String):String {
        check(!wiped);require(ManualTags.canonical(listOf(name))==listOf(name))
        val input=PackageWriter(256).use{it.string("AIyou/manual-tag-selector/value/1",64);it.string(name,160);it.bytes()}
        return try{packageHex(mac(key,input))}finally{input.fill(0)}
    }
    fun wipe(){key.fill(0);wiped=true}
    private fun mac(key:ByteArray,bytes:ByteArray):ByteArray=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(key,"HmacSHA256"));doFinal(bytes)}
}

/** No all-vault name map: complete streaming authentication and at most 21 retained values. */
internal class UnifiedManualTags(private val database:RecordsSqlDatabase,private val tags:SqlManualTagRepository) {
    private data class Receipt(val epoch:Long,val count:Long,val digest:String)
    private class Choice(override val id:String,override val name:String):UnifiedTagChoice { override fun toString()="UnifiedTagChoice(redacted)" }
    private inner class Page(val receipt:Receipt,values:List<Choice>,override val hasNext:Boolean):UnifiedTagPage {
        val owner=this@UnifiedManualTags
        override val choices:List<UnifiedTagChoice> = java.util.Collections.unmodifiableList(values.toList())
        override fun toString()="UnifiedTagPage(redacted)"
    }
    private inner class Selection(override val id:String,override val name:String,val receipt:Receipt):UnifiedTagSelection {
        val owner=this@UnifiedManualTags
        override fun toString()="UnifiedTagSelection(redacted)"
    }
    private class Scan(val receipt:Receipt,val values:List<Choice>,val found:Boolean)
    private val names=Comparator<String>{a,b->
        var x=0;var y=0;var result=0
        while(x<a.length&&y<b.length){val p=a.codePointAt(x);val q=b.codePointAt(y);if(p!=q){result=p.compareTo(q);break};x+=Character.charCount(p);y+=Character.charCount(q)}
        if(result!=0)result else (a.length-x).compareTo(b.length-y)
    }
    private fun selection(value:UnifiedTagSelection):Selection=(value as? Selection)?.takeIf{it.owner===this}?:throw UnifiedKeywordConflict()
    private fun page(value:UnifiedTagPage):Page=(value as? Page)?.takeIf{it.owner===this}?:throw UnifiedKeywordConflict()
    fun choices(after:UnifiedTagPage?,guard:()->Unit):UnifiedTagPage=database.transaction{sql->
        val previous=after?.let(::page);if(previous!=null&&!previous.hasNext)throw UnifiedKeywordConflict()
        val scan=scan(sql,previous?.choices?.lastOrNull()?.name,null,guard)
        if(previous!=null&&previous.receipt!=scan.receipt)throw UnifiedKeywordConflict()
        Page(scan.receipt,scan.values.take(20),scan.values.size>20)
    }
    fun select(value:UnifiedTagPage,choice:UnifiedTagChoice,guard:()->Unit):UnifiedTagSelection=database.transaction{sql->
        val p=page(value);if(p.choices.none{it===choice})throw UnifiedKeywordConflict()
        val expected=Selection(choice.id,choice.name,p.receipt)
        val scan=scan(sql,null,expected,guard)
        if(scan.receipt!=p.receipt||!scan.found)throw UnifiedKeywordConflict()
        Selection(expected.id,expected.name,scan.receipt)
    }
    fun refresh(value:UnifiedTagSelection,guard:()->Unit):UnifiedTagSelection=database.transaction{sql->
        val s=selection(value);val scan=scan(sql,null,s,guard)
        if(!scan.found)throw UnifiedKeywordConflict()
        Selection(s.id,s.name,scan.receipt)
    }
    fun validate(sql:RecordsSqlConnection,value:UnifiedTagSelection,guard:()->Unit):String {
        val s=selection(value);val scan=scan(sql,null,s,guard)
        if(!scan.found||scan.receipt!=s.receipt)throw UnifiedKeywordConflict()
        return s.name
    }
    /** Authenticates even a tag outside the requested intersection; never catches corruption as false. */
    fun matches(sql:RecordsSqlConnection,source:SourceVersion,value:UnifiedTagSelection):Boolean {
        val s=selection(value);val row=tags.readWithin(sql,source.sourceId)
        var found=false
        row.names.forEach{val id=tags.valueId(it);if(id==s.id){if(it!=s.name)throw UnifiedKeywordConflict();found=true}}
        return found
    }
    private fun scan(sql:RecordsSqlConnection,after:String?,expected:Selection?,guard:()->Unit):Scan {
        guard();val epoch=sql.query("SELECT epoch FROM local_search_epoch WHERE slot=1").single().single() as Long;require(epoch>=0)
        val digest=MessageDigest.getInstance("SHA-256");var count=0L;var cursor:String?=null;var found=false
        val top=java.util.TreeMap<String,Choice>(names)
        fun append(write:(PackageWriter)->Unit){val bytes=PackageWriter(4096).use{write(it);it.bytes()};try{digest.update(bytes)}finally{bytes.fill(0)}}
        append{it.string("AIyou/unified/manual-tags/1",64);it.i64(epoch)}
        do {
            guard()
            val rows=sql.query("SELECT e.id,length(CAST(r.originalText AS BLOB)),length(t.ciphertext) FROM entries e "+
                "JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision "+
                "LEFT JOIN entry_manual_tags t ON t.entryId=e.id LEFT JOIN remote_source_access a ON a.entryId=e.id "+
                "WHERE COALESCE(a.deleted,0)=0 AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"+
                (if(cursor==null)"" else " AND e.id COLLATE BINARY > ? COLLATE BINARY")+" ORDER BY e.id COLLATE BINARY LIMIT 20",
                if(cursor==null)emptyList()else listOf(cursor))
            rows.forEach{meta->
                guard();require((meta[1] as Long) in 0..400000);meta[2]?.let{require((it as Long) in 29..8192)}
                val id=meta[0] as String
                val source=sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(id)).single()
                val body=source[1] as String;require(body.length<=100000&&OriginalExports.hash(body)==source[2]);ZoneId.of(source[5] as String);ProcessingPolicy.valueOf(source[6] as String)
                val t=tags.readWithin(sql,id)
                append{w->w.string(id,512);w.i64(source[0] as Long);w.string(source[2] as String,64);w.i64(source[3] as Long);w.i64(source[4] as Long);w.string(source[5] as String,128);w.string(source[6] as String,32);w.i64(t.version);w.strings(t.names,12,160)}
                t.names.forEach{name->
                    val valueId=tags.valueId(name)
                    if(expected!=null&&valueId==expected.id){if(name!=expected.name)throw UnifiedKeywordConflict();found=true}
                    if(after==null||names.compare(name,after)>0){
                        if(top.values.any{it.id==valueId&&it.name!=name})throw UnifiedKeywordConflict()
                        top[name]=Choice(valueId,name);if(top.size>21)top.pollLastEntry()
                    }
                }
                count=Math.addExact(count,1);guard()
            }
            cursor=rows.lastOrNull()?.get(0) as? String
        }while(rows.size==20)
        append{it.i64(count)};guard()
        return Scan(Receipt(epoch,count,packageHex(digest.digest())),top.values.toList(),found)
    }
}
