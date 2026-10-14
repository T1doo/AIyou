package com.t1doo.aiyou.data

/** Same SQL is executed by Room migration and real host SQLite regression fixtures. */
internal object ManualTagSqlSchema {
    val migration4To5 = listOf(
        "CREATE TABLE entry_manual_tags (entryId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, ciphertext BLOB NOT NULL, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    )
    val guard = "CREATE TRIGGER IF NOT EXISTS manual_tag_version_guard BEFORE UPDATE ON entry_manual_tags WHEN OLD.entryId IS NOT NEW.entryId OR OLD.version = 9223372036854775807 OR NEW.version != OLD.version + 1 BEGIN SELECT RAISE(ABORT, 'manual tag version conflict'); END"
}
