-- ADR 0034, 0035: every release records its content hash. Cutting and receiving a release compute
-- it from the content; 1.x kept no release content, and the 2.0 upgrade (V20261018201400) deletes
-- its releases before each catalog's first 2.0 release is cut, so no release is without one and the
-- background job that computed them later (ReleaseContentHashBackfill) is gone.
ALTER TABLE catalog_releases ALTER COLUMN content_hash SET NOT NULL;

COMMENT ON COLUMN catalog_releases.content_hash IS
    'The release''s content hash (its V6 fingerprint, ADR 0034): what identifies it, whatever its number or route. Recorded when it is cut or received.';
