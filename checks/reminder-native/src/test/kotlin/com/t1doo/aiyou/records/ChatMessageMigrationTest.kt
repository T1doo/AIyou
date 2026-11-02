package com.t1doo.aiyou.records

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room16→17 and actual EncryptedEntryRepository DI over controlled platform SQLite, not SQLCipher/device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ChatMessageMigrationTest {
    @Test fun realRoom16To17AndProductionOwnerPersistAcceptedAnswerThenColdRetireOldAuthority() {
        val app=RuntimeEnvironment.getApplication();val name="synthetic-chat-"+UUID.randomUUID()+".db"
        val root=Files.createTempDirectory("synthetic-room-chat-").toFile();val key=ByteArray(32){(it+11).toByte()}
        val old=contract(16);val current=contract(17);val source=UUID.randomUUID().toString();val original="Room合成原文🙂"
        var live:EncryptedEntryRepository?=null;var opened:RecordsDatabase?=null
        try {
            val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name(name)
                .callback(object:SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db:SupportSQLiteDatabase) {
                        val entities=old.getJSONArray("entities")
                        for(i in 0 until entities.length()) {
                            val e=entities.getJSONObject(i);val table=e.getString("tableName")
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                            val indices=e.optJSONArray("indices")?:org.json.JSONArray()
                            for(n in 0 until indices.length())db.execSQL(indices.getJSONObject(n).getString("createSql").replace("\${TABLE_NAME}",table))
                        }
                        val setup=old.getJSONArray("setupQueries");for(i in 0 until setup.length())db.execSQL(setup.getString(i))
                        installManualTaskGuard(db)
                    }
                    override fun onUpgrade(db:SupportSQLiteDatabase,from:Int,to:Int)=error("Only the production Room migration upgrades")
                }).build())
            helper.use {h->val db=h.writableDatabase
                db.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",arrayOf(source))
                db.execSQL("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",arrayOf(source,original,OriginalExports.hash(original)))
                db.execSQL("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",arrayOf(source))
                validateExistingReminderSchema(SupportRecordsSqlConnection(db),old)
            }
            fun open():EncryptedEntryRepository {
                val db=Room.databaseBuilder(app,RecordsDatabase::class.java,name).addMigrations(recordsMigration16To17)
                    .allowMainThreadQueries().addCallback(object:RoomDatabase.Callback() {
                        override fun onOpen(db:SupportSQLiteDatabase) {
                            installChatMessageGuards(db);installSavedQuestionGuards(db);installRemoteTextGuards(db)
                            installRevisionGuard(db);installAttachmentGuard(db);installManualTagGuard(db);installManualTaskGuard(db)
                            installModelConnectionGuards(db);installModelCapabilityGuards(db)
                        }
                    }).build().also {it.openHelper.writableDatabase}
                opened=db;val sql=DrainingRecordsSqlDatabase(RoomRecordsSqlDatabase(db));val hooks=SqlErasureFileHooks(sql)
                val media=EncryptedAttachmentStore(root.resolve("media"),RoomAttachmentLedger(db),key.copyOf()).also{it.installErasureHooks(hooks)}
                val edits=EncryptedOriginalEditStore(root.resolve("edits"),key.copyOf()).also{it.installErasureHooks(hooks)}
                return EncryptedEntryRepository(db,key.copyOf(),media,edits,sql).also {live=it}
            }
            val first=open();val sql=SupportRecordsSqlConnection(opened!!.openHelper.writableDatabase)
            assertEquals(17L,sql.query("PRAGMA user_version").single().single());validateExistingReminderSchema(sql,current)
            assertThrows(Exception::class.java){validateExistingReminderSchema(sql,old)}
            assertEquals(original,sql.query("SELECT originalText FROM entry_revisions").single().single())
            val catalog=first.modelConnections as SqlModelConnectionCatalog
            val changed=catalog.change(ModelConnectionChange("synthetic-room-chat",0,"create",ModelConnectionDetails("合成隔离连接","https://room-history.invalid/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION))))!!
            val config=catalog.apply(catalog.prepare(changed,"synthetic-bind","not-a-real-provider-key".toCharArray()))
            val profiles=first.modelCapabilities;val now=Instant.parse("2026-11-02T01:00:00Z")
            val probe=profiles.begin(config,"controlled-no-network-probe",now.toEpochMilli());assertTrue(profiles.admit(probe))
            profiles.finish(probe,TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,now.toEpochMilli()))
            val record=first.recordAuthorizations;val version=SourceVersion(source,1)
            val preview=record.previewConnectionAuthorization(record.readAuthorization(version).binding,config,TextPurpose.CONVERSATION){now}
            record.changeAuthorization(RecordAuthorizationChange.confirm(preview,now.minusSeconds(1),now.plusSeconds(600),UUID.randomUUID().toString())){now}
            val connection=config.textConnection(TextPurpose.CONVERSATION)!!
            val request=SelectedQuestionRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"这条Room合成记录说了什么？",
                SelectedQuestionTarget(connection.protocol,connection.apiRoot,connection.modelId,connection.version),now.toEpochMilli(),"UTC",
                listOf(SelectedQuestionSource(OriginalTextReference(version,0,original.codePointCount(0,original.length),original),5,"UTC",ProcessingPolicy.REMOTE_ALLOWED)))
            val authority=first.selectedQuestionAuthorizations;val permit=authority.authorizeReviewedSend(authority.review(request,connection,"selected-question-protocol-v1"){now}){now}
            authority.admit(permit){now}
            val reply=SelectedQuestionReply(request.id,request.selectionId,false,listOf(SelectedQuestionParagraph("Room隔离合成回答🙂",listOf(request.sources.single().reference))))
            val receipt=authority.accept(permit,reply){now};authority.cancelPending(permit)
            val history=first.chatHistory;val saved=history.list().single();val view=history.open(saved.id,saved.version)!!
            assertEquals(reply.paragraphs.single().text,view.reply!!.paragraphs.single().text)
            first.closeAndAwait();live=null;opened=null
            val second=open();val fresh=second.chatHistory
            assertFalse(history.current(view));assertFalse(fresh.current(view));assertNotNull(second.selectedQuestionAuthorizations.checkReceipt(receipt){now})
            val reopened=fresh.open(saved.id,saved.version)!!;assertTrue(fresh.current(reopened))
            assertEquals(reply.paragraphs.single().text,reopened.reply!!.paragraphs.single().text)
            opened!!.openHelper.writableDatabase.execSQL("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",arrayOf(source))
            val retired=fresh.list().single();assertEquals(saved.question,retired.question);assertEquals(ChatMessageState.INVALIDATED,retired.state)
            assertFalse(fresh.current(reopened));assertNull(fresh.open(retired.id,retired.version)!!.reply)
            validateExistingReminderSchema(SupportRecordsSqlConnection(opened!!.openHelper.writableDatabase),current)
        }finally{live?.closeAndAwait();opened?.close();key.fill(0);app.deleteDatabase(name);root.deleteRecursively()}
    }
    private fun contract(version:Int)=JSONObject(File(System.getProperty("aiyou.root"),"core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/$version.json").readText()).getJSONObject("database")
}
