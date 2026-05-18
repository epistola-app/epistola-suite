-- Probe migration used only by MigrationLauncherIntegrationTest's validate
-- fail-fast case. It is added to spring.flyway.locations for a validate-mode
-- context only; validate never migrates, so this is never applied and the
-- shared Testcontainer database stays clean.
CREATE TABLE IF NOT EXISTS zz_pending_probe (id INT);
