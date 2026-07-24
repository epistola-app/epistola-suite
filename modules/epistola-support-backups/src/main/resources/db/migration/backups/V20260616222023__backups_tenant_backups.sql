-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- tenant_backups: locally-stored faithful tenant backup artifacts.
--
-- A backup is a full-fidelity, identity-preserving "undo" of a tenant's authoring
-- data (built by this module's BuildTenantBackup, in app.epistola.suite.tenantbackup),
-- stored here as the encrypted artifact bytes. This is distinct from the catalog-export
-- snapshots that the Upgrading feature ships to the hub for compatibility checks.
--
-- Stored locally (not on the hub): backups exist to correct mistakes, so keeping
-- the artifact in the same database the tenant lives in is the right scope. A hub
-- transport can be added later behind the same TenantBackupStore port.
CREATE TABLE tenant_backups (
    id            UUID         PRIMARY KEY,
    tenant_key    TENANT_KEY   NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    fingerprint   VARCHAR(64)  NOT NULL,
    schema_stamp  VARCHAR(50)  NOT NULL,
    build_version VARCHAR(100) NOT NULL,
    table_count   INTEGER      NOT NULL,
    row_count     INTEGER      NOT NULL,
    blob_count    INTEGER      NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    content       BYTEA        NOT NULL,
    captured_at   TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The Backups UI lists newest-first per tenant.
CREATE INDEX idx_tenant_backups_tenant_captured ON tenant_backups (tenant_key, captured_at DESC);
