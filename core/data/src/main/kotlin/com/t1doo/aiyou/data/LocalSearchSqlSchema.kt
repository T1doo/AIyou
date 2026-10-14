package com.t1doo.aiyou.data

/** The very same additive SQL is executed by Room migration and the synthetic host driver. */
internal object LocalSearchSqlSchema {
    val migration6To7 = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS local_search_fts USING FTS4(entryId TEXT NOT NULL, bodyTokens TEXT NOT NULL, tagTokens TEXT NOT NULL, tokenize=simple, notindexed=entryId)",
        "CREATE TABLE IF NOT EXISTS local_search_documents (entryId TEXT NOT NULL PRIMARY KEY, sourceRevision INTEGER NOT NULL, contentHash TEXT NOT NULL, tagVersion INTEGER NOT NULL, visibilityGeneration INTEGER NOT NULL, tokenizerVersion INTEGER NOT NULL, tokenizerProfile TEXT NOT NULL, indexedEpoch INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS local_search_epoch (slot INTEGER NOT NULL PRIMARY KEY, epoch INTEGER NOT NULL, tokenizerVersion INTEGER NOT NULL, tokenizerProfile TEXT NOT NULL)",
        "INSERT INTO local_search_epoch(slot,epoch,tokenizerVersion,tokenizerProfile) VALUES(1,0,1,'UNKNOWN')",
        "UPDATE local_jobs SET state='PENDING' WHERE operation='LOCAL_INDEX' AND EXISTS(SELECT 1 FROM entries e WHERE e.id=entryId AND e.currentRevision=sourceRevision AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1))",
    )

    private fun invalidate(id: String) = "DELETE FROM local_search_fts WHERE entryId=" + id + "; " +
        "DELETE FROM local_search_documents WHERE entryId=" + id + "; " +
        "UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1; " +
        "UPDATE local_jobs SET state=CASE WHEN EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=" + id + " AND a.deleted=1) THEN 'CANCELLED' ELSE 'PENDING' END " +
        "WHERE entryId=" + id + " AND operation='LOCAL_INDEX' AND sourceRevision=(SELECT currentRevision FROM entries WHERE id=" + id + "); " +
        "UPDATE local_jobs SET state='CANCELLED' WHERE entryId=" + id + " AND operation='LOCAL_INDEX' AND sourceRevision!=(SELECT currentRevision FROM entries WHERE id=" + id + ") AND state IN ('PENDING','RUNNING','WAITING_CONDITIONS'); "

    val guards: List<String> = buildList {
        add("CREATE TRIGGER IF NOT EXISTS local_search_epoch_guard BEFORE UPDATE ON local_search_epoch WHEN OLD.slot!=1 OR NEW.slot!=1 OR OLD.epoch<0 OR OLD.epoch=9223372036854775807 OR NEW.epoch!=OLD.epoch+1 BEGIN SELECT RAISE(ABORT,'local search epoch conflict'); END")
        for ((table, key) in listOf("entries" to "id", "entry_manual_tags" to "entryId", "remote_source_access" to "entryId")) {
            for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                val id = (if (event == "DELETE") "OLD." else "NEW.") + key
                add("CREATE TRIGGER IF NOT EXISTS local_search_" + table + "_" + event.lowercase() +
                    " AFTER " + event + " ON " + table + " BEGIN " + invalidate(id) + " END")
            }
        }
    }
}
