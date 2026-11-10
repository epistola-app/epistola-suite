-- rollback-compatible: true
-- reason: Adds tables; the feedback tables are left as they are, so code predating it still reads them.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Feedback becomes support requests (ADR 0053). The support requests start empty: feedback was alpha
-- and is not moved. Its tables (feedback, feedback_comments, feedback_assets) are left in place, unused,
-- because a migration never deletes data; a later major release may drop them. The one released
-- migration of this module keeps its path (db/migration/feedback), as released files must.

-- ============================================================================
-- SUPPORT REQUESTS
-- ============================================================================

CREATE TABLE support_requests (
    tenant_key         TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    id                 UUID        NOT NULL,
    title              TEXT        NOT NULL,
    category           VARCHAR(30) NOT NULL,
    status             VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    -- The handler's priority, which starts as the requested one and comes back from whoever handles it.
    priority           VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    requested_priority VARCHAR(30) NOT NULL,
    source_url         TEXT,
    console_logs       TEXT,
    server_logs        TEXT,
    metadata           JSONB,
    -- NOT NULL with no ON DELETE SET NULL by design: a support request always has an author. The one
    -- deliberate exception to the "audit FKs use ON DELETE SET NULL" rule applied across the schema.
    created_by         UUID        NOT NULL REFERENCES users (id),
    -- Who handles it (ADR 0053 §3–4): NULL, the installation's own support desk (its inbox);
    -- otherwise the connection it went to. Only the handler sets the status and the final priority.
    handler_connection UUID        REFERENCES support_connections (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, id)
);

CREATE INDEX idx_support_requests_status ON support_requests (tenant_key, status);
CREATE INDEX idx_support_requests_created_at ON support_requests (tenant_key, created_at DESC);
CREATE INDEX idx_support_requests_created_by ON support_requests (tenant_key, created_by);
-- The support desk's inbox across tenants, newest first per status.
CREATE INDEX idx_support_requests_inbox ON support_requests (status, created_at DESC);

CREATE TRIGGER trg_support_requests_updated_at
    BEFORE UPDATE ON support_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE support_requests IS 'Support requests (ADR 0053): title, kind, status and priorities; the text is in support_request_messages.';
COMMENT ON COLUMN support_requests.metadata IS 'Client metadata captured at submission: browser, viewport, app version, etc.';
COMMENT ON COLUMN support_requests.server_logs IS 'JSON array of the server log lines of the submitter''s recent requests, when attached; NULL otherwise.';
COMMENT ON COLUMN support_requests.handler_connection IS
    'Who handles the request (ADR 0053 §4): NULL, the installation''s own inbox; otherwise the connection it went to.';

-- ============================================================================
-- MESSAGES: the question, the replies, written here or arriving over a connection
-- ============================================================================

CREATE TABLE support_request_messages (
    tenant_key      TENANT_KEY  NOT NULL,
    request_id      UUID        NOT NULL,
    id              UUID        NOT NULL,
    -- The order messages were written here, the question first.
    seq             BIGSERIAL   NOT NULL UNIQUE,
    -- QUESTION (the description), REPLY, STATUS, or NOTE (the support desk's internal note, which is
    -- never sent). No CHECK: every query names the kinds it reads, so a new kind needs no migration.
    kind            VARCHAR(16) NOT NULL,
    author_name     TEXT        NOT NULL DEFAULT '',
    author_email    TEXT,
    -- Who wrote it here, for the person reference sent in place of the name; NULL for a message from
    -- a connection, or once that user is deleted.
    author_id       UUID        REFERENCES users (id) ON DELETE SET NULL,
    body            TEXT        NOT NULL,
    -- NULL: written here. Otherwise the connection it came from, with that connection's id for it.
    from_connection UUID        REFERENCES support_connections (id) ON DELETE SET NULL,
    -- The name of the connection it came from when it arrived: kept once that connection is removed,
    -- so it never reads as written here. NULL: written here.
    from_connection_name TEXT,
    remote_id       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, request_id, id),
    CONSTRAINT fk_support_request_messages_request FOREIGN KEY (tenant_key, request_id)
        REFERENCES support_requests (tenant_key, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_support_request_messages_remote ON support_request_messages (tenant_key, from_connection, remote_id) WHERE remote_id IS NOT NULL;

COMMENT ON TABLE support_request_messages IS 'A support request''s messages (ADR 0053 §3): its question (id = the request''s), the replies, written here or arriving over a connection, and the desk''s internal notes (NOTE), which are never sent.';
COMMENT ON COLUMN support_request_messages.author_id IS
    'Who wrote it here, for the person reference sent in place of the name (ADR 0053 §3); NULL for a message from a connection.';

-- ============================================================================
-- ASSETS (attachments: screenshots, files)
-- ============================================================================

CREATE TABLE support_request_assets (
    tenant_key      TENANT_KEY   NOT NULL,
    request_id      UUID         NOT NULL,
    id              UUID         NOT NULL,
    content         BYTEA        NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    filename        VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, request_id, id),
    CONSTRAINT fk_support_request_assets_request FOREIGN KEY (tenant_key, request_id)
        REFERENCES support_requests (tenant_key, id) ON DELETE CASCADE
);

COMMENT ON TABLE support_request_assets IS 'Attachments of support requests (screenshots, files), stored as blobs.';

-- A tenant's consent and the installation default are in support_sharing_choice and
-- support_sharing_default under the section 'requests' (V20261108192223). What of a request may go
-- to a connection is in support_request_delivered_items, the same for every connection.

-- ============================================================================
-- DELIVERIES: what was sent where, per support connection (ADR 0053 §4)
-- ============================================================================

CREATE TABLE support_request_deliveries (
    tenant_key      TENANT_KEY  NOT NULL,
    request_id      UUID        NOT NULL,
    connection_id   UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    -- PENDING (to be sent), SYNCED (the connection has it, remote_ref says as what) or FAILED
    -- (refused five times in a row, still tried). No row: the request is not going there.
    state           VARCHAR(16) NOT NULL CONSTRAINT chk_support_request_deliveries_state CHECK (state IN ('PENDING', 'SYNCED', 'FAILED')),
    remote_ref      TEXT,
    remote_url      TEXT,
    attempts        INT         NOT NULL DEFAULT 0,
    -- Not sent (or its status not sent) before this: the back-off, doubling per refusal, at most six hours.
    next_attempt_at TIMESTAMPTZ,
    -- The status was changed here after the connection had the request and it has not taken it yet.
    status_unsent   BOOLEAN     NOT NULL DEFAULT FALSE,
    status_attempts INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, request_id, connection_id),
    CONSTRAINT fk_support_request_deliveries_request FOREIGN KEY (tenant_key, request_id)
        REFERENCES support_requests (tenant_key, id) ON DELETE CASCADE
);
CREATE INDEX idx_support_request_deliveries_remote ON support_request_deliveries (connection_id, tenant_key, remote_ref) WHERE remote_ref IS NOT NULL;
CREATE INDEX idx_support_request_deliveries_waiting ON support_request_deliveries (connection_id, state) WHERE state <> 'SYNCED';

COMMENT ON TABLE support_request_deliveries IS 'Where a support request goes (ADR 0053 §4): one row per request and support connection, with what that connection knows it as.';

-- Whether a connection has a message or an attachment, and the back-off until it does: a row per
-- item, so one the connection refuses never holds back the ones behind it. item_id is the message's
-- or the asset's id; both are unique within the request.
CREATE TABLE support_request_delivered_items (
    tenant_key      TENANT_KEY  NOT NULL,
    request_id      UUID        NOT NULL,
    item_id         UUID        NOT NULL,
    connection_id   UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    -- A message: the connection's id for it, once it took it.
    remote_id       TEXT,
    -- An attachment: when the connection took it.
    sent_at         TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_key, request_id, item_id, connection_id),
    CONSTRAINT fk_support_request_delivered_items_request FOREIGN KEY (tenant_key, request_id)
        REFERENCES support_requests (tenant_key, id) ON DELETE CASCADE
);
-- What is still to go to a connection, per tenant: what a withdrawal drops.
CREATE INDEX idx_support_request_delivered_items_unsent ON support_request_delivered_items (connection_id, tenant_key)
    WHERE remote_id IS NULL AND sent_at IS NULL;
