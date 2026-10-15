package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException
import java.time.ZoneId

/** Original evidence only, read from one counted SQLCipher transaction. No file IO under the DB lock. */
internal class SqlFactRecapRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository) : FactRecapRepository {
    private inner class Proof(override val range: FactRecapRange, values: List<FactRecapCard>) : FactRecapSnapshot {
        val owner=this@SqlFactRecapRepository
        override val cards=Collections.unmodifiableList(values.toList())
        override fun toString()="FactRecapSnapshot(redacted)"
    }
    override fun capture(range: FactRecapRange): FactRecapSnapshot = safely {
        database.transaction { Proof(range,read(it,range)) }
    }
    override fun publishCurrent(snapshot: FactRecapSnapshot,publish: ()->Unit)=verify(snapshot,null,publish)
    override fun resolve(snapshot: FactRecapSnapshot,source: SourceVersion,publish: ()->Unit)=verify(snapshot,source,publish)
    private fun verify(snapshot: FactRecapSnapshot,source: SourceVersion?,publish: ()->Unit): Boolean = safely {
        val proof=snapshot as? Proof ?: return@safely false
        if(proof.owner!==this || source!=null && proof.cards.none { it.source==source }) return@safely false
        database.transaction { sql ->
            val fresh=read(sql,proof.range)
            if(fresh.size!=proof.cards.size || fresh.zip(proof.cards).any { !it.first.same(it.second) }) false else { publish();true }
        }
    }
    private fun read(sql: RecordsSqlConnection,range: FactRecapRange): List<FactRecapCard> {
        val rows=sql.query("SELECT e.id,e.currentRevision,e.capturedAtMillis,e.captureTimeZone,r.contentHash,"+
            "COALESCE(a.generation,0),length(CAST(r.originalText AS BLOB)),e.privacyPolicy FROM entries e "+
            "JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision "+
            "LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE COALESCE(a.deleted,0)=0 "+
            "AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id) "+
            "AND e.capturedAtMillis>=? AND e.capturedAtMillis<? ORDER BY e.capturedAtMillis,e.id LIMIT 1001",
            listOf(range.startMillis,range.endMillis))
        if(rows.size>1000 || rows.sumOf { it[6] as Long }>8_000_000) throw FactRecapTooBroad()
        var units=0L;var imageCount=0
        return rows.map { r ->
            val id=r[0] as String;ManualTags.identifier(id)
            val revision=Math.toIntExact(r[1] as Long);require(revision>0)
            require((r[5] as Long)>=0)
            ProcessingPolicy.valueOf(r[7] as String)
            val body=sql.query("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=?",listOf(id,revision)).single().single() as String
            units+=body.length; if(units>2_000_000) throw FactRecapTooBroad()
            require(body.length<=100_000 && OriginalExports.hash(body)==r[4]);ZoneId.of(r[3] as String)
            val imageRows=sql.query("SELECT id,mediaType,sha256,byteSize,state FROM attachments WHERE entryId=? AND revision=? ORDER BY id LIMIT 65",listOf(id,revision))
            imageCount+=imageRows.size;if(imageCount>20_000 || imageRows.size>64) throw FactRecapTooBroad()
            val images=imageRows.map { image ->
                val intent=AttachmentIntent(image[0] as String,id,revision,image[1] as String,image[2] as String,image[3] as Long)
                ManualTags.identifier(intent.id);require(intent.mediaType in setOf("image/png","image/jpeg") && intent.byteSize in 1..16_777_216 && intent.sha256.matches(Regex("[0-9a-f]{64}")))
                FactRecapImage(intent,AttachmentState.valueOf(image[4] as String))
            }
            require(body.isNotBlank() || images.isNotEmpty())
            val label=tags.readWithin(sql,id)
            val end=body.offsetByCodePoints(0,minOf(240,body.codePointCount(0,body.length)))
            FactRecapCard(SourceVersion(id,revision.toLong()),r[2] as Long,r[3] as String,r[4] as String,r[5] as Long,
                body.substring(0,end),end<body.length,label,Collections.unmodifiableList(images),ProcessingPolicy.valueOf(r[7] as String))
        }
    }
    private inline fun <T> safely(block: ()->T): T = try { block() }
        catch(e:CancellationException){throw e} catch(e:FactRecapTooBroad){throw e}
        catch(_:Exception){throw FactRecapStorageFailure()}
}
