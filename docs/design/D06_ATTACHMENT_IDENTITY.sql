-- Reject duplicate identity before SQLite REPLACE can implicitly delete its old row.
-- Normal retries read/acknowledge existing intent; restore uses plain INSERT/new identities.
CREATE TRIGGER IF NOT EXISTS immutable_attachment_identity
BEFORE INSERT ON attachments
WHEN EXISTS(SELECT 1 FROM attachments WHERE id=NEW.id)
BEGIN
  SELECT RAISE(ABORT,'attachment identity exists');
END;
