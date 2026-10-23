-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds an installation-level table. No backed-up table changes.
-- rollback-compatible: true
-- reason: Adds a table that code predating it never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- What each applied migration declared about the code that predates it, kept where an older build
-- can read it.
--
-- A migration declares in its own file whether code from before it can still run against the schema
-- it leaves (`-- rollback-compatible: true|false`). The build that applies it can read that header; a
-- build rolled back to an earlier version cannot, because it does not ship the file. So the migration
-- step copies each applied migration's declaration here, and every build checks this table at startup
-- before it touches the schema: an applied migration it does not ship must be recorded here as
-- rollback-compatible, or the build refuses to start. See docs/migrations.md#rollback-compatibility.
--
-- Each row names the migration's row in flyway_schema_history by its key, installed_rank, and
-- carries the migration's version too, so the table reads on its own and joins to Flyway's history.
-- A row is written once and never changed: a migration's header is part of its checksum, so the
-- declaration cannot change after the migration has been applied.
--
-- installed_rank is deliberately not a foreign key. On PostgreSQL, Flyway runs a migration on one
-- connection and inserts its history row on another while the migration's transaction is still
-- open. Creating a foreign key to flyway_schema_history here locks that table until the migration
-- commits, so Flyway's own insert waits on it and the migration never finishes.
CREATE TABLE flyway_rollback_compatibility (
    installed_rank INTEGER PRIMARY KEY,
    version TEXT NOT NULL,
    rollback_compatible BOOLEAN NOT NULL,
    recorded_by TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE flyway_rollback_compatibility IS
    'Per applied migration: whether code that predates it may run against the schema. Read at startup by builds that do not ship the migration.';
COMMENT ON COLUMN flyway_rollback_compatibility.installed_rank IS
    'Key of the migration''s row in flyway_schema_history (not a foreign key; see the migration).';
COMMENT ON COLUMN flyway_rollback_compatibility.version IS
    'The migration''s version, as in flyway_schema_history.version for that row.';
COMMENT ON COLUMN flyway_rollback_compatibility.rollback_compatible IS
    'The migration''s rollback-compatible header. false when the header is false or absent (default deny).';
COMMENT ON COLUMN flyway_rollback_compatibility.recorded_by IS
    'Build version of the migration step that recorded the row; null when the build carries no version.';
COMMENT ON COLUMN flyway_rollback_compatibility.recorded_at IS
    'When the row was recorded.';
