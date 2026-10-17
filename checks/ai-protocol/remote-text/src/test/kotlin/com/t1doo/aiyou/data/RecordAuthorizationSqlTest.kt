package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Existing canonical host v8, real product SQL/codec and independent processes; NOT Room exports.
 * Synthetic complete metadata is seeded through the existing API only in this fixture. No secret
 * material/resolver, HTTP/client, AI exchange or source queue is constructed by these operations.
 */
class RecordAuthorizationSqlTest {
    @Test fun previewCancelAndMissingConnectionNeverChangeLocalDefaultOrCreateWork() = Harness(false).use { h ->
        val before=h.read();val jobs=h.f.db.read("SELECT * FROM local_jobs")
        val p=h.repo().previewAuthorization(before.binding,TextPurpose.ORGANIZATION){NOW}
        assertEquals(RecordCredentialReadiness.NOT_CONFIGURED,p.credentialReadiness);assertFalse(p.executionReady)
        assertFalse(p.authorizationCurrent)
        assertThrows(IllegalArgumentException::class.java){RecordAuthorizationChange.confirm(p,NOW,NOW.plusSeconds(60),uuid())}
        assertEquals(before,h.read());assertEquals(jobs,h.f.db.read("SELECT * FROM local_jobs"))
        assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy);h.noSending()
    }

    @Test fun completeMetadataIsNotCredentialReadinessAndExplicitConfirmationPersistsAcrossReopen() = Harness().use { h ->
        val before=h.read();assertEquals(ProcessingPolicy.LOCAL_ONLY,before.policy);assertNull(before.consent)
        val p=h.preview();assertEquals(RecordCredentialReadiness.UNVERIFIED,p.credentialReadiness)
        assertFalse(p.executionReady);assertFalse(p.authorizationCurrent)
        // Cancelling the preview is no repository mutation, even with complete stored metadata.
        assertEquals(before,h.read());assertEquals(0,h.receipts())
        val request=h.confirm(p);val saved=h.repo().changeAuthorization(request){NOW}
        assertFalse(saved.replayed);assertTrue(saved.decisionStillCurrent)
        assertEquals(request.grant,saved.current.consent);assertEquals(before.binding.generation+1,saved.current.binding.generation)
        h.f.reopen();assertEquals(saved.current,h.read())
        val reopened=h.preview();assertTrue(reopened.authorizationCurrent);assertFalse(reopened.executionReady)
        assertEquals(RecordCredentialReadiness.UNVERIFIED,reopened.credentialReadiness)
        assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy);assertEquals(1,h.receipts());h.noSending()
    }

    @Test fun actualCommittedLostAckReopensAndReplaysOneImmutableMutationWithoutExtendingConsent() = Harness().use { h ->
        val request=h.confirm();var once=true
        h.f.db.afterSql={if(it=="COMMIT"&&once){once=false;error("Synthetic lost authorization ACK after actual COMMIT")}}
        assertThrows(RecordAuthorizationStorageFailure::class.java){h.repo().changeAuthorization(request){NOW}}
        h.f.db.afterSql=null;val committed=h.read();assertEquals(request.grant,committed.consent);assertEquals(1,h.receipts())
        h.f.reopen();val replay=h.repo().changeAuthorization(request){NOW.plusSeconds(5)}
        assertTrue(replay.replayed);assertTrue(replay.decisionStillCurrent);assertEquals(committed,replay.current)
        assertEquals(request.grant!!.issuedAt,replay.current.consent!!.issuedAt)
        assertEquals(request.grant!!.expiresAt,replay.current.consent!!.expiresAt)
        assertEquals(1,h.receipts());h.noSending()
    }

    @Test fun sqlAbortRollsBackPermissionAndReceiptBeforeExactRetryAdvancesOnce() = Harness().use { h ->
        val before=h.read();val request=h.confirm();var once=true
        h.f.db.beforeCommit={if(once){once=false;error("Synthetic transaction abort before commit")}}
        assertThrows(RecordAuthorizationStorageFailure::class.java){h.repo().changeAuthorization(request){NOW}}
        h.f.db.beforeCommit=null;assertEquals(before,h.read());assertEquals(0,h.receipts())
        val result=h.repo().changeAuthorization(request){NOW};assertFalse(result.replayed)
        val replay=h.repo().changeAuthorization(request){NOW};assertTrue(replay.replayed)
        assertEquals(result.current,replay.current);assertEquals(1,h.receipts());h.noSending()
    }

    @Test fun withdrawalAndLateCommittedReplayNeverReauthorizeOrRecreateAConnection() = Harness().use { h ->
        val confirmation=h.confirm();h.repo().changeAuthorization(confirmation){NOW}
        val withdrawal=RecordAuthorizationChange.withdraw(h.read(),ProcessingPolicy.LOCAL_ONLY,uuid())
        val revoked=h.repo().changeAuthorization(withdrawal){NOW.plusSeconds(1)}
        assertEquals(ProcessingPolicy.LOCAL_ONLY,revoked.current.policy);assertNull(revoked.current.consent);assertNull(revoked.current.connection)
        val oldAck=h.repo().changeAuthorization(confirmation){NOW.plusSeconds(2)}
        assertTrue(oldAck.replayed);assertFalse(oldAck.decisionStillCurrent);assertEquals(revoked.current,oldAck.current)
        assertEquals(revoked.current,h.read())
        assertTrue(h.repo().changeAuthorization(withdrawal){NOW.plusSeconds(3)}.replayed)
        assertEquals(2,h.receipts());h.noSending()
    }

    @Test fun configurationAndPolicyAbaRejectBothFrozenFirstReadAndUncommittedOldDecision() = Harness().use { h ->
        val before=h.read();val preview=h.preview();val old=h.confirm(preview)
        h.set(h.connection.copy(modelId="synthetic-other-model"),ProcessingPolicy.AI_NONE)
        h.set(h.connection,ProcessingPolicy.LOCAL_ONLY)
        assertEquals(before.connection,h.read().connection);assertEquals(before.policy,h.read().policy)
        assertTrue(h.read().binding.generation>before.binding.generation)
        assertThrows(RecordAuthorizationConflict::class.java){h.repo().previewAuthorization(before.binding,TextPurpose.ORGANIZATION){NOW}}
        assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(old){NOW}}
        assertEquals(0,h.receipts())
        val fresh=h.confirm();h.repo().changeAuthorization(fresh){NOW};assertEquals(1,h.receipts());h.noSending()
    }

    @Test fun changedConfigurationAndMutationCollisionCannotOverwriteCurrentScopeOrReplayAsANewGrant() = Harness().use { h ->
        val first=h.confirm();h.repo().changeAuthorization(first){NOW}
        h.set(h.connection.copy(version=2,apiRoot="https://other.example.invalid/custom/"),ProcessingPolicy.LOCAL_ONLY)
        val current=h.read();val old=h.repo().changeAuthorization(first){NOW}
        assertTrue(old.replayed);assertFalse(old.decisionStillCurrent);assertEquals(current,old.current)
        val collision=h.confirm(h.preview(),first.mutationId)
        assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(collision){NOW}}
        assertEquals(current,h.read());assertEquals(1,h.receipts());h.noSending()
    }

    @Test fun sourceRevisionAbaRejectsOldPreviewAndDecisionAndNeverReappliesAKnownReceipt() = Harness().use { h ->
        val before=h.read();val uncommitted=h.confirm()
        h.f.db.write("INSERT INTO entry_revisions SELECT entryId,2,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? AND revision=1",h.id)
        fun switchTo(revision:Int){h.f.db.write("UPDATE entries SET currentRevision=? WHERE id=?",revision,h.id)}
        switchTo(2)
        assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(uncommitted){NOW}}
        switchTo(1);h.set(h.connection,ProcessingPolicy.LOCAL_ONLY)
        val returned=h.read()
        assertEquals(before.binding.source,returned.binding.source);assertEquals(before.binding.sourceHash,returned.binding.sourceHash)
        assertEquals(before.policy,returned.policy);assertEquals(before.connection,returned.connection)
        assertTrue(returned.binding.generation>before.binding.generation)
        assertThrows(RecordAuthorizationConflict::class.java){h.repo().previewAuthorization(before.binding,TextPurpose.ORGANIZATION){NOW}}
        assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(uncommitted){NOW}}
        assertEquals(0,h.receipts())
        val confirmed=h.confirm();h.repo().changeAuthorization(confirmed){NOW}
        switchTo(2);switchTo(1);h.set(h.connection,ProcessingPolicy.LOCAL_ONLY)
        val current=h.read();val late=h.repo().changeAuthorization(confirmed){NOW}
        assertTrue(late.replayed);assertFalse(late.decisionStillCurrent);assertEquals(current,late.current)
        assertEquals(current,h.read());assertNull(current.consent);assertEquals(1,h.receipts());h.noSending()
    }

    @Test fun expiryAndClockCancellationAtFinalWriteRollBackTheWholeDecision() = Harness().use { h ->
        val before=h.read();val request=h.confirm();var calls=0
        assertThrows(RecordAuthorizationNotCurrent::class.java){h.repo().changeAuthorization(request){if(++calls==1)NOW else NOW.plusSeconds(600)}}
        assertEquals(before,h.read());assertEquals(0,h.receipts())
        val cancelled=CancellationException("Synthetic trusted-clock cancellation");calls=0
        val caught=assertThrows(CancellationException::class.java){h.repo().changeAuthorization(request){if(++calls==1)NOW else throw cancelled}}
        assertSame(cancelled,caught);assertEquals(before,h.read());assertEquals(0,h.receipts())
        val saved=h.repo().changeAuthorization(request){NOW}
        val expiredAck=h.repo().changeAuthorization(request){NOW.plusSeconds(600)}
        assertTrue(expiredAck.replayed);assertFalse(expiredAck.decisionStillCurrent);assertEquals(saved.current,expiredAck.current)
        h.noSending()
    }

    @Test(timeout=15_000) fun independentSqlWritersCanConfirmOnlyOneExpectedGeneration() = Harness().use { h ->
        val db=h.f.second();val second=DrainingRecordsSqlDatabase(db)
        try {
            val p=h.preview();val a=h.confirm(p);val b=h.confirm(p)
            val start=CountDownLatch(1);val done=CountDownLatch(2);val successes=CopyOnWriteArrayList<RecordAuthorizationReceipt>();val failures=CopyOnWriteArrayList<Throwable>()
            val workers=listOf(h.repo() to a,SqlRecordAuthorizationRepository(second) to b).map{(repo,request)->thread{
                try {check(start.await(3,TimeUnit.SECONDS));successes+=repo.changeAuthorization(request){NOW}}
                catch(t:Throwable){failures+=t}finally{done.countDown()}
            }}
            start.countDown();assertTrue(done.await(7,TimeUnit.SECONDS));workers.forEach{it.join(1000);assertFalse(it.isAlive)}
            assertEquals(1,successes.size);assertEquals(1,failures.size);assertTrue(failures.single()is RecordAuthorizationConflict)
            assertEquals(1,h.receipts());assertEquals(successes.single().current,h.read());h.noSending()
        } finally {second.seal();second.awaitIdle();db.close()}
    }

    @Test fun changedHashSourceAndDeletionFencesRejectOldDecisionsWithoutRestoringAnything():Unit=runBlocking {
        Harness().use { h ->
            val before=h.read();val bad=RecordAuthorizationPreview(before.copy(binding=before.binding.copy(sourceHash="0".repeat(64))),TextPurpose.ORGANIZATION,false)
            assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(h.confirm(bad)){NOW}}
            val first=h.confirm();h.repo().changeAuthorization(first){NOW}
            val record=h.f.bin.read(h.id);val trash=h.f.bin.apply(RecycleMutation(h.source,record.generation,false,uuid()))
            assertTrue(trash.deleted)
            assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(first){NOW}}
            assertTrue(h.f.erase.erase(h.f.intent(h.id)).complete)
            assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(first){NOW}}
            assertTrue(h.f.db.read("SELECT id FROM entries WHERE id=?",h.id).isEmpty());h.noSending()
        }
    }

    private class Harness(connected:Boolean=true):AutoCloseable {
        val f=PermanentErasureFixture();val id=f.entry();val source=SourceVersion(id,1)
        // Existing, complete synthetic metadata, not a manufactured production credential or draft.
        val connection=ProviderConnection("synthetic-existing-profile",1,"https://provider.example.invalid/custom/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",SecretReference("synthetic-existing-reference"))
        init{if(connected)set(connection,ProcessingPolicy.LOCAL_ONLY)}
        fun repo()=SqlRecordAuthorizationRepository(f.gateway)
        fun read()=repo().readAuthorization(source)
        fun preview()=repo().previewAuthorization(read().binding,TextPurpose.ORGANIZATION){NOW}
        fun confirm(p:RecordAuthorizationPreview=preview(),mutation:String=uuid())=
            RecordAuthorizationChange.confirm(p,NOW,NOW.plusSeconds(600),mutation)
        fun set(connection:ProviderConnection?,policy:ProcessingPolicy){
            val raw=RemoteTextTransactions(f.gateway);val generation=raw.readAccess(id)!!.generation
            assertNull(raw.updateAuthorization(source,generation,policy,connection,null))
        }
        fun receipts()=(f.db.read("SELECT count(*) FROM local_jobs WHERE operation LIKE 'RECORD_AUTH:%'").single().single()as Long).toInt()
        fun noSending(){assertTrue(f.db.read("SELECT id FROM remote_text_jobs").isEmpty());assertTrue(f.db.read("SELECT jobId FROM text_annotations").isEmpty())}
        override fun close(){f.close()}
    }
    companion object {private val NOW=Instant.parse("2026-10-17T10:00:00Z");private fun uuid()=UUID.randomUUID().toString()}
}
