package com.t1doo.aiyou.data

import android.content.Context
import android.os.UserManager
import com.t1doo.aiyou.domain.*
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.DatabaseErrorHandler
import org.json.JSONObject
import java.io.File

/** Never calls the full records factory, creates directories, migrates, or accepts a replacement key. */
object ExistingReminderStore {
    fun open(context:Context):OwnedReminderRuntime {
        val app=context.applicationContext
        return ExistingReminderOpenProtocol.open(app.noBackupFilesDir,
            {app.getSystemService(UserManager::class.java)?.isUserUnlocked==true},
            {existing->AndroidDatabaseKeyProvider(File(app.noBackupFilesDir,"keys/${RepositoryKeyPurpose.DATABASE.fileName}"),
                EncryptedEntryRepository.DATABASE_KEY_ALIAS,RepositoryKeyPurpose.DATABASE.associatedData).load(existing)}){file,key->
            var db:SQLiteDatabase?=null
            try{
                SqlCipherPrivacyLog.install()
                System.loadLibrary("sqlcipher")
                val opened=SQLiteDatabase.openDatabase(file.absolutePath,key,null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    DatabaseErrorHandler { corrupt, _ ->
                        try{corrupt.close()}catch(_:Throwable){throw ReminderRuntimeClosureUnconfirmed(arrayOf(corrupt,key))}
                        throw StorageKeyUnavailable()
                    },null)
                db=opened
                opened.setForeignKeyConstraintsEnabled(true)
                val contract=app.assets.open("reminder-schema-v15.json").use{JSONObject(it.readBytes().toString(Charsets.UTF_8)).getJSONObject("database")}
                validateExistingReminderSchema(SupportRecordsSqlConnection(opened),contract)
                val raw=object:RecordsSqlDatabase {
                    override fun <T> transaction(block:(RecordsSqlConnection)->T):T {
                        opened.beginTransaction()
                        try{return block(SupportRecordsSqlConnection(opened)).also{opened.setTransactionSuccessful()}}
                        finally{opened.endTransaction()}
                    }
                }
                val sql=DrainingRecordsSqlDatabase(raw);val stores=ReminderRuntimeStores(sql,key,app.packageName)
                object:OwnedReminderRuntime {
                    override val resources=stores.resources
                    private var closed=false
                    override fun close(){sql.seal()}
                    @Synchronized override fun awaitClosed(){
                        close();sql.checkExternalWait();sql.awaitIdle()
                        if(!closed){
                            try{opened.close()}catch(_:Throwable){throw ReminderRuntimeClosureUnconfirmed(arrayOf(opened,stores,key))}
                            closed=true
                            try{stores.close()}finally{key.fill(0)}
                        }
                    }
                }
            }catch(f:Throwable){
                if(f is ReminderRuntimeClosureUnconfirmed)throw f
                try{db?.close()}catch(_:Throwable){throw ReminderRuntimeClosureUnconfirmed(arrayOf(db,key))}
                if(f is java.util.concurrent.CancellationException)throw f
                throw StorageKeyUnavailable()
            }
        }
    }
}

/** Metadata only: no original/task row scans, no migration or guard repair on the cold path. */
internal fun validateExistingReminderSchema(sql:RecordsSqlConnection,contract:JSONObject) {
    require(contract.getInt("version")==15 && sql.query("PRAGMA user_version").single().single()==15L)
    val hash=contract.getString("identityHash");require(hash.matches(Regex("[a-f0-9]{32}")))
    require(sql.query("SELECT identity_hash FROM room_master_table WHERE id=42").single().single()==hash)
    val entities=contract.getJSONArray("entities")
    fun normalize(s:String)=s.replace(Regex("\\s+"),"").replace("`","").replace("\"","").replace("ifnotexists","",true).lowercase()
    val allowed=(0 until entities.length()).map{entities.getJSONObject(it).getString("tableName")}.toSet()+setOf("room_master_table","android_metadata","local_search_fts_content","local_search_fts_segments","local_search_fts_segdir","local_search_fts_docsize","local_search_fts_stat")
    val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' LIMIT 65");require(tables.size<=64 && tables.all{it[0] in allowed || (it[0] as String).startsWith("sqlite_")})
    for(index in 0 until entities.length()){
        val entity=entities.getJSONObject(index);val name=entity.getString("tableName");require(name.matches(Regex("[a-z_]+")))
        val actual=sql.query("PRAGMA table_info($name)");val fields=entity.getJSONArray("fields")
        require(actual.size==fields.length())
        if(entity.has("ftsVersion")){
            require(actual.map{it[1]}==(0 until fields.length()).map{fields.getJSONObject(it).getString("columnName")})
            val actualSql=sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",listOf(name)).single().single() as String
            require(normalize(actualSql)==normalize(entity.getString("createSql").replace("\${TABLE_NAME}",name)))
            continue
        }
        for(column in 0 until fields.length()){
            val expected=fields.getJSONObject(column);val row=actual[column]
            val defaultValue=if(expected.has("defaultValue"))expected.getString("defaultValue") else null
            require(row[4]==defaultValue)
            require(row[1]==expected.getString("columnName") && (row[2] as String).uppercase()==expected.getString("affinity") && (row[3]==1L)==expected.optBoolean("notNull",false))
        }
        val pk=entity.getJSONObject("primaryKey").getJSONArray("columnNames")
        require(actual.filter{(it[5] as Long)>0}.sortedBy{it[5] as Long}.map{it[1]}==(0 until pk.length()).map{pk.getString(it)})
        val foreign=(entity.optJSONArray("foreignKeys")?:org.json.JSONArray())
        val expectedForeign=mutableListOf<List<String>>()
        for(f in 0 until foreign.length()){
            val key=foreign.getJSONObject(f);val cols=key.getJSONArray("columns");val refs=key.getJSONArray("referencedColumns")
            for(c in 0 until cols.length())expectedForeign+=listOf(key.getString("table"),cols.getString(c),refs.getString(c),key.getString("onUpdate"),key.getString("onDelete"))
        }
        val actualForeign=sql.query("PRAGMA foreign_key_list($name)").map{listOf(it[2],it[3],it[4],it[5],it[6]).map{v->v as String}}
        require(actualForeign.groupingBy{it}.eachCount()==expectedForeign.groupingBy{it}.eachCount())
        val indices=(entity.optJSONArray("indices")?:org.json.JSONArray());val actualIndices=sql.query("PRAGMA index_list($name)")
        for(n in 0 until indices.length()){
            val expected=indices.getJSONObject(n);val indexName=expected.getString("name");require(indexName.matches(Regex("[A-Za-z0-9_]+")))
            val row=actualIndices.single{it[1]==indexName};require((row[2]==1L)==expected.getBoolean("unique"))
            val columns=expected.getJSONArray("columnNames")
            require(sql.query("PRAGMA index_info($indexName)").map{it[2]}==(0 until columns.length()).map{columns.getString(it)})
        }
    }
    val expected=normalize(ManualTaskSqlSchema.guard)
    val found=sql.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND tbl_name='manual_tasks'").map{normalize(it[0] as String)}
    require(expected in found)
}
