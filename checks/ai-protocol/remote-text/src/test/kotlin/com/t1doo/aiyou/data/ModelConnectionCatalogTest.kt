package com.t1doo.aiyou.data

import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Actual v12 predecessor and production v13 SQL; host SQLite is not native SQLCipher evidence. */
class ModelConnectionCatalogTest {
    @Test fun actualTwelveMigrationAndColdBothProtocolsRetainOriginalsAndNeverCreateAuthority() {
        ConnectionCatalogFixture().use { f ->
            val first=f.catalog.change(change("first",0,"create1"))!!
            val second=f.catalog.change(change("second",0,"create2",details(ConnectionProtocol.ANTHROPIC_MESSAGES)))!!
            assertEquals(1L,first.version);assertEquals(1L,second.version)
            f.reopen();assertEquals(listOf(first,second),f.catalog.configurations())
            assertEquals(listOf("合成原文", "LOCAL_ONLY"),f.db.read("SELECT r.originalText,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id").single())
            assertEquals(listOf("合成独立草稿",7L),f.db.read("SELECT text,version FROM capture_draft").single())
            for(table in listOf("remote_source_access","remote_text_jobs","text_annotations","local_jobs")) assertTrue(f.db.read("SELECT * FROM $table").isEmpty())
            assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy)
        }
    }
    @Test fun lostCommitAcknowledgementConfirmsSameMutationButLaterChangesAndTombstoneRejectOldReplay() {
        ConnectionCatalogFixture().use { f ->
            val request=change("config",0,"create");var written=false
            f.db.afterSql={sql -> if(sql.startsWith("INSERT INTO model_configurations"))written=true
                if(sql=="COMMIT"&&written){written=false;throw IOException("Synthetic ACK loss")} }
            assertThrows(ModelConnectionStorageFailure::class.java){f.catalog.change(request)}
            f.db.afterSql=null;f.reopen()
            val one=f.catalog.change(request)!!;assertEquals(1L,one.version)
            val two=f.catalog.change(change(one.id,1,"update",details(name="合成第二版")))!!
            assertThrows(ModelConnectionConflict::class.java){f.catalog.change(request)}
            val remove=ModelConnectionChange(two.id,two.version,"delete",null)
            assertNull(f.catalog.change(remove));assertNull(f.catalog.change(remove));f.reopen()
            assertTrue(f.catalog.configurations().isEmpty())
            assertEquals(listOf(3L,1L,null),f.db.read("SELECT version,deleted,payload FROM model_configurations").single())
            assertThrows(ModelConnectionConflict::class.java){f.catalog.change(request)}
            assertThrows(ModelConnectionConflict::class.java){f.catalog.change(change(one.id,2,"late-update"))}
        }
    }
    @Test fun independentOwnerConflictAndAbortedWritePreserveExactPreviousPayload() {
        ConnectionCatalogFixture().use { f ->
            f.catalog.change(change("config",0,"create"))
            f.second().use { second ->
                val other=SqlModelConnectionCatalog(second)
                f.catalog.change(change("config",1,"winner",details(name="合成获胜值")))
                assertThrows(ModelConnectionConflict::class.java){other.change(change("config",1,"stale"))}
            }
            val before=f.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray
            f.db.beforeCommit={throw IOException("Synthetic rollback")}
            assertThrows(ModelConnectionStorageFailure::class.java){f.catalog.change(change("config",2,"aborted"))}
            f.db.beforeCommit=null
            assertArrayEquals(before,f.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray)
            assertEquals(2L,f.catalog.configurations().single().version)
            assertThrows(RuntimeException::class.java){f.db.write("DELETE FROM model_configurations")}
        }
    }
    @Test fun malformedAndOversizedPayloadsFailWithoutResetOrReplacement() {
        ConnectionCatalogFixture().use { f ->
            f.catalog.change(change("config",0,"create"))
            f.db.write("UPDATE model_configurations SET version=2,lastMutationId='corrupt',payload=? WHERE id='config'",byteArrayOf(0,0,0,99))
            val before=f.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray
            assertThrows(ModelConnectionStorageFailure::class.java){f.catalog.configurations()}
            assertThrows(ModelConnectionStorageFailure::class.java){f.catalog.change(change("config",2,"overwrite"))}
            assertArrayEquals(before,f.db.read("SELECT payload FROM model_configurations").single().single() as ByteArray)
            f.db.write("DROP TRIGGER model_configuration_values_update")
            f.db.write("UPDATE model_configurations SET version=3,lastMutationId='oversize',payload=zeroblob(300000) WHERE id='config'")
            assertThrows(ModelConnectionStorageFailure::class.java){f.catalog.configurations()}
            assertEquals(300000L,f.db.read("SELECT length(payload) FROM model_configurations").single().single())
        }
    }
    @Test fun activeAndTombstoneLimitsRejectWholeMutationWithoutCleaningHistory() {
        ConnectionCatalogFixture().use { f ->
            repeat(32){f.catalog.change(change("id$it",0,"create$it"))}
            assertThrows(ModelConnectionLimit::class.java){f.catalog.change(change("one-more",0,"too-many"))}
            assertEquals(32,f.catalog.configurations().size)
            f.catalog.change(ModelConnectionChange("id0",1,"delete",null));f.catalog.change(change("one-more",0,"fits"))
            assertEquals(33L,f.db.read("SELECT count(*) FROM model_configurations").single().single())
        }
        ConnectionCatalogFixture().use { f ->
            f.db.transaction { sql ->repeat(1024){sql.execute("INSERT INTO model_configurations VALUES(?,2,?,1,NULL)",listOf("dead$it","deleted$it"))} }
            assertTrue(f.catalog.configurations().isEmpty())
            assertThrows(ModelConnectionLimit::class.java){f.catalog.change(change("new",0,"stop"))}
            assertEquals(1024L,f.db.read("SELECT count(*) FROM model_configurations").single().single())
        }
    }
    @Test fun unsafeRootsAndMutablePurposeAliasesCannotEnterTheSavedDefinition() {
        for(root in listOf("http://example.test/v1/","https://key@example.test/v1/","https://example.test/v1/?key=synthetic",
            "https://example.test/v1/#fragment","https://example.test:0/","https://example.test/a/../v1/",
            "https://example.test/%2e%2e/v1/","https://example.test/a%2fb/v1/","https://example.test/a%5cb/v1/",
            "https://example.test/v1/chat/completions","https://example.test/v1/messages/"))
            assertThrows(Exception::class.java){ModelConnectionDetails("合成",root,ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION))}
        val mutable=mutableSetOf(ModelConfigurationPurpose.CONVERSATION)
        val config=ModelConnectionDetails("合成","https://example.test/custom/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",mutable)
        mutable.clear();assertEquals(setOf(ModelConfigurationPurpose.CONVERSATION),config.purposes)
        assertEquals("HTTPS://example.test/custom/v1/",ModelConnectionDetails("合成","HTTPS://example.test/custom/v1/",ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",config.purposes).apiRoot)
        assertFalse(config.toString().contains("example.test"))
    }
    @Test fun savedBothProtocolDefinitionsBuildOnlySyntheticDescriptionsAndPreserveProxyPrefix() {
        ConnectionCatalogFixture().use { f ->
            ConnectionProtocol.entries.forEach { protocol ->
                val config=f.catalog.change(change(protocol.name,0,"save-${protocol.name}",details(protocol)))!!
                val request=ProtocolRequests.prepare(config.details.apiRoot,Protocol.valueOf(protocol.name),
                    TextRequest(config.details.modelId,listOf(TextMessage(MessageRole.USER,"合成固定协议样本")),maxOutputTokens=16))
                assertEquals("/custom/v1/"+if(protocol==ConnectionProtocol.CHAT_COMPLETIONS)"chat/completions" else "messages",request.endpoint.path)
                assertTrue(request.bodyJson.contains("synthetic-model"));assertTrue(request.headers.keys.none { it.equals("Authorization",true)||it.equals("x-api-key",true) })
            }
            assertTrue(f.db.read("SELECT id FROM remote_text_jobs").isEmpty())
        }
    }
    companion object {
        fun details(protocol:ConnectionProtocol=ConnectionProtocol.CHAT_COMPLETIONS,name:String="合成连接")=
            ModelConnectionDetails(name,"https://example.test/custom/v1/",protocol,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION,ModelConfigurationPurpose.ORGANIZATION))
        fun change(id:String,version:Long,mutation:String,details:ModelConnectionDetails=details())=ModelConnectionChange(id,version,mutation,details)
    }
}

internal class ConnectionCatalogFixture:AutoCloseable {
    private val root=Files.createTempDirectory("aiyou-synthetic-config-")
    private val bridge=root.resolve("sqlite_bridge.py")
    private val file=root.resolve("records.sqlite")
    var db:PythonSqliteDatabase;private set
    val catalog get()=SqlModelConnectionCatalog(db)
    init {
        javaClass.getResourceAsStream("/sqlite_bridge.py").use { Files.copy(checkNotNull(it),bridge) }
        db=PythonSqliteDatabase(file,bridge,true,12)
        db.transaction { sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES('original',1,10,'UTC','LOCAL_ONLY')")
            sql.execute("INSERT INTO entry_revisions VALUES('original',1,'合成原文',?,10)",listOf(OriginalExports.hash("合成原文")))
            sql.execute("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            ModelConnectionSqlSchema.migration12To13.forEach { sql.execute(it) };sql.execute("PRAGMA user_version=13")
        }
    }
    fun reopen(){db.close();db=PythonSqliteDatabase(file,bridge,false,12)}
    fun second()=PythonSqliteDatabase(file,bridge,false,12)
    override fun close(){db.close();root.toFile().deleteRecursively()}
}
