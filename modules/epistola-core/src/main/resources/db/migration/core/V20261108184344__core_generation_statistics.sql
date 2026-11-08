-- rollback-compatible: true
-- reason: Adds tables, which code predating them never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Generation statistics per tenant: documents generated and failed, rolled up from the
-- generation results in the background, and previews rendered, flushed from memory once a minute.
-- Hours are kept for a short while (epistola.statistics.hourly-retention), then folded into days, which
-- are kept. No foreign key to tenants: a deleted tenant's history is still history.
CREATE TABLE generation_stats_hourly (
    tenant_key VARCHAR(63) NOT NULL,
    hour       TIMESTAMPTZ NOT NULL CHECK (date_trunc('hour', hour) = hour),
    completed  BIGINT      NOT NULL DEFAULT 0 CHECK (completed >= 0),
    failed     BIGINT      NOT NULL DEFAULT 0 CHECK (failed >= 0),
    previews   BIGINT      NOT NULL DEFAULT 0 CHECK (previews >= 0),
    PRIMARY KEY (tenant_key, hour)
);

CREATE INDEX generation_stats_hourly_hour ON generation_stats_hourly (hour);

CREATE TABLE generation_stats_daily (
    tenant_key VARCHAR(63) NOT NULL,
    day        DATE        NOT NULL,
    completed  BIGINT      NOT NULL DEFAULT 0 CHECK (completed >= 0),
    failed     BIGINT      NOT NULL DEFAULT 0 CHECK (failed >= 0),
    previews   BIGINT      NOT NULL DEFAULT 0 CHECK (previews >= 0),
    PRIMARY KEY (tenant_key, day)
);

-- Where the roll-up stands in generation_results: every result up to `sequence` is counted, and every
-- result created before `complete_until` is.
CREATE TABLE generation_stats_position (
    singleton      BOOLEAN     NOT NULL PRIMARY KEY DEFAULT TRUE CONSTRAINT chk_generation_stats_position_singleton CHECK (singleton),
    sequence       BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ,
    complete_until TIMESTAMPTZ
);
-- Its one row exists from the start: a round locks it (FOR UPDATE), so two rounds never count the same
-- results, also the very first time.
INSERT INTO generation_stats_position (singleton, sequence) VALUES (TRUE, 0);

-- Every monthly partition the maintenance dropped, with what it held: for comparing months long after
-- their rows are gone. The row count is PostgreSQL's estimate, so recording it reads no rows.
CREATE TABLE partition_history (
    partition_name VARCHAR(128) NOT NULL PRIMARY KEY,
    table_name     VARCHAR(128) NOT NULL,
    month          DATE        NOT NULL,
    estimated_rows BIGINT      NOT NULL,
    size_bytes     BIGINT      NOT NULL,
    dropped_at     TIMESTAMPTZ NOT NULL
);
