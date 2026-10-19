package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.UUID

/** Versioned local configuration and sealed secrets in the same existing SQLCipher aggregate. */
internal class SqlModelConnectionCatalog(private val database: RecordsSqlDatabase, keyBytes: ByteArray? = null) :
    ModelConnectionCatalog, ConnectionSecretVault, AutoCloseable {
    private val cipher=keyBytes?.let(::ConnectionSecretCipher)
    @Volatile private var closed=false
    private val leaseLock=Any()
    private val issued=mutableListOf<Pair<SecretReference,OwnedConnectionSecret>>()
    override fun configurations(): List<ModelConnectionConfiguration> = safe { database.transaction { sql ->
        preflight(sql)
        val values = sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations ORDER BY id LIMIT 1025")
            .map(::read).mapNotNull { it.configuration }
        if (values.size > 32) throw ModelConnectionLimit()
        Collections.unmodifiableList(values)
    } }
    override fun change(request: ModelConnectionChange): ModelConnectionConfiguration? = retiring { safe { database.transaction { sql ->
        preflight(sql)
        val current = sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations WHERE id=?", listOf(request.id))
            .singleOrNull()?.let(::read)
        if (current?.mutation == request.mutationId) {
            if (current.kind!=0 || current.version != request.expectedVersion + 1 || current.configuration?.details != request.details) throw ModelConnectionConflict()
            return@transaction current.configuration
        }
        if ((current?.version ?: 0) != request.expectedVersion || current?.deleted == true) throw ModelConnectionConflict()
        // Keep tombstones bounded too; never delete unknown history to make room.
        if (current == null && (sql.query("SELECT count(*) FROM model_configurations").single().single() as Long) >= 1024) throw ModelConnectionLimit()
        if (current == null && sql.query("SELECT id FROM model_configurations WHERE deleted=0 LIMIT 32").size >= 32) throw ModelConnectionLimit()
        val next = Math.addExact(request.expectedVersion, 1)
        val bytes = request.details?.let { ModelConnectionPayload.encode(ModelConnectionPayload.Value(it)) }
        if (current == null) sql.execute("INSERT INTO model_configurations VALUES(?,?,?,?,?)", listOf(request.id,next,request.mutationId,0,bytes))
        else if (sql.execute("UPDATE model_configurations SET version=?,lastMutationId=?,deleted=?,payload=? WHERE id=? AND version=? AND deleted=0",
                listOf(next,request.mutationId,if(bytes==null) 1 else 0,bytes,request.id,request.expectedVersion)) != 1) throw ModelConnectionConflict()
        read(sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations WHERE id=?", listOf(request.id)).single()).let {
            check(it.version == next && it.configuration?.details == request.details); it.configuration
        }
    } } }
    private fun preflight(sql: RecordsSqlConnection) {
        val row=sql.query("SELECT count(*),COALESCE(SUM(length(payload)),0),COALESCE(MAX(length(id)),0),COALESCE(MAX(length(lastMutationId)),0),SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END) FROM model_configurations").single()
        if((row[0] as Long)>1024 || (row[4] as? Long ?: 0)>32) throw ModelConnectionLimit()
        require((row[1] as Long)<=262144 && (row[2] as Long)<=128 && (row[3] as Long)<=128)
    }
    private data class Row(val version: Long, val mutation: String, val deleted: Boolean,
        val configuration: ModelConnectionConfiguration?, val kind: Int, val receipt: SecretReference?, val cell: ByteArray?)
    private fun read(row: List<Any?>): Row {
        val id = row[0] as String; val version = row[1] as Long; val mutation = row[2] as String; val deleted = row[3] as Long
        require(id.length in 1..128 && id.none { it.isISOControl() } && version > 0 && mutation.length in 1..128 && mutation.none { it.isISOControl() })
        require(deleted in 0L..1L && ((deleted == 1L) == (row[4] == null)))
        val value=if(deleted==1L) null else ModelConnectionPayload.decode(row[4] as ByteArray)
        return Row(version,mutation,deleted==1L,value?.let { ModelConnectionConfiguration(id,version,it.details,
            it.receipt.takeIf { _->it.kind==1 }) },value?.kind?:0,value?.receipt,value?.cell)
    }
    private class Prepared(val owner: Any, override val expected: ModelConnectionConfiguration,
        val mutation: String, override val reference: SecretReference, val payload: ByteArray) : PreparedConnectionSecret {
        override fun toString()="PreparedConnectionSecret(redacted)"
    }
    override fun prepare(expected: ModelConnectionConfiguration, mutationId: String, ownedCharacters: CharArray): PreparedConnectionSecret =
        try { safe { database.transaction { sql ->
            check(!closed);val crypto=checkNotNull(cipher);preflight(sql);mutation(mutationId);require(expected.version<Long.MAX_VALUE)
            require(ownedCharacters.size in 1..2048 && ownedCharacters.all { it.code in 33..126 })
            val current=row(sql,expected.id);if(current?.configuration!=expected) throw ModelConnectionConflict()
            val ref=SecretReference("vault-v1:"+UUID.randomUUID());val next=Math.addExact(expected.version,1)
            val plain=ByteArray(ownedCharacters.size+1);plain[0]=1
            ownedCharacters.forEachIndexed { i,c->plain[i+1]=c.code.toByte() }
            val cell=try { crypto.crypt(expected.id,next,mutationId,ref,expected.details,plain,true) } finally { plain.fill(0) }
            try { Prepared(this,expected,mutationId,ref,ModelConnectionPayload.encode(ModelConnectionPayload.Value(expected.details,1,ref,cell))) }
            finally { cell.fill(0) }
        } } } finally { ownedCharacters.fill('\u0000') }

    override fun apply(prepared: PreparedConnectionSecret): ModelConnectionConfiguration = retiring { safe { database.transaction { sql ->
        check(!closed);val p=prepared as? Prepared?:throw ModelConnectionConflict();if(p.owner!==this) throw ModelConnectionConflict()
        preflight(sql);val old=row(sql,p.expected.id)?:throw ModelConnectionConflict()
        if(old.mutation==p.mutation) {
            if(old.deleted||old.kind!=1||old.version!=p.expected.version+1||old.configuration==null) throw ModelConnectionConflict()
            val payload=sql.query("SELECT payload FROM model_configurations WHERE id=?",listOf(p.expected.id)).single().single() as ByteArray
            if(!payload.contentEquals(p.payload)) throw ModelConnectionConflict()
            return@transaction checkNotNull(old.configuration)
        }
        if(old.configuration!=p.expected) throw ModelConnectionConflict()
        val value=ModelConnectionPayload.decode(p.payload)
        val plain=checkNotNull(cipher).crypt(p.expected.id,p.expected.version+1,p.mutation,p.reference,p.expected.details,checkNotNull(value.cell),false)
        try { validatePlain(plain) } finally { plain.fill(0) }
        update(sql,p.expected,p.mutation,p.payload)
    } } }

    override fun revoke(expected: ModelConnectionConfiguration, mutationId: String): ModelConnectionConfiguration = retiring { safe { database.transaction { sql ->
        check(!closed);checkNotNull(cipher);preflight(sql);mutation(mutationId);require(expected.version<Long.MAX_VALUE)
        val ref=expected.secretRef?:throw ModelConnectionConflict();val old=row(sql,expected.id)?:throw ModelConnectionConflict()
        if(old.mutation==mutationId) {
            if(old.kind!=2||old.receipt!=ref||old.version!=expected.version+1||old.configuration?.details!=expected.details) throw ModelConnectionConflict()
            return@transaction checkNotNull(old.configuration)
        }
        if(old.configuration!=expected) throw ModelConnectionConflict()
        update(sql,expected,mutationId,ModelConnectionPayload.encode(ModelConnectionPayload.Value(expected.details,2,ref)))
    } } }

    private fun mutation(value: String) { require(value.length in 1..128 && value.none { it.isISOControl() }) }
    private fun row(sql: RecordsSqlConnection,id: String)=sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations WHERE id=?",listOf(id)).singleOrNull()?.let(::read)
    private fun update(sql: RecordsSqlConnection,expected: ModelConnectionConfiguration,mutation: String,payload: ByteArray): ModelConnectionConfiguration {
        if(sql.execute("UPDATE model_configurations SET version=?,lastMutationId=?,payload=? WHERE id=? AND version=? AND deleted=0",
            listOf(expected.version+1,mutation,payload,expected.id,expected.version))!=1) throw ModelConnectionConflict()
        return checkNotNull(row(sql,expected.id)?.configuration)
    }
    private fun referenced(sql: RecordsSqlConnection,ref: SecretReference): Row? {
        preflight(sql)
        return sql.query("SELECT id,version,lastMutationId,deleted,payload FROM model_configurations ORDER BY id LIMIT 1025")
            .map(::read).singleOrNull { it.configuration?.secretRef==ref }
    }
    override fun current(reference: SecretReference): Boolean = try {
        if(closed||cipher==null) false else database.transaction { sql ->
            referenced(sql,reference)?.let { authenticate(it);!closed }?:false
        }
    } catch(_: Exception) { false }

    override fun resolve(reference: SecretReference): OwnedConnectionSecret? {
        val value=safe { database.transaction { sql ->
            check(!closed);val crypto=checkNotNull(cipher);val row=referenced(sql,reference)?:return@transaction null
            val config=checkNotNull(row.configuration)
            val plain=crypto.crypt(config.id,config.version,row.mutation,reference,config.details,checkNotNull(row.cell),false)
            try { validatePlain(plain)
                OwnedConnectionSecret.take(CharArray(plain.size-1){(plain[it+1].toInt() and 255).toChar()}) }
            finally { plain.fill(0) }
        } }?:return null
        synchronized(leaseLock) {
            issued.removeAll { it.second.isClosed }
            if(closed||issued.size>=32) { value.close();throw ModelConnectionStorageFailure() }
            issued.add(reference to value)
        }
        if(!current(reference)) { value.close();return null };return value
    }
    override fun watch(reference: SecretReference,onRetired: ()->Unit): AutoCloseable =
        PersistentSourceBoundTextSubscription.open({ if(current(reference)) null else SendDenial.SECRET_UNAVAILABLE },{ onRetired() })
    private inline fun <T> retiring(block: ()->T): T = try { block() } finally { retireLeases() }
    private fun retireLeases() {
        val values=synchronized(leaseLock) { issued.toList() }
        values.forEach { (ref,value)->if(!current(ref))value.close() }
        synchronized(leaseLock) { issued.removeAll { it.second.isClosed } }
    }
    override fun close() {
        closed=true
        val values=synchronized(leaseLock) { issued.map { it.second }.also { issued.clear() } }
        values.forEach { it.close() }
    }
    internal fun connectionCurrent(sql: RecordsSqlConnection,connection: ProviderConnection,purpose: TextPurpose): Boolean {
        if(closed||cipher==null) return false
        preflight(sql);val value=row(sql,connection.providerId)?:return false;val config=value.configuration?:return false
        if(config.secretRef==null || !runCatching { config.textConnection(purpose)==connection }.getOrDefault(false)) return false
        authenticate(value);return !closed
    }
    internal fun capabilityCurrent(sql: RecordsSqlConnection, expected: ModelConnectionConfiguration): Boolean {
        if(closed||cipher==null||expected.secretRef==null) return false
        preflight(sql);val value=row(sql,expected.id)?:return false
        if(value.configuration!=expected) return false
        authenticate(value);return !closed
    }
    private fun authenticate(row: Row) {
        val c=checkNotNull(row.configuration);val ref=checkNotNull(c.secretRef)
        val plain=checkNotNull(cipher).crypt(c.id,c.version,row.mutation,ref,c.details,checkNotNull(row.cell),false)
        try { validatePlain(plain) } finally { plain.fill(0) }
    }
    private fun validatePlain(plain: ByteArray) {
        require(plain.size in 2..2049 && plain[0]==1.toByte() && (1 until plain.size).all { (plain[it].toInt() and 255) in 33..126 })
    }
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: ModelConnectionConflict) { throw e }
        catch(e: ModelConnectionLimit) { throw e }
        catch(_: Exception) { throw ModelConnectionStorageFailure() }
}
