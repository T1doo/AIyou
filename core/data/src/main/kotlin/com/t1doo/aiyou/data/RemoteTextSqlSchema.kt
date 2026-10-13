package com.t1doo.aiyou.data

/** The exact migration/guards are shared by Room and the real SQLite host contract. */
object RemoteTextSqlSchema {
    val originalRevisionGuard = "CREATE TRIGGER IF NOT EXISTS immutable_original_revision BEFORE UPDATE ON entry_revisions BEGIN SELECT RAISE(ABORT, 'immutable original revision'); END"
    val migration3To4: List<String> = listOf(
        "CREATE TABLE remote_source_access (entryId TEXT NOT NULL PRIMARY KEY, generation INTEGER NOT NULL, deleted INTEGER NOT NULL, connection BLOB, consent BLOB, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
        "CREATE TABLE remote_text_jobs (id TEXT NOT NULL PRIMARY KEY, bindingKey TEXT NOT NULL, entryId TEXT NOT NULL, sourceRevision INTEGER NOT NULL, generation INTEGER NOT NULL, purpose TEXT NOT NULL, promptVersion TEXT NOT NULL, connection BLOB NOT NULL, consent BLOB NOT NULL, state TEXT NOT NULL, sourceHash TEXT NOT NULL, FOREIGN KEY(entryId,sourceRevision) REFERENCES entry_revisions(entryId,revision) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
        "CREATE UNIQUE INDEX index_remote_text_jobs_bindingKey ON remote_text_jobs(bindingKey)",
        "CREATE INDEX index_remote_text_jobs_entryId_sourceRevision ON remote_text_jobs(entryId,sourceRevision)",
        "CREATE TABLE text_annotations (jobId TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, sourceRevision INTEGER NOT NULL, generation INTEGER NOT NULL, candidate BLOB NOT NULL, resultHash TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, FOREIGN KEY(jobId) REFERENCES remote_text_jobs(id) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(entryId,sourceRevision) REFERENCES entry_revisions(entryId,revision) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
        "CREATE INDEX index_text_annotations_entryId_sourceRevision ON text_annotations(entryId,sourceRevision)",
    )

    val guards: List<String> = listOf(
        originalRevisionGuard,
        "CREATE TRIGGER IF NOT EXISTS remote_source_changed AFTER UPDATE ON entries WHEN OLD.currentRevision IS NOT NEW.currentRevision OR OLD.privacyPolicy IS NOT NEW.privacyPolicy OR OLD.capturedAtMillis IS NOT NEW.capturedAtMillis OR OLD.captureTimeZone IS NOT NEW.captureTimeZone BEGIN INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(NEW.id,1,0,NULL,NULL) ON CONFLICT(entryId) DO UPDATE SET generation=generation+1, connection=NULL, consent=NULL; UPDATE remote_text_jobs SET state='CANCELLED' WHERE entryId=NEW.id AND state IN ('QUEUED','ADMITTED'); DELETE FROM text_annotations WHERE entryId=NEW.id; END",
        "CREATE TRIGGER IF NOT EXISTS remote_access_changed AFTER UPDATE ON remote_source_access WHEN OLD.generation IS NOT NEW.generation OR OLD.deleted IS NOT NEW.deleted OR OLD.connection IS NOT NEW.connection OR OLD.consent IS NOT NEW.consent BEGIN UPDATE remote_text_jobs SET state='CANCELLED' WHERE entryId=NEW.entryId AND state IN ('QUEUED','ADMITTED'); DELETE FROM text_annotations WHERE entryId=NEW.entryId; END",
        "CREATE TRIGGER IF NOT EXISTS immutable_remote_text_job BEFORE UPDATE ON remote_text_jobs WHEN OLD.id IS NOT NEW.id OR OLD.bindingKey IS NOT NEW.bindingKey OR OLD.entryId IS NOT NEW.entryId OR OLD.sourceRevision IS NOT NEW.sourceRevision OR OLD.generation IS NOT NEW.generation OR OLD.purpose IS NOT NEW.purpose OR OLD.promptVersion IS NOT NEW.promptVersion OR OLD.connection IS NOT NEW.connection OR OLD.consent IS NOT NEW.consent OR OLD.sourceHash IS NOT NEW.sourceHash BEGIN SELECT RAISE(ABORT,'immutable remote text job'); END",
        "CREATE TRIGGER IF NOT EXISTS remote_text_job_state BEFORE UPDATE OF state ON remote_text_jobs WHEN OLD.state IS NOT NEW.state AND NOT (OLD.state='QUEUED' AND NEW.state IN ('ADMITTED','CANCELLED') OR OLD.state='ADMITTED' AND NEW.state IN ('COMMITTED','CANCELLED')) BEGIN SELECT RAISE(ABORT,'invalid remote text job state'); END",
        "CREATE TRIGGER IF NOT EXISTS immutable_text_annotation BEFORE UPDATE ON text_annotations BEGIN SELECT RAISE(ABORT,'immutable text annotation'); END",
    )
}
