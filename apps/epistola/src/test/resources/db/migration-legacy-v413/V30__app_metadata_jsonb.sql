-- Convert app_metadata.value from TEXT to JSONB so it can carry structured
-- values (e.g. the installation identity row under key 'installation' holds
-- {id, createdAt}, and hub credentials live under 'support.hub.credentials').
--
-- The only existing row is the V1-seeded 'demo_version' = '0.0.0', which is
-- not read by any caller in the current codebase (verified) — drop it
-- before the type change so the cast doesn't need to round-trip a non-JSON
-- literal.
DELETE FROM app_metadata WHERE key = 'demo_version';

ALTER TABLE app_metadata
    ALTER COLUMN value TYPE JSONB
    USING to_jsonb(value);

COMMENT ON COLUMN app_metadata.value
    IS 'Setting value as JSONB (string, number, object, array, …)';

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
