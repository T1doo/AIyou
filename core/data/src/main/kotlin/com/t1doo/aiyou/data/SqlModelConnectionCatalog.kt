package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.concurrent.CancellationException

/** No new key or cipher: this non-secret metadata lives inside the existing SQLCipher database. */
internal class SqlModelConnectionCatalog(private val database: RecordsSqlDatabase) : ModelConnectionCatalog {
    override fun configurations(): List<ModelConnectionConfiguration> = safe { database.transaction { sql ->
        preflight(sql)
        val values = sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations ORDER BY id LIMIT 1025")
            .map(::read).mapNotNull { it.configuration }
        if (values.size > 32) throw ModelConnectionLimit()
        Collections.unmodifiableList(values)
    } }
    override fun change(request: ModelConnectionChange): ModelConnectionConfiguration? = safe { database.transaction { sql ->
        preflight(sql)
        val current = sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations WHERE id=?", listOf(request.id))
            .singleOrNull()?.let(::read)
        if (current?.mutation == request.mutationId) {
            if (current.version != request.expectedVersion + 1 || current.configuration?.details != request.details) throw ModelConnectionConflict()
            return@transaction current.configuration
        }
        if ((current?.version ?: 0) != request.expectedVersion || current?.deleted == true) throw ModelConnectionConflict()
        // Keep tombstones bounded too; never delete unknown history to make room.
        if (current == null && (sql.query("SELECT count(*) FROM model_configurations").single().single() as Long) >= 1024) throw ModelConnectionLimit()
        if (current == null && sql.query("SELECT id FROM model_configurations WHERE deleted=0 LIMIT 32").size >= 32) throw ModelConnectionLimit()
        val next = Math.addExact(request.expectedVersion, 1)
        val bytes = request.details?.let(::encode)
        if (current == null) sql.execute("INSERT INTO model_configurations VALUES(?,?,?,?,?)", listOf(request.id,next,request.mutationId,0,bytes))
        else if (sql.execute("UPDATE model_configurations SET version=?,lastMutationId=?,deleted=?,payload=? WHERE id=? AND version=? AND deleted=0",
                listOf(next,request.mutationId,if(bytes==null) 1 else 0,bytes,request.id,request.expectedVersion)) != 1) throw ModelConnectionConflict()
        read(sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations WHERE id=?", listOf(request.id)).single()).let {
            check(it.version == next && it.configuration?.details == request.details); it.configuration
        }
    } }
    private fun preflight(sql: RecordsSqlConnection) {
        val row=sql.query("SELECT count(*),COALESCE(SUM(length(payload)),0),COALESCE(MAX(length(id)),0),COALESCE(MAX(length(lastMutationId)),0),SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END) FROM model_configurations").single()
        if((row[0] as Long)>1024 || (row[4] as? Long ?: 0)>32) throw ModelConnectionLimit()
        require((row[1] as Long)<=262144 && (row[2] as Long)<=128 && (row[3] as Long)<=128)
    }
    private data class Row(val version: Long, val mutation: String, val deleted: Boolean, val configuration: ModelConnectionConfiguration?)
    private fun read(row: List<Any?>): Row {
        val id = row[0] as String; val version = row[1] as Long; val mutation = row[2] as String; val deleted = row[3] as Long
        require(id.length in 1..128 && id.none { it.isISOControl() } && version > 0 && mutation.length in 1..128 && mutation.none { it.isISOControl() })
        require(deleted in 0L..1L && ((deleted == 1L) == (row[4] == null)))
        return Row(version,mutation,deleted==1L,if(deleted==1L) null else ModelConnectionConfiguration(id,version,decode(row[4] as ByteArray)))
    }
    private fun encode(d: ModelConnectionDetails): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out -> out.writeInt(1); listOf(d.name,d.apiRoot,d.protocol.name,d.modelId).forEach(out::writeUTF)
            out.writeInt(d.purposes.size); d.purposes.sortedBy { it.ordinal }.forEach { out.writeUTF(it.name) } }
        buffer.toByteArray().also { require(it.size <= 8192) }
    }
    private fun decode(raw: ByteArray): ModelConnectionDetails {
        require(raw.size in 1..8192)
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt()==1)
            val name=input.readUTF();val root=input.readUTF();val protocol=ConnectionProtocol.valueOf(input.readUTF());val model=input.readUTF()
            val count=input.readInt();require(count in 1..5)
            val purposes=List(count){ModelConfigurationPurpose.valueOf(input.readUTF())};require(purposes.distinct().size==count && input.read()==-1)
            ModelConnectionDetails(name,root,protocol,model,purposes.toSet())
        }
    }
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: ModelConnectionConflict) { throw e }
        catch(e: ModelConnectionLimit) { throw e }
        catch(_: Exception) { throw ModelConnectionStorageFailure() }
}
