package com.t1doo.aiyou.data

/** Disposable observations only. Updating a connection invalidates its profile in that same transaction. */
internal object ModelCapabilitySqlSchema {
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS model_capability_retire AFTER UPDATE ON model_configurations " +
            "BEGIN DELETE FROM model_capability_profiles WHERE configId=NEW.id; END",
        "CREATE TRIGGER IF NOT EXISTS model_capability_transition BEFORE UPDATE ON model_capability_profiles WHEN " +
            "OLD.configId IS NOT NEW.configId OR " +
            "NOT ((NEW.generation=OLD.generation+1 AND OLD.generation<9223372036854775807 AND NEW.probeId IS NOT OLD.probeId AND NEW.phase='PENDING') OR " +
            "(NEW.generation=OLD.generation AND NEW.probeId=OLD.probeId AND ((OLD.phase='PENDING' AND NEW.phase IN ('ADMITTED','FINISHED')) OR " +
            "(OLD.phase='ADMITTED' AND NEW.phase='FINISHED')))) " +
            "BEGIN SELECT RAISE(ABORT,'capability transition rejected'); END"
    ) + listOf("INSERT","UPDATE").map { event ->
        "CREATE TRIGGER IF NOT EXISTS model_capability_values_${event.lowercase()} BEFORE $event ON model_capability_profiles WHEN " +
            "typeof(NEW.configId)!='text' OR length(NEW.configId) NOT BETWEEN 1 AND 128 OR " +
            "typeof(NEW.generation)!='integer' OR NEW.generation<=0 OR typeof(NEW.probeId)!='text' OR length(NEW.probeId) NOT BETWEEN 1 AND 128 OR " +
            "NEW.phase NOT IN ('PENDING','ADMITTED','FINISHED') OR typeof(NEW.payload)!='blob' OR length(NEW.payload) NOT BETWEEN 1 AND 12288 OR " +
            "NOT EXISTS(SELECT 1 FROM model_configurations WHERE id=NEW.configId AND deleted=0) " +
            "BEGIN SELECT RAISE(ABORT,'invalid capability profile'); END"
    }
    val migration13To14 = listOf("CREATE TABLE model_capability_profiles (configId TEXT NOT NULL PRIMARY KEY, generation INTEGER NOT NULL, probeId TEXT NOT NULL, phase TEXT NOT NULL, payload BLOB NOT NULL, FOREIGN KEY(configId) REFERENCES model_configurations(id) ON UPDATE NO ACTION ON DELETE NO ACTION)") + guards
}
