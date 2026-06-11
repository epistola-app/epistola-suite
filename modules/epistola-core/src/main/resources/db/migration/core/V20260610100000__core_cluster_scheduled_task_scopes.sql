ALTER TABLE cluster_tasks_scheduled
    ADD COLUMN execution_scope TEXT NOT NULL DEFAULT 'single_owner';

ALTER TABLE cluster_tasks_scheduled
    ADD CONSTRAINT chk_cluster_tasks_scheduled_execution_scope
        CHECK (execution_scope IN ('single_owner', 'each_capable_node'));

COMMENT ON COLUMN cluster_tasks_scheduled.execution_scope IS
    'single_owner runs each occurrence once across the cluster; each_capable_node runs it once per active capable node.';

CREATE TABLE cluster_tasks_scheduled_node_state (
    task_key                TEXT        NOT NULL REFERENCES cluster_tasks_scheduled(task_key) ON DELETE CASCADE,
    node_id                 TEXT        NOT NULL,
    next_due_at             TIMESTAMPTZ NOT NULL,
    lease_expires_at        TIMESTAMPTZ,
    last_started_at         TIMESTAMPTZ,
    last_completed_at       TIMESTAMPTZ,
    last_failed_at          TIMESTAMPTZ,
    attempt_count           INTEGER     NOT NULL DEFAULT 0,
    consecutive_failures    INTEGER     NOT NULL DEFAULT 0,
    last_error              TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_key, node_id),
    CONSTRAINT chk_cluster_tasks_scheduled_node_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_cluster_tasks_scheduled_node_consecutive_failures CHECK (consecutive_failures >= 0)
);

CREATE INDEX idx_cluster_tasks_scheduled_node_due
    ON cluster_tasks_scheduled_node_state(task_key, node_id, next_due_at);
CREATE INDEX idx_cluster_tasks_scheduled_node_lease
    ON cluster_tasks_scheduled_node_state(task_key, node_id, lease_expires_at);

COMMENT ON TABLE cluster_tasks_scheduled_node_state IS
    'Per-node runtime state for scheduled tasks that must run on every active capable node.';
COMMENT ON COLUMN cluster_tasks_scheduled_node_state.task_key IS 'Scheduled task definition key.';
COMMENT ON COLUMN cluster_tasks_scheduled_node_state.node_id IS 'Cluster node that owns this runtime state.';
COMMENT ON COLUMN cluster_tasks_scheduled_node_state.next_due_at IS 'Next occurrence for this node.';
COMMENT ON COLUMN cluster_tasks_scheduled_node_state.lease_expires_at IS 'Crash-recovery deadline for this node execution.';
