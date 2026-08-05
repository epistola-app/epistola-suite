-- backup-restore-compatibility: backward=true forward=true
-- reason: Data-only cleanup of audit_log, which is excluded from tenant backup/restore. No
-- backed-up table changes, so a backup restores cleanly across this migration in either direction.
--
-- Consumer heartbeat/partition-assignment touches and acknowledgement cursor advances are
-- high-frequency transport housekeeping, not operator actions. They are now NotAudited; remove
-- the historical success and failure rows that were recorded before that classification.
DELETE FROM audit_log
WHERE action IN ('TouchConsumerNode', 'AcknowledgeGenerationResults');
