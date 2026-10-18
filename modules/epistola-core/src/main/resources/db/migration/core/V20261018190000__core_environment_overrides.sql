-- An environment can override which version of a catalog it uses as its latest.
--
-- Until now an environment used the highest release it serves (or the highest satisfying a request's
-- version selector). An override names what it uses instead, for every request that does not ask
-- for an exact release:
--
-- - one of the releases it serves: a rollback, or holding back a newer release;
-- - a draft of the catalog, live: what is changed in it is what documents get, with no release.
--
-- An environment marked production serves only published versions, so its overrides name releases.
-- Environments already named or keyed as production are marked, so they are protected from the
-- start.

-- 1. The production mark ----------------------------------------------------------------------
ALTER TABLE environments ADD COLUMN production BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE environments
SET production = TRUE
WHERE id IN ('production', 'prod', 'prd') OR lower(name) IN ('production', 'prod', 'prd', 'productie');

COMMENT ON COLUMN environments.production IS
    'Serves only published versions: an override here names a release it serves, never a draft.';

-- 2. Overrides --------------------------------------------------------------------------------
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

-- 3. Requests and documents rendered from a draft --------------------------------------------
-- No foreign key: a document outlives the draft it came from. The label is the draft's name when it
-- was rendered ("Next version" for the next version), so the document still says where it came from.
ALTER TABLE document_generation_requests
    ADD COLUMN draft_id UUID,
    ADD COLUMN draft_label VARCHAR(100);

ALTER TABLE document_generation_requests
    DROP CONSTRAINT chk_requests_render_source,
    ADD CONSTRAINT chk_requests_render_source CHECK (
        version_key IS NOT NULL OR environment_key IS NOT NULL OR release_version IS NOT NULL OR draft_id IS NOT NULL
    ),
    ADD CONSTRAINT chk_requests_release_or_draft CHECK (release_version IS NULL OR draft_id IS NULL);

COMMENT ON COLUMN document_generation_requests.draft_id IS
    'The draft this request renders, live, bound when it was accepted through an environment override. NULL when it renders a release.';
COMMENT ON COLUMN document_generation_requests.draft_label IS 'The draft''s name when the request was accepted';

ALTER TABLE documents
    ADD COLUMN draft_id UUID,
    ADD COLUMN draft_label VARCHAR(100);

ALTER TABLE documents
    DROP CONSTRAINT chk_documents_render_source,
    ADD CONSTRAINT chk_documents_render_source CHECK (version_key IS NOT NULL OR release_version IS NOT NULL OR draft_id IS NOT NULL);

COMMENT ON COLUMN documents.draft_id IS 'The draft this document was rendered from, live, through an environment override. NULL when rendered from a release.';
COMMENT ON COLUMN documents.draft_label IS 'The draft''s name when the document was rendered';

-- The collect feed says the same of each result.
ALTER TABLE generation_results
    ADD COLUMN draft_id UUID,
    ADD COLUMN draft_label VARCHAR(100);

COMMENT ON COLUMN generation_results.draft_id IS 'The draft rendered, live, through an environment that follows it. NULL when rendered from a release.';
COMMENT ON COLUMN generation_results.draft_label IS 'The draft''s name when it was rendered';

-- 4. The environment's history records overrides --------------------------------------------
-- Setting an override names the release it uses (`version`) or the draft (`draft_label`, its name
-- then); clearing one names what it used.
ALTER TABLE environment_deployment_log ADD COLUMN draft_label VARCHAR(100);

ALTER TABLE environment_deployment_log DROP CONSTRAINT chk_environment_deployment_log_action;
ALTER TABLE environment_deployment_log ADD CONSTRAINT chk_environment_deployment_log_action CHECK (
    (action IN ('DEPLOYED', 'UNDEPLOYED') AND version IS NOT NULL)
    OR (action IN ('OVERRIDE_SET', 'OVERRIDE_CLEARED') AND (version IS NOT NULL OR draft_label IS NOT NULL))
);

COMMENT ON COLUMN environment_deployment_log.draft_label IS
    'For an override naming a draft: the draft''s name when the override was set or cleared. NULL otherwise.';
