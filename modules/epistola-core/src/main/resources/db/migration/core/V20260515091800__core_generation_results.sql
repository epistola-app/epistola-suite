-- v0.3 generation result collection — server-side storage.
--
-- Three new tables:
--   generation_results          — append-only row per terminal generation result.
--                                 RANGE-partitioned by `created_at` (monthly
--                                 sub-partitions managed by
--                                 PartitionMaintenanceScheduler) for cheap
--                                 retention via DROP TABLE.
--   consumer_partition_cursors  — per-(consumer, partition) cursor advanced on ack.
--   consumer_node_assignments   — last-seen heartbeat per (consumer, node), drives
--                                 the consistent hash ring.
--
-- The routing-partition count is hard-coded to 64 in `Partition.TOTAL_PARTITIONS`.
-- The `partition` column on `generation_results` is the routing-partition number
-- (0..63) — distinct from the Postgres physical partitions managed below by
-- RANGE(created_at). The two concepts share a name but are orthogonal: routing
-- partitions group rows by consumer-affinity; Postgres partitions group rows
-- by month for retention.

-- Domain types: same idiom as TENANT_KEY / TEMPLATE_KEY (V4 onward) — push the
-- range/enum constraint into PG so a misbehaving inserter can't corrupt the
-- table. The Kotlin side (`Partition.TOTAL_PARTITIONS = 64`, `ResultStatus`
-- enum) stays the source of truth at the application boundary; the domains
-- are the safety net.
CREATE DOMAIN GENERATION_PARTITION AS INTEGER
    CHECK (VALUE >= 0 AND VALUE < 64);

CREATE DOMAIN GENERATION_RESULT_STATUS AS TEXT
    CHECK (VALUE IN ('COMPLETED', 'FAILED'));

CREATE TABLE generation_results (
    sequence       BIGSERIAL,
    partition      GENERATION_PARTITION NOT NULL,    -- routing-partition (0..63)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    request_id     UUID        NOT NULL,
    batch_id       UUID,
    tenant_key     TENANT_KEY  NOT NULL,
    routing_key    TEXT        NOT NULL,
    status         GENERATION_RESULT_STATUS NOT NULL,

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

    -- Postgres requires every partition-key column in the PK. `sequence` alone
    -- would be unique (BIGSERIAL), but adding `created_at` lets it serve as PK
    -- *and* partition key.
    PRIMARY KEY (sequence, created_at)
) PARTITION BY RANGE (created_at);

-- Hot read path: WHERE partition IN (...) AND sequence > cursor.
-- Inherited by every monthly child partition; the planner uses it for
-- index-only scans pruned to the relevant month.
CREATE INDEX idx_generation_results_partition_sequence
    ON generation_results (partition, sequence);

-- Tenant filter — every collect call is scoped to the caller's tenant.
CREATE INDEX idx_generation_results_tenant_key
    ON generation_results (tenant_key);

-- Bootstrap current and next month so inserts work immediately after Flyway
-- runs. The PartitionMaintenanceScheduler takes over creation/cleanup once
-- the application context is up.
DO
$$
DECLARE
    cur_month        DATE := date_trunc('month', NOW())::date;
    next_month       DATE := (date_trunc('month', NOW()) + INTERVAL '1 month')::date;
    month_after_next DATE := (date_trunc('month', NOW()) + INTERVAL '2 months')::date;
BEGIN
    EXECUTE format(
        'CREATE TABLE generation_results_%s PARTITION OF generation_results '
        'FOR VALUES FROM (%L) TO (%L)',
        to_char(cur_month, 'YYYY_MM'), cur_month, next_month
    );
    EXECUTE format(
        'CREATE TABLE generation_results_%s PARTITION OF generation_results '
        'FOR VALUES FROM (%L) TO (%L)',
        to_char(next_month, 'YYYY_MM'), next_month, month_after_next
    );
END
$$;

-- Per-(tenant, consumer, partition) ack cursor. Advanced when the consumer sends
-- `acknowledgeUpTo` on the next /generation/collect call. `last_acked_sequence`
-- starts at 0 (no rows acked yet); the read query uses `sequence > cursor`.
--
-- `tenant_key` is technically derivable from `consumer_id` (every X-API-Key
-- belongs to one tenant), but storing it explicitly is defense-in-depth: even
-- if the auth filter ever returns a wrong tenant, queries cannot cross
-- tenant boundaries.
CREATE TABLE consumer_partition_cursors (
    tenant_key           TENANT_KEY           NOT NULL,
    consumer_id          TEXT                 NOT NULL,
    partition            GENERATION_PARTITION NOT NULL,
    last_acked_sequence  BIGINT               NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ          NOT NULL DEFAULT NOW(),
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
    tenant_key    TENANT_KEY  NOT NULL,
    consumer_id   TEXT        NOT NULL,
    node_id       TEXT        NOT NULL,
    partitions    JSONB       NOT NULL,             -- e.g. [0, 3, 7]
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_key, consumer_id, node_id)
);

-- Idle-node sweep: query for "active nodes for this (tenant, consumer)".
CREATE INDEX idx_consumer_node_assignments_tenant_consumer_lastseen
    ON consumer_node_assignments (tenant_key, consumer_id, last_seen_at);

COMMENT ON TABLE generation_results IS 'Append-only terminal generation results. RANGE-partitioned by created_at (monthly child partitions managed by PartitionMaintenanceScheduler) for cheap retention via DROP TABLE.';
COMMENT ON COLUMN generation_results.sequence IS 'Monotonic BIGSERIAL; consumers page with sequence > cursor';
COMMENT ON COLUMN generation_results.partition IS 'Routing-partition number (0..63), orthogonal to the Postgres month partitions. Groups rows by consumer affinity.';
COMMENT ON COLUMN generation_results.created_at IS 'When the result was recorded. Also the Postgres RANGE partition key.';
COMMENT ON COLUMN generation_results.request_id IS 'Originating document_generation_requests.id';
COMMENT ON COLUMN generation_results.batch_id IS 'Originating batch id, when the request was part of a batch';
COMMENT ON COLUMN generation_results.tenant_key IS 'Owning tenant slug. Intentionally NOT a foreign key — this is append-only collected data that must survive tenant deletion.';
COMMENT ON COLUMN generation_results.routing_key IS 'Routing key used to compute the routing partition';
COMMENT ON COLUMN generation_results.status IS 'Terminal status: COMPLETED or FAILED';
COMMENT ON COLUMN generation_results.completed_at IS 'When generation reached its terminal state';
COMMENT ON COLUMN consumer_partition_cursors.last_acked_sequence IS 'Highest sequence the consumer has acknowledged for this (tenant, consumer, partition). Starts at 0.';

COMMENT ON TABLE consumer_partition_cursors IS 'Per-(tenant, consumer, partition) acknowledgement cursor. Advanced when a consumer sends acknowledgeUpTo on the next /generation/collect call.';
COMMENT ON COLUMN consumer_partition_cursors.tenant_key IS 'Owning tenant slug. Stored explicitly as defense-in-depth so queries cannot cross tenant boundaries even if auth misbehaves.';
COMMENT ON COLUMN consumer_partition_cursors.consumer_id IS 'Logical consumer identity (one per X-API-Key)';
COMMENT ON COLUMN consumer_partition_cursors.partition IS 'Routing-partition number (0..63) this cursor tracks';
COMMENT ON COLUMN consumer_partition_cursors.updated_at IS 'When the cursor was last advanced';

COMMENT ON TABLE consumer_node_assignments IS 'Last-seen heartbeat per (tenant, consumer, node). Drives the consistent hash ring for partition assignment.';
COMMENT ON COLUMN consumer_node_assignments.tenant_key IS 'Owning tenant slug';
COMMENT ON COLUMN consumer_node_assignments.consumer_id IS 'Logical consumer identity (one per X-API-Key)';
COMMENT ON COLUMN consumer_node_assignments.node_id IS 'Individual node within the consumer';
COMMENT ON COLUMN consumer_node_assignments.partitions IS 'Routing partitions assigned to this node on its last call (e.g. [0, 3, 7]). Stored for observability and echo-back.';
COMMENT ON COLUMN consumer_node_assignments.last_seen_at IS 'Timestamp of the most recent ping/collect from this node. Idle nodes are swept from the ring.';
