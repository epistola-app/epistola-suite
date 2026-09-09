-- backup-restore-compatibility: backward=true forward=true
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Identifiers and vocabularies Exchange owns are stored as TEXT rather than mirrored
-- with its length limits. varchar(n) and text are the same storage in PostgreSQL, so a
-- length here buys no space — only a constraint on a value this side does not define.
-- If Exchange ever lengthens a connection reference or relaxes a namespace limit, a
-- mirrored limit turns that into an insert failure during enrollment or publication,
-- fixable only by shipping a Suite migration. Status columns keep their CHECK: those
-- are this application's own state machine, not someone else's vocabulary.
CREATE TABLE exchange_tenant_connections (
    tenant_key TENANT_KEY PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    tenant_connection_id UUID UNIQUE,
    tenant_connection_reference TEXT UNIQUE,
    issuer TEXT NOT NULL,
    base_url TEXT NOT NULL,
    authorization_request_endpoint TEXT NOT NULL,
    token_endpoint TEXT NOT NULL,
    organization_slug TEXT,
    organization_name TEXT,
    oauth_application_id UUID,
    client_secret TEXT,
    scopes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    namespaces TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    default_namespace TEXT,
    access_token TEXT,
    access_token_expires_at TIMESTAMPTZ,
    refresh_token TEXT,
    refresh_token_expires_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'REAUTHORIZATION_REQUIRED', 'BLOCKED')),
    -- Why this is not progressing, as data: a code the UI turns into a sentence, and whatever the
    -- far side said, kept as supporting detail. Prose composed at failure time put the
    -- transport's own words in front of people and could never be improved for rows already
    -- written. See ADR 0017.
    error_code TEXT,
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE exchange_oauth_authorizations (
    tenant_key TENANT_KEY PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    state_hash CHAR(64) NOT NULL UNIQUE,
    code_verifier TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE catalog_exchange_bindings (
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_key CATALOG_KEY NOT NULL,
    namespace TEXT NOT NULL,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_key, catalog_key)
);

CREATE TABLE catalog_release_publications (
    id UUID PRIMARY KEY,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,
    version VARCHAR(50) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    namespace TEXT NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('READY', 'SUBMITTED', 'RETRY', 'ACCEPTED', 'REJECTED', 'FAILED', 'CANCELLED')),
    idempotency_key UUID NOT NULL,
    remote_publication_id UUID,
    -- When Exchange first took the submission. Following one is otherwise unbounded: it consumes no
    -- retry budget (nothing failed), so a submission Exchange never decides would be polled for ever
    -- while holding its retained archive. Aged in SQL, never against the application clock.
    submitted_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    -- Why this is not progressing, as data: a code the UI turns into a sentence, and whatever the
    -- far side said, kept as supporting detail. Prose composed at failure time put the
    -- transport's own words in front of people and could never be improved for rows already
    -- written. See ADR 0017.
    error_code TEXT,
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_key, catalog_key, version),
    FOREIGN KEY (tenant_key, catalog_key, version)
        REFERENCES catalog_releases(tenant_key, catalog_key, version) ON DELETE CASCADE
);

-- The retained release ZIP lives beside the outbox row rather than inside it.
--
-- catalog_release_publications is a work queue the cluster worker polls on a fixed
-- delay, and mixing multi-megabyte values into the table being polled makes releasing
-- one an UPDATE to NULL: a dead tuple plus orphaned TOAST chunks in the hot table,
-- waiting on autovacuum. Here it is a DELETE that reclaims cleanly, and the row that
-- decides what to do next stays small.
--
-- Same shape as document_content, which was split out for the same reason (see #738),
-- and size_bytes is stored rather than measured so retention and the installation-wide
-- gauge read a number instead of detoasting every archive to weigh it.
CREATE TABLE catalog_release_publication_archives (
    publication_id UUID PRIMARY KEY
        REFERENCES catalog_release_publications(id) ON DELETE CASCADE,
    bytes BYTEA NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX catalog_release_publications_work
    ON catalog_release_publications(status, next_attempt_at)
    WHERE status IN ('READY', 'SUBMITTED', 'RETRY');

COMMENT ON TABLE exchange_tenant_connections IS
    'One Exchange OAuth connection per Suite tenant; credentials are encrypted by JDBI and excluded from portable backups.';
COMMENT ON COLUMN exchange_tenant_connections.token_endpoint IS
    'Token endpoint discovered from the issuer''s OAuth metadata when the connection was authorized; never reconstructed from a hard-coded path.';
COMMENT ON COLUMN exchange_tenant_connections.oauth_application_id IS
    'Exchange OAuth application selected or created during browser authorization.';
COMMENT ON COLUMN exchange_tenant_connections.client_secret IS
    'Encrypted OAuth application credential used only for backchannel token refresh.';
COMMENT ON TABLE exchange_oauth_authorizations IS
    'Short-lived redirect state and encrypted PKCE verifier; excluded from portable backups.';
COMMENT ON TABLE catalog_exchange_bindings IS
    'Immutable namespace chosen when a catalog is first queued for Exchange publication. Deliberately NOT tied to the catalogs row: Exchange keeps what was published under these coordinates even after the local catalog is deleted, so a catalog recreated under the same key must land in the same namespace rather than silently claiming a second one.';
COMMENT ON COLUMN catalog_exchange_bindings.published_at IS
    'When a release of this catalog first reached Exchange, fixing the namespace. NULL while the choice is still local and correctable.';
COMMENT ON TABLE catalog_release_publications IS
    'Durable publication outbox: one row per release queued for Exchange, holding only what the worker needs to decide what to do next. The release ZIP itself is in catalog_release_publication_archives.';
COMMENT ON TABLE catalog_release_publication_archives IS
    'The exact release ZIP retained until Exchange accepts or rejects the publication, or an administrator withdraws it. Separate from the outbox row so releasing the bytes is a DELETE rather than an UPDATE to NULL in a table the worker polls continuously.';
COMMENT ON COLUMN catalog_release_publication_archives.size_bytes IS
    'Stored rather than derived: retention and the installation-wide retained-bytes gauge read this instead of detoasting every archive to measure it.';
