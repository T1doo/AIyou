package com.t1doo.aiyou.records

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Room migration/schema validator under controlled Android SQLite; not SQLCipher/device execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SavedQuestionMigrationTest {
    @Test fun realRoom15To16PreservesOriginalAndBothColdContractsThenReopensQuestion() {
        val app=RuntimeEnvironment.getApplication(); val name="synthetic-question-"+UUID.randomUUID()+".db"
        val old=contract(15); val current=contract(17);val key=ByteArray(32) { (it+7).toByte() }
        var room:RecordsDatabase?=null
        try {
            val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name(name)
                .callback(object:SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db:SupportSQLiteDatabase) {
                        val entities=old.getJSONArray("entities")
                        for(n in 0 until entities.length()) {
                            val e=entities.getJSONObject(n);val table=e.getString("tableName")
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                            val indices=e.optJSONArray("indices")?:org.json.JSONArray()
                            for(i in 0 until indices.length())db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))
                        }
                        val setup=old.getJSONArray("setupQueries");for(n in 0 until setup.length())db.execSQL(setup.getString(n))
                        db.execSQL(ManualTaskSqlSchema.guard)
                    }
                    override fun onUpgrade(db:SupportSQLiteDatabase,from:Int,to:Int)=error("Only the actual Room migration may upgrade")
                }).build())
            helper.use {
                val db=it.writableDatabase
                db.beginTransaction()
                try {
                    db.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('synthetic',1,5,'UTC','LOCAL_ONLY')")
                    db.execSQL("INSERT INTO entry_revisions VALUES('synthetic',1,'合成原文',?,5)",arrayOf(OriginalExports.hash("合成原文")))
                    db.setTransactionSuccessful()
                } finally {db.endTransaction()}
                validateExistingReminderSchema(SupportRecordsSqlConnection(db),old)
            }
            fun open():RecordsDatabase = Room.databaseBuilder(app,RecordsDatabase::class.java,name)
                .addMigrations(recordsMigration15To16,recordsMigration16To17).allowMainThreadQueries()
                .addCallback(object:RoomDatabase.Callback() {
                    override fun onCreate(db:SupportSQLiteDatabase) {installSavedQuestionGuards(db);installChatMessageGuards(db)}
                    override fun onOpen(db:SupportSQLiteDatabase) {installSavedQuestionGuards(db);installChatMessageGuards(db)}
                }).build().also {it.openHelper.writableDatabase}
            val first=open();room=first
            val sql=SupportRecordsSqlConnection(first.openHelper.writableDatabase)
            assertEquals(17L,sql.query("PRAGMA user_version").single().single())
            validateExistingReminderSchema(sql,current)
            assertThrows(Exception::class.java) {validateExistingReminderSchema(sql,old)}
            assertEquals(listOf("合成原文"),sql.query("SELECT originalText FROM entry_revisions").single())
            val id=UUID.randomUUID().toString();val repo=SqlSavedQuestionRepository(RoomRecordsSqlDatabase(first),key)
            try { repo.save(SaveQuestion(id,0,UUID.randomUUID().toString(),"合成离线问题🙂",10)) } finally {repo.wipeKey()}
            first.close();room=null
            val second=open();room=second;val again=SqlSavedQuestionRepository(RoomRecordsSqlDatabase(second),key)
            try {assertEquals("合成离线问题🙂",again.read(id)!!.question)}finally{again.wipeKey()}
            validateExistingReminderSchema(SupportRecordsSqlConnection(second.openHelper.writableDatabase),current)
        } finally {room?.close();key.fill(0);app.deleteDatabase(name)}
    }
    private fun contract(version:Int)=JSONObject(File(System.getProperty("aiyou.root"),
        "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/$version.json").readText()).getJSONObject("database")
}
