package com.t1doo.aiyou.data

/** Additive Room migration and host tests execute these same statements. No FK to erased sources. */
internal object PermanentErasureSqlSchema {
    val migration7To8 = listOf(
        "CREATE TABLE permanent_erasure_receipts (entryId TEXT NOT NULL PRIMARY KEY, sourceRevision INTEGER NOT NULL, generation INTEGER NOT NULL, mutationId TEXT NOT NULL, phase TEXT NOT NULL, plan TEXT NOT NULL, packageProof TEXT NOT NULL)",
        "CREATE UNIQUE INDEX index_permanent_erasure_receipts_mutationId ON permanent_erasure_receipts(mutationId)",
        "CREATE TABLE record_package_receipts (id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, sourceRevision INTEGER NOT NULL, operation TEXT NOT NULL, state TEXT NOT NULL)",
        "INSERT INTO record_package_receipts(id,entryId,sourceRevision,operation,state) SELECT id,entryId,sourceRevision,operation,state FROM local_jobs WHERE operation IN ('PACKAGE_RESTORE','PACKAGE_ORIGIN')",
        "CREATE TABLE owned_file_creations (bucket TEXT NOT NULL, name TEXT NOT NULL, entryId TEXT NOT NULL, state TEXT NOT NULL, identity TEXT NOT NULL, PRIMARY KEY(bucket,name))",
        "CREATE INDEX index_owned_file_creations_entryId ON owned_file_creations(entryId)",
    )
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS immutable_erasure_intent BEFORE UPDATE ON permanent_erasure_receipts WHEN OLD.entryId IS NOT NEW.entryId OR OLD.sourceRevision IS NOT NEW.sourceRevision OR OLD.generation IS NOT NEW.generation OR OLD.mutationId IS NOT NEW.mutationId OR OLD.packageProof IS NOT NEW.packageProof OR NOT ((OLD.phase='PREPARED' AND NEW.phase='PLANNED' AND OLD.plan='' AND NEW.plan!='') OR (OLD.phase='PLANNED' AND NEW.phase='DONE' AND OLD.plan!='' AND NEW.plan='')) BEGIN SELECT RAISE(ABORT,'erasure receipt conflict'); END",
        "CREATE TRIGGER IF NOT EXISTS erased_source_no_insert BEFORE INSERT ON entries WHEN EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=NEW.id) BEGIN SELECT RAISE(ABORT,'erased source'); END",
        "CREATE TRIGGER IF NOT EXISTS erased_source_no_update BEFORE UPDATE ON entries WHEN EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=NEW.id) BEGIN SELECT RAISE(ABORT,'erasing source'); END",
        "CREATE TRIGGER IF NOT EXISTS erased_revision_no_insert BEFORE INSERT ON entry_revisions WHEN EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=NEW.entryId) BEGIN SELECT RAISE(ABORT,'erasing source'); END",
        "CREATE TRIGGER IF NOT EXISTS erased_source_no_restore BEFORE UPDATE ON remote_source_access WHEN NEW.deleted=0 AND EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=NEW.entryId) BEGIN SELECT RAISE(ABORT,'erasing source'); END",
        "CREATE TRIGGER IF NOT EXISTS erased_attachment_no_insert BEFORE INSERT ON attachments WHEN EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=NEW.entryId) BEGIN SELECT RAISE(ABORT,'erasing source'); END",
        "CREATE TRIGGER IF NOT EXISTS erased_attachment_no_update BEFORE UPDATE ON attachments WHEN EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=NEW.entryId) BEGIN SELECT RAISE(ABORT,'erasing source'); END",
    )
}

internal class SqlErasureFileHooks(private val database: RecordsSqlDatabase) : ErasureFileHooks {
    override fun fenced(entryId: String): Boolean = database.transaction { sql -> fenced(sql, entryId) }
    override fun reserve(entryId: String, bucket: String, name: String) = database.transaction { sql ->
        check(!fenced(sql, entryId))
        sql.execute("INSERT INTO owned_file_creations(bucket,name,entryId,state,identity) VALUES(?,?,?,'RESERVED','')", listOf(bucket,name,entryId)); Unit
    }
    override fun created(entryId: String, bucket: String, name: String, identity: String) = database.transaction { sql ->
        // A fence may have been committed during CREATE_NEW. Record ownership, then refuse publication.
        check(sql.execute("UPDATE owned_file_creations SET state='CREATED',identity=? WHERE bucket=? AND name=? AND entryId=? AND state='RESERVED' AND identity=''",
            listOf(identity,bucket,name,entryId)) == 1); Unit
    }
    override fun creation(entryId: String, bucket: String, name: String): String? = database.transaction { sql ->
        sql.query("SELECT identity FROM owned_file_creations WHERE bucket=? AND name=? AND entryId=? AND state='CREATED'", listOf(bucket,name,entryId)).singleOrNull()?.single() as String?
    }
    override fun forgotten(entryId: String, bucket: String, name: String) = database.transaction { sql ->
        sql.execute("DELETE FROM owned_file_creations WHERE bucket=? AND name=? AND entryId=?", listOf(bucket,name,entryId)); Unit
    }
    companion object {
        fun fenced(sql: RecordsSqlConnection, entryId: String) =
            sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=?", listOf(entryId)).isNotEmpty()
    }
}
