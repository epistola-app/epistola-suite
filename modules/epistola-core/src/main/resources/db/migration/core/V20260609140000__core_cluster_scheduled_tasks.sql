-- Durable cluster scheduled tasks.
--
-- Scheduled tasks are persistent recurring work definitions. A local
-- @Scheduled poller only wakes the subsystem; the task row owns recurrence,
-- affinity, and crash recovery.

CREATE TABLE cluster_tasks_scheduled (
    task_key                TEXT        PRIMARY KEY,
    tenant_key              TENANT_KEY  REFERENCES tenants(id) ON DELETE CASCADE,
    routing_key             TEXT        NOT NULL,
    task_type               TEXT        NOT NULL,
    required_capability     TEXT        NOT NULL DEFAULT 'suite',
    payload                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    schedule_kind           TEXT        NOT NULL,
    cron_expression         TEXT,
    interval_ms             BIGINT,
    zone_id                 TEXT        NOT NULL DEFAULT 'UTC',
    failure_policy          TEXT        NOT NULL DEFAULT 'retry_same_due',
    catch_up_policy         TEXT        NOT NULL DEFAULT 'coalesce',
    enabled                 BOOLEAN     NOT NULL DEFAULT true,
    next_due_at             TIMESTAMPTZ NOT NULL,
    lease_owner_node_id     TEXT,
    lease_expires_at        TIMESTAMPTZ,
    last_started_at         TIMESTAMPTZ,
    last_completed_at       TIMESTAMPTZ,
    last_failed_at          TIMESTAMPTZ,
    attempt_count           INTEGER     NOT NULL DEFAULT 0,
    consecutive_failures    INTEGER     NOT NULL DEFAULT 0,
    last_error              TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cluster_tasks_scheduled_schedule_kind CHECK (schedule_kind IN ('cron', 'fixed_delay', 'fixed_rate')),
    CONSTRAINT chk_cluster_tasks_scheduled_failure_policy CHECK (failure_policy IN ('retry_same_due', 'advance_on_failure')),
    CONSTRAINT chk_cluster_tasks_scheduled_catch_up_policy CHECK (catch_up_policy IN ('coalesce', 'catch_up')),
    CONSTRAINT chk_cluster_tasks_scheduled_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_cluster_tasks_scheduled_consecutive_failures CHECK (consecutive_failures >= 0),
    CONSTRAINT chk_cluster_tasks_scheduled_interval CHECK (
        (schedule_kind = 'cron' AND cron_expression IS NOT NULL AND interval_ms IS NULL)
        OR (schedule_kind IN ('fixed_delay', 'fixed_rate') AND cron_expression IS NULL AND interval_ms IS NOT NULL AND interval_ms > 0)
    )
);

CREATE INDEX idx_cluster_tasks_scheduled_due
    ON cluster_tasks_scheduled(enabled, next_due_at)
    WHERE enabled = true;
CREATE INDEX idx_cluster_tasks_scheduled_tenant_key
    ON cluster_tasks_scheduled(tenant_key)
    WHERE tenant_key IS NOT NULL;
CREATE INDEX idx_cluster_tasks_scheduled_lease_owner
    ON cluster_tasks_scheduled(lease_owner_node_id);
CREATE INDEX idx_cluster_tasks_scheduled_routing_key
    ON cluster_tasks_scheduled(routing_key);
CREATE INDEX idx_cluster_tasks_scheduled_required_capability
    ON cluster_tasks_scheduled(required_capability);

COMMENT ON TABLE cluster_tasks_scheduled IS 'Durable recurring task definitions claimed by active cluster nodes using Postgres leases.';
COMMENT ON COLUMN cluster_tasks_scheduled.task_key IS 'Stable idempotency key for the scheduled task definition.';
COMMENT ON COLUMN cluster_tasks_scheduled.tenant_key IS 'Tenant that owns this task, or NULL for system-level tasks.';
COMMENT ON COLUMN cluster_tasks_scheduled.routing_key IS 'Affinity key used to choose the owning cluster node.';
COMMENT ON COLUMN cluster_tasks_scheduled.task_type IS 'Handler discriminator.';
COMMENT ON COLUMN cluster_tasks_scheduled.required_capability IS 'Node capability required to claim and execute this scheduled task.';
COMMENT ON COLUMN cluster_tasks_scheduled.payload IS 'Small handler payload. Large data should live in domain tables.';
COMMENT ON COLUMN cluster_tasks_scheduled.schedule_kind IS 'cron, fixed_delay, or fixed_rate.';
COMMENT ON COLUMN cluster_tasks_scheduled.next_due_at IS 'Next occurrence to claim. Recurrence is advanced from this durable value.';
COMMENT ON COLUMN cluster_tasks_scheduled.failure_policy IS 'retry_same_due retries failed occurrences; advance_on_failure skips to the next scheduled occurrence.';
COMMENT ON COLUMN cluster_tasks_scheduled.catch_up_policy IS 'coalesce skips missed intervals; catch_up preserves each missed occurrence.';
COMMENT ON COLUMN cluster_tasks_scheduled.lease_owner_node_id IS 'Cluster node that currently owns execution.';
COMMENT ON COLUMN cluster_tasks_scheduled.lease_expires_at IS 'Crash-recovery deadline after which another node can reclaim.';
