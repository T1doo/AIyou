package com.t1doo.aiyou.data

/** Shared monotonic receipt only. Image changes never rebuild body FTS or local jobs. */
internal object ReadyImageSqlSchema {
    val epochGuards=listOf(
"""
CREATE TRIGGER IF NOT EXISTS unified_ready_image_epoch_insert
AFTER INSERT ON attachments
WHEN NEW.mediaType IN ('image/png','image/jpeg')
BEGIN
  UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1;END;
""".trimIndent(),
"""
CREATE TRIGGER IF NOT EXISTS unified_ready_image_epoch_update
AFTER UPDATE ON attachments
WHEN (OLD.mediaType IN ('image/png','image/jpeg') OR NEW.mediaType IN ('image/png','image/jpeg'))
 AND (OLD.id IS NOT NEW.id OR OLD.entryId IS NOT NEW.entryId OR OLD.revision IS NOT NEW.revision
      OR OLD.mediaType IS NOT NEW.mediaType OR OLD.sha256 IS NOT NEW.sha256
      OR OLD.byteSize IS NOT NEW.byteSize OR OLD.state IS NOT NEW.state)
BEGIN
  UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1;END;
""".trimIndent(),
"""
CREATE TRIGGER IF NOT EXISTS unified_ready_image_epoch_delete
AFTER DELETE ON attachments
WHEN OLD.mediaType IN ('image/png','image/jpeg')
BEGIN
  UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1;END;
""".trimIndent()
    )
}
