-- Scheduler-liveness signal for cluster nodes (issue #723).
--
-- last_seen_at is written by the heartbeat on the dedicated cluster-maintenance
-- thread, deliberately decoupled from the scheduler poll thread so a slow handler
-- cannot make a healthy node look dead. The flip side: a node whose poll thread is
-- wedged (e.g. the classloader deadlock in #724) keeps heartbeating and so stays
-- "active" and the elected rendezvous owner of its single-owner tasks — silently
-- starving them fleet-wide.
--
-- last_poll_completed_at is stamped by the scheduler at the end of each poll cycle,
-- so it advances only while the poll thread itself is making progress. Single-owner
-- ownership election filters on it in addition to last_seen_at, so a
-- heartbeating-but-wedged node is dropped from election and its due tasks are
-- re-owned by a healthy node. NULL until a node completes its first poll cycle.

ALTER TABLE cluster_nodes
    ADD COLUMN last_poll_completed_at TIMESTAMPTZ;

COMMENT ON COLUMN cluster_nodes.last_poll_completed_at IS
    'Most recent completed scheduler poll cycle. Scheduler-liveness signal (distinct from the heartbeat-maintained last_seen_at) used to exclude a wedged-but-heartbeating node from single-owner task election. NULL until the first poll completes.';
