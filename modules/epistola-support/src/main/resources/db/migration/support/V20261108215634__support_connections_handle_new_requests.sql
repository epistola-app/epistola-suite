-- rollback-compatible: true
-- reason: Adds a column with a default and an index, which code predating them never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Who handles new support requests (ADR 0053 §4): the connection marked here, or with none, the
-- installation's own support desk (its inbox).
ALTER TABLE support_connections ADD COLUMN handles_new_requests BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN support_connections.handles_new_requests IS
    'New support requests go to this connection; with none, the installation''s own inbox handles them.';

-- At most one connection handles new support requests.
CREATE UNIQUE INDEX uq_support_connections_handles_new_requests ON support_connections (handles_new_requests) WHERE handles_new_requests;
