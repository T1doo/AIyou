package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual production codecs/AEAD/CAS/SQL; synthetic materials, never Android Keystore/device evidence. */
class ConnectionSecretVaultTest {
    @Test fun legacyRowsStayReadableAndColdEncryptedReferenceRotationAndDeleteRetireOwnedMemory() {
        ConnectionCatalogFixture().use { f ->
            val key=key();val vault=SqlModelConnectionCatalog(f.db,key);val initial=create(vault)
            val chars=material();val intent=vault.prepare(initial,"put",chars);assertTrue(chars.all { it=='\u0000' })
            val bound=vault.apply(intent);assertEquals(2L,bound.version);assertEquals(intent.reference,bound.secretRef)
            assertFalse(String(payload(f),Charsets.ISO_8859_1).contains(MATERIAL));assertEquals(listOf(bound),f.catalog.configurations())
            val lease=vault.resolve(bound.secretRef!!)!!;val copy=lease.copyCharacters()
            try { assertArrayEquals(material(),copy) } finally { copy.fill('\u0000') }
            vault.close();assertTrue(lease.isClosed);f.reopen()
            val cold=SqlModelConnectionCatalog(f.db,key);assertTrue(cold.current(bound.secretRef!!))
            cold.resolve(bound.secretRef!!)!!.use { val c=it.copyCharacters();try { assertArrayEquals(material(),c) } finally { c.fill('\u0000') } }
            val rotated=cold.apply(cold.prepare(bound,"rotate",material()));assertNotEquals(bound.secretRef,rotated.secretRef)
            assertFalse(cold.current(bound.secretRef!!));assertNull(cold.resolve(bound.secretRef!!))
            val active=cold.resolve(rotated.secretRef!!)!!
            val edited=cold.change(ModelConnectionChange(rotated.id,rotated.version,"edit",rotated.details))!!
            assertNull(edited.secretRef);assertTrue(active.isClosed)
            val rebound=cold.apply(cold.prepare(edited,"again",material()));val last=cold.resolve(rebound.secretRef!!)!!
            assertNull(cold.change(ModelConnectionChange(rebound.id,rebound.version,"delete",null)))
            assertTrue(last.isClosed);assertFalse(cold.current(rebound.secretRef!!))
            assertEquals("LOCAL_ONLY",f.db.read("SELECT privacyPolicy FROM entries").single().single())
            assertEquals("合成独立草稿",f.db.read("SELECT text FROM capture_draft").single().single());cold.close();key.fill(0)
        }
    }
    @Test fun sealedIntentLostAcknowledgementConfirmsExactCiphertextAndCrossOperationReplayConflicts() {
        ConnectionCatalogFixture().use { f ->
            val vault=SqlModelConnectionCatalog(f.db,key());val initial=create(vault);val p=vault.prepare(initial,"put",material());var wrote=false
            f.db.afterSql={s->if(s.startsWith("UPDATE model_configurations"))wrote=true
                if(s=="COMMIT"&&wrote){wrote=false;throw IOException("Synthetic lost ACK")} }
            assertThrows(ModelConnectionStorageFailure::class.java){vault.apply(p)};f.db.afterSql=null
            val before=payload(f);val bound=vault.apply(p);assertEquals(2L,bound.version);assertArrayEquals(before,payload(f))
            assertThrows(ModelConnectionConflict::class.java){vault.change(ModelConnectionChange(bound.id,1,"put",bound.details))}
            val oldLease=vault.resolve(bound.secretRef!!)!!
            f.db.afterSql={s->if(s.startsWith("UPDATE model_configurations"))wrote=true
                if(s=="COMMIT"&&wrote){wrote=false;throw IOException("Synthetic revoke ACK loss")} }
            assertThrows(ModelConnectionStorageFailure::class.java){vault.revoke(bound,"revoke")};f.db.afterSql=null
            assertTrue("Committed revocation must wipe a previously issued lease even after ACK loss",oldLease.isClosed)
            val revoked=vault.revoke(bound,"revoke");assertNull(revoked.secretRef);assertEquals(revoked,vault.revoke(bound,"revoke"))
            assertThrows(ModelConnectionConflict::class.java){vault.change(ModelConnectionChange(bound.id,bound.version,"revoke",bound.details))}
            assertThrows(ModelConnectionConflict::class.java){vault.apply(p)}
            val forged=object:PreparedConnectionSecret { override val expected=revoked;override val reference=p.reference }
            assertThrows(ModelConnectionConflict::class.java){vault.apply(forged)}
            assertThrows(ModelConnectionConflict::class.java){SqlModelConnectionCatalog(f.db,key()).apply(p)}
            val edited=vault.change(ModelConnectionChange(revoked.id,revoked.version,"between",revoked.details))!!
            assertNull(vault.change(ModelConnectionChange(edited.id,edited.version,"put",null)))
            val tombstone=f.db.read("SELECT version,lastMutationId,deleted,payload FROM model_configurations WHERE id='config'").single()
            assertThrows(ModelConnectionConflict::class.java){vault.apply(p)}
            assertEquals(tombstone,f.db.read("SELECT version,lastMutationId,deleted,payload FROM model_configurations WHERE id='config'").single())
            assertTrue(vault.configurations().isEmpty());vault.close()
        }
    }
    @Test fun abortAndInvalidOrOversizedInputPreserveOriginalBytesAndClearEveryTransferredArray() {
        ConnectionCatalogFixture().use { f ->
            val vault=SqlModelConnectionCatalog(f.db,key());val initial=create(vault);val p=vault.prepare(initial,"put",material());val before=payload(f)
            f.db.write("CREATE TEMP TRIGGER synthetic_vault_abort BEFORE UPDATE ON model_configurations BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
            assertThrows(ModelConnectionStorageFailure::class.java){vault.apply(p)};assertArrayEquals(before,payload(f))
            f.db.write("DROP TRIGGER synthetic_vault_abort");assertEquals(2L,vault.apply(p).version)
            for(chars in listOf("synthetic\ninvalid".toCharArray(),CharArray(2049){'X'})) {
                assertThrows(ModelConnectionStorageFailure::class.java){vault.prepare(initial,"bad",chars)};assertTrue(chars.all { it=='\u0000' })
            }
            val large=ModelConnectionDetails("中".repeat(80),"https://example.test/"+"中".repeat(2010),ConnectionProtocol.CHAT_COMPLETIONS,"中".repeat(256),ModelConfigurationPurpose.entries.toSet())
            val row=vault.change(ModelConnectionChange("large",0,"large",large))!!;val previous=f.db.read("SELECT payload FROM model_configurations WHERE id='large'").single().single() as ByteArray
            val oversized=CharArray(2048){'X'};assertThrows(ModelConnectionStorageFailure::class.java){vault.prepare(row,"oversized",oversized)}
            assertTrue(oversized.all { it=='\u0000' });assertArrayEquals(previous,f.db.read("SELECT payload FROM model_configurations WHERE id='large'").single().single() as ByteArray);vault.close()
        }
    }
    @Test fun authenticatedCellCannotMoveBetweenConfigurationVersionsDestinationsOrOwners() {
        ConnectionCatalogFixture().use { f ->
            val vault=SqlModelConnectionCatalog(f.db,key());val bound=vault.apply(vault.prepare(create(vault),"put",material()));val bytes=payload(f)
            val second=vault.change(ModelConnectionChange("other",0,"other",bound.details))!!
            vault.change(ModelConnectionChange(bound.id,bound.version,"clear",bound.details))
            f.db.write("UPDATE model_configurations SET version=2,lastMutationId='put',payload=? WHERE id=?",bytes,second.id)
            assertFalse(vault.current(bound.secretRef!!));assertThrows(ModelConnectionStorageFailure::class.java){vault.resolve(bound.secretRef!!)}
            assertEquals(2,f.db.read("SELECT id FROM model_configurations").size)
            val config=vault.configurations().single { it.id==second.id }
            assertFalse(vault.connectionCurrentForTest(f,config.textConnection(TextPurpose.ORGANIZATION)))
            f.db.write("UPDATE model_configurations SET version=3,lastMutationId='tampered',payload=? WHERE id=?",bytes.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() },second.id)
            assertFalse(vault.current(bound.secretRef!!));vault.close()
        }
    }
    @Test fun strictTransactionRejectsRevocationBeforeAdmitCommitAndAlreadyCommittedReuseWithoutErasingHistory() {
        for(stage in 0..2) ConnectionCatalogFixture().use { f ->
            val vault=SqlModelConnectionCatalog(f.db,key());val bound=vault.apply(vault.prepare(create(vault),"put",material()));val repo=strict(f,vault);approve(repo,bound)
            val intent=RemoteSendIntent(SOURCE,TextPurpose.ORGANIZATION);val job=(repo.queue(intent,"synthetic-vault",CLOCK) as SourceQueueOutcome.Queued).job
            if(stage>0) assertNull(repo.admit(job,CLOCK))
            if(stage==2) assertTrue(repo.commit(job,candidate(),CLOCK) is SourceCommitOutcome.Committed)
            assertTrue(vault.current(bound.secretRef!!));vault.revoke(bound,"revoke")
            if(stage==0) assertNotNull(repo.admit(job,CLOCK))
            assertEquals(SourceCommitDenial.AUTHORIZATION_CHANGED,(repo.commit(job,candidate(),CLOCK) as SourceCommitOutcome.Denied).reason)
            assertTrue(repo.queue(intent,"synthetic-vault",CLOCK) is SourceQueueOutcome.Denied)
            assertTrue(repo.annotations(SOURCE,CLOCK).isEmpty());assertEquals(if(stage==2)1 else 0,f.db.read("SELECT jobId FROM text_annotations").size);vault.close()
        }
    }
    @Test fun strictGateRequiresEntireCurrentSnapshotAndNeverFallsBackOnLegacyMissingOrCorruptStorage() {
        ConnectionCatalogFixture().use { f ->
            val vault=SqlModelConnectionCatalog(f.db,key());val initial=create(vault);val repo=strict(f,vault)
            val legacy=ProviderConnection(initial.id,initial.version,initial.details.apiRoot,initial.details.protocol,initial.details.modelId,SecretReference("synthetic-legacy"))
            approve(repo,legacy);assertTrue(repo.queue(RemoteSendIntent(SOURCE,TextPurpose.ORGANIZATION),"legacy",CLOCK) is SourceQueueOutcome.Denied)
            val bound=vault.apply(vault.prepare(initial,"put",material()));val valid=bound.textConnection(TextPurpose.ORGANIZATION)
            for(c in listOf(valid.copy(apiRoot="https://another.example.test/v1/"),valid.copy(modelId="other-model"),valid.copy(protocol=ConnectionProtocol.ANTHROPIC_MESSAGES),valid.copy(chatBudgetField=ChatBudgetField.MAX_COMPLETION_TOKENS),valid.copy(chatUsageSupported=true),valid.copy(version=valid.version+1))) {
                approve(repo,c);assertTrue(repo.queue(RemoteSendIntent(SOURCE,TextPurpose.ORGANIZATION),"changed",CLOCK) is SourceQueueOutcome.Denied)
            }
            approve(repo,valid);assertTrue(repo.queue(RemoteSendIntent(SOURCE,TextPurpose.ORGANIZATION),"current",CLOCK) is SourceQueueOutcome.Queued)
            f.db.write("DROP TABLE model_configurations")
            assertFalse(vault.current(valid.secretRef));assertEquals(SourceCommitDenial.STORAGE_UNAVAILABLE,(repo.queue(RemoteSendIntent(SOURCE,TextPurpose.ORGANIZATION),"missing",CLOCK) as SourceQueueOutcome.Denied).reason);vault.close()
        }
    }
    @Test fun registrationBeforeInvalidationAndOwnerCloseRetireIssuedValuesWithoutWaitingOnTheCallback() {
        ConnectionCatalogFixture().use { f ->
            val vault=SqlModelConnectionCatalog(f.db,key());val bound=vault.apply(vault.prepare(create(vault),"put",material()));val owned=vault.resolve(bound.secretRef!!)!!
            val latch=CountDownLatch(1);val watch=vault.watch(bound.secretRef!!){latch.countDown()};vault.close()
            assertTrue(owned.isClosed);assertTrue(latch.await(3,TimeUnit.SECONDS));watch.close();assertFalse(vault.current(bound.secretRef!!))
            var immediate=0;vault.watch(bound.secretRef!!){immediate++}.close();assertEquals(1,immediate)
        }
    }
    private fun SqlModelConnectionCatalog.connectionCurrentForTest(f:ConnectionCatalogFixture,c:ProviderConnection)=try { f.db.transaction { connectionCurrent(it,c,TextPurpose.ORGANIZATION) } } catch(_:Exception){false}
    companion object {
        const val MATERIAL="SYNTHETIC_ONLY_VAULT_MATERIAL_123"
        fun key()=ByteArray(32){(it+81).toByte()}
        fun material()=MATERIAL.toCharArray()
        internal fun create(v:SqlModelConnectionCatalog)=v.change(ModelConnectionCatalogTest.change("config",0,"create"))!!
        internal fun payload(f:ConnectionCatalogFixture)=f.db.read("SELECT payload FROM model_configurations WHERE id='config'").single().single() as ByteArray
        val NOW:Instant=Instant.parse("2026-10-19T09:00:00Z");val CLOCK:()->Instant={NOW};val SOURCE=SourceVersion("original",1)
        internal fun strict(f:ConnectionCatalogFixture,v:SqlModelConnectionCatalog)=RemoteTextTransactions(f.db,connectionCurrent=v::connectionCurrent)
        fun approve(repo:RemoteTextTransactions,c:ModelConnectionConfiguration)=approve(repo,c.textConnection(TextPurpose.ORGANIZATION))
        fun approve(repo:RemoteTextTransactions,c:ProviderConnection) { assertNull(repo.updateAuthorization(SOURCE,repo.readAccess(SOURCE.sourceId)!!.generation,ProcessingPolicy.REMOTE_ALLOWED,c,
            ConsentGrant("synthetic-vault-consent",1,SOURCE,c,TextPurpose.ORGANIZATION,NOW.minusSeconds(1),NOW.plusSeconds(3600)))) }
        fun candidate()=DerivedTextCandidate("合成候选",listOf(OriginalTextReference(SOURCE,0,4,"合成原文")))
    }
}
