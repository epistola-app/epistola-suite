-- v0.3 generation result collection — server-side storage.
--
-- Three new tables:
--   generation_results          — append-only row per terminal generation result.
--                                 Multi-level partitioned: LIST by `partition`
--                                 (64 children, one per routing partition),
--                                 RANGE by `created_at` (monthly sub-partitions
--                                 created by PartitionMaintenanceScheduler).
--                                 LIST gives query isolation when filtering by
--                                 partition; RANGE gives `DROP TABLE`-based
--                                 retention.
--   consumer_partition_cursors  — per-(consumer, partition) cursor advanced on ack.
--   consumer_node_assignments   — last-seen heartbeat per (consumer, node), drives
--                                 the consistent hash ring.
--
-- The partition count is hard-coded to 64 in `Partition.TOTAL_PARTITIONS` and
-- mirrored here. Changing this number requires a multi-step "create new
-- partitioned table, copy data, swap" migration; we commit to 64 up front.

-- Top-level partitioned table: LIST by routing partition.
-- PRIMARY KEY must include all partition keys (partition, created_at) per Postgres
-- declarative-partitioning rules. `sequence` is uniquely assigned by the global
-- BIGSERIAL, so adding it to the PK preserves uniqueness without any extra
-- constraint.
CREATE TABLE generation_results (
    sequence       BIGSERIAL,
    partition      INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    request_id     UUID        NOT NULL,
    batch_id       UUID,
    tenant_key     TEXT        NOT NULL,
    routing_key    TEXT        NOT NULL,
    status         TEXT        NOT NULL,            -- 'COMPLETED' | 'FAILED'

    document_id    UUID,
    correlation_id TEXT,
    template_id    TEXT,
    variant_id     TEXT,
    version_id     INTEGER,                         -- spec: integer | null
    filename       TEXT,
    content_type   TEXT,
    size_bytes     BIGINT,
    error          TEXT,
    completed_at   TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (partition, sequence, created_at)
) PARTITION BY LIST (partition);

-- 64 LIST children. Each is itself partitioned by RANGE (created_at) so the
-- maintenance scheduler can create monthly sub-partitions and DROP them past
-- retention. The migration also seeds the current and next month's date
-- sub-partitions so the system is immediately functional after Flyway runs —
-- the scheduler then takes over for ongoing creation/cleanup.
DO
$$
DECLARE
    p INTEGER;
    cur_month DATE := date_trunc('month', NOW())::date;
    next_month DATE := (date_trunc('month', NOW()) + INTERVAL '1 month')::date;
    month_after_next DATE := (date_trunc('month', NOW()) + INTERVAL '2 months')::date;
BEGIN
    FOR p IN 0..63 LOOP
        EXECUTE format(
            'CREATE TABLE generation_results_p%s PARTITION OF generation_results '
            'FOR VALUES IN (%s) PARTITION BY RANGE (created_at)',
            p, p
        );
        -- Current month
        EXECUTE format(
            'CREATE TABLE generation_results_p%s_%s PARTITION OF generation_results_p%s '
            'FOR VALUES FROM (%L) TO (%L)',
            p, to_char(cur_month, 'YYYY_MM'), p, cur_month, next_month
        );
        -- Next month (so cross-month inserts at month boundaries always have a home)
        EXECUTE format(
            'CREATE TABLE generation_results_p%s_%s PARTITION OF generation_results_p%s '
            'FOR VALUES FROM (%L) TO (%L)',
            p, to_char(next_month, 'YYYY_MM'), p, next_month, month_after_next
        );
    END LOOP;
END
$$;

-- Hot read path: WHERE partition IN (...) AND sequence > cursor.
-- Inherited by every LIST child (and propagates to monthly sub-partitions).
CREATE INDEX idx_generation_results_partition_sequence
    ON generation_results (partition, sequence);

-- Tenant filter — every collect call is scoped to the caller's tenant.
CREATE INDEX idx_generation_results_tenant_key
    ON generation_results (tenant_key);

-- Per-(tenant, consumer, partition) ack cursor. Advanced when the consumer sends
-- `acknowledgeUpTo` on the next /generation/collect call. `last_acked_sequence`
-- starts at 0 (no rows acked yet); the read query uses `sequence > cursor`.
--
-- `tenant_key` is technically derivable from `consumer_id` (every X-API-Key
-- belongs to one tenant), but storing it explicitly is defense-in-depth: even
-- if the auth filter ever returns a wrong tenant, queries cannot cross
-- tenant boundaries.
CREATE TABLE consumer_partition_cursors (
    tenant_key           TEXT        NOT NULL,
    consumer_id          TEXT        NOT NULL,
    partition            INTEGER     NOT NULL,
    last_acked_sequence  BIGINT      NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, consumer_id, partition)
);

-- Last-seen heartbeat per (tenant, consumer, node). Updated on every ping/collect
-- call. The partition assignment service queries this table for "this consumer's
-- currently-active nodes" (filtered by `last_seen_at > now - idle_timeout_ms`)
-- and feeds the result to the consistent hash ring.
--
-- `partitions` is the assignment we returned to the node on its last call —
-- stored for observability and so the API can echo back what we told the node
-- last time without recomputing the ring.
CREATE TABLE consumer_node_assignments (
    tenant_key    TEXT        NOT NULL,
    consumer_id   TEXT        NOT NULL,
    node_id       TEXT        NOT NULL,
    partitions    JSONB       NOT NULL,             -- e.g. [0, 3, 7]
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_key, consumer_id, node_id)
);

-- Idle-node sweep: query for "active nodes for this (tenant, consumer)".
CREATE INDEX idx_consumer_node_assignments_tenant_consumer_lastseen
    ON consumer_node_assignments (tenant_key, consumer_id, last_seen_at);
