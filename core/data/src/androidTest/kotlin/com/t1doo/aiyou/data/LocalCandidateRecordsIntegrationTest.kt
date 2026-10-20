package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLCipher owner assertions. Compilation/DEX collection never means device PASS. */
@RunWith(AndroidJUnit4::class)
class LocalCandidateRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val key = ByteArray(32) { (it + 101).toByte() }
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val file get() = File(directory, "records.db")
    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.noBackupFilesDir, "local-candidates-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        System.loadLibrary("sqlcipher")
    }
    @After fun after() { try { stores.forEach { it.closeAndAwait() } } finally {
        key.fill(0); if (::directory.isInitialized) directory.deleteRecursively()
    } }

    @Test fun encryptedVersionNineMigrationPreservesOpaqueAnnotationsAndExplicitExcerptReviews(): Unit = runBlocking {
        val text = "我不喝咖啡🙂，这不是稳定人格推断。\n标签：#生活"
        val oldText = "合成旧来源"
        val oldSource = SourceVersion("remote", 1)
        val oldBytes = RemoteTextCodec.encodeCandidate(DerivedTextCandidate("合成旧 opaque 注记",
            listOf(OriginalTextReference(oldSource, 0, oldText.codePointCount(0,oldText.length), oldText))))
        val connection = ProviderConnection("synthetic-old", 1, "https://provider.example.test/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS, "synthetic-model", SecretReference("synthetic-ref"))
        val grant = ConsentGrant("synthetic-grant", 1, oldSource, connection, TextPurpose.ORGANIZATION,
            java.time.Instant.EPOCH, java.time.Instant.ofEpochSecond(100))
        NativeRecordsSchemaFixture.create(file, key, 9) { sql ->
            for ((id, original, policy) in listOf(Triple("local",text,"LOCAL_ONLY"),Triple("remote",oldText,"REMOTE_ALLOWED"))) {
                sql.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,55,'UTC',?)", arrayOf(id,policy))
                sql.execSQL("INSERT INTO entry_revisions VALUES(?,1,?,?,55)",arrayOf(id,original,OriginalExports.hash(original)))
            }
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO local_jobs VALUES('index','local',1,'LOCAL_INDEX','PENDING')")
            sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            sql.execSQL("INSERT INTO remote_source_access VALUES('remote',1,0,?,?)",
                arrayOf(RemoteTextCodec.encodeConnection(connection),RemoteTextCodec.encodeConsent(grant)))
            sql.execSQL("INSERT INTO remote_text_jobs VALUES('old-job',?,'remote',1,1,'ORGANIZATION','old-v1',?,?,'COMMITTED',?)",
                arrayOf(RemoteTextCodec.binding(oldSource,TextPurpose.ORGANIZATION,"old-v1",1,connection,grant),
                    RemoteTextCodec.encodeConnection(connection),RemoteTextCodec.encodeConsent(grant),OriginalExports.hash(oldText)))
            sql.execSQL("INSERT INTO text_annotations VALUES('old-job','remote',1,1,?,?,55)",
                arrayOf(oldBytes,RemoteTextCodec.sha256(oldBytes)))
        }
        var store = open(); NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        assertEquals(TextDraft("draft",7,"合成独立草稿"),store.readDraft())
        assertTrue(store.localCandidates.batches(oldSource).isEmpty())
        store.database.openHelper.writableDatabase.query("SELECT candidate FROM text_annotations WHERE jobId='old-job'").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertArrayEquals(oldBytes,cursor.getBlob(0)); assertFalse(cursor.moveToNext())
        }
        val source = SourceVersion("local",1)
        val job = store.localCandidates.begin(LocalCandidateStart("batch",source,60))
        store.localCandidates.complete(job,LocalExcerptRules.extract(job))
        store.closeAndAwait(); store=open()
        val proposed=store.localCandidates.batches(source).single()
        assertEquals(text,proposed.items.single().proposal.quote); assertEquals(ExcerptReview.PROPOSED,proposed.items.single().review)
        val request=review(proposed,ExcerptReview.ACCEPTED,"accept")
        store.localCandidates.review(request); val bytes=payload(store)
        store.localCandidates.review(request); assertArrayEquals(bytes,payload(store))
        store.closeAndAwait(); store=open()
        assertEquals(ExcerptReview.ACCEPTED,store.localCandidates.batches(source).single().items.single().review)
        val accepted = store.localCandidates.batches(source).single()
        val correction = ExcerptReopenRequest(accepted.id, source, accepted.sourceHash, accepted.generation,
            accepted.version, "excerpt-1", ExcerptReview.ACCEPTED, "correct-native", 95)
        store.localCandidates.reopen(correction); val correctedBytes = payload(store)
        store.localCandidates.reopen(correction); assertArrayEquals(correctedBytes, payload(store))
        store.closeAndAwait(); store = open()
        val pending = store.localCandidates.batches(source).single()
        assertEquals(ExcerptReview.PROPOSED, pending.items.single().review)
        assertThrows(LocalCandidateConflict::class.java) { store.localCandidates.review(request) }
        store.localCandidates.review(review(pending, ExcerptReview.REJECTED, "reject-corrected"))
        assertEquals(ExcerptReview.REJECTED, store.localCandidates.batches(source).single().items.single().review)
        assertEquals(text,store.entries().single { it.id=="local" }.originalText)
        assertTrue(store.manualTasks.snapshots().isEmpty()); assertTrue(store.manualTags.read("local").names.isEmpty())
        val tagPreview = store.sourceTags.preview(source)
        val tagRequest = SourceTagRequest(tagPreview, tagPreview.proposals.single(), "native-source-tag", 99)
        store.sourceTags.apply(tagRequest)
        store.sourceTags.apply(tagRequest)
        store.closeAndAwait(); store = open()
        assertEquals(listOf("生活"), store.manualTags.read("local").names)
        assertEquals(1L, store.manualTags.read("local").version)
        val memoryPreview = store.sourceMemories.preview(source)
        val memoryContent = SourceMemoryContent(SourceMemoryRules.reference(memoryPreview,text),SourceMemoryKind.EVENT,SourceMemoryAssertion.UNCERTAIN,"合成当次人工备注")
        val memoryCreate = SourceMemoryCreate("native-memory",memoryPreview,memoryContent,"native-memory-create",100)
        val pendingMemory = store.sourceMemories.create(memoryCreate)
        store.sourceMemories.create(memoryCreate)
        val inbox = checkNotNull(store.sourceMemories.inbox)
        val pendingPage = inbox.capture()
        assertEquals(listOf(pendingMemory.id), pendingPage.items.map { it.id })
        assertTrue(inbox.resolve(pendingPage, pendingPage.items.single()) {})
        val acceptMemory = SourceMemoryMutation(pendingMemory.id,source,pendingMemory.sourceHash,pendingMemory.generation,pendingMemory.version,1,
            SourceMemoryReview.PROPOSED,SourceMemoryAction.ACCEPT,null,"native-memory-accept",101)
        val acceptedMemory = store.sourceMemories.mutate(acceptMemory)
        assertFalse(inbox.publishCurrent(pendingPage) {})
        assertTrue(inbox.capture().items.isEmpty())
        val acceptedPage = inbox.capture(section = SourceMemoryInboxSection.ACCEPTED)
        assertEquals(acceptedMemory.id, acceptedPage.items.single().id)
        val memoryCorrection = SourceMemoryMutation(acceptedMemory.id,source,acceptedMemory.sourceHash,acceptedMemory.generation,acceptedMemory.version,1,
            SourceMemoryReview.ACCEPTED,SourceMemoryAction.CORRECT,memoryContent.copy(kind=SourceMemoryKind.OCCASION_EMOTION,assertion=SourceMemoryAssertion.CONTEXT,note="纠正：只是本次原文线索"),"native-memory-correct",102)
        store.sourceMemories.mutate(memoryCorrection)
        assertFalse(inbox.resolve(acceptedPage, acceptedPage.items.single()) {})
        assertTrue(inbox.capture(section = SourceMemoryInboxSection.ACCEPTED).items.isEmpty())
        store.closeAndAwait(); store = open()
        val memoryRead = store.sourceMemories.memories(source).single()
        assertEquals(SourceMemoryReview.PROPOSED,memoryRead.current!!.review)
        assertEquals(memoryRead.id, checkNotNull(store.sourceMemories.inbox).capture().items.single().id)
        assertEquals(SourceMemoryReview.ACCEPTED,memoryRead.versions.first().priorReview)
        assertEquals(SourceMemoryReview.SUPERSEDED,memoryRead.versions.first().review)
        assertThrows(SourceMemoryConflict::class.java) { store.sourceMemories.mutate(acceptMemory) }
        assertEquals(1,store.localCandidates.batches(source).size)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(text))
    }
    @Test fun nativeSourceEditTrashRestoreAndErasureInvalidateCandidatesAndLateCompletion(): Unit = runBlocking {
        var store=open(); val entry=store.saveDraft(store.updateDraft("合成未完成计划，只是引用🙂"),55,"UTC")
        val source=SourceVersion(entry.id,1); val job=store.localCandidates.begin(LocalCandidateStart("batch",source,60))
        val initial=store.localCandidates.complete(job,LocalExcerptRules.extract(job))
        store.localCandidates.review(review(initial,ExcerptReview.ACCEPTED,"accept"))
        store.revise(entry.id,1,"合成新版本：不代表已完成",70)
        val next=SourceVersion(entry.id,2)
        assertThrows(LocalCandidateConflict::class.java) { store.localCandidates.complete(job,LocalExcerptRules.extract(job)) }
        assertEquals(LocalCandidateState.INVALIDATED,store.localCandidates.batches(next).single().state)
        val pending=store.localCandidates.begin(LocalCandidateStart("late",next,80))
        var record=store.recycleBin.read(entry.id)
        store.recycleBin.apply(RecycleMutation(next,record.generation,false,"trash"))
        store.closeAndAwait();store=open()
        assertThrows(LocalCandidateConflict::class.java) { store.localCandidates.complete(pending,LocalExcerptRules.extract(pending)) }
        record=store.recycleBin.read(entry.id)
        store.recycleBin.apply(RecycleMutation(next,record.generation,true,"restore"))
        record=store.recycleBin.read(entry.id)
        assertNull(store.sourceBoundText.updateAuthorization(next,record.generation,ProcessingPolicy.LOCAL_ONLY,null,null))
        assertTrue(store.localCandidates.batches(next).all { it.state==LocalCandidateState.INVALIDATED && it.items.isEmpty() })
        record=store.recycleBin.read(entry.id)
        val trashed=store.recycleBin.apply(RecycleMutation(next,record.generation,false,"trash-again"))
        assertTrue(store.permanentErasure.erase(PermanentErasureIntent(next,trashed.generation,"erase")).complete)
        store.database.openHelper.writableDatabase.query("SELECT id FROM local_candidate_batches WHERE entryId=?",arrayOf(entry.id)).use { cursor ->
            assertFalse(cursor.moveToNext())
        }
        assertThrows(LocalCandidateConflict::class.java) { store.localCandidates.complete(pending,LocalExcerptRules.extract(pending)) }
    }
    @Test fun nativeReviewAbortAndIndependentOwnersPreserveSingleAuthenticatedDecision(): Unit = runBlocking {
        val first=open();val entry=first.saveDraft(first.updateDraft("合成双所有者摘录🙂"),55,"UTC")
        val source=SourceVersion(entry.id,1);val job=first.localCandidates.begin(LocalCandidateStart("batch",source,60))
        val batch=first.localCandidates.complete(job,LocalExcerptRules.extract(job));val second=open()
        val sql=first.database.openHelper.writableDatabase
        val bytes=payload(first)
        sql.execSQL("CREATE TRIGGER synthetic_candidate_abort AFTER UPDATE ON local_candidate_batches BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(LocalCandidateStorageFailure::class.java) { first.localCandidates.review(review(batch,ExcerptReview.ACCEPTED,"abort")) }
        assertArrayEquals(bytes,payload(second));assertEquals(1L,second.localCandidates.batches(source).single().version)
        sql.execSQL("DROP TRIGGER synthetic_candidate_abort")
        val go=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val outcomes=listOf(first,second).mapIndexed { index, owner -> pool.submit<String> {
                go.await()
                try { owner.localCandidates.review(review(batch,if(index==0)ExcerptReview.ACCEPTED else ExcerptReview.REJECTED,"winner-"+index));"saved" }
                catch(_:LocalCandidateConflict) { "conflict" }
            } };go.countDown()
            assertEquals(listOf("conflict","saved"),outcomes.map { it.get(3,TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)) }
        assertEquals(2L,first.localCandidates.batches(source).single().version)
        val borrowed=first.localCandidates;first.closeAndAwait()
        assertThrows(LocalCandidateStorageFailure::class.java) { borrowed.batches(source) }
        second.closeAndAwait();assertEquals(2L,open().localCandidates.batches(source).single().version)
    }
    private fun review(batch:LocalCandidateBatch,decision:ExcerptReview,id:String)=ExcerptReviewRequest(batch.id,batch.source,
        batch.sourceHash,batch.generation,batch.version,"excerpt-1",decision,id,90)
    private fun payload(store:EncryptedEntryRepository)=store.database.openHelper.writableDatabase
        .query("SELECT payload FROM local_candidate_batches WHERE id='batch'").use { cursor ->
            check(cursor.moveToFirst());cursor.getBlob(0).also { check(!cursor.moveToNext()) }
        }
    private fun open()=EncryptedEntryRepository.openWithKey(context,file,key.copyOf()).also(stores::add)
}
