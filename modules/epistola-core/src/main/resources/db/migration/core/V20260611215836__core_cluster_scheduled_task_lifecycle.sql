-- Lifecycle for code-defined cluster scheduled tasks.
--
-- Scheduled task rows are durable, but a definition can disappear from code
-- (a feature removed, a module dropped). To delete such orphaned rows safely
-- across a multi-node fleet, every node records the schedules it carries in a
-- per-node registration table. A task is orphaned only when no node that vouches
-- for it has been seen (heartbeated) within a grace window; orphans are then
-- hard-deleted. This is naturally safe during rolling deploys — old nodes keep
-- vouching until they leave — and a returning definition is simply re-created.

-- The "nodes that vouch for this schedule" set. Liveness is read from
-- cluster_nodes.last_seen_at (joined on node_id), so this table only needs to
-- record membership. ON DELETE CASCADE drops a task's registrations (and per-node
-- state) when the task row is deleted.
CREATE TABLE cluster_scheduled_task_registrations (
    task_key TEXT NOT NULL REFERENCES cluster_tasks_scheduled(task_key) ON DELETE CASCADE,
    node_id  TEXT NOT NULL,
    PRIMARY KEY (task_key, node_id)
);

CREATE INDEX idx_cluster_scheduled_task_registrations_task
    ON cluster_scheduled_task_registrations(task_key);

COMMENT ON TABLE cluster_scheduled_task_registrations IS
    'Per-node record of which cluster nodes carry each code-defined scheduled task. Used to detect orphaned definitions safely across a multi-node fleet; a task no live node vouches for is deleted.';
COMMENT ON COLUMN cluster_scheduled_task_registrations.task_key IS 'Scheduled task definition this node carries.';
COMMENT ON COLUMN cluster_scheduled_task_registrations.node_id IS 'Cluster node that vouches for the definition being present in its code.';
