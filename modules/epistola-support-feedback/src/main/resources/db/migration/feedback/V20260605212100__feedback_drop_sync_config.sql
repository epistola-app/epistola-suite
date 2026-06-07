-- Feedback sync moved from per-tenant GitHub config to installation-wide
-- epistola-hub sync (gated by epistola.support.enabled). The per-tenant
-- provider/PAT configuration table is no longer used; the inbound poll cursor
-- now lives in app_metadata under key 'feedback.sync.lastPolledAt'.
DROP TABLE IF EXISTS feedback_sync_config;
