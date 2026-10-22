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
-- Rows are written once and never changed: a migration's header is part of its checksum, so the
-- declaration cannot change after the migration has been applied.
CREATE TABLE schema_rollback_compatibility (
    version TEXT PRIMARY KEY,
    rollback_compatible BOOLEAN NOT NULL,
    recorded_by TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE schema_rollback_compatibility IS
    'Per applied migration: whether code that predates it may run against the schema. Read at startup by builds that do not ship the migration.';
COMMENT ON COLUMN schema_rollback_compatibility.version IS
    'Flyway migration version, as in flyway_schema_history.version.';
COMMENT ON COLUMN schema_rollback_compatibility.rollback_compatible IS
    'The migration''s rollback-compatible header. false when the header is false or absent (default deny).';
COMMENT ON COLUMN schema_rollback_compatibility.recorded_by IS
    'Build version of the migration step that recorded the row; null when the build carries no version.';
COMMENT ON COLUMN schema_rollback_compatibility.recorded_at IS
    'When the row was recorded.';
