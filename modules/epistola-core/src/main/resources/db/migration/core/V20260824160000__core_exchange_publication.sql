-- backup-restore-compatibility: backward=true forward=true
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

CREATE TABLE exchange_tenant_connections (
    tenant_key TENANT_KEY PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    tenant_connection_id UUID UNIQUE,
    tenant_connection_reference VARCHAR(29) UNIQUE,
    issuer TEXT NOT NULL,
    base_url TEXT NOT NULL,
    organization_slug TEXT,
    organization_name TEXT,
    scopes VARCHAR(30)[] NOT NULL DEFAULT ARRAY[]::VARCHAR[],
    namespaces VARCHAR(63)[] NOT NULL DEFAULT ARRAY[]::VARCHAR[],
    default_namespace VARCHAR(63),
    access_token TEXT,
    access_token_expires_at TIMESTAMPTZ,
    refresh_token TEXT,
    refresh_token_expires_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'REAUTHORIZATION_REQUIRED', 'BLOCKED')),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE exchange_device_authorizations (
    tenant_key TENANT_KEY PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    device_code TEXT NOT NULL,
    user_code VARCHAR(20) NOT NULL,
    verification_uri TEXT NOT NULL,
    verification_uri_complete TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    poll_interval_seconds INTEGER NOT NULL,
    next_poll_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE catalog_exchange_bindings (
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,
    namespace VARCHAR(63) NOT NULL,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, catalog_key),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE
);

CREATE TABLE catalog_release_publications (
    id UUID PRIMARY KEY,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,
    version VARCHAR(50) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    namespace VARCHAR(63),
    archive BYTEA,
    status VARCHAR(30) NOT NULL CHECK (status IN ('WAITING_SETUP', 'READY', 'SUBMITTING', 'SUBMITTED', 'RETRY', 'ACCEPTED', 'REJECTED', 'FAILED')),
    idempotency_key UUID NOT NULL,
    remote_publication_id UUID,
    remote_status_url TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_key, catalog_key, version),
    FOREIGN KEY (tenant_key, catalog_key, version)
        REFERENCES catalog_releases(tenant_key, catalog_key, version) ON DELETE CASCADE
);

CREATE INDEX catalog_release_publications_work
    ON catalog_release_publications(status, next_attempt_at)
    WHERE status IN ('WAITING_SETUP', 'READY', 'SUBMITTED', 'RETRY');

COMMENT ON TABLE exchange_tenant_connections IS
    'One Exchange device-grant connection per Suite tenant; credentials are encrypted by JDBI and excluded from portable backups.';
COMMENT ON TABLE exchange_device_authorizations IS
    'Cluster-safe pending device grants; the device code is encrypted and never included in tenant backups.';
COMMENT ON TABLE catalog_exchange_bindings IS
    'Immutable namespace chosen when a catalog is first queued for Exchange publication.';
COMMENT ON TABLE catalog_release_publications IS
    'Durable publication outbox containing the exact release ZIP until Exchange accepts or rejects it.';
