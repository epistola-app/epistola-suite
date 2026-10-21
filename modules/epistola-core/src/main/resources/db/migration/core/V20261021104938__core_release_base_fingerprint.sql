-- Catalog wire v8 (contract ADR 0005): a release names the release it was made from by number and
-- fingerprint (release.base). A received release keeps both, so its lineage can be followed to the
-- merge base, and an installed release of that number checked to be the same one.
ALTER TABLE catalog_releases ADD COLUMN base_fingerprint VARCHAR(80);

COMMENT ON COLUMN catalog_releases.base_fingerprint IS
    'The qualified catalog fingerprint of base_version (release.base): as a received release named it, or the base''s own when released here; NULL when it named none, or for a release recorded before this column.';
