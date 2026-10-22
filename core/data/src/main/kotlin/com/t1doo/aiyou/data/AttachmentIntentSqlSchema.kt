package com.t1doo.aiyou.data

/** Shared by Room create/migrate/cold-open and host SQL owners. REPLACE is not an UPDATE. */
internal object AttachmentIntentSqlSchema {
    val updateGuard = "CREATE TRIGGER IF NOT EXISTS immutable_attachment_intent BEFORE UPDATE ON attachments WHEN OLD.id IS NOT NEW.id OR OLD.entryId IS NOT NEW.entryId OR OLD.revision IS NOT NEW.revision OR OLD.mediaType IS NOT NEW.mediaType OR OLD.sha256 IS NOT NEW.sha256 OR OLD.byteSize IS NOT NEW.byteSize BEGIN SELECT RAISE(ABORT, 'immutable attachment intent'); END"
    val identityGuard = """
CREATE TRIGGER IF NOT EXISTS immutable_attachment_identity
BEFORE INSERT ON attachments
WHEN EXISTS(SELECT 1 FROM attachments WHERE id=NEW.id)
BEGIN
  SELECT RAISE(ABORT,'attachment identity exists');
END;
""".trimIndent()
    val guards=listOf(updateGuard,identityGuard)
}
