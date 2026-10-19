package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production controller with a driven owned queue; persistence/protocol ports are explicit synthetic fakes. */
class ModelCapabilityControllerTest {
    @Test fun explicitReviewRunsOneSampleAndResultOnlyRetryNeverCreatesAnotherExchange():Unit=runBlocking {
        val h=Harness();val s=h.open();assertEquals(0,h.calls)
        h.controller.beginProbe(s,h.config);assertEquals(0,h.calls)
        h.profiles.loseAck=true;h.controller.runProbe(s,h.editor());val running=h.editor();h.drain()
        val uncertain=h.editor();assertNotNull(uncertain.pending);assertFalse(uncertain.busy)
        assertEquals(1,h.calls);assertEquals(1,h.profiles.begins)
        h.controller.runProbe(s,uncertain);h.drain();assertEquals(1,h.calls)
        h.controller.retryProbePersistence(s,uncertain);h.drain()
        assertEquals(1,h.calls);assertEquals(1,h.exchanges);assertEquals(2,h.profiles.finishes)
        assertSame(h.profiles.observations[0],h.profiles.observations[1]);assertNull(h.controller.visible().probeEditor)
        h.controller.runProbe(s,running);h.drain();assertEquals(1,h.calls)
        assertEquals(CapabilityState.SUPPORTED,h.controller.visible().capabilityProfiles.single().observation.state)
        assertEquals("合成当前草稿",h.local.text);assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy)
    }
    @Test fun oldRunningStopStillRetiresSameReviewDuringSlowPersistenceButCannotCloseNewReview():Unit=runBlocking {
        val h=Harness();val s=h.open();h.controller.beginProbe(s,h.config);h.controller.runProbe(s,h.editor())
        val oldRunning=h.editor()
        h.profiles.beforeFinish={
            assertNotSame(oldRunning,h.editor())
            assertEquals(oldRunning.reviewId,h.editor().reviewId)
            h.controller.cancelProbe(s,oldRunning)
            assertNull(h.controller.visible().probeEditor);assertTrue(h.controller.visible().busy)
        }
        h.one();assertNull(h.controller.visible().probeEditor)
        assertTrue(h.controller.visible().capabilityProfiles.isEmpty()) // The late callback did not fill it.
        h.profiles.beforeFinish=null;h.drain() // A distinct fresh SQL/port read may confirm admitted persistence.
        h.controller.beginProbe(s,h.config);val replacement=h.editor();assertNotEquals(oldRunning.reviewId,replacement.reviewId)
        h.controller.cancelProbe(s,oldRunning);assertSame(replacement,h.editor());assertTrue(h.cancellations>0)
    }
    @Test fun derivedArchiveReadFailureHidesSupportButNeverDisablesCredentialRevokeOrConfigurationEditing():Unit=runBlocking {
        val h=Harness();val s=h.open();h.controller.beginProbe(s,h.config);h.controller.runProbe(s,h.editor());h.drain()
        assertEquals(CapabilityState.SUPPORTED,h.controller.visible().capabilityProfiles.single().observation.state)
        h.profiles.readFailure=true;h.controller.reload(s);h.drain()
        val ui=h.controller.visible();assertNull(ui.error);assertNotNull(ui.capabilityError)
        assertEquals(listOf(h.config),ui.configurations);assertTrue(ui.capabilityProfiles.isEmpty())
        h.controller.beginProbe(s,h.config);assertNull(h.controller.visible().probeEditor)
        h.controller.beginSecret(s,h.config);assertNotNull(h.controller.visible().secretEditor)
        h.controller.confirmSecretRevoke(s,h.controller.visible().secretEditor!!)
        assertTrue(h.controller.visible().secretEditor!!.confirmRevoke)
        h.controller.cancelSecret(s,h.controller.visible().secretEditor!!)
        h.controller.begin(s,h.config);assertNotNull(h.controller.visible().editor)
    }
    @Test fun retiredQueueOwnerReplacementAndLateNonCancellableCompletionCannotFillOrPersistIntoNewPage():Unit=runBlocking {
        val h=Harness();val first=h.open();h.controller.beginProbe(first,h.config);val old=h.editor()
        h.controller.runProbe(first,old);h.controller.close(first);val second=h.open();h.drain()
        assertEquals(0,h.calls);assertEquals(0,h.profiles.begins)
        h.controller.runProbe(first,old);assertNull(h.controller.visible().probeEditor)
        h.controller.beginProbe(second,h.config)
        h.duringExecute={ h.controller.close(second);h.controller.open(h.catalog) }
        h.controller.runProbe(second,h.editor());h.one()
        assertEquals(1,h.calls);assertEquals(0,h.profiles.finishes)
        assertTrue(h.controller.visible().busy);assertTrue(h.controller.visible().capabilityProfiles.isEmpty())
        h.drain();assertNull(h.controller.visible().probeEditor)
        val third=h.controller.visible().session;h.controller.beginProbe(third,h.config);val review=h.editor()
        h.local=h.local.copy(modelCapabilityOwner=Profiles({h.config}))
        h.controller.runProbe(third,review);h.drain();assertEquals(1,h.calls)
        assertTrue(h.controller.visible().configurations.isEmpty());assertNotNull(h.controller.visible().error)
    }
    @Test fun defaultProductionGateNeverBeginsOrResolvesAndConflictDoesNotAdoptANewConfiguration():Unit=runBlocking {
        val closed=Harness(enabled=false);val s=closed.open();closed.controller.beginProbe(s,closed.config)
        assertNull(closed.controller.visible().probeEditor);assertEquals(0,closed.profiles.begins);assertEquals(0,closed.calls)
        val h=Harness();val session=h.open();h.controller.beginProbe(session,h.config);h.config=h.config.copy(version=3)
        h.controller.runProbe(session,h.editor());h.drain()
        assertTrue(h.editor().conflict);assertEquals(0,h.calls);assertEquals(3L,h.config.version)
        h.controller.retryProbePersistence(session,h.editor());h.drain();assertEquals(0,h.calls)
    }
    private class Profiles(val config:()->ModelConnectionConfiguration):ModelCapabilityRepository {
        var value:ModelCapabilityProfile?=null;var begins=0;var finishes=0;var readFailure=false;var loseAck=false
        var beforeFinish:(()->Unit)?=null;val observations=mutableListOf<TextCapabilityObservation>()
        override fun profiles():List<ModelCapabilityProfile> {
            if(readFailure)throw IOException("Synthetic archive read failure")
            return listOfNotNull(value?.takeIf {it.connection==config()})
        }
        override fun begin(expected:ModelConnectionConfiguration,probeId:String,startedAtMillis:Long):ModelCapabilityProbeTicket {
            if(expected!=config())throw ModelConnectionConflict();begins++
            val p=ModelCapabilityProfile(expected,probeId,begins.toLong(),startedAtMillis,
                TextCapabilityObservation(CapabilityState.UNKNOWN,CapabilityProbeReason.IN_PROGRESS,startedAtMillis))
            value=p;return object:ModelCapabilityProbeTicket {override val profile=p}
        }
        override fun current(ticket:ModelCapabilityProbeTicket)=ticket.profile.connection==config()&&value?.probeId==ticket.profile.probeId
        override fun admit(ticket:ModelCapabilityProbeTicket)=current(ticket)
        override fun watch(ticket:ModelCapabilityProbeTicket,onRetired:()->Unit)=AutoCloseable { }
        override fun finish(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile {
            beforeFinish?.invoke();finishes++;observations+=observation
            if(!current(ticket))throw ModelConnectionConflict()
            return ticket.profile.copy(observation=observation).also {
                value=it;if(loseAck){loseAck=false;throw IOException("Synthetic committed archive ACK loss")}
            }
        }
    }
    private class Harness(enabled:Boolean=true) {
        var config=ModelConnectionConfiguration("synthetic-config",2,ModelConnectionDetails("合成连接","https://example.test/custom/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION)),SecretReference("synthetic-key-reference"))
        val catalog=object:ModelConnectionCatalog {
            override fun configurations()=listOf(config)
            override fun change(request:ModelConnectionChange):ModelConnectionConfiguration?=error("No catalog write in a probe")
        }
        val profiles=Profiles({config})
        val vault=object:ConnectionSecretVault {
            override fun prepare(expected:ModelConnectionConfiguration,mutationId:String,ownedCharacters:CharArray):PreparedConnectionSecret=error("No key writes")
            override fun apply(prepared:PreparedConnectionSecret):ModelConnectionConfiguration=error("No key writes")
            override fun revoke(expected:ModelConnectionConfiguration,mutationId:String):ModelConnectionConfiguration=error("No key writes")
            override fun current(reference:SecretReference)=reference==config.secretRef
            override fun resolve(reference:SecretReference):OwnedConnectionSecret?=error("Controller cannot resolve key material")
            override fun watch(reference:SecretReference,onRetired:()->Unit):AutoCloseable=error("Controller cannot send")
        }
        var local=LocalRecordUiState(ready=true,opening=false,text="合成当前草稿",modelConnectionOwner=catalog,modelSecretOwner=vault,modelCapabilityOwner=profiles)
        private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        var calls=0;var exchanges=0;var cancellations=0;var duringExecute:(()->Unit)?=null
        private val probe=object:ModelCapabilityProbe {
            override fun newExchange(ticket:ModelCapabilityProbeTicket):ModelCapabilityProbeExchange {
                exchanges++
                return object:ModelCapabilityProbeExchange {
                    override fun execute():TextCapabilityObservation {calls++;duringExecute?.invoke()
                        return TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,NOW.toEpochMilli())}
                    override fun cancel(){cancellations++}
                }
            }
        }
        private val store=object:EntryRepository {
            override val modelConnections=catalog
            override val modelCapabilities=profiles
            override val localModelConnections get()=LocalModelConnectionServices(vault,object:SourceBoundTextRepository {
                override fun queue(intent:RemoteSendIntent,promptVersion:String,clock:()->Instant):SourceQueueOutcome=error("No record authority")
                override fun check(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No record authority")
                override fun subscribe(job:SourceBoundTextJob,clock:()->Instant,onInvalidated:(SendDenial)->Unit):SourceBoundTextSubscription=error("No record authority")
                override fun admit(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No record authority")
                override fun commit(job:SourceBoundTextJob,candidate:DerivedTextCandidate,clock:()->Instant):SourceCommitOutcome=error("No record authority")
                override fun cancel(job:SourceBoundTextJob):Unit=error("No record authority")
            })
            override suspend fun readDraft():TextDraft?=error("No draft reads")
            override suspend fun updateDraft(text:String):TextDraft=error("No draft writes")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No original writes")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No original writes")
        }
        val controller=ModelConnectionsController({local},{queue.addLast(it);true},probe.takeIf {enabled},Clock.fixed(NOW,ZoneOffset.UTC))
        suspend fun open():Long=controller.open(catalog).also {drain()}
        fun editor()=checkNotNull(controller.visible().probeEditor)
        suspend fun one(){queue.removeFirst()(store)}
        suspend fun drain(){while(queue.isNotEmpty())one()}
    }
    companion object {private val NOW=Instant.parse("2026-10-19T10:00:00Z")}
}
