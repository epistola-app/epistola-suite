-- Application log table: captures application log output (logger events) for a
-- bounded, queryable operational view in the UI and a future hub-forwarding leg.
--
-- Distinct from `event_log` (a command-completion AUDIT trail): this table holds
-- raw logger output (Logback events). Written asynchronously and batched by the
-- in-app appender; pruned to a 1-week window by a scheduled retention job.
--
-- Plain (non-partitioned) table: weekly volume is expected to be low, so a single
-- indexed table + nightly DELETE is sufficient. The `occurred_at` index keeps
-- both the retention DELETE and the newest-first viewer cheap; if volume ever
-- warrants it, this can be converted to native daily RANGE partitioning (mirroring
-- the documents partition approach) without changing the read/write contracts.

CREATE TABLE application_log (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    level       TEXT NOT NULL,
    logger      TEXT NOT NULL,
    message     TEXT NOT NULL,
    thread      TEXT,
    instance_id TEXT NOT NULL,
    tenant_key  TENANT_KEY,
    trace_id    TEXT,
    span_id     TEXT,
    exception   TEXT,
    attributes  JSONB
);

-- Drives the retention DELETE and the default newest-first listing.
CREATE INDEX idx_application_log_occurred ON application_log (occurred_at DESC);
-- Tenant viewer: a tenant's own rows, newest first.
CREATE INDEX idx_application_log_tenant_occurred ON application_log (tenant_key, occurred_at DESC);
-- Level filter (ERROR/WARN/...), newest first.
CREATE INDEX idx_application_log_level_occurred ON application_log (level, occurred_at DESC);

COMMENT ON TABLE application_log IS 'Append-only application log output (logger events), retained ~1 week and viewable in the UI. Distinct from event_log (command audit trail).';
COMMENT ON COLUMN application_log.id IS 'UUIDv7 — time-ordered, generated in-app; stable id for future hub forwarding.';
COMMENT ON COLUMN application_log.occurred_at IS 'Timestamp of the log event (from the logging event, not the DB clock).';
COMMENT ON COLUMN application_log.level IS 'Log level: ERROR | WARN | INFO | DEBUG | TRACE.';
COMMENT ON COLUMN application_log.logger IS 'Logger name that produced the event.';
COMMENT ON COLUMN application_log.message IS 'Formatted log message.';
COMMENT ON COLUMN application_log.thread IS 'Name of the thread that produced the event.';
COMMENT ON COLUMN application_log.instance_id IS 'Application instance (NodeIdentity.nodeId) that produced the event.';
COMMENT ON COLUMN application_log.tenant_key IS 'Tenant the event was produced for (NULL for system/background logs). Intentionally NOT a foreign key — the log is append-only and must survive tenant deletion and stay forwarding-friendly.';
COMMENT ON COLUMN application_log.trace_id IS 'Distributed trace id from MDC, when present.';
COMMENT ON COLUMN application_log.span_id IS 'Span id from MDC, when present.';
COMMENT ON COLUMN application_log.exception IS 'Rendered exception/stack trace, when the event carried a throwable.';
COMMENT ON COLUMN application_log.attributes IS 'Remaining MDC entries as JSONB (excludes trace_id/span_id, which are promoted to columns).';
