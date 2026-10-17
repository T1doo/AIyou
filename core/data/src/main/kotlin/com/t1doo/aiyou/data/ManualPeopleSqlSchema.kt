package com.t1doo.aiyou.data

/** The same SQL is used by Room 11->12 and host SQLite. No rewrite of existing records or policies. */
internal object ManualPeopleSqlSchema {
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS manual_people_version_guard BEFORE UPDATE ON entry_manual_people WHEN " +
            "OLD.entryId IS NOT NEW.entryId OR OLD.version=9223372036854775807 OR NEW.version!=OLD.version+1 OR " +
            "OLD.lastMutationId IS NEW.lastMutationId BEGIN SELECT RAISE(ABORT,'manual people version conflict'); END"
    ) + listOf("INSERT", "UPDATE").map { event ->
        "CREATE TRIGGER IF NOT EXISTS manual_people_values_" + event.lowercase() + " BEFORE " + event +
            " ON entry_manual_people WHEN typeof(NEW.version)!='integer' OR NEW.version<=0 OR " +
            "typeof(NEW.lastMutationId)!='text' OR length(NEW.lastMutationId) NOT BETWEEN 1 AND 128 OR " +
            "typeof(NEW.ciphertext)!='blob' OR length(NEW.ciphertext) NOT BETWEEN 29 AND 8192 " +
            "BEGIN SELECT RAISE(ABORT,'invalid manual people'); END"
    } + listOf("INSERT", "UPDATE", "DELETE").map { event ->
        // People are a separate exact filter, never inserted into original-text FTS or AI annotations.
        "CREATE TRIGGER IF NOT EXISTS manual_people_search_" + event.lowercase() + " AFTER " + event +
            " ON entry_manual_people BEGIN UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1; END"
    }
    val migration11To12 = listOf(
        "CREATE TABLE entry_manual_people (entryId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, ciphertext BLOB NOT NULL, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
    ) + guards
}
