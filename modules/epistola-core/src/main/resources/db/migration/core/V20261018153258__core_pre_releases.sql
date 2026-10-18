-- Pre-releases (contract ADR 0002, amendment 2): long-term work is released as MAJOR.MINOR.PATCH-PRERELEASE
-- (`2.0.0-redesign-2027.1`). Its number orders with the release of the same number, below it, and it
-- is never "the latest release": the latest-release lookups leave it out.
--
-- The sort keys now take the number of a pre-release too (they were NULL for it, as for a legacy
-- label), and version_pre_release says which releases are pre-releases. No row changes: no
-- pre-release exists before this migration.

ALTER TABLE catalog_releases
    ALTER COLUMN version_major SET EXPRESSION AS ((substring(version FROM '^(\d+)\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$'))::int),
    ALTER COLUMN version_minor SET EXPRESSION AS ((substring(version FROM '^\d+\.(\d+)\.\d+(?:-[0-9A-Za-z.-]+)?$'))::int),
    ALTER COLUMN version_patch SET EXPRESSION AS ((substring(version FROM '^\d+\.\d+\.(\d+)(?:-[0-9A-Za-z.-]+)?$'))::int);

ALTER TABLE catalog_releases
    ADD COLUMN version_pre_release TEXT GENERATED ALWAYS AS (substring(version FROM '^\d+\.\d+\.\d+-([0-9A-Za-z.-]+)$')) STORED;

COMMENT ON COLUMN catalog_releases.version_major IS
    'Sort key derived from version, a pre-release''s included; NULL for labels that are not MAJOR.MINOR.PATCH[-PRERELEASE].';
COMMENT ON COLUMN catalog_releases.version_pre_release IS
    'The pre-release identifiers of a pre-release (long-term work released before it is ready); NULL for a release. A pre-release is never the latest release.';
