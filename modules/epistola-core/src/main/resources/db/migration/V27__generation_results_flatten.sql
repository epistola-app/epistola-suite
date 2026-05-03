-- Flatten `generation_results` from multi-level (LIST × RANGE) to single-level
-- (RANGE-by-month) partitioning.
--
-- The original V26 design used LIST(partition) → RANGE(created_at) so that
-- partition-filtered reads could prune at the LIST level. In practice the
-- cost of that two-tier shape (256 sub-partitions per year, regex-driven
-- maintenance, the `DO $$` bootstrap, and a "missing sub-partition" failure
-- mode at month boundaries) outweighs the win — partition-filtered reads
-- are already pruned by an `(partition, sequence)` index, and the data
-- volume per LIST child is tiny.
--
-- Pre-prod, no rows worth preserving — DROP and recreate flat. The
-- partitioning column (`partition`) is still in the PK so the existing
-- `Partition.partitionFor(routingKey)` machinery works unchanged; only the
-- physical layout differs.

DROP TABLE IF EXISTS generation_results CASCADE;

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
    version_id     INTEGER,
    filename       TEXT,
    content_type   TEXT,
    size_bytes     BIGINT,
    error          TEXT,
    completed_at   TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (sequence, created_at)
) PARTITION BY RANGE (created_at);

-- Hot read path: WHERE partition IN (...) AND sequence > cursor — the
-- query plan picks this up via index-only scan on each surviving monthly
-- partition.
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
