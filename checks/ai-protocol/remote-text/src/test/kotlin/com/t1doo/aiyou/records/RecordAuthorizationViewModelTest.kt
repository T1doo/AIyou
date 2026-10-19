package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual ViewModel/controller and product SQL on the existing canonical v8 host fixture.
 * Complete connection/consent metadata is SYNTHETIC fixture setup only, never a product path.
 */
class RecordAuthorizationViewModelTest {
    @Test(timeout=12_000) fun unconfiguredOriginalShowsNoReadyCapabilityAndKeepsExistingLocalChoices():Unit=Harness(false).use { h -> runBlocking {
        h.ready();val ui=h.open();assertEquals(ProcessingPolicy.LOCAL_ONLY,ui.snapshot!!.policy)
        assertEquals(RecordCredentialReadiness.NOT_CONFIGURED,ui.authorization!!.credentialReadiness)
        assertFalse(ui.authorization.executionReady);assertFalse(ui.authorization.authorizationCurrent)
        assertTrue(authorizationStatus(ui.authorization).contains("未配置"))
        h.vm.localProcessing.prepareWithdrawal(ui.session);assertNull(h.value().withdrawalConfirmation)
        h.vm.localProcessing.prepare(ui.session,LocalProcessingChoice.AI_NONE)
        h.vm.localProcessing.confirm(ui.session,h.value().confirmation!!)
        h.ui { !it.busy&&it.snapshot?.policy==ProcessingPolicy.AI_NONE }
        assertEquals(h.original,h.entryText());assertEquals(0,h.receipts());h.noSending()
    } }

    @Test(timeout=12_000) fun viewingNeverGrantsAndExplicitWithdrawalCancelRejectsRetainedConfirmation():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();val before=h.read()
        assertTrue(ui.authorization!!.authorizationCurrent);assertFalse(ui.authorization.executionReady)
        assertEquals(RecordCredentialReadiness.UNVERIFIED,ui.authorization.credentialReadiness)
        assertFalse(authorizationStatus(ui.authorization).contains(h.connection.secretRef.value))
        assertEquals(before,h.read());assertEquals(0,h.receipts())
        h.vm.localProcessing.prepareWithdrawal(ui.session);val old=h.value().withdrawalConfirmation!!
        h.vm.localProcessing.cancelWithdrawal(ui.session,old);assertEquals(before,h.read())
        h.vm.localProcessing.prepareWithdrawal(ui.session);val fresh=h.value().withdrawalConfirmation!!
        h.vm.localProcessing.confirmWithdrawal(ui.session,old);assertTrue(h.changes.isEmpty())
        h.vm.localProcessing.confirmWithdrawal(ui.session,fresh)
        val done=h.ui { !it.busy&&it.notice!=null };assertEquals(ProcessingPolicy.LOCAL_ONLY,done.snapshot!!.policy)
        assertNull(done.authorization!!.snapshot.connection);assertNull(done.authorization.snapshot.consent)
        assertEquals(1,h.receipts());assertEquals(before.binding.generation+1,h.read().binding.generation)
        assertEquals(h.original,h.entryText());h.noSending()
    } }

    @Test(timeout=12_000) fun actualCommitLostAckRetainsExactWithdrawalAcrossVisibleGenerationRefresh():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();val before=h.read();h.vm.localProcessing.prepareWithdrawal(ui.session)
        val request=h.value().withdrawalConfirmation!!;h.loseAck=true
        h.vm.localProcessing.confirmWithdrawal(ui.session,request)
        val unknown=h.ui { !it.busy&&it.error!=null };assertSame(request,unknown.pendingWithdrawal)
        assertNull(unknown.notice);assertNull(unknown.authorization);assertEquals(1,h.receipts())
        assertEquals(before.binding.generation+1,h.read().binding.generation)
        h.vm.localProcessing.retry(ui.session,unknown.request)
        val done=h.ui { !it.busy&&it.notice!=null };assertEquals(1,h.changes.size)
        assertEquals(ProcessingPolicy.LOCAL_ONLY,done.snapshot!!.policy);assertEquals(1,h.receipts());h.noSending()
    } }

    @Test(timeout=12_000) fun oldWithdrawalReceiptReportsLaterAuthorizationWithoutReapplyingOrClaimingCurrentRevocation():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.localProcessing.prepareWithdrawal(ui.session);h.loseAck=true
        h.vm.localProcessing.confirmWithdrawal(ui.session,h.value().withdrawalConfirmation!!)
        val unknown=h.ui { !it.busy&&it.error!=null }
        h.seed(h.connection.copy(version=2,modelId="synthetic-later-model"));val later=h.read()
        h.vm.localProcessing.retry(ui.session,unknown.request)
        val reconciled=h.ui { !it.busy&&it.notice!=null }
        assertEquals(later,reconciled.authorization!!.snapshot);assertEquals(later,h.read())
        assertEquals(ProcessingPolicy.REMOTE_ALLOWED,reconciled.snapshot!!.policy)
        assertTrue(reconciled.notice!!.contains("当前范围以本次读取为准"));assertFalse(reconciled.notice.contains("已撤回"))
        assertEquals(1,h.changes.size);assertEquals(1,h.receipts());h.noSending()
    } }

    @Test(timeout=12_000) fun renderedSourceAndFirstReadCannotAdoptASourceRevisionAba():Unit=Harness().use { h -> runBlocking {
        h.ready();val rendered=h.rendered()
        h.f.db.write("INSERT INTO entry_revisions SELECT entryId,2,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? AND revision=1",h.id)
        h.f.db.write("UPDATE entries SET currentRevision=2 WHERE id=?",h.id)
        h.f.db.write("UPDATE entries SET currentRevision=1 WHERE id=?",h.id);h.seed(h.connection)
        h.vm.localProcessing.open(rendered)
        val rejected=h.ui { !it.busy&&it.error!=null };assertNull(rejected.authorization);assertNull(rejected.withdrawalConfirmation)
        h.vm.localProcessing.close(rejected.session);h.vm.refreshLocalSearch()
        withTimeout(2_000){h.vm.state.first { it.localProcessingVersions[h.id]?.generation==h.read().binding.generation }}
        h.vm.localProcessing.open(rendered);assertNull(h.vm.state.value.localProcessing)
        h.open();assertTrue(h.changes.isEmpty());assertEquals(0,h.receipts());h.noSending()
    } }

    @Test(timeout=12_000) fun policyConfigurationAbaAfterPreparationCannotApplyAStaleWithdrawal():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.localProcessing.prepareWithdrawal(ui.session);val old=h.value().withdrawalConfirmation!!
        h.seed(h.connection.copy(modelId="synthetic-intermediate"));h.seed(h.connection)
        h.vm.localProcessing.confirmWithdrawal(ui.session,old)
        h.ui { !it.busy&&it.error!=null };assertEquals(ProcessingPolicy.REMOTE_ALLOWED,h.read().policy)
        assertEquals(0,h.receipts());h.noSending()
    } }

    @Test(timeout=12_000) fun trashAndPermanentDeletionRejectConfirmedOldCallbacksWithoutResurrection():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.localProcessing.prepareWithdrawal(ui.session);val old=h.value().withdrawalConfirmation!!
        val record=h.f.bin.read(h.id);h.f.bin.apply(RecycleMutation(h.source,record.generation,false,java.util.UUID.randomUUID().toString()))
        h.vm.localProcessing.confirmWithdrawal(ui.session,old)
        withTimeout(2_000){h.vm.state.first { it.localProcessing==null }}
        assertEquals(0,h.receipts());assertTrue(h.f.erase.erase(h.f.intent(h.id)).complete)
        h.vm.localProcessing.confirmWithdrawal(ui.session,old)
        assertTrue(h.f.db.read("SELECT id FROM entries WHERE id=?",h.id).isEmpty());h.noSending()
    } }

    @Test(timeout=12_000) fun replacingTheStorageOwnerRejectsTheOldRenderedButtonAndConfirmation():Unit=Harness().use { h -> runBlocking {
        h.ready();val rendered=h.rendered();val ui=h.open();h.vm.localProcessing.prepareWithdrawal(ui.session)
        val old=h.value().withdrawalConfirmation!!;val next=h.newPort();h.activePort=next;h.vm.refreshLocalSearch()
        withTimeout(2_000){h.vm.state.first { it.ready&&!it.opening&&it.recordAuthorizationOwner===next }}
        assertNull(h.vm.state.value.localProcessing);h.vm.localProcessing.open(rendered)
        h.vm.localProcessing.confirmWithdrawal(ui.session,old)
        assertNull(h.vm.state.value.localProcessing);assertTrue(h.changes.isEmpty());assertEquals(0,h.receipts());h.noSending()
    } }

    @Test(timeout=12_000) fun closingThePageRetiresADelayedReadAndDisposedCallbacksNeverWrite():Unit=Harness().use { h -> runBlocking {
        h.ready();val entered=CountDownLatch(1);val release=CountDownLatch(1);h.readGate=entered to release
        h.vm.localProcessing.open(h.rendered());assertTrue(entered.await(2,TimeUnit.SECONDS))
        val session=h.value().session;h.vm.localProcessing.close(session);release.countDown()
        h.vm.changeText("合成队列确认")
        withTimeout(2_000){h.vm.state.first { it.draftNotice=="草稿已保存在本机" }}
        assertTrue(h.completedReads.get()>0);assertNull(h.vm.state.value.localProcessing)
        val ui=h.open();h.vm.localProcessing.prepareWithdrawal(ui.session);val old=h.value().withdrawalConfirmation!!
        h.dispose();h.vm.localProcessing.confirmWithdrawal(ui.session,old);assertTrue(h.changes.isEmpty());h.noSending()
    } }

    private class Harness(connected:Boolean=true):AutoCloseable {
        val f=PermanentErasureFixture();val id=f.entry();val source=SourceVersion(id,1);val original="合成私密原文🙂"
        val connection=ProviderConnection("synthetic-existing",1,"https://provider.example.invalid/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",SecretReference("synthetic-reference"))
        val changes=CopyOnWriteArrayList<RecordAuthorizationChange>();val completedReads=AtomicInteger();var remoteCalls=0
        @Volatile var loseAck=false
        @Volatile var readGate:Pair<CountDownLatch,CountDownLatch>?=null
        fun read()=SqlRecordAuthorizationRepository(f.gateway).readAuthorization(source)
        fun seed(config:ProviderConnection){
            val access=RemoteTextTransactions(f.gateway);val before=access.readAccess(id)!!
            val grant=ConsentGrant("synthetic-grant",config.version,source,config,TextPurpose.ORGANIZATION,NOW.minusSeconds(10),NOW.plusSeconds(600))
            assertNull(access.updateAuthorization(source,before.generation,ProcessingPolicy.REMOTE_ALLOWED,config,grant))
        }
        fun newPort():RecordAuthorizationRepository {
            val sql=SqlRecordAuthorizationRepository(f.gateway)
            return object:RecordAuthorizationRepository by sql {
                override fun readAuthorization(source:SourceVersion):RecordAuthorizationSnapshot {
                    readGate?.let{readGate=null;it.first.countDown();check(it.second.await(3,TimeUnit.SECONDS))}
                    return sql.readAuthorization(source).also{completedReads.incrementAndGet()}
                }
                override fun changeAuthorization(request:RecordAuthorizationChange,clock:()->Instant):RecordAuthorizationReceipt {
                    changes+=request
                    if(loseAck)f.db.afterSql={if(it=="COMMIT"){loseAck=false;error("Synthetic lost withdrawal ACK after actual COMMIT")}}
                    return try{sql.changeAuthorization(request,clock)}finally{f.db.afterSql=null}
                }
            }
        }
        @Volatile var activePort:RecordAuthorizationRepository=newPort()
        init{if(connected)seed(connection)}
        private val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){f.gateway.stopAndAwaitIdle()}
        private val owner=object:EntryRepository by base,AutoCloseable {
            override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override val recordAuthorizations get()=activePort
            override val sourceBoundText:SourceBoundTextRepository? get(){remoteCalls++;error("No send capability")}
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==8L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(NOW,ZoneOffset.UTC),bindAi={remoteCalls++;error("No client or secret resolution")})
        suspend fun ready()=withTimeout(2_000){vm.state.first{it.ready&&!it.opening&&it.recordAuthorizationOwner!=null}}
        fun rendered()=checkNotNull(vm.state.value.authorizationReadingSource(id,1))
        suspend fun open():LocalProcessingUiState{vm.localProcessing.open(rendered());return ui{!it.busy&&it.authorization!=null}}
        fun value()=checkNotNull(vm.state.value.localProcessing)
        suspend fun ui(test:(LocalProcessingUiState)->Boolean)=withTimeout(2_000){vm.state.first{it.localProcessing?.let(test)==true}.localProcessing!!}
        fun entryText()=vm.state.value.entries.single{it.id==id}.originalText
        fun receipts()=(f.db.read("SELECT count(*) FROM local_jobs WHERE operation LIKE 'RECORD_AUTH:%'").single().single()as Long).toInt()
        fun noSending(){
            assertEquals(0,remoteCalls)
            val db=if(disposed)f.second()else f.db
            try{assertTrue(db.read("SELECT * FROM remote_text_jobs").isEmpty());assertTrue(db.read("SELECT * FROM text_annotations").isEmpty())}
            finally{if(disposed)db.close()}
        }
        private var disposed=false
        fun dispose(){if(!disposed){disposed=true;vm.clearForHostTest()};assertTrue(base.closed.await(2,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it}}
        override fun close(){try{dispose()}finally{f.close()}}
    }
    companion object {private val NOW=Instant.parse("2026-10-17T11:00:00Z")}
}
