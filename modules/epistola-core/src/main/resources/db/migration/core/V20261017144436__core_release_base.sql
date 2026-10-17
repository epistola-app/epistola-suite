-- A release records its base: the released version its draft started from (contract ADR 0002),
-- which what it changed is measured against. Null when it had none (a catalog's first release, or
-- a release installed from elsewhere, whose publisher's base is not in the archive) and for every
-- release cut before this column: the draft's base is gone once it is released, so it cannot be
-- backfilled, and readers fall back to the release below it.
ALTER TABLE catalog_releases ADD COLUMN base_version VARCHAR(50);

COMMENT ON COLUMN catalog_releases.base_version IS
    'The released version this release''s draft started from; null when it had none or was released before it was recorded.';
