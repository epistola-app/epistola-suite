-- A load test renders a catalog release: the one its environment serves, or the catalog's latest
-- when it names no environment. New runs therefore carry neither a template version nor,
-- necessarily, an environment. Existing runs keep what they recorded; only a run naming both
-- stays invalid.
ALTER TABLE load_test_runs DROP CONSTRAINT chk_load_test_version_or_environment;

ALTER TABLE load_test_runs ADD CONSTRAINT chk_load_test_version_or_environment CHECK (
    version_key IS NULL OR environment_key IS NULL
);
