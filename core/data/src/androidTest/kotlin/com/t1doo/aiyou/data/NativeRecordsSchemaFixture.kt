package com.t1doo.aiyou.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject

/** Schema-only fixture from the exact checked-in Room export; never copies authorization logic. */
internal object NativeRecordsSchemaFixture {
    fun create(file: File, syntheticKey: ByteArray, version: Int, seed: (SQLiteDatabase) -> Unit) {
        require(version in 2..12)
        check(!file.exists()) { "Native fixture must own a new database file" }
        val schema = exported(version)
        check(schema.getInt("version") == version)
        SQLiteDatabase.openOrCreateDatabase(file.absolutePath, syntheticKey, null, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.rawQuery("PRAGMA foreign_keys", emptyArray()).use { check(it.moveToFirst() && it.getInt(0) == 1) }
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (position in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.beginTransaction()
            try { seed(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
            db.version = version
            db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", emptyArray()).use {
                check(it.moveToFirst() && it.getString(0) == schema.getString("identityHash"))
            }
            db.rawQuery("PRAGMA foreign_key_check", emptyArray()).use { check(!it.moveToFirst()) }
        }
    }

    /** Reads the actual generated v13 export; never substitutes Room validation or fabricates an identity. */
    fun assertCurrentSchema(db: SupportSQLiteDatabase) {
        val schema = exported(13)
        assertEquals(13, db.version)
        db.query("PRAGMA foreign_keys").use { check(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            check(it.moveToFirst()); assertEquals(schema.getString("identityHash"), it.getString(0))
        }
        val entities = schema.getJSONArray("entities")
        val expectedTables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
        val actualTables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('android_metadata','room_master_table')").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(20, expectedTables.size)
        val ftsShadows = setOf("content", "segments", "segdir", "docsize", "stat").map { "local_search_fts_" + it }.toSet()
        assertEquals(expectedTables + ftsShadows, actualTables)
        for (position in 0 until entities.length()) {
            val entity = entities.getJSONObject(position)
            val table = entity.getString("tableName")
            val fields = entity.getJSONArray("fields")
            if (table == "local_search_fts") {
                // FTS PRAGMA nullability differs from declared fields. Verify the
                // exact generated CREATE/options, columns and shadow inventory.
                // The ordinary tables below retain their complete strict checks.
                fun normalize(value: String) = value.replace("IF NOT EXISTS ", "").replace(96.toChar().toString(), "").replace("\"", "")
                    .replace(Regex("\\s+"), "").lowercase()
                val actualSql = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='local_search_fts'").use {
                    check(it.moveToFirst()); it.getString(0)
                }
                assertEquals(normalize(entity.getString("createSql").replace("\${TABLE_NAME}", table)), normalize(actualSql))
                val columns = db.query("PRAGMA table_info(local_search_fts)").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                }
                assertEquals((0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }, columns)
                continue
            }
            val primaryKeys = strings(entity.getJSONObject("primaryKey").getJSONArray("columnNames"))
            val expectedColumns = (0 until fields.length()).map { index ->
                val field = fields.getJSONObject(index)
                val name = field.getString("columnName")
                Column(name, field.getString("affinity"), field.optBoolean("notNull"),
                    if (field.has("defaultValue") && !field.isNull("defaultValue")) field.getString("defaultValue") else null,
                    primaryKeys.indexOf(name).let { if (it < 0) 0 else it + 1 })
            }
            val actualColumns = db.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1,
                        if (cursor.isNull(4)) null else cursor.getString(4), cursor.getInt(5)))
                }
            }
            assertEquals("Native columns for $table", expectedColumns, actualColumns)

            val foreignKeys = entity.optJSONArray("foreignKeys") ?: JSONArray()
            val expectedForeignKeys = (0 until foreignKeys.length()).map { index ->
                val key = foreignKeys.getJSONObject(index)
                ForeignKey(key.getString("table"), key.getString("onUpdate"), key.getString("onDelete"),
                    strings(key.getJSONArray("columns")), strings(key.getJSONArray("referencedColumns")))
            }.toSet()
            val rows = db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ForeignKeyRow(cursor.getInt(0), cursor.getInt(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6)))
                }
            }
            val actualForeignKeys = rows.groupBy { it.id }.values.map { unsorted ->
                val ordered = unsorted.sortedBy { it.sequence }
                ForeignKey(ordered.first().table, ordered.first().update, ordered.first().delete,
                    ordered.map { it.from }, ordered.map { it.to })
            }.toSet()
            assertEquals("Native foreign keys/actions for $table", expectedForeignKeys, actualForeignKeys)

            val indices = entity.optJSONArray("indices") ?: JSONArray()
            val expectedIndices = (0 until indices.length()).map { indices.getJSONObject(it) }
            val actualIndices = db.query("PRAGMA index_list(`$table`)").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (!name.startsWith("sqlite_")) put(name, cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1)
                    }
                }
            }
            assertEquals("Native declared indexes for $table", expectedIndices.map { it.getString("name") }.toSet(), actualIndices.keys)
            expectedIndices.forEach { index ->
                val name = index.getString("name")
                assertEquals("Native unique index $name", index.getBoolean("unique"), actualIndices[name])
                val columns = strings(index.getJSONArray("columnNames"))
                val actualColumnsInIndex = db.query("PRAGMA index_info(`$name`)").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(2)) }
                }.sortedBy { it.first }.map { it.second }
                assertEquals("Native index column order $name", columns, actualColumnsInIndex)
                val explicitOrders = strings(index.optJSONArray("orders") ?: JSONArray())
                val orders = columns.indices.map { explicitOrders.getOrNull(it) ?: "ASC" }
                val actualOrders = db.query("PRAGMA index_xinfo(`$name`)").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) if (cursor.getInt(cursor.getColumnIndexOrThrow("key")) == 1) {
                            add(cursor.getInt(0) to if (cursor.getInt(cursor.getColumnIndexOrThrow("desc")) == 1) "DESC" else "ASC")
                        }
                    }
                }.sortedBy { it.first }.map { it.second }
                assertEquals("Native index sort order $name", orders, actualOrders)
            }
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun exported(version: Int): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("records-schema-v$version.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        .getJSONObject("database").also { check(it.getInt("version") == version) }
    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
    private data class Column(val name: String, val type: String, val notNull: Boolean, val default: String?, val primaryKey: Int)
    private data class ForeignKey(val table: String, val update: String, val delete: String, val from: List<String>, val to: List<String>)
    private data class ForeignKeyRow(val id: Int, val sequence: Int, val table: String, val from: String, val to: String,
                                     val update: String, val delete: String)
}
