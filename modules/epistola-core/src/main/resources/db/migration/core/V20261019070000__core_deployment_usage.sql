-- How much each deployed catalog version is used, so the people running an environment can see which
-- versions nobody asks for any more before undeploying them. Counted when a generation request through
-- the environment is accepted for that version; kept on the deployment, so undeploying resets it.
ALTER TABLE environment_catalog_deployments
    ADD COLUMN documents_requested BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_used_at TIMESTAMPTZ;

COMMENT ON COLUMN environment_catalog_deployments.documents_requested IS
    'Generation requests through this environment accepted for this version since it was deployed (flushed about once a minute)';
COMMENT ON COLUMN environment_catalog_deployments.last_used_at IS
    'When a generation request through this environment was last accepted for this version; NULL when none since it was deployed';
