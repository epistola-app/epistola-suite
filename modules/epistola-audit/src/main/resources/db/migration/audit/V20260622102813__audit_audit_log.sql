-- backup-restore-compatibility: backward=true forward=true
-- reason: Additive — creates audit_log, which is excluded from tenant backup/restore
-- (AuditBackupTables contributor). No change to any backed-up table, so a backup restores
-- cleanly across this migration in either direction.
--
-- Audit log: a PII-free, append-only, forever-retained trail of "who did what, when".
--
-- Distinct from the two adjacent logs:
--   * event_log         — full command payloads (CONTAINS PII), for future replay. Not an audit log.
--   * application_log    — raw Logback output, pruned to ~1 week.
-- This table deliberately stores NO free text and NO payload: only the action
-- (command class name), the entity it touched, the acting user as an opaque
-- surrogate id, the tenant, the outcome, and a machine-readable error code on
-- failure. Because it holds no personal information, it never has to be deleted
-- for privacy/GDPR reasons — a future user/tenant erasure scrubs the mapping in
-- the `users`/`tenants` tables, after which the surrogate ids here resolve to
-- "(deleted)". The row itself stays valid forever.
--
-- Forever retention + high write volume is handled by native monthly RANGE
-- partitioning on `occurred_at` (mirroring the `documents` tables). Partition
-- pruning keeps the newest-first viewer and time-bounded queries fast no matter
-- how much history accumulates. PartitionMaintenanceScheduler creates partitions
-- monthly; audit_log is registered create-only (NEVER auto-dropped) — old
-- partitions are kept (and may later be detached/archived to cold storage, never
-- deleted).

CREATE TABLE audit_log (
    id            UUID         NOT NULL,            -- UUIDv7 — time-ordered; stable id for future hub forwarding
    occurred_at   TIMESTAMPTZ  NOT NULL,            -- EpistolaClock.instant() at command dispatch (partition key)
    tenant_key    TENANT_KEY,                       -- tenant slug; NULL for system-scope. NOT a foreign key — survives tenant deletion.
    actor_user_id UUID,                             -- EpistolaPrincipal.userId (humans, API keys, system user). NOT a foreign key — survives user deletion/erasure. NULL only if unauthenticated.
    action        VARCHAR(255) NOT NULL,            -- command/query class simpleName, e.g. "CreateTheme"
    operation     VARCHAR(8)   NOT NULL,            -- 'WRITE' (command) | 'READ' (audited query)
    entity_type   VARCHAR(255),                     -- entity the command acted on, e.g. "Theme" (nullable)
    entity_id     VARCHAR(255),                     -- EntityIdentifiable.entityId, when applicable (nullable)
    outcome       VARCHAR(16)  NOT NULL,            -- 'SUCCESS' | 'FAILURE'
    error_code    VARCHAR(255),                     -- non-PII: ValidationCode name or exception class simpleName. NULL on success. NEVER the exception message.
    details       JSONB,                            -- optional, author-supplied PII-free key/values (AuditDetailed), e.g. {"backupId": "..."}
    instance_id   TEXT,                             -- NodeIdentity.nodeId that recorded the entry
    PRIMARY KEY (occurred_at, id),                  -- partition key must be part of the PK; also serves the keyset (occurred_at, id) cursor
    CONSTRAINT audit_log_outcome_check CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT audit_log_operation_check CHECK (operation IN ('WRITE', 'READ'))
) PARTITION BY RANGE (occurred_at);

-- No initial partitions created here — PartitionMaintenanceScheduler creates the
-- current + next month partitions at startup and monthly thereafter.

-- Tenant viewer: a tenant's own rows, newest first.
CREATE INDEX idx_audit_log_tenant_occurred ON audit_log (tenant_key, occurred_at DESC);
-- "What did this user do" lookups, newest first.
CREATE INDEX idx_audit_log_actor_occurred ON audit_log (actor_user_id, occurred_at DESC);
-- Filter by action (command), newest first.
CREATE INDEX idx_audit_log_action_occurred ON audit_log (action, occurred_at DESC);

COMMENT ON TABLE audit_log IS 'PII-free, append-only, forever-retained audit trail of command executions ("who did what, when"). Monthly RANGE-partitioned on occurred_at; never auto-dropped. Distinct from event_log (PII payloads) and application_log (~1-week logger output).';
COMMENT ON COLUMN audit_log.id IS 'UUIDv7 — time-ordered, generated in-app; stable id for future hub forwarding.';
COMMENT ON COLUMN audit_log.occurred_at IS 'When the command was dispatched (EpistolaClock). Partition key.';
COMMENT ON COLUMN audit_log.tenant_key IS 'Tenant the command was scoped to (NULL for system/root commands). Intentionally NOT a foreign key — the log is append-only and must survive tenant deletion.';
COMMENT ON COLUMN audit_log.actor_user_id IS 'Opaque surrogate id of the acting user/API-key/system principal. Intentionally NOT a foreign key — survives user deletion/erasure; resolve to a display name via a read-time LEFT JOIN to users. NULL only for unauthenticated actions.';
COMMENT ON COLUMN audit_log.action IS 'Command/query class simple name (e.g. "CreateTheme", "GetDocument").';
COMMENT ON COLUMN audit_log.operation IS 'WRITE for an audited command, READ for an audited query (data access).';
COMMENT ON COLUMN audit_log.entity_type IS 'Type of the entity the command acted on (e.g. "Theme"), when known.';
COMMENT ON COLUMN audit_log.entity_id IS 'Identifier of the entity the command acted on (EntityIdentifiable.entityId), when applicable.';
COMMENT ON COLUMN audit_log.outcome IS 'SUCCESS if the handler returned, FAILURE if it threw.';
COMMENT ON COLUMN audit_log.error_code IS 'Machine-readable, PII-free failure code (ValidationCode name or exception class simple name). NULL on success. Never the exception message.';
COMMENT ON COLUMN audit_log.details IS 'Optional, author-supplied PII-free key/value details (AuditDetailed), e.g. the restored backup id. The only free-form field; never a payload dump.';
COMMENT ON COLUMN audit_log.instance_id IS 'Application instance (NodeIdentity.nodeId) that recorded the entry.';
