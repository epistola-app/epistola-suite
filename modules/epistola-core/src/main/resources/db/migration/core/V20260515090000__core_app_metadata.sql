-- Key-value metadata table for application-level settings and internal state.
--
-- `value` is JSONB so it can carry structured values: the installation
-- identity lives under key 'installation' as {id, createdAt}, and hub
-- credentials live under 'support.hub.credentials'.
CREATE TABLE app_metadata (
    key VARCHAR(100) PRIMARY KEY,
    value JSONB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_metadata_key ON app_metadata(key);

COMMENT ON TABLE app_metadata IS 'Key-value store for application-level settings and internal state';
COMMENT ON COLUMN app_metadata.key IS 'Setting identifier (e.g., demo_version)';
COMMENT ON COLUMN app_metadata.value IS 'Setting value as JSONB (string, number, object, array, …)';
COMMENT ON COLUMN app_metadata.updated_at IS 'When this setting was last changed';

-- Initialise the installation identity exactly once per database.
--
-- The hub server REQUIRES UUIDv7 (rejects other versions at registration time).
-- Postgres 18 has uuid_v7() natively; on Postgres 17 we build one in pure SQL
-- by taking the 48-bit unix-ms timestamp, the literal version nibble (7), and
-- 19 random hex chars from gen_random_uuid() (whose variant nibble at hex
-- position 17 is already 8/9/a/b — the same variant UUIDv7 uses, so we
-- inherit it directly).
--
-- The createdAt timestamp is formatted as ISO-8601 UTC with microseconds and
-- a literal 'Z' suffix so Jackson's Instant deserialiser parses it cleanly.
--
-- This is the only data step in the baseline: InstallationService.get() fails
-- loudly if the row is missing and there is no runtime bootstrap.
INSERT INTO app_metadata (key, value)
VALUES (
    'installation',
    jsonb_build_object(
        'id', (
            lpad(to_hex((extract(epoch from NOW()) * 1000)::bigint), 12, '0')
            || '7'
            || substring(replace(gen_random_uuid()::text, '-', '') FROM 14)
        )::uuid::text,
        'createdAt', to_char(NOW() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    )
);
