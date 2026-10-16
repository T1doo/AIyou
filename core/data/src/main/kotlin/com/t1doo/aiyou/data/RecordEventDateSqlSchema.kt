package com.t1doo.aiyou.data

/** A separate metadata receipt in the existing SQLCipher database. No original or capture rewrite. */
internal object RecordEventDateSqlSchema {
    private const val valid = "typeof(NEW.version)='integer' AND NEW.version>0 AND " +
        "typeof(NEW.lastMutationId)='text' AND length(NEW.lastMutationId) BETWEEN 1 AND 128 AND " +
        "typeof(NEW.lastSourceRevision)='integer' AND NEW.lastSourceRevision>0 AND " +
        "EXISTS(SELECT 1 FROM entries e WHERE e.id=NEW.entryId AND NEW.lastSourceRevision<=e.currentRevision) AND " +
        "((NEW.localDate IS NULL AND NEW.timeZone IS NULL) OR " +
        "(typeof(NEW.localDate)='text' AND length(NEW.localDate)=10 AND " +
        "NEW.localDate GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' AND " +
        "typeof(NEW.timeZone)='text' AND length(NEW.timeZone) BETWEEN 1 AND 128))"
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS event_date_values_insert BEFORE INSERT ON entry_event_dates WHEN NOT (" + valid + ") BEGIN SELECT RAISE(ABORT,'invalid event date metadata'); END",
        "CREATE TRIGGER IF NOT EXISTS event_date_values_update BEFORE UPDATE ON entry_event_dates WHEN NOT (" + valid + ") BEGIN SELECT RAISE(ABORT,'invalid event date metadata'); END",
        "CREATE TRIGGER IF NOT EXISTS event_date_version_guard BEFORE UPDATE ON entry_event_dates WHEN " +
            "OLD.entryId IS NOT NEW.entryId OR OLD.version=9223372036854775807 OR NEW.version!=OLD.version+1 OR " +
            "OLD.lastMutationId IS NEW.lastMutationId BEGIN SELECT RAISE(ABORT,'event date version conflict'); END",
    ) + listOf("INSERT", "UPDATE", "DELETE").map { event ->
        // Event metadata changes only the publication epoch; original FTS/jobs and remote authority stay intact.
        "CREATE TRIGGER IF NOT EXISTS event_date_search_" + event.lowercase() + " AFTER " + event +
            " ON entry_event_dates BEGIN UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1; END"
    }
    val migration10To11 = listOf(
        "CREATE TABLE entry_event_dates (entryId TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, lastSourceRevision INTEGER NOT NULL, localDate TEXT, timeZone TEXT, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    ) + guards
}
