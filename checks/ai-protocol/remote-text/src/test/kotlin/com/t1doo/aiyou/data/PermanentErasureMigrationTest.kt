package com.t1doo.aiyou.data

import kotlinx.serialization.json.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Exact v7 export and actual 7→8 SQL; this does not stand in for Room/SQLCipher migration. */
class PermanentErasureMigrationTest {
    @Test fun migrationRollbackAndColdReopenPreserveAllV7RowsAndCopyPackageReceiptsByteExactly(): Unit = PermanentErasureFixture().use { f ->
        val pack=RecordPackageCipherTest.verified();val id=PermanentErasureFixture.uuid();f.packages.restore(pack,listOf(id),100)
        val schema=Json.parseToJsonElement(Files.readString(PythonSqliteDatabase.repositoryRoot().resolve("core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/7.json"))).jsonObject["database"]!!.jsonObject
        val tables=schema["entities"]!!.jsonArray.map {it.jsonObject["tableName"]!!.jsonPrimitive.content}
        assertEquals(13,tables.size)
        val rows=tables.associateWith {f.db.read("SELECT * FROM $it ORDER BY 1")}
        val file=f.root.resolve("migration.sqlite")
        var db=PythonSqliteDatabase(file,f.bridge,true,7)
        try {
            db.transaction {sql ->for(table in tables) for(row in rows.getValue(table))
                sql.execute("INSERT INTO $table VALUES("+row.joinToString(",") {"?"}+")",row)
            }
            fun snapshot()=tables.associateWith {db.read("SELECT * FROM $it ORDER BY 1").map {row ->row.map {if(it is ByteArray) it.toList() else it}}}
            val before=snapshot()
            assertThrows(IllegalStateException::class.java) {db.transaction {sql ->
                PermanentErasureSqlSchema.migration7To8.forEach {sql.execute(it)};error("Synthetic migration abort")
            }}
            assertEquals(before,snapshot());assertTrue(db.read("SELECT name FROM sqlite_master WHERE name='record_package_receipts'").isEmpty())
            assertEquals(7L,db.read("PRAGMA user_version").single().single())
            db.transaction {sql ->PermanentErasureSqlSchema.migration7To8.forEach {sql.execute(it)};PermanentErasureSqlSchema.guards.forEach {sql.execute(it)};sql.execute("PRAGMA user_version=8")}
            assertEquals(before,snapshot())
            val expected=db.read("SELECT id,entryId,sourceRevision,operation,state FROM local_jobs WHERE operation IN ('PACKAGE_RESTORE','PACKAGE_ORIGIN') ORDER BY id")
            assertEquals(expected,db.read("SELECT * FROM record_package_receipts ORDER BY id"))
            for(table in listOf("record_package_receipts","permanent_erasure_receipts","owned_file_creations")) assertTrue(db.read("PRAGMA foreign_key_list($table)").isEmpty())
            db.close();db=PythonSqliteDatabase(file,f.bridge,false,7)
            val tags=SqlManualTagRepository(db,PermanentErasureFixture.KEY)
            try {
                assertEquals(8L,db.read("PRAGMA user_version").single().single())
                assertTrue(SqlRecordPackageRepository(db,tags).restore(pack,listOf(PermanentErasureFixture.uuid()),999).replayed)
                assertEquals(expected,db.read("SELECT * FROM record_package_receipts ORDER BY id"));assertEquals(before,snapshot())
                assertTrue(db.read("PRAGMA foreign_key_check").isEmpty())
            } finally {tags.wipeKey()}
        } finally {db.close()}
    }
}
