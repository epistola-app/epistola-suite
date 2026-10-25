-- ADR 0033, step 6: an image's bytes are deduplicated alike, whatever the image. The `sensitive`
-- flag chose a tenant-isolated scope for them (#738, #751); it is gone, and with it the scope.
-- Bytes a tenant kept apart move into the shared store, deduplicated by their hash; every link
-- to them follows; the tenant copies go.

-- 1. The shared store gets every tenant's bytes it lacks.
INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes, created_at)
SELECT DISTINCT ON (content_hash) 'global', content_hash, content, content_type, size_bytes, created_at
FROM asset_content
WHERE scope <> 'global'
ORDER BY content_hash, created_at
ON CONFLICT DO NOTHING;

-- 2. Every link points at the shared copy; a link the content already had there is not doubled.
ALTER TABLE resource_binaries DROP CONSTRAINT fk_resource_binaries_binary;
DELETE FROM resource_binaries rb
WHERE rb.scope <> 'global'
  AND EXISTS (
      SELECT 1 FROM resource_binaries g
      WHERE g.tenant_key = rb.tenant_key AND g.content_hash = rb.content_hash AND g.scope = 'global' AND g.binary_hash = rb.binary_hash
  );
UPDATE resource_binaries SET scope = 'global' WHERE scope <> 'global';

-- 3. The tenant copies go; the scope with them.
DELETE FROM asset_content WHERE scope <> 'global';

DROP INDEX idx_resource_binaries_binary;
ALTER TABLE resource_binaries DROP CONSTRAINT pk_resource_binaries;
ALTER TABLE resource_binaries DROP COLUMN scope;
ALTER TABLE resource_binaries ADD CONSTRAINT pk_resource_binaries PRIMARY KEY (tenant_key, content_hash, binary_hash);
CREATE INDEX idx_resource_binaries_binary ON resource_binaries (binary_hash);

ALTER TABLE asset_content DROP CONSTRAINT asset_content_pkey;
ALTER TABLE asset_content DROP COLUMN scope;
ALTER TABLE asset_content ADD CONSTRAINT asset_content_pkey PRIMARY KEY (content_hash);

ALTER TABLE resource_binaries
    ADD CONSTRAINT fk_resource_binaries_binary FOREIGN KEY (binary_hash) REFERENCES asset_content (content_hash);

COMMENT ON TABLE asset_content IS
    'The bytes of images and font faces, once per installation, by their SHA-256 (content_hash). What a stored content needs is linked through resource_binaries.';
