-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- What a language checker reported for one block of text (ADR 0044): a cache, keyed by the checker,
-- its configuration (version, rule set, settings), the locale and the hash of the block's checkable
-- text. A block is checked once per key, wherever it appears; a changed configuration is a new key,
-- so nothing here is ever invalidated, only no longer read. Every row can be computed again, so
-- deleting rows loses nothing but the time to recompute them.
--
-- Per tenant on purpose: shared, a result would tell one tenant whether another holds a sentence.
CREATE TABLE quality_text_results (
    tenant_key    TENANT_KEY   NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    checker_id    VARCHAR(64)  NOT NULL,
    configuration VARCHAR(200) NOT NULL,
    locale        VARCHAR(35)  NOT NULL,
    block_hash    CHAR(64)     NOT NULL,
    -- The checker's diagnostics for the block: offsets into its checkable text, rule, category,
    -- severity, message, suggestions. Empty when it found nothing, which is an answer worth keeping.
    diagnostics   JSONB        NOT NULL,
    checked_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_key, checker_id, configuration, locale, block_hash)
);

COMMENT ON TABLE quality_text_results IS 'Language-check results by content (ADR 0044): a cache keyed by checker, configuration, locale and block hash; safe to delete.';
