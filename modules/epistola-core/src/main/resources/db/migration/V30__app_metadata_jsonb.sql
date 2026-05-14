-- Convert app_metadata.value from TEXT to JSONB so it can carry structured
-- values (e.g. the installation identity row under key 'installation' holds
-- {id, createdAt}).
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
