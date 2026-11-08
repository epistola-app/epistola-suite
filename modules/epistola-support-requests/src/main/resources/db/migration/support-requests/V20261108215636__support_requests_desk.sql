-- rollback-compatible: true
-- reason: Adds nullable columns, an index and a wider check, which code predating them never reads or writes.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The installation's own support desk (ADR 0053 §3–4): a handler per request, internal notes, and who
-- wrote a message here, for the person reference sent in place of the name.

-- Who handles a request: NULL, the installation's own inbox; otherwise the connection it went to.
-- Only the handler sets the status and the final priority.
ALTER TABLE support_requests ADD COLUMN handler_connection UUID REFERENCES support_connections (id) ON DELETE SET NULL;
COMMENT ON COLUMN support_requests.handler_connection IS
    'Who handles the request (ADR 0053 §4): NULL, the installation''s own inbox; otherwise the connection it went to.';

-- What was already sent stays where it went: a request delivered to a connection is handled there.
UPDATE support_requests r
SET handler_connection = d.connection_id
FROM support_request_deliveries d
WHERE d.tenant_key = r.tenant_key AND d.request_id = r.id;

-- The inbox across tenants, newest first per status.
CREATE INDEX idx_support_requests_inbox ON support_requests (status, created_at DESC);

-- Internal notes of the desk (NOTE), which are never sent.
ALTER TABLE support_request_messages DROP CONSTRAINT chk_support_request_messages_kind;
ALTER TABLE support_request_messages ADD CONSTRAINT chk_support_request_messages_kind CHECK (kind IN ('QUESTION', 'REPLY', 'STATUS', 'NOTE'));

-- Who wrote a message here; NULL for a message from a connection, or once that user is deleted.
ALTER TABLE support_request_messages ADD COLUMN author_id UUID REFERENCES users (id) ON DELETE SET NULL;
COMMENT ON COLUMN support_request_messages.author_id IS
    'Who wrote it here, for the person reference sent in place of the name (ADR 0053 §3); NULL for a message from a connection.';

COMMENT ON TABLE support_request_messages IS 'A support request''s messages (ADR 0053 §3): its question (id = the request''s), the replies, written here or arriving over a connection, and the desk''s internal notes (NOTE), which are never sent.';
