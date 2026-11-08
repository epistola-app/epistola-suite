-- rollback-compatible: true
-- reason: Adds a table, which code predating it never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Usage for billing (ADR 0052 §6) is read from core's generation statistics; this records which
-- finished hours were reported to which support connection (ADR 0053), so each is sent once to each.
CREATE TABLE support_usage_reports (
    connection_id UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    hour          TIMESTAMPTZ NOT NULL CHECK (date_trunc('hour', hour) = hour),
    reported_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (connection_id, hour)
);
