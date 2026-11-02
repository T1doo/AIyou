package com.t1doo.aiyou.data

/** Independent of Entry/derived-candidate invalidation: authorization changes never erase a user question. */
internal object SavedQuestionSqlSchema {
    const val columns = "id,version,lastMutationId,createdAtMillis,updatedAtMillis,state,payload"
    val create = "CREATE TABLE saved_questions (id TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, state TEXT NOT NULL, payload BLOB NOT NULL)"
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS saved_questions_insert BEFORE INSERT ON saved_questions WHEN NEW.version<1 OR NEW.createdAtMillis<0 OR NEW.updatedAtMillis<NEW.createdAtMillis OR NEW.state NOT IN ('ACTIVE','DELETED') OR length(NEW.payload)<29 OR length(NEW.payload)>4096 OR (SELECT count(*) FROM saved_questions)>=128 BEGIN SELECT RAISE(ABORT,'Invalid saved question'); END",
        "CREATE TRIGGER IF NOT EXISTS saved_questions_update BEFORE UPDATE ON saved_questions WHEN NEW.id!=OLD.id OR NEW.createdAtMillis!=OLD.createdAtMillis OR OLD.version=9223372036854775807 OR NEW.version!=OLD.version+1 OR NEW.lastMutationId=OLD.lastMutationId OR NEW.updatedAtMillis<OLD.updatedAtMillis OR OLD.state='DELETED' OR NEW.state NOT IN ('ACTIVE','DELETED') OR length(NEW.payload)<29 OR length(NEW.payload)>4096 BEGIN SELECT RAISE(ABORT,'Invalid saved question transition'); END",
        "CREATE TRIGGER IF NOT EXISTS saved_questions_delete BEFORE DELETE ON saved_questions BEGIN SELECT RAISE(ABORT,'Saved question tombstone required'); END",
    )
}
