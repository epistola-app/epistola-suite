-- Least-privilege API keys: scope each key to a subset of tenant roles instead of
-- implicitly granting all of them. Previously every key authenticated as all tenant
-- roles; now the granted roles are stored per key and the auth filter builds the
-- principal from exactly these.
--
-- Backfill: every pre-existing key authenticated as ALL tenant roles, so seed existing
-- rows with the full set to preserve their behavior across the upgrade — downgrading
-- them to read-only would silently break integrations that performed writes.
ALTER TABLE api_keys
    ADD COLUMN roles VARCHAR(30)[] NOT NULL DEFAULT
        ARRAY['CONTENT_VIEWER', 'CONTENT_AUTHOR', 'DOCUMENT_GENERATOR', 'CONTENT_PUBLISHER', 'TENANT_ADMINISTRATOR']::VARCHAR[];

-- New keys must declare their scope explicitly (CreateApiKey validates non-empty; the
-- demo seed passes its roles). Drop the column default so a bare INSERT can't silently
-- mint an all-roles key — the all-roles default above is only the one-time backfill.
ALTER TABLE api_keys
    ALTER COLUMN roles DROP DEFAULT;

COMMENT ON COLUMN api_keys.roles IS
    'Tenant roles granted to this key (least-privilege scope). Subset of TenantRole names; the auth filter grants exactly these for the key''s tenant.';
