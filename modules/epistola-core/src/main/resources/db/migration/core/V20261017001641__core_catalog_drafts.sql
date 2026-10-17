-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A catalog consists of versions (contract ADR 0002): released ones, which catalog_releases holds,
-- and drafts, which this table numbers. An authored catalog's working copy is its open draft: it
-- has a number of its own, the released version it started from (its base) and, when it was
-- created from another version, that one. Releasing a draft opens the next.
--
-- One draft per catalog for now: the working copy is still one set of rows. Several drafts at once
-- come with content kept per draft, which then drops the uniqueness on catalog_id.
CREATE TABLE catalog_drafts (
    tenant_key   TENANT_KEY   NOT NULL,
    catalog_id   UUID         NOT NULL,
    version      VARCHAR(50)  NOT NULL,
    base_version VARCHAR(50),
    created_from VARCHAR(50),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, catalog_id, version),
    CONSTRAINT uq_catalog_drafts_one_per_catalog UNIQUE (tenant_key, catalog_id),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_drafts IS
    'The open draft of each authored catalog: its version number, its base release and what it was created from.';

-- Every authored catalog gets the draft it is working on: the patch after its latest release, or
-- 1.0.0 when it was never released. A release whose version is not SemVer has no order and is
-- skipped as a base; the authored releases the suite cut always are.
INSERT INTO catalog_drafts (tenant_key, catalog_id, version, base_version)
SELECT c.tenant_key, c.catalog_id,
       COALESCE(latest.version_major || '.' || latest.version_minor || '.' || (latest.version_patch + 1), '1.0.0'),
       latest.version
FROM catalogs c
LEFT JOIN LATERAL (
    SELECT r.version, r.version_major, r.version_minor, r.version_patch
    FROM catalog_releases r
    WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.version_major IS NOT NULL
    ORDER BY r.version_major DESC, r.version_minor DESC, r.version_patch DESC
    LIMIT 1
) latest ON TRUE
WHERE c.type = 'AUTHORED';
