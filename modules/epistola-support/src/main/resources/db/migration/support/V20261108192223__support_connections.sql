-- rollback-compatible: true
-- reason: Adds a table, which code predating it never reads.
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
    auth         VARCHAR(32) NOT NULL CONSTRAINT chk_support_connections_auth CHECK (auth IN ('PRIVATE_KEY_JWT', 'BEARER_TOKEN')),
    escalates_to UUID        REFERENCES support_connections (id) ON DELETE SET NULL,
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
    'What this connection learns of tenants and how much telemetry carries, as an administrator saved it; NULL: the configuration, else the installation''s settings (app_metadata support.privacy).';
COMMENT ON COLUMN support_connections.version IS
    'Raised by every write to the row: a node that finds it unchanged knows its copy is current.';
