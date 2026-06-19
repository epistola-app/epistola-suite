-- Least-privilege API keys: scope each key to a subset of tenant roles instead of
-- implicitly granting all of them. Previously every key authenticated as all tenant
-- roles; now the granted roles are stored per key and the auth filter builds the
-- principal from exactly these.
--
-- Default for any pre-existing / unspecified key is the narrowest useful scope
-- (read-only), so an un-migrated key cannot perform writes.
ALTER TABLE api_keys
    ADD COLUMN roles VARCHAR(30)[] NOT NULL DEFAULT ARRAY['CONTENT_VIEWER']::VARCHAR[];

COMMENT ON COLUMN api_keys.roles IS
    'Tenant roles granted to this key (least-privilege scope). Subset of TenantRole names; the auth filter grants exactly these for the key''s tenant.';
