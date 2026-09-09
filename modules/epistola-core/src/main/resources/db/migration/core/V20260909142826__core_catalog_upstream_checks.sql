-- backup-restore-compatibility: backward=true forward=true
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- What a subscribed catalog's source is currently offering, as of the last time we asked.
--
-- Deliberately its own table rather than columns on `catalogs`, for two reasons. It is
-- transient knowledge about somebody else's server: `catalogs` is in the tenant backup
-- set, so state kept there would be captured and restored, resurrecting "update to v2.1
-- available" long after v3 shipped or that release was withdrawn. And the worker
-- bookkeeping below (the claim lease, the attempt count, the next due time) has no
-- business on a row that browse, REST and MCP all read.
--
-- Source-agnostic on purpose. A catalog subscribed from a manifest URL and one installed
-- from Epistola Exchange differ in how they are asked, not in what the answer means, and
-- until now neither had anywhere to keep the answer: checking for updates was a live
-- fetch made while rendering the catalogs page, so nothing could be said about a catalog
-- nobody happened to be looking at.
CREATE TABLE catalog_upstream_checks (
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,

    -- What the last successful check found upstream.
    available_release_version TEXT,
    -- The catalog wire schema version the source publishes, when it says. Exchange serves
    -- an archive and advertises no wire version, so this stays NULL for those and the
    -- mismatch is only discovered at import.
    available_schema_version INTEGER,
    available_published_at TIMESTAMPTZ,

    -- Whether the release this catalog is actually on is still offered. A withdrawn one
    -- keeps working locally, so this is reported rather than acted on.
    installed_availability TEXT CHECK (installed_availability IN ('AVAILABLE', 'BLOCKED', 'WITHDRAWN')),
    installed_availability_checked_at TIMESTAMPTZ,
    -- The digest of the archive actually imported. Not installed_fingerprint, which is the
    -- canonical content fingerprint: this is the bytes, for integrity and conditional refetch.
    installed_archive_sha256 CHAR(64),

    last_checked_at TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    check_attempts INTEGER NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,

    -- Why the last check did not produce an answer, as data. Same rule as everywhere else:
    -- a code this application defines, plus whatever the far side said, kept apart so the
    -- wording can be improved for rows already written. See ADR 0017.
    error_code TEXT,
    error_detail TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, catalog_key),
    -- Unlike catalog_exchange_bindings, which has no FK because a publication must outlive
    -- the catalog it came from, this row describes a live local mirror and is meaningless
    -- without it. The cascade is also what keeps the catalog domain from ever having to
    -- tell the Exchange integration that a catalog was removed.
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs (tenant_key, id) ON DELETE CASCADE
);

-- The worker's only query: rows whose next_check_at has passed.
CREATE INDEX catalog_upstream_checks_due ON catalog_upstream_checks (next_check_at);

COMMENT ON TABLE catalog_upstream_checks IS
    'Last known upstream release state per subscribed catalog. Excluded from tenant backups: '
    'transient knowledge about a remote source, not tenant content.';

-- Deliberately NO unique index on catalogs(tenant_key, source_url).
--
-- It was tempting: two catalogs mirroring one source would each overwrite the other on every
-- upgrade. But a catalog's key comes from its source manifest's slug, and RegisterCatalog upserts
-- on that key alone -- so a publisher who renames their catalog leaves the old row behind with the
-- same source_url and a new row beside it. That pair is reachable on any installation that has ever
-- subscribed to a URL, which would make this constraint fail the migration on somebody else's data,
-- and refuse a legitimate re-subscribe afterwards. The rule this would enforce matters only for
-- Exchange, where the source is chosen rather than derived, and ExchangeCatalogInstaller enforces it
-- there before a byte is downloaded.
