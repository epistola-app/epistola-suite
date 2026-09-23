-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds generated sort columns derived from a column that already exists. A backup from
-- either side carries the same data; the columns recompute themselves on restore.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Order releases by version in SQL, not in the application.
--
-- `catalog_releases.version` is text, and a release version sorts by its components, not
-- lexicographically: `1.10.0` is newer than `1.9.0` but sorts before it. `GetLatestCatalogRelease`
-- therefore read *every* release row for a catalog and picked the maximum in memory. That is an
-- unbounded read for one answer, and it grows with every release.
--
-- Generated rather than written by the release command: the value is a pure function of `version`,
-- so it cannot drift, and it is filled for rows that arrive by any other route -- a tenant restore,
-- a future importer -- without each writer having to remember.
--
-- `substring(... from ...)` yields NULL when the pattern does not match, which is deliberate:
-- `SemVer.parseOrNull` tolerates legacy labels such as `5.5` or `1`, and those keep NULL components,
-- sort last, and fall back to `released_at`.
ALTER TABLE catalog_releases
    ADD COLUMN version_major INT GENERATED ALWAYS AS (substring(version FROM '^(\d+)\.\d+\.\d+$')::int) STORED,
    ADD COLUMN version_minor INT GENERATED ALWAYS AS (substring(version FROM '^\d+\.(\d+)\.\d+$')::int) STORED,
    ADD COLUMN version_patch INT GENERATED ALWAYS AS (substring(version FROM '^\d+\.\d+\.(\d+)$')::int) STORED;

COMMENT ON COLUMN catalog_releases.version_major IS
    'Sort key derived from version; NULL for labels that are not MAJOR.MINOR.PATCH.';

-- Serves the "latest release of this catalog" lookup, which is now ORDER BY ... LIMIT 1.
CREATE INDEX idx_catalog_releases_version_order
    ON catalog_releases (tenant_key, catalog_key, version_major DESC, version_minor DESC, version_patch DESC);
