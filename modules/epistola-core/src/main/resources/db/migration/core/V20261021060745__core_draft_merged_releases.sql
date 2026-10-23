-- A draft records the releases it has merged (ADR 0032): a release that is not its base and that it
-- took in by a merge. A draft is not behind a release it merged.
ALTER TABLE catalog_versions ADD COLUMN merged_releases VARCHAR(50)[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN catalog_versions.merged_releases IS
    'Drafts only: the releases of the catalog merged into this draft (ADR 0032), so it is not behind them.';
