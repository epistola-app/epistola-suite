-- Lifecycle for code-defined cluster scheduled tasks.
--
-- Scheduled task rows are durable, but a definition can disappear from code
-- (a feature removed, a module dropped). This migration adds the state needed to
-- automatically retire and later purge such orphaned definitions safely.
--
-- Detection uses a per-node registration table: every node records the schedules
-- it actively registers at startup. A code-defined task is orphaned only when no
-- currently-active node vouches for it (and its newest registration is older than
-- a grace period). This is naturally safe during rolling deploys — old nodes keep
-- vouching until they leave — and does not rely on build-version equality.

-- The "nodes that vouch for this schedule" set. ON DELETE CASCADE means purging a
-- task drops its registrations, and prevents orphaned rows from outliving tasks.
CREATE TABLE cluster_scheduled_task_registrations (
    task_key      TEXT        NOT NULL REFERENCES cluster_tasks_scheduled(task_key) ON DELETE CASCADE,
    node_id       TEXT        NOT NULL,
    build_version TEXT,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_key, node_id)
);

CREATE INDEX idx_cluster_scheduled_task_registrations_task
    ON cluster_scheduled_task_registrations(task_key);

COMMENT ON TABLE cluster_scheduled_task_registrations IS
    'Per-node record of which cluster nodes actively registered each code-defined scheduled task. Used to detect orphaned definitions safely across a multi-node fleet.';
COMMENT ON COLUMN cluster_scheduled_task_registrations.task_key IS 'Scheduled task definition this node registered.';
COMMENT ON COLUMN cluster_scheduled_task_registrations.node_id IS 'Cluster node that vouches for the definition being present in its code.';
COMMENT ON COLUMN cluster_scheduled_task_registrations.build_version IS 'Build version the node reported when it last registered the definition.';
COMMENT ON COLUMN cluster_scheduled_task_registrations.registered_at IS 'When the node last registered the definition; gates the retirement grace period.';

-- Management mode distinguishes code-defined tasks (eligible for automatic
-- retirement) from manual/operator-created tasks (never auto-deleted).
ALTER TABLE cluster_tasks_scheduled
    ADD COLUMN management_mode   TEXT NOT NULL DEFAULT 'code',
    ADD COLUMN retired_at        TIMESTAMPTZ,
    ADD COLUMN retirement_reason TEXT;

ALTER TABLE cluster_tasks_scheduled
    ADD CONSTRAINT chk_cluster_tasks_scheduled_management_mode
        CHECK (management_mode IN ('code', 'manual'));

-- Retired tasks are filtered out of normal due-selection cheaply.
CREATE INDEX idx_cluster_tasks_scheduled_retired_at
    ON cluster_tasks_scheduled(retired_at)
    WHERE retired_at IS NOT NULL;

COMMENT ON COLUMN cluster_tasks_scheduled.management_mode IS
    'code: definition owned by application code, eligible for automatic retirement when it disappears. manual: operator-created, never auto-retired.';
COMMENT ON COLUMN cluster_tasks_scheduled.retired_at IS
    'When the task was soft-retired (definition no longer present on any active node). NULL means active. Retired rows are purged after a retention window.';
COMMENT ON COLUMN cluster_tasks_scheduled.retirement_reason IS 'Human-readable reason the task was retired, surfaced in Operations.';
