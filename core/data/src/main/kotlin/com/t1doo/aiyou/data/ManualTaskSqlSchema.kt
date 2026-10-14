package com.t1doo.aiyou.data

/** Independent confirmed tasks: there is no foreign key to a record or AI proposal. */
internal object ManualTaskSqlSchema {
    val migration5To6 = listOf(
        "CREATE TABLE manual_tasks (id TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, ciphertext BLOB NOT NULL)",
    )
    val guard = "CREATE TRIGGER IF NOT EXISTS manual_task_version_guard BEFORE UPDATE ON manual_tasks WHEN OLD.id IS NOT NEW.id OR OLD.version = 9223372036854775807 OR NEW.version != OLD.version + 1 BEGIN SELECT RAISE(ABORT, 'manual task version conflict'); END"
}
