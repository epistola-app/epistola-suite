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
-- The connection the product domain's discovery document names (ADR 0030) has a fixed id and is
-- created here, so the tables that hang off a connection always have it to refer to.

CREATE TABLE support_connections (
    id           UUID        NOT NULL PRIMARY KEY,
    name         TEXT        NOT NULL,
    source       VARCHAR(16) NOT NULL CONSTRAINT chk_support_connections_source CHECK (source IN ('DISCOVERED', 'CONFIGURED')),
    endpoint     TEXT,
    auth         VARCHAR(32) NOT NULL CONSTRAINT chk_support_connections_auth CHECK (auth IN ('PRIVATE_KEY_JWT', 'BEARER_TOKEN')),
    escalates_to UUID        REFERENCES support_connections (id) ON DELETE SET NULL,
    credentials  TEXT,
    status       JSONB,
    services     JSONB,
    contact      JSONB,
    refusals     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    node_clocks  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_support_connections_endpoint CHECK (source = 'DISCOVERED' OR endpoint IS NOT NULL)
);

-- One connection comes from the product domain's discovery document.
CREATE UNIQUE INDEX uq_support_connections_discovered ON support_connections (source) WHERE source = 'DISCOVERED';
-- A configured connection is named by its endpoint: the configuration that names it again at the next
-- start finds it, and nodes starting together cannot create it twice.
CREATE UNIQUE INDEX uq_support_connections_configured_endpoint ON support_connections (endpoint) WHERE source = 'CONFIGURED';

COMMENT ON TABLE support_connections IS
    'Parties the installation shares with (ADR 0053): registration, status, selected services and contact per connection.';
COMMENT ON COLUMN support_connections.endpoint IS
    'The support service''s issuer; NULL for the DISCOVERED connection, whose endpoint comes from the product domain or epistola.support.endpoint.';
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
COMMENT ON COLUMN support_connections.version IS
    'Raised by every write to the row: a node that finds it unchanged knows its copy is current.';

INSERT INTO support_connections (id, name, source, auth)
VALUES ('01900000-0000-7000-8000-000000000001', 'Epistola support', 'DISCOVERED', 'PRIVATE_KEY_JWT');
