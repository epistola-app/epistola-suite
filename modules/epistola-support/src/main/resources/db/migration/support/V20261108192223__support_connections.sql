-- rollback-compatible: true
-- reason: Adds tables, which code predating them never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Support connections (ADR 0053): the parties outside the operation of the installation it shares
-- with. Epistola is one, a partner another; nothing in the schema tells them apart. Everything the
-- suite keeps about one connection is in its row: the encrypted registration, the status every node
-- reads, the selected services, the contact, refusals and node clock errors,
-- and the version every write raises so other nodes see a change with one primary-key read.
--
-- No row is created here: the connection epistola.support.* configures is stored at startup while
-- epistola.support.enabled is on, and the others when they are configured or added.

CREATE TABLE support_connections (
    id           UUID        NOT NULL PRIMARY KEY,
    name         TEXT        NOT NULL,
    endpoint     TEXT        NOT NULL,
    -- How the suite signs in: PRIVATE_KEY_JWT (OAuth dynamic client registration, RFC 7591, then
    -- private_key_jwt) or BEARER_TOKEN (a token the other side issued). No CHECK: a version that does
    -- not know a kind leaves that connection alone, so a new kind needs no migration.
    auth         VARCHAR(32) NOT NULL,
    escalates_to UUID        REFERENCES support_connections (id) ON DELETE SET NULL,
    -- New support requests go to this connection (ADR 0053 §4); with none, the installation's own
    -- support desk (its inbox) handles them.
    handles_new_requests BOOLEAN NOT NULL DEFAULT FALSE,
    credentials  TEXT,
    status       JSONB,
    services     JSONB,
    contact      JSONB,
    privacy      JSONB,
    -- Set from `epistola.support.connections` and kept so at every start; FALSE once an administrator
    -- added or saved it, which the configuration then leaves alone.
    from_configuration BOOLEAN NOT NULL DEFAULT FALSE,
    refusals     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    node_clocks  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- A connection is named by its endpoint: the configuration that names it again at the next start
    -- finds it, and nodes starting together cannot create it twice.
    CONSTRAINT uq_support_connections_endpoint UNIQUE (endpoint)
);

-- At most one connection handles new support requests.
CREATE UNIQUE INDEX uq_support_connections_handles_new_requests ON support_connections (handles_new_requests) WHERE handles_new_requests;

COMMENT ON TABLE support_connections IS
    'Parties the installation shares with (ADR 0053): registration, status, selected services and contact per connection.';
COMMENT ON COLUMN support_connections.endpoint IS
    'The other side''s base URL or issuer, without a trailing slash: the connection''s identity.';
COMMENT ON COLUMN support_connections.credentials IS
    'The registration (private key, client id, claim code), encrypted with the credential cipher; NULL while not registered.';
COMMENT ON COLUMN support_connections.status IS
    'The last check''s outcome every node reads; NULL before the first check.';
COMMENT ON COLUMN support_connections.services IS
    'The services an installation administrator selected; NULL: the configuration (epistola.support.services) applies.';
COMMENT ON COLUMN support_connections.refusals IS
    'Per service, the version at which the service answered insufficient_scope: a token asked for later is newer news.';
COMMENT ON COLUMN support_connections.node_clocks IS
    'Per node id, a clock error the service reported for that node, stamped by the database clock.';
COMMENT ON COLUMN support_connections.privacy IS
    'What this connection learns of tenants and how much telemetry carries, as an administrator saved it; NULL: its configuration entry, else the configured default (epistola.support.privacy).';
COMMENT ON COLUMN support_connections.version IS
    'Raised by every write to the row: a node that finds it unchanged knows its copy is current.';
COMMENT ON COLUMN support_connections.handles_new_requests IS
    'New support requests go to this connection; with none, the installation''s own inbox handles them.';

-- ============================================================================
-- SHARING: what each tenant shares over each connection
-- ============================================================================

-- What each tenant shares over each support connection (ADR 0052 §5, ADR 0053), for every shared
-- service alike: support requests, the catalog mirror, usage. `section` is the service's key.

-- What an installation administrator decided beyond a default: NOT_ALLOWED, no tenant shares it;
-- REQUIRED, every tenant does, without a choice. No row: tenants choose, following the default.
CREATE TABLE support_sharing_restriction (
    connection_id UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    section       VARCHAR(64) NOT NULL,
    restriction   VARCHAR(16) NOT NULL CONSTRAINT chk_support_sharing_restriction CHECK (restriction IN ('NOT_ALLOWED', 'REQUIRED')),
    set_at        TIMESTAMPTZ NOT NULL,
    set_by        UUID        REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (connection_id, section)
);

COMMENT ON TABLE support_sharing_restriction IS
    'Per connection and shared service (requests, mirror, usage): NOT_ALLOWED or REQUIRED for every tenant. No row: tenants choose.';

-- A tenant's choice: given (sharing) or withdrawn. A withdrawal keeps the row and stamps
-- withdrawn_at/_by; giving again clears them. Sharing while
-- withdrawn_at IS NULL. A tenant that chose not to share without ever having shared has a withdrawn
-- row never given. follows_default: written by the installation default rather than the tenant.
-- No row: the tenant follows the default as it stands.
CREATE TABLE support_sharing_choice (
    connection_id           UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    section                 VARCHAR(64) NOT NULL,
    tenant_key              TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    given_at                TIMESTAMPTZ,
    given_by                UUID        REFERENCES users (id) ON DELETE SET NULL,
    withdrawn_at            TIMESTAMPTZ,
    withdrawn_by            UUID        REFERENCES users (id) ON DELETE SET NULL,
    -- When the connection confirmed it holds nothing of the tenant any more, after a withdrawal.
    withdrawal_confirmed_at TIMESTAMPTZ,
    follows_default         BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The version of the statement of what is shared that was agreed to, where the service has one.
    statement_version       VARCHAR(32),
    -- What the service lets a tenant choose beyond sharing or not, e.g. the mirror's {"shareByDefault": true}.
    options                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (connection_id, section, tenant_key),
    CONSTRAINT chk_support_sharing_choice_given_or_withdrawn CHECK (given_at IS NOT NULL OR withdrawn_at IS NOT NULL),
    CONSTRAINT chk_support_sharing_choice_confirmed CHECK (withdrawal_confirmed_at IS NULL OR withdrawn_at IS NOT NULL)
);

COMMENT ON TABLE support_sharing_choice IS
    'A tenant''s choice to share a service over a support connection (ADR 0053). Sharing while withdrawn_at IS NULL; no row: it follows the default.';

-- The installation's default per connection and service, for tenants without a choice of their own.
-- No row: not shared. from_configuration: set by the deployment configuration (Helm
-- support.tenantDefaults), not a person.
CREATE TABLE support_sharing_default (
    connection_id      UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    section            VARCHAR(64) NOT NULL,
    share              BOOLEAN     NOT NULL,
    statement_version  VARCHAR(32),
    options            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    set_at             TIMESTAMPTZ NOT NULL,
    set_by             UUID        REFERENCES users (id) ON DELETE SET NULL,
    from_configuration BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (connection_id, section)
);

COMMENT ON TABLE support_sharing_default IS
    'The installation''s default per support connection and shared service, for tenants without a choice of their own. No row: not shared.';

-- ============================================================================
-- USAGE: which hours were reported to which connection
-- ============================================================================

-- Usage for billing (ADR 0052 §6) is read from core's generation statistics; this records which
-- finished hours were reported to which support connection (ADR 0053), so each is sent once to each.
CREATE TABLE support_usage_reports (
    connection_id UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    hour          TIMESTAMPTZ NOT NULL CHECK (date_trunc('hour', hour) = hour),
    reported_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (connection_id, hour)
);
