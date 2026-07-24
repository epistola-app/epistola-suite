-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Durable cluster timer events.
--
-- Timers are leased delayed messages. A local @Scheduled poller only wakes the
-- timer subsystem; business work is claimed through this table so execution is
-- stable across nodes and recoverable after crashes.

CREATE TABLE cluster_timers (
    timer_key           TEXT        PRIMARY KEY,
    tenant_key          TENANT_KEY  REFERENCES tenants(id) ON DELETE CASCADE,
    routing_key         TEXT        NOT NULL,
    timer_type          TEXT        NOT NULL,
    required_capability TEXT        NOT NULL DEFAULT 'suite',
    due_at              TIMESTAMPTZ NOT NULL,
    payload             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status              TEXT        NOT NULL DEFAULT 'scheduled',
    lease_owner_node_id TEXT,
    lease_expires_at    TIMESTAMPTZ,
    last_started_at     TIMESTAMPTZ,
    last_completed_at   TIMESTAMPTZ,
    attempt_count       INTEGER     NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cluster_timers_status CHECK (status IN ('scheduled', 'running', 'paused')),
    CONSTRAINT chk_cluster_timers_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_cluster_timers_due ON cluster_timers(status, due_at);
CREATE INDEX idx_cluster_timers_tenant_key ON cluster_timers(tenant_key) WHERE tenant_key IS NOT NULL;
CREATE INDEX idx_cluster_timers_lease_owner ON cluster_timers(lease_owner_node_id);
CREATE INDEX idx_cluster_timers_routing_key ON cluster_timers(routing_key);
CREATE INDEX idx_cluster_timers_required_capability ON cluster_timers(required_capability);

COMMENT ON TABLE cluster_timers IS 'Durable timer events claimed by active cluster nodes using Postgres leases.';
COMMENT ON COLUMN cluster_timers.timer_key IS 'Stable idempotency key for the timer.';
COMMENT ON COLUMN cluster_timers.tenant_key IS 'Tenant that owns this timer, or NULL for system-level timers.';
COMMENT ON COLUMN cluster_timers.routing_key IS 'Affinity key used to choose the owning cluster node.';
COMMENT ON COLUMN cluster_timers.timer_type IS 'Handler discriminator.';
COMMENT ON COLUMN cluster_timers.required_capability IS 'Node capability required to claim and execute this timer.';
COMMENT ON COLUMN cluster_timers.due_at IS 'Earliest time at which the timer may be claimed.';
COMMENT ON COLUMN cluster_timers.payload IS 'Small handler payload. Large data should live in domain tables.';
COMMENT ON COLUMN cluster_timers.status IS 'scheduled, running, or paused.';
COMMENT ON COLUMN cluster_timers.lease_owner_node_id IS 'Cluster node that currently owns execution.';
COMMENT ON COLUMN cluster_timers.lease_expires_at IS 'Crash-recovery deadline after which another node can reclaim.';
