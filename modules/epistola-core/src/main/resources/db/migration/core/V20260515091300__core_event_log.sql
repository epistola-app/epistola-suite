-- Event log: append-only record of every command execution (full JSONB payload),
-- for observability and future replay/recovery.
--
-- The payload carries command parameters, so this log is PII-bearing and must be
-- aged out — unlike the PII-free `audit_log`. Retention is enforced structurally by
-- native monthly RANGE partitioning on `occurred_at` (mirroring `documents` and
-- `audit_log`): PartitionMaintenanceScheduler creates the current+next-month
-- partitions and DROPs partitions past the retention window
-- (`epistola.partitions.event-log-retention-months`), so old PII leaves by dropping
-- a whole partition rather than a row-by-row DELETE + vacuum.

CREATE TABLE event_log (
    id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    tenant_key TENANT_KEY,
    entity_id VARCHAR(255),
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    instance_id VARCHAR(255),
    -- The partition key must be part of the PK; (occurred_at, id) also serves the
    -- newest-first listing and keyset cursor, so no separate occurred_at index is needed.
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

-- No initial partitions are declared here beyond the bootstrap below;
-- PartitionMaintenanceScheduler owns ongoing create/drop.

-- Performance indexes for common queries (inherited by every monthly child partition).
CREATE INDEX idx_event_log_type ON event_log(event_type);
-- tenant_key-only scans are served by the leftmost prefix of idx_event_log_tenant_occurred.
CREATE INDEX idx_event_log_tenant_occurred ON event_log(tenant_key, occurred_at DESC) WHERE tenant_key IS NOT NULL;

-- Bootstrap current and next month so the AFTER_COMMIT event-log writes work
-- immediately after Flyway runs (before the scheduler's startup bootstrap). The
-- scheduler takes over creation/cleanup once the application context is up; its
-- CREATE ... IF NOT EXISTS makes the overlap a no-op. Mirrors generation_results.
DO
$$
DECLARE
    cur_month        DATE := date_trunc('month', NOW())::date;
    next_month       DATE := (date_trunc('month', NOW()) + INTERVAL '1 month')::date;
    month_after_next DATE := (date_trunc('month', NOW()) + INTERVAL '2 months')::date;
BEGIN
    EXECUTE format(
        'CREATE TABLE event_log_%s PARTITION OF event_log FOR VALUES FROM (%L) TO (%L)',
        to_char(cur_month, 'YYYY_MM'), cur_month, next_month
    );
    EXECUTE format(
        'CREATE TABLE event_log_%s PARTITION OF event_log FOR VALUES FROM (%L) TO (%L)',
        to_char(next_month, 'YYYY_MM'), next_month, month_after_next
    );
END
$$;

COMMENT ON TABLE event_log IS 'Append-only, PII-bearing record of command executions (full payload), for observability and future replay/recovery. Monthly RANGE-partitioned on occurred_at with retention (epistola.partitions.event-log-retention-months). Distinct from audit_log (PII-free, kept forever) and application_log (~1-week logger output).';
COMMENT ON COLUMN event_log.id IS 'UUIDv7 — time-ordered, generated app-side; with occurred_at forms the PK. Matches audit_log/application_log. The id''s embedded timestamp and occurred_at are independent clocks, so a by-id lookup must still constrain occurred_at to prune partitions.';
COMMENT ON COLUMN event_log.event_type IS 'Fully-qualified command/event name (e.g., the command class name)';
COMMENT ON COLUMN event_log.tenant_key IS 'Tenant slug this event belongs to (NULL for system-level events). Intentionally NOT a foreign key — the log is append-only and must survive tenant deletion.';
COMMENT ON COLUMN event_log.entity_id IS 'Identifier of the entity the event acted on, when applicable';
COMMENT ON COLUMN event_log.payload IS 'Full event payload as JSONB (command parameters / result snapshot). PII-bearing — the reason this log is retention-bounded.';
COMMENT ON COLUMN event_log.occurred_at IS 'When the event was recorded. Also the Postgres RANGE partition key.';
COMMENT ON COLUMN event_log.instance_id IS 'Application instance (hostname-pid) that produced the event';
