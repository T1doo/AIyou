package com.t1doo.aiyou.data

internal object LocalCandidateSqlSchema {
    val migration9To10 = listOf(
        "CREATE TABLE local_candidate_batches (id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, sourceRevision INTEGER NOT NULL, sourceHash TEXT NOT NULL, generation INTEGER NOT NULL, ruleVersion INTEGER NOT NULL, version INTEGER NOT NULL, state TEXT NOT NULL, lastMutationId TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, payload BLOB NOT NULL, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX index_local_candidate_batches_entryId_sourceRevision ON local_candidate_batches(entryId,sourceRevision)",
    )
    private val invalidate = "UPDATE local_candidate_batches SET state='INVALIDATED',payload=X'',version=CASE WHEN version<9223372036854775807 THEN version+1 ELSE version END,lastMutationId='SOURCE_INVALIDATED' WHERE entryId=NEW.id AND state IN ('RUNNING','READY');"
    private val accessInvalidate = invalidate.replace("NEW.id", "NEW.entryId")
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS candidate_source_changed AFTER UPDATE ON entries WHEN OLD.currentRevision IS NOT NEW.currentRevision OR OLD.privacyPolicy IS NOT NEW.privacyPolicy OR OLD.capturedAtMillis IS NOT NEW.capturedAtMillis OR OLD.captureTimeZone IS NOT NEW.captureTimeZone BEGIN " + invalidate + " END",
        "CREATE TRIGGER IF NOT EXISTS candidate_access_inserted AFTER INSERT ON remote_source_access BEGIN " + accessInvalidate + " END",
        "CREATE TRIGGER IF NOT EXISTS candidate_access_changed AFTER UPDATE ON remote_source_access WHEN OLD.generation IS NOT NEW.generation OR OLD.deleted IS NOT NEW.deleted OR OLD.connection IS NOT NEW.connection OR OLD.consent IS NOT NEW.consent BEGIN " + accessInvalidate + " END",
        "CREATE TRIGGER IF NOT EXISTS candidate_access_deleted AFTER DELETE ON remote_source_access BEGIN " + accessInvalidate.replace("NEW.entryId", "OLD.entryId") + " END",
        "CREATE TRIGGER IF NOT EXISTS immutable_candidate_binding BEFORE UPDATE ON local_candidate_batches WHEN OLD.id IS NOT NEW.id OR OLD.entryId IS NOT NEW.entryId OR OLD.sourceRevision IS NOT NEW.sourceRevision OR OLD.sourceHash IS NOT NEW.sourceHash OR OLD.generation IS NOT NEW.generation OR OLD.ruleVersion IS NOT NEW.ruleVersion OR OLD.createdAtMillis IS NOT NEW.createdAtMillis OR NOT (OLD.version<9223372036854775807 AND NEW.version=OLD.version+1 OR OLD.version=9223372036854775807 AND NEW.version=OLD.version AND NEW.state='INVALIDATED' AND length(NEW.payload)=0) BEGIN SELECT RAISE(ABORT,'immutable candidate binding or version'); END",
        "CREATE TRIGGER IF NOT EXISTS candidate_state_guard BEFORE UPDATE ON local_candidate_batches WHEN NOT (OLD.state='RUNNING' AND NEW.state IN ('READY','CANCELLED','INVALIDATED') OR OLD.state='READY' AND NEW.state IN ('READY','CANCELLED','INVALIDATED')) BEGIN SELECT RAISE(ABORT,'invalid candidate transition'); END",
    )
}
