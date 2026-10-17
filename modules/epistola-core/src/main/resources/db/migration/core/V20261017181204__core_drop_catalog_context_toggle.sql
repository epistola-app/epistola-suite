-- backup-restore-compatibility: backward=true forward=true
-- reason: Data-only cleanup of feature_toggles; no structural change to any backed-up table, so a
-- backup restores cleanly across this migration in either direction.
--
-- The `catalog-context` feature toggle is retired: in 2.0 every catalog page states the catalog
-- version it shows, always (docs/catalog-workspace-navigation.md). Delete any tenant override rows
-- so no dangling state remains.
DELETE FROM feature_toggles
WHERE feature_key = 'catalog-context';
