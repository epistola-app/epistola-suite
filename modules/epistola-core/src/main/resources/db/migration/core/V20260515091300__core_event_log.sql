-- Event log table for audit trail and debugging
-- Captures every command execution for observability and future replay/recovery

CREATE TABLE event_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    tenant_key VARCHAR(100),
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
