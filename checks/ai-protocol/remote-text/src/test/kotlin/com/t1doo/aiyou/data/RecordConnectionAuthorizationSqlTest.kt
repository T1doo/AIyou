package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Product authorization/catalog/vault SQL on actual Room13 export followed by existing14 migration.
 * Synthetic metadata/material only. No resolve, client, probe or sending is installed.
 */
class RecordConnectionAuthorizationSqlTest {
    @Test fun bothProtocolsChooseSeparateTargetWithoutForgingBeforeAndReplaceSinglePurposeAcrossColdReopen() {
        for(protocol in ConnectionProtocol.entries) Harness(protocol).use { h ->
            val before=h.read();val review=h.review();assertNull(before.connection)
            assertEquals(before,review.snapshot);assertEquals(h.config.textConnection(TextPurpose.ORGANIZATION),review.targetConnection)
            assertEquals(RecordCredentialReadiness.LOCAL_REFERENCE_CURRENT,review.credentialReadiness)
            assertFalse(review.executionReady);assertFalse(review.authorizationCurrent);assertEquals(before,h.read());h.noSending()
            val request=h.change(review);val saved=h.repo().changeAuthorization(request){NOW}
            assertTrue(saved.decisionStillCurrent);assertEquals(before.binding.generation+1,saved.current.binding.generation)
            assertEquals(request.grant,saved.current.consent);assertEquals(h.config.textConnection(TextPurpose.ORGANIZATION),saved.current.connection)
            assertEquals("合成原文",h.original());assertEquals("合成独立草稿",h.draft());h.noSending()
            h.f.reopen();assertEquals(saved.current,h.read())
            val next=h.change(h.review(TextPurpose.CONVERSATION));val replaced=h.repo().changeAuthorization(next){NOW}
            assertTrue(replaced.decisionStillCurrent);assertEquals(TextPurpose.CONVERSATION,replaced.current.consent!!.purpose)
            assertEquals(before.binding.generation+2,replaced.current.binding.generation)
            assertFalse(h.repo().confirmAuthorization(request){NOW}!!.decisionStillCurrent)
            assertEquals(replaced.current,h.read());h.noSending()
        }
    }
    @Test fun everyConfigurationEditKeyReplacementRevokeAndDeleteRejectFrozenReviewBeforeAnyGrant() {
        for(kind in 0..8) Harness().use { h ->
            val request=h.change();val before=h.read();val c=h.config;val d=c.details
            when(kind) {
                0 -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"rename",ModelConnectionCatalogTest.details(name="合成重命名")))
                1 -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"root",ModelConnectionDetails(d.name,"https://other.example.test/prefix/",d.protocol,d.modelId,d.purposes)))
                2 -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"model",ModelConnectionDetails(d.name,d.apiRoot,d.protocol,"other-model",d.purposes)))
                3 -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"protocol",ModelConnectionCatalogTest.details(ConnectionProtocol.ANTHROPIC_MESSAGES)))
                4 -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"purpose",ModelConnectionDetails(d.name,d.apiRoot,d.protocol,d.modelId,setOf(ModelConfigurationPurpose.CONVERSATION))))
                5 -> h.f.catalog.apply(h.f.catalog.prepare(c,"replace","SYNTHETIC_REPLACEMENT_ONLY".toCharArray()))
                6 -> h.f.catalog.revoke(c,"revoke")
                7 -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"delete",null))
                else -> h.f.catalog.change(ModelConnectionChange(c.id,c.version,"same-details-new-version",d))
            }
            assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(request){NOW}}
            assertNull(h.repo().confirmAuthorization(request){NOW});assertEquals(before,h.read());assertEquals(0,h.receipts());h.noSending()
        }
    }
    @Test fun changedSourceRevisionGenerationAbaAndDeletionCannotApplyOldReview() {
        for(kind in 0..2) Harness().use { h ->
            val request=h.change();val before=h.read()
            when(kind) {
                0 -> {
                    h.f.db.write("INSERT INTO entry_revisions SELECT entryId,2,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId='original' AND revision=1")
                    h.f.db.write("UPDATE entries SET currentRevision=2 WHERE id='original'")
                }
                1 -> {
                    val raw=RemoteTextTransactions(h.f.db)
                    assertNull(raw.updateAuthorization(SOURCE,before.binding.generation,ProcessingPolicy.AI_NONE,null,null))
                    assertNull(raw.updateAuthorization(SOURCE,before.binding.generation+1,ProcessingPolicy.LOCAL_ONLY,null,null))
                }
                else -> assertNull(RemoteTextTransactions(h.f.db).tombstone(SOURCE,before.binding.generation))
            }
            assertThrows(RecordAuthorizationConflict::class.java){h.repo().changeAuthorization(request){NOW}}
            assertEquals(0,h.receipts());h.noSending()
        }
    }
    @Test fun lostCommitAckColdConfirmationIsReadOnlyAndNeverExtendsIntervalOrRestoresRevokedConfiguration() {
        Harness().use { h ->
            val request=h.change();var written=false
            h.f.db.afterSql={sql ->if(sql.startsWith("INSERT INTO local_jobs"))written=true
                if(sql=="COMMIT"&&written){written=false;throw IOException("Synthetic grant committed ACK loss")}}
            assertThrows(RecordAuthorizationStorageFailure::class.java){h.repo().changeAuthorization(request){NOW}}
            h.f.db.afterSql=null;val saved=h.read();assertEquals(1,h.receipts());h.f.reopen()
            h.readOnly {
                val receipt=h.repo().confirmAuthorization(request){NOW.plusSeconds(30)}!!
                assertTrue(receipt.replayed);assertTrue(receipt.decisionStillCurrent);assertEquals(saved,receipt.current)
                assertEquals(request.grant!!.issuedAt,receipt.current.consent!!.issuedAt)
                assertEquals(request.grant!!.expiresAt,receipt.current.consent!!.expiresAt)
            }
            h.f.catalog.revoke(h.config,"revoke")
            assertFalse(h.repo().previewAuthorization(h.read().binding,TextPurpose.ORGANIZATION){NOW}.authorizationCurrent)
            h.readOnly { assertFalse(h.repo().confirmAuthorization(request){NOW}!!.decisionStillCurrent) }
            assertEquals(1,h.receipts());assertEquals(saved,h.read())
            val strict=RemoteTextTransactions(h.f.db,connectionCurrent=h.f.catalog::connectionCurrent)
            assertTrue(strict.queue(RemoteSendIntent(SOURCE,TextPurpose.ORGANIZATION),"synthetic-not-sent"){NOW} is SourceQueueOutcome.Denied)
            h.noSending()
        }
    }
    @Test fun realRollbackReadOnlyConfirmationReturnsMissingWithoutRetryingTheDecision() {
        Harness().use { h ->
            val request=h.change();val before=h.read()
            h.f.db.beforeCommit={throw IOException("Synthetic before-commit abort")}
            assertThrows(RecordAuthorizationStorageFailure::class.java){h.repo().changeAuthorization(request){NOW}}
            h.f.db.beforeCommit=null
            h.readOnly { repeat(2){assertNull(h.repo().confirmAuthorization(request){NOW.plusSeconds(it.toLong())})} }
            assertEquals(before,h.read());assertEquals(0,h.receipts());h.noSending()
        }
    }
    @Test fun corruptedCapabilityOrCatalogHidesValidityButCannotPreventExplicitWithdrawal() {
        for(kind in 0..1) Harness().use { h ->
            val request=h.change();h.repo().changeAuthorization(request){NOW}
            if(kind==0) {
                h.f.profiles.begin(h.config,"synthetic-profile",100)
                h.f.db.write("UPDATE model_capability_profiles SET generation=2,probeId='corrupt',phase='PENDING',payload=?",byteArrayOf(0,0,0,99))
                assertThrows(RecordAuthorizationStorageFailure::class.java){h.review()}
            } else h.f.db.write("UPDATE model_configurations SET version=version+1,lastMutationId='corrupt',payload=? WHERE id=?",byteArrayOf(0,0,0,99),h.config.id)
            val before=h.read();val metadata=h.repo().previewAuthorization(before.binding,TextPurpose.ORGANIZATION){NOW}
            if(kind==1)assertFalse(metadata.authorizationCurrent)
            val withdrawal=RecordAuthorizationChange.withdraw(before,ProcessingPolicy.LOCAL_ONLY,uuid())
            val withdrawn=h.repo().changeAuthorization(withdrawal){NOW}
            assertTrue(withdrawn.decisionStillCurrent);assertNull(withdrawn.current.consent);assertNull(withdrawn.current.connection)
            h.readOnly { assertTrue(h.repo().confirmAuthorization(withdrawal){NOW}!!.decisionStillCurrent) }
            assertEquals("合成原文",h.original());h.noSending()
        }
    }
    private class Harness(protocol:ConnectionProtocol=ConnectionProtocol.CHAT_COMPLETIONS):AutoCloseable {
        val f=CapabilitySqlFixture().also {it.migrate()}
        val config=f.connection(protocol=protocol)
        fun repo()=SqlRecordAuthorizationRepository(f.db,f.catalog,f.profiles)
        fun read()=repo().readAuthorization(SOURCE)
        fun review(purpose:TextPurpose=TextPurpose.ORGANIZATION)=repo().previewConnectionAuthorization(read().binding,config,purpose){NOW}
        fun change(preview:RecordAuthorizationPreview=review())=RecordAuthorizationChange.confirm(preview,NOW,NOW.plusSeconds(3600),uuid())
        fun receipts()=(f.db.read("SELECT count(*) FROM local_jobs WHERE operation LIKE 'RECORD_AUTH:%'").single().single()as Long).toInt()
        fun original()=f.db.read("SELECT originalText FROM entry_revisions WHERE entryId='original' AND revision=1").single().single()
        fun draft()=f.db.read("SELECT text FROM capture_draft").single().single()
        fun noSending(){for(t in listOf("remote_text_jobs","text_annotations"))assertTrue(f.db.read("SELECT * FROM $t").isEmpty());assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy)}
        fun readOnly(block:()->Unit){f.db.beforeSql={sql ->if(listOf("INSERT","UPDATE","DELETE","REPLACE").any {sql.startsWith(it)})error("Confirmation must be read-only")};try{block()}finally{f.db.beforeSql=null}}
        override fun close()=f.close()
    }
    companion object {private val SOURCE=SourceVersion("original",1);private val NOW=Instant.parse("2026-10-19T13:00:00Z");private fun uuid()=UUID.randomUUID().toString()}
}
