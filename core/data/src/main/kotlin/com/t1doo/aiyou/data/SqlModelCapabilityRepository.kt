package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** Borrowed SQLCipher owner. Capability observations never create source grants/jobs or modify a connection. */
internal class SqlModelCapabilityRepository(private val database: RecordsSqlDatabase,
    private val catalog: SqlModelConnectionCatalog) : ModelCapabilityRepository {
    private class Ticket(val owner: Any, override val profile: ModelCapabilityProfile) : ModelCapabilityProbeTicket {
        override fun toString()="ModelCapabilityProbeTicket(redacted)"
    }
    private data class Row(val phase: String,val profile: ModelCapabilityProfile)
    override fun profiles(): List<ModelCapabilityProfile> = safe { database.transaction { sql ->
        preflight(sql)
        Collections.unmodifiableList(sql.query("SELECT configId,generation,probeId,phase,payload FROM model_capability_profiles ORDER BY configId LIMIT 33")
            .map(::read).filter { catalog.capabilityCurrent(sql,it.profile.connection) }.map { it.profile })
    } }
    override fun begin(expected: ModelConnectionConfiguration, probeId: String, startedAtMillis: Long): ModelCapabilityProbeTicket = safe { database.transaction { sql ->
        preflight(sql)
        if(!catalog.capabilityCurrent(sql,expected)) throw ModelConnectionConflict()
        val old=row(sql,expected.id)
        if(old?.profile?.probeId==probeId) {
            if(old.profile.connection!=expected || old.profile.startedAtMillis!=startedAtMillis) throw ModelConnectionConflict()
            return@transaction Ticket(this,initial(old.profile))
        }
        val profile=ModelCapabilityProfile(expected,probeId,Math.addExact(old?.profile?.generation?:0,1),startedAtMillis,
            TextCapabilityObservation(CapabilityState.UNKNOWN,CapabilityProbeReason.IN_PROGRESS,startedAtMillis))
        val payload=ModelCapabilityPayload.encode(profile)
        if(old==null) sql.execute("INSERT INTO model_capability_profiles VALUES(?,?,?,?,?)",listOf(expected.id,profile.generation,probeId,"PENDING",payload))
        else if(sql.execute("UPDATE model_capability_profiles SET generation=?,probeId=?,phase='PENDING',payload=? WHERE configId=? AND generation=? AND probeId=?",
                listOf(profile.generation,probeId,payload,expected.id,old.profile.generation,old.profile.probeId))!=1) throw ModelConnectionConflict()
        Ticket(this,profile)
    } }
    override fun current(ticket: ModelCapabilityProbeTicket): Boolean = try { database.transaction { sql ->
        val t=owned(ticket);preflight(sql)
        val row=row(sql,t.profile.connection.id)
        row!=null&&row.phase!="FINISHED"&&initial(row.profile)==t.profile&&catalog.capabilityCurrent(sql,t.profile.connection)
    } } catch(_: Exception) { false }

    override fun admit(ticket: ModelCapabilityProbeTicket): Boolean = safe { database.transaction { sql ->
        val t=owned(ticket);preflight(sql)
        val old=row(sql,t.profile.connection.id)?:return@transaction false
        if(old.phase!="PENDING" || initial(old.profile)!=t.profile || !catalog.capabilityCurrent(sql,t.profile.connection)) return@transaction false
        sql.execute("UPDATE model_capability_profiles SET phase='ADMITTED' WHERE configId=? AND generation=? AND probeId=? AND phase='PENDING'",
            listOf(t.profile.connection.id,t.profile.generation,t.profile.probeId))==1
    } }
    override fun watch(ticket: ModelCapabilityProbeTicket,onRetired: ()->Unit): AutoCloseable =
        PersistentSourceBoundTextSubscription.open({if(current(ticket))null else SendDenial.CONFIGURATION_INVALID},{onRetired()})

    override fun finish(ticket: ModelCapabilityProbeTicket, observation: TextCapabilityObservation): ModelCapabilityProfile = safe { database.transaction { sql ->
        val t=owned(ticket);preflight(sql)
        val old=row(sql,t.profile.connection.id)?:throw ModelConnectionConflict()
        if(initial(old.profile)!=t.profile || !catalog.capabilityCurrent(sql,t.profile.connection)) throw ModelConnectionConflict()
        require(observation.reason!=CapabilityProbeReason.IN_PROGRESS && observation.checkedAtMillis>=t.profile.startedAtMillis)
        if(old.phase=="FINISHED") {
            if(old.profile.observation!=observation) throw ModelConnectionConflict()
            return@transaction old.profile // Exact uncertain-commit retry performs no UPDATE, admission or request.
        }
        if(observation.state!=CapabilityState.UNKNOWN && old.phase!="ADMITTED") throw ModelConnectionConflict()
        val saved=t.profile.copy(observation=observation)
        if(sql.execute("UPDATE model_capability_profiles SET phase='FINISHED',payload=? WHERE configId=? AND generation=? AND probeId=? AND phase=?",
            listOf(ModelCapabilityPayload.encode(saved),saved.connection.id,saved.generation,saved.probeId,old.phase))!=1) throw ModelConnectionConflict()
        saved
    } }
    override fun confirm(ticket: ModelCapabilityProbeTicket, observation: TextCapabilityObservation): ModelCapabilityProfile? = safe { database.transaction { sql ->
        val t=owned(ticket);preflight(sql)
        val old=row(sql,t.profile.connection.id)?:throw ModelConnectionConflict()
        if(initial(old.profile)!=t.profile || !catalog.capabilityCurrent(sql,t.profile.connection)) throw ModelConnectionConflict()
        if(old.phase!="FINISHED") return@transaction null
        if(old.profile.observation!=observation) throw ModelConnectionConflict()
        old.profile
    } }
    private fun owned(ticket: ModelCapabilityProbeTicket) = (ticket as? Ticket)?.takeIf {it.owner===this}?:throw ModelConnectionConflict()
    private fun initial(profile: ModelCapabilityProfile)=profile.copy(observation=TextCapabilityObservation(CapabilityState.UNKNOWN,CapabilityProbeReason.IN_PROGRESS,profile.startedAtMillis))
    private fun row(sql: RecordsSqlConnection,id: String)=sql.query("SELECT configId,generation,probeId,phase,payload FROM model_capability_profiles WHERE configId=?",listOf(id)).singleOrNull()?.let(::read)
    private fun read(row: List<Any?>): Row {
        val value=ModelCapabilityPayload.decode(row[4] as ByteArray);val phase=row[3] as String
        require(value.connection.id==row[0] && value.generation==row[1] && value.probeId==row[2] && phase in setOf("PENDING","ADMITTED","FINISHED"))
        require((phase=="FINISHED")== (value.observation.reason!=CapabilityProbeReason.IN_PROGRESS))
        return Row(phase,value)
    }
    private fun preflight(sql: RecordsSqlConnection) {
        val row=sql.query("SELECT count(*),COALESCE(SUM(length(payload)),0) FROM model_capability_profiles").single()
        require((row[0] as Long)<=32 && (row[1] as Long)<=393216)
    }
    private inline fun <T> safe(block: ()->T): T = try {block()}
        catch(e: CancellationException){throw e}
        catch(e: ModelConnectionConflict){throw e}
        catch(_: Exception){throw ModelConnectionStorageFailure()}
}
