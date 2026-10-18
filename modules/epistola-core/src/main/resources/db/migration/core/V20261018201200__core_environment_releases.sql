-- An environment uses releases of catalogs, replacing environment_activations, which pinned a template
-- version per variant per environment. Activations are deliberately not converted (docs/catalog-
-- release-model-v2.md, D1): every environment starts with nothing deployed, and environment_activations
-- is left unread until it is dropped with the version tables.
--
-- - An environment serves any number of releases of a catalog: a request through it gets the highest,
--   or the highest that satisfies its version selector.
-- - Its history keeps every change, for showing what changed, when and by whom.
-- - An override names what it uses instead of the highest: one of the releases it serves, or a draft,
--   live. An environment marked production uses only published versions.

-- 1. The production mark ----------------------------------------------------------------------
ALTER TABLE environments ADD COLUMN production BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE environments
SET production = TRUE
WHERE id IN ('production', 'prod', 'prd') OR lower(name) IN ('production', 'prod', 'prd', 'productie');

COMMENT ON COLUMN environments.production IS
    'Serves only published versions: an override here names a release it serves, never a draft.';

-- 2. What an environment serves ----------------------------------------------------------------
CREATE TABLE environment_catalog_deployments (
    tenant_key          TENANT_KEY      NOT NULL,
    environment_key     ENVIRONMENT_KEY NOT NULL,
    version             VARCHAR(50)     NOT NULL,
    deployed_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deployed_by         UUID            REFERENCES users (id) ON DELETE SET NULL,
    catalog_id          UUID            NOT NULL,
    documents_requested BIGINT          NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMPTZ,
    CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, environment_key, catalog_id, version),
    CONSTRAINT fk_environment_catalog_deployments_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    -- A deployed release cannot be deleted. Deferred, like the release keys before it, so deleting a
    -- tenant -- which removes both sides -- is not refused by the order Postgres cascades in. The
    -- commands that delete or forget a release refuse first, with a message naming the environment.
    CONSTRAINT fk_environment_catalog_deployments_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases (tenant_key, catalog_id, version) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE environment_catalog_deployments IS
    'The catalog releases each environment serves: any number per (environment, catalog). Generation by environment picks the highest it serves, or the highest that satisfies the request''s version selector, when a request is accepted.';

COMMENT ON COLUMN environment_catalog_deployments.documents_requested IS
    'Generation requests through this environment accepted for this version since it was deployed (flushed about once a minute)';
COMMENT ON COLUMN environment_catalog_deployments.last_used_at IS
    'When a generation request through this environment was last accepted for this version; NULL when none since it was deployed';

-- "Which environments serve this release" -- the guard on deleting or forgetting it.
CREATE INDEX idx_environment_catalog_deployments_release ON environment_catalog_deployments (tenant_key, catalog_id, version);

-- 3. What it served over time ---------------------------------------------------------------------
-- environment_catalog_deployments holds what an environment serves now. This log keeps every change, so
-- an environment can show what changed, when and by whom, and offer to deploy an earlier release
-- again. It is written in the same transaction as the current state, so the two cannot disagree. It
-- names the catalog as it was then, and has no foreign key to the release: the history outlives a
-- catalog's later rename and a release deleted later.
CREATE TABLE environment_deployment_log (
    tenant_key       TENANT_KEY      NOT NULL,
    id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    environment_key  ENVIRONMENT_KEY NOT NULL,
    catalog_key      CATALOG_KEY     NOT NULL,
    action           VARCHAR(20)     NOT NULL,
    version          VARCHAR(50),
    previous_version VARCHAR(50),
    changed_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    changed_by       UUID            REFERENCES users (id) ON DELETE SET NULL,
    registry         TEXT            NOT NULL,
    namespace        TEXT            NOT NULL,
    draft_label      VARCHAR(100),
    CONSTRAINT pk_environment_deployment_log PRIMARY KEY (tenant_key, id),
    CONSTRAINT fk_environment_deployment_log_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    CONSTRAINT chk_environment_deployment_log_action CHECK (
        (action IN ('DEPLOYED', 'UNDEPLOYED') AND version IS NOT NULL)
        OR (action IN ('OVERRIDE_SET', 'OVERRIDE_CLEARED') AND (version IS NOT NULL OR draft_label IS NOT NULL))
    )
);

COMMENT ON TABLE environment_deployment_log IS
    'Every deploy and undeploy of a catalog release to an environment, newest last. Written with environment_catalog_deployments in one transaction.';

COMMENT ON COLUMN environment_deployment_log.version IS
    'The release deployed or undeployed, or the release an override used.';
COMMENT ON COLUMN environment_deployment_log.previous_version IS
    'For a deploy that replaced the catalog''s other releases: the release it replaced. Null otherwise.';
COMMENT ON COLUMN environment_deployment_log.draft_label IS
    'For an override naming a draft: the draft''s name when the override was set or cleared. NULL otherwise.';

-- An environment's history, newest first.
CREATE INDEX idx_environment_deployment_log_environment
    ON environment_deployment_log (tenant_key, environment_key, changed_at DESC);

-- 4. Overrides --------------------------------------------------------------------------------
CREATE TABLE environment_catalog_overrides (
    tenant_key      TENANT_KEY      NOT NULL,
    environment_key ENVIRONMENT_KEY NOT NULL,
    catalog_id      UUID            NOT NULL,
    version         VARCHAR(50),
    draft_id        UUID,
    set_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    set_by          UUID REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT pk_environment_catalog_overrides PRIMARY KEY (tenant_key, environment_key, catalog_id),
    CONSTRAINT chk_environment_catalog_overrides_one CHECK ((version IS NULL) <> (draft_id IS NULL)),
    CONSTRAINT fk_environment_catalog_overrides_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    -- A release override names a release the environment serves; undeploying it ends the override.
    CONSTRAINT fk_environment_catalog_overrides_deployment FOREIGN KEY (tenant_key, environment_key, catalog_id, version)
        REFERENCES environment_catalog_deployments (tenant_key, environment_key, catalog_id, version) ON DELETE CASCADE,
    -- A draft override names a draft of the same catalog; discarding the draft ends the override.
    CONSTRAINT fk_environment_catalog_overrides_draft FOREIGN KEY (tenant_key, draft_id, catalog_id)
        REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE
);

COMMENT ON TABLE environment_catalog_overrides IS
    'What an environment uses as its latest version of a catalog, instead of the highest release it serves: one of those releases, or a draft (live). A request naming an exact release is not affected.';
COMMENT ON COLUMN environment_catalog_overrides.version IS 'The release used, one the environment serves; NULL for a draft override';
COMMENT ON COLUMN environment_catalog_overrides.draft_id IS 'The draft used, live; NULL for a release override. Never on a production environment';
COMMENT ON COLUMN environment_catalog_overrides.set_at IS 'When the override was set';
COMMENT ON COLUMN environment_catalog_overrides.set_by IS 'Who set it (NULL if the user was deleted)';

