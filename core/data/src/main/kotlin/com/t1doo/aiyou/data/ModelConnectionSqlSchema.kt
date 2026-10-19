package com.t1doo.aiyou.data

/** Planned D04 metadata, separate from source grants, jobs and record backup contents. */
internal object ModelConnectionSqlSchema {
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS model_configuration_version BEFORE UPDATE ON model_configurations WHEN " +
            "OLD.id IS NOT NEW.id OR OLD.deleted=1 OR OLD.version=9223372036854775807 OR NEW.version!=OLD.version+1 OR " +
            "OLD.lastMutationId IS NEW.lastMutationId BEGIN SELECT RAISE(ABORT,'configuration version conflict'); END",
        "CREATE TRIGGER IF NOT EXISTS model_configuration_delete BEFORE DELETE ON model_configurations " +
            "BEGIN SELECT RAISE(ABORT,'configuration tombstone required'); END"
    ) + listOf("INSERT", "UPDATE").map { event ->
        "CREATE TRIGGER IF NOT EXISTS model_configuration_values_${event.lowercase()} BEFORE $event ON model_configurations WHEN " +
            "typeof(NEW.id)!='text' OR length(NEW.id) NOT BETWEEN 1 AND 128 OR " +
            "typeof(NEW.version)!='integer' OR NEW.version<=0 OR typeof(NEW.lastMutationId)!='text' OR " +
            "length(NEW.lastMutationId) NOT BETWEEN 1 AND 128 OR typeof(NEW.deleted)!='integer' OR NEW.deleted NOT IN (0,1) OR " +
            "(NEW.deleted=1 AND NEW.payload IS NOT NULL) OR (NEW.deleted=0 AND (typeof(NEW.payload)!='blob' OR length(NEW.payload) NOT BETWEEN 1 AND 8192)) " +
            "BEGIN SELECT RAISE(ABORT,'invalid configuration'); END"
    }
    val migration12To13 = listOf("CREATE TABLE model_configurations (id TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, deleted INTEGER NOT NULL, payload BLOB)") + guards
}
