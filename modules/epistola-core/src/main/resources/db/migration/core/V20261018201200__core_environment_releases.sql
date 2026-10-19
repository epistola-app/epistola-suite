-- An environment uses releases of catalogs, replacing environment_activations, which pinned a template
-- version per variant per environment. Activations are deliberately not converted (docs/catalog-
-- release-model-v2.md, D1): every environment starts with nothing deployed, and environment_activations
-- is left unread until it is dropped with the version tables.
--
-- - An environment serves catalog versions (contract ADR 0003, "Deploying"): any number of releases of
--   a catalog, and at most one draft of it, rendered live. A request through it that names no version
--   gets the draft, else the highest release; one naming a range or a number, the highest release
--   that satisfies it. An environment marked production serves only releases.
-- - Its history keeps every change, for showing what changed, when and by whom.

-- 1. The production mark ----------------------------------------------------------------------
ALTER TABLE environments ADD COLUMN production BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE environments
SET production = TRUE
WHERE id IN ('production', 'prod', 'prd') OR lower(name) IN ('production', 'prod', 'prd', 'productie');

COMMENT ON COLUMN environments.production IS
    'Serves only releases: a draft is never deployed here.';

-- 2. What an environment serves ----------------------------------------------------------------
CREATE TABLE environment_catalog_deployments (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_key          TENANT_KEY      NOT NULL,
    environment_key     ENVIRONMENT_KEY NOT NULL,
    version             VARCHAR(50),
    draft_id            UUID,
    deployed_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deployed_by         UUID            REFERENCES users (id) ON DELETE SET NULL,
    catalog_id          UUID            NOT NULL,
    documents_requested BIGINT          NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMPTZ,
    CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, id),
    -- A release by its number, or a draft by its version: exactly one.
    CONSTRAINT chk_environment_catalog_deployments_one CHECK ((version IS NULL) <> (draft_id IS NULL)),
    CONSTRAINT fk_environment_catalog_deployments_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    -- A deployed release cannot be deleted. Deferred, like the release keys before it, so deleting a
    -- tenant -- which removes both sides -- is not refused by the order Postgres cascades in. The
    -- commands that delete or forget a release refuse first, with a message naming the environment.
    CONSTRAINT fk_environment_catalog_deployments_release FOREIGN KEY (tenant_key, catalog_id, version)
        REFERENCES catalog_releases (tenant_key, catalog_id, version) DEFERRABLE INITIALLY DEFERRED,
    -- A deployed draft is a draft of the same catalog. Releasing it turns the row into a deployment
    -- of the release, and discarding it ends the deployment, both logged by the command first; the
    -- cascade only backs that up.
    CONSTRAINT fk_environment_catalog_deployments_draft FOREIGN KEY (tenant_key, draft_id, catalog_id)
        REFERENCES catalog_versions (tenant_key, version_id, catalog_id) ON DELETE CASCADE
);

-- A release is served once per environment; a catalog has at most one draft deployed per environment.
CREATE UNIQUE INDEX uq_environment_catalog_deployments_release
    ON environment_catalog_deployments (tenant_key, environment_key, catalog_id, version) WHERE version IS NOT NULL;
CREATE UNIQUE INDEX uq_environment_catalog_deployments_draft
    ON environment_catalog_deployments (tenant_key, environment_key, catalog_id) WHERE draft_id IS NOT NULL;

COMMENT ON TABLE environment_catalog_deployments IS
    'The catalog versions each environment serves: any number of releases per (environment, catalog), and at most one draft. Generation by environment picks the draft for a request naming no version, else the highest release it serves, or the highest that satisfies the request''s version range, when a request is accepted.';

COMMENT ON COLUMN environment_catalog_deployments.version IS 'The release deployed; NULL for a deployed draft';
COMMENT ON COLUMN environment_catalog_deployments.draft_id IS 'The draft deployed, rendered live; NULL for a release. Never on a production environment';

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
    version_key      VARCHAR(16),
    CONSTRAINT pk_environment_deployment_log PRIMARY KEY (tenant_key, id),
    CONSTRAINT fk_environment_deployment_log_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    CONSTRAINT chk_environment_deployment_log_action CHECK (
        (action IN ('DEPLOYED', 'UNDEPLOYED') AND (version IS NOT NULL OR draft_label IS NOT NULL))
        OR (action = 'RELEASED' AND version IS NOT NULL)
    )
);

COMMENT ON TABLE environment_deployment_log IS
    'Every deploy and undeploy of a catalog version to an environment, and every deployed draft released, newest last. Written with environment_catalog_deployments in one transaction.';

COMMENT ON COLUMN environment_deployment_log.version IS
    'The release deployed, undeployed or released; NULL for a draft deployed or undeployed.';
COMMENT ON COLUMN environment_deployment_log.previous_version IS
    'For a deploy that replaced the catalog''s other releases: the release it replaced. Null otherwise.';
COMMENT ON COLUMN environment_deployment_log.draft_label IS
    'For a draft deployed, undeployed or released: the draft''s name then. NULL for a release.';
COMMENT ON COLUMN environment_deployment_log.version_key IS
    'The version''s public id (contract versionId) when the change was made: a draft''s, or the release''s.';

-- An environment's history, newest first.
CREATE INDEX idx_environment_deployment_log_environment
    ON environment_deployment_log (tenant_key, environment_key, changed_at DESC);
