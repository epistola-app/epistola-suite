-- Event log table for audit trail and debugging
-- Captures every command execution for observability and future replay/recovery

CREATE TABLE event_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    tenant_key TENANT_KEY,
    entity_id VARCHAR(255),
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    instance_id VARCHAR(255)
);

-- Performance indexes for common queries
CREATE INDEX idx_event_log_type ON event_log(event_type);
CREATE INDEX idx_event_log_tenant ON event_log(tenant_key) WHERE tenant_key IS NOT NULL;
CREATE INDEX idx_event_log_occurred ON event_log(occurred_at DESC);
CREATE INDEX idx_event_log_tenant_occurred ON event_log(tenant_key, occurred_at DESC) WHERE tenant_key IS NOT NULL;

COMMENT ON TABLE event_log IS 'Append-only audit trail of command executions, for observability and future replay/recovery.';
COMMENT ON COLUMN event_log.id IS 'Monotonic BIGSERIAL sequence (also the natural ordering of events)';
COMMENT ON COLUMN event_log.event_type IS 'Fully-qualified command/event name (e.g., the command class name)';
COMMENT ON COLUMN event_log.tenant_key IS 'Tenant slug this event belongs to (NULL for system-level events). Intentionally NOT a foreign key — the log is append-only and must survive tenant deletion.';
COMMENT ON COLUMN event_log.entity_id IS 'Identifier of the entity the event acted on, when applicable';
COMMENT ON COLUMN event_log.payload IS 'Full event payload as JSONB (command parameters / result snapshot)';
COMMENT ON COLUMN event_log.occurred_at IS 'When the event was recorded';
COMMENT ON COLUMN event_log.instance_id IS 'Application instance (hostname-pid) that produced the event';
