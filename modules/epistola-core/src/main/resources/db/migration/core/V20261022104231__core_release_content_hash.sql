-- A release is identified by its content (contract ADR 0003, amendment of 2026-10-22; ADR 0034).
--
-- A release's identity is a hash over its content only: the V6 fingerprint (`v6:<hex>`), which
-- leaves out the catalog's own name, the release block and the catalog's presentation. Two releases
-- with the same content hash are the same release, whatever number or route they came by. Every
-- release keeps the fingerprint it was cut or received with (V5 or older for releases from before
-- V6: immutable, and verifiable with the algorithm it names); the content hash is recorded beside it
-- and is what "is this the same release?" is decided by.
--
-- A release cut or received with a V6 fingerprint has it as its content hash. The others get theirs
-- from what they retained, computed by the application (`ReleaseContentHashBackfill`, a cluster
-- task at startup, and on demand wherever a release's hash is asked for first): the canonical form is
-- the contract library's, which SQL cannot reproduce.

ALTER TABLE catalog_releases ADD COLUMN content_hash VARCHAR(80);

UPDATE catalog_releases SET content_hash = fingerprint WHERE fingerprint LIKE 'v6:%';

CREATE INDEX idx_catalog_releases_content_hash ON catalog_releases (tenant_key, catalog_id, content_hash);

COMMENT ON COLUMN catalog_releases.content_hash IS
    'The release''s identity: the V6 fingerprint of its content (v6:<hex>), which covers no name, release block or '
    'presentation. Its fingerprint when that is V6; else computed from its retained content. Null until computed.';
