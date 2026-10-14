package com.t1doo.aiyou.data

/** Four additive columns in the same encrypted record; deleting entries removes the receipt too. */
internal object RecordFavoriteSqlSchema {
    private const val valid = "typeof(NEW.isFavorite)='integer' AND typeof(NEW.favoriteVersion)='integer' AND typeof(NEW.lastFavoriteSourceRevision)='integer' " +
        "AND NEW.isFavorite IN (0,1) AND NEW.favoriteVersion>=0 AND " +
        "((NEW.favoriteVersion=0 AND NEW.isFavorite=0 AND NEW.lastFavoriteMutationId IS NULL AND NEW.lastFavoriteSourceRevision=0) OR " +
        "(NEW.favoriteVersion>0 AND typeof(NEW.lastFavoriteMutationId)='text' AND length(NEW.lastFavoriteMutationId) BETWEEN 1 AND 128 " +
        "AND NEW.lastFavoriteSourceRevision BETWEEN 1 AND NEW.currentRevision))"
    val guards = listOf(
        "CREATE TRIGGER IF NOT EXISTS favorite_values_insert BEFORE INSERT ON entries WHEN NOT (" + valid + ") BEGIN SELECT RAISE(ABORT,'invalid favorite metadata'); END",
        "CREATE TRIGGER IF NOT EXISTS favorite_values_update BEFORE UPDATE ON entries WHEN NOT (" + valid + ") BEGIN SELECT RAISE(ABORT,'invalid favorite metadata'); END",
        "CREATE TRIGGER IF NOT EXISTS favorite_version_guard BEFORE UPDATE ON entries WHEN " +
            "(OLD.isFavorite IS NOT NEW.isFavorite OR OLD.favoriteVersion IS NOT NEW.favoriteVersion OR OLD.lastFavoriteMutationId IS NOT NEW.lastFavoriteMutationId OR OLD.lastFavoriteSourceRevision IS NOT NEW.lastFavoriteSourceRevision) AND " +
            "(OLD.favoriteVersion=9223372036854775807 OR NEW.favoriteVersion!=OLD.favoriteVersion+1 OR OLD.lastFavoriteMutationId IS NEW.lastFavoriteMutationId OR " +
            "OLD.id IS NOT NEW.id OR OLD.currentRevision IS NOT NEW.currentRevision OR OLD.capturedAtMillis IS NOT NEW.capturedAtMillis OR OLD.captureTimeZone IS NOT NEW.captureTimeZone OR OLD.privacyPolicy IS NOT NEW.privacyPolicy) " +
            "BEGIN SELECT RAISE(ABORT,'favorite version conflict'); END",
    )

    // Legacy v7/v8 guards retain their original behavior. Only v9 installs this replacement.
    val sourceIndexUpdate = "CREATE TRIGGER local_search_entries_update AFTER UPDATE ON entries BEGIN " +
        "UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1; " +
        "DELETE FROM local_search_fts WHERE entryId=NEW.id AND (" + sourceChanged + "); " +
        "DELETE FROM local_search_documents WHERE entryId=NEW.id AND (" + sourceChanged + "); " +
        "UPDATE local_jobs SET state=CASE WHEN EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=NEW.id AND a.deleted=1) THEN 'CANCELLED' ELSE 'PENDING' END " +
            "WHERE entryId=NEW.id AND operation='LOCAL_INDEX' AND sourceRevision=NEW.currentRevision AND (" + sourceChanged + "); " +
        "UPDATE local_jobs SET state='CANCELLED' WHERE entryId=NEW.id AND operation='LOCAL_INDEX' AND sourceRevision!=NEW.currentRevision AND state IN ('PENDING','RUNNING','WAITING_CONDITIONS') AND (" + sourceChanged + "); END"
    private const val sourceChanged = "OLD.id IS NOT NEW.id OR OLD.currentRevision IS NOT NEW.currentRevision OR OLD.capturedAtMillis IS NOT NEW.capturedAtMillis OR OLD.captureTimeZone IS NOT NEW.captureTimeZone OR OLD.privacyPolicy IS NOT NEW.privacyPolicy"

    val migration8To9 = listOf(
        "ALTER TABLE entries ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE entries ADD COLUMN favoriteVersion INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE entries ADD COLUMN lastFavoriteMutationId TEXT",
        "ALTER TABLE entries ADD COLUMN lastFavoriteSourceRevision INTEGER NOT NULL DEFAULT 0",
        "DROP TRIGGER IF EXISTS local_search_entries_update",
        sourceIndexUpdate,
    ) + guards
}
