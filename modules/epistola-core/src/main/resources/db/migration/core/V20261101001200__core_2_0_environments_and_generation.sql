-- The 2.0 upgrade, step 12 of 16 (docs/migrations.md, "The 2.0 chain"): environments serve catalog
-- versions, and generation history names what it rendered.

-- 1. Environments ---------------------------------------------------------------------------------
-- An environment serves catalog versions (contract ADR 0003, "Deploying"): any number of releases of
-- a catalog, and at most one draft of it, rendered live. One marked production serves only releases.
-- The 1.x activations, which pinned a template version per variant per environment, are deliberately
-- not converted (docs/catalog-release-model-v2.md, D1): every environment starts with nothing
-- deployed. Step 11 kept what each had activated.
ALTER TABLE environments ADD COLUMN production BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE environments
SET production = TRUE
WHERE id IN ('production', 'prod', 'prd') OR lower(name) IN ('production', 'prod', 'prd', 'productie');

COMMENT ON COLUMN environments.production IS 'Serves only releases: a draft is never deployed here.';

-- Optimistic locking for settings people change through the API (contract: ETag / If-Match on
-- updateTenant and updateEnvironment): a revision every update changing the row raises by one
-- (bump_content_revision, step 16).
ALTER TABLE tenants ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE environments ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN tenants.revision IS
    'Raised by one on every update that changes the row (bump_content_revision); a write naming the revision it read is refused when it differs.';
COMMENT ON COLUMN environments.revision IS
    'Raised by one on every update that changes the row (bump_content_revision); a write naming the revision it read is refused when it differs.';

CREATE TABLE environment_catalog_deployments (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_key          TENANT_KEY      NOT NULL,
    environment_key     ENVIRONMENT_KEY NOT NULL,
    catalog_id          UUID            NOT NULL,
    target_version_id   UUID            NOT NULL,
    deployed_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deployed_by         UUID            REFERENCES users (id) ON DELETE SET NULL,
    documents_requested BIGINT          NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMPTZ,
    CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, id),
    CONSTRAINT fk_environment_catalog_deployments_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    -- A deployed version cannot be deleted. Deferred, so deleting a tenant -- which removes both sides
    -- -- is not refused by the order Postgres cascades in; the commands that delete a version refuse
    -- first, naming the environment.
    CONSTRAINT fk_environment_catalog_deployments_version FOREIGN KEY (tenant_key, target_version_id, catalog_id)
        REFERENCES catalog_versions (tenant_key, version_id, catalog_id) DEFERRABLE INITIALLY DEFERRED
);

-- A version is served once per environment; at most one draft of a catalog (epistola_one_deployed_draft).
CREATE UNIQUE INDEX uq_environment_catalog_deployments_version
    ON environment_catalog_deployments (tenant_key, environment_key, target_version_id);
-- Which environments serve a version: the guard on deleting it.
CREATE INDEX idx_environment_catalog_deployments_version ON environment_catalog_deployments (tenant_key, target_version_id);

COMMENT ON TABLE environment_catalog_deployments IS
    'The catalog versions each environment serves: any number of releases per (environment, catalog), and at most one draft. Generation by environment picks the draft for a request naming no version, else the highest release it serves, or the highest release that satisfies the range or number a request names.';
COMMENT ON COLUMN environment_catalog_deployments.target_version_id IS
    'The version deployed: a release, or a draft rendered live (never on a production environment). A deployed draft stays deployed as the release it becomes.';
COMMENT ON COLUMN environment_catalog_deployments.documents_requested IS
    'Generation requests through this environment accepted for this version since it was deployed (flushed about once a minute)';
COMMENT ON COLUMN environment_catalog_deployments.last_used_at IS
    'When a generation request through this environment was last accepted for this version; NULL when none since it was deployed';

-- What an environment served over time, written in the same transaction as what it serves now. It
-- names the catalog as it was then, and has no foreign key to the version: the history outlives a
-- catalog's later rename and a version deleted later.
CREATE TABLE environment_deployment_log (
    tenant_key       TENANT_KEY      NOT NULL,
    id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    environment_key  ENVIRONMENT_KEY NOT NULL,
    registry         TEXT            NOT NULL,
    namespace        TEXT            NOT NULL,
    catalog_key      CATALOG_KEY     NOT NULL,
    action           VARCHAR(20)     NOT NULL,
    version          VARCHAR(128),
    previous_version VARCHAR(128),
    draft_label      VARCHAR(100),
    version_key      VARCHAR(36),
    changed_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    changed_by       UUID            REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT pk_environment_deployment_log PRIMARY KEY (tenant_key, id),
    CONSTRAINT fk_environment_deployment_log_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    -- Written as the 2.0 alphas left it after widening the column, so the schema is theirs to the letter.
    CONSTRAINT chk_environment_deployment_log_action CHECK (
        ((action)::text = ANY (ARRAY[('DEPLOYED'::character varying)::text, ('UNDEPLOYED'::character varying)::text])
            AND (version IS NOT NULL OR draft_label IS NOT NULL))
        OR ((action)::text = 'RELEASED' AND version IS NOT NULL)
    )
);

-- An environment's history, newest first.
CREATE INDEX idx_environment_deployment_log_environment
    ON environment_deployment_log (tenant_key, environment_key, changed_at DESC);

COMMENT ON TABLE environment_deployment_log IS
    'Every deploy and undeploy of a catalog version to an environment, and every deployed draft released, newest last. Written with environment_catalog_deployments in one transaction.';
COMMENT ON COLUMN environment_deployment_log.version IS 'The release deployed, undeployed or released; NULL for a draft deployed or undeployed.';
COMMENT ON COLUMN environment_deployment_log.previous_version IS
    'For a deploy that replaced the catalog''s other releases: the release it replaced. Null otherwise.';
COMMENT ON COLUMN environment_deployment_log.draft_label IS 'For a draft deployed, undeployed or released: the draft''s name then. NULL for a release.';
COMMENT ON COLUMN environment_deployment_log.version_key IS
    'The version''s public id (contract versionId) when the change was made: a draft''s, or the release''s.';

-- 2. Generation history ---------------------------------------------------------------------------
-- From 2.0 a request renders a catalog version: a release, or a draft an environment follows, live.
-- A request, its document and its collected result name it: by release number, or by the draft's id
-- and its name then, and by the version's id (contract versionId) either way. History recorded before
-- 2.0 names a template version (version_key) instead, and keeps it. None has a foreign key: a document
-- outlives the version it came from.
ALTER TABLE document_generation_requests
    ADD COLUMN release_version VARCHAR(128),
    ADD COLUMN draft_id VARCHAR(36),
    ADD COLUMN draft_label VARCHAR(100),
    ADD COLUMN catalog_version_id TEXT,
    -- How often the request was taken back from a node that stopped while rendering it; after a few
    -- recoveries it is failed with the reason, so a render that brings its node down is not handed on forever.
    ADD COLUMN recoveries INT NOT NULL DEFAULT 0,
    DROP CONSTRAINT chk_requests_version_or_environment,
    ADD CONSTRAINT chk_requests_render_source CHECK (
        version_key IS NOT NULL OR environment_key IS NOT NULL OR release_version IS NOT NULL OR draft_id IS NOT NULL
    ),
    ADD CONSTRAINT chk_requests_release_or_draft CHECK (release_version IS NULL OR draft_id IS NULL);

ALTER TABLE documents
    ALTER COLUMN version_key DROP NOT NULL,
    ADD COLUMN release_version VARCHAR(128),
    ADD COLUMN draft_id VARCHAR(36),
    ADD COLUMN draft_label VARCHAR(100),
    ADD COLUMN catalog_version_id TEXT,
    ADD CONSTRAINT chk_documents_render_source CHECK (version_key IS NOT NULL OR release_version IS NOT NULL OR draft_id IS NOT NULL);

ALTER TABLE generation_results
    ADD COLUMN release_version VARCHAR(128),
    ADD COLUMN draft_id VARCHAR(36),
    ADD COLUMN draft_label VARCHAR(100),
    ADD COLUMN catalog_version_id TEXT;

COMMENT ON COLUMN document_generation_requests.release_version IS 'The catalog release this request renders, bound when it was accepted. NULL for requests accepted before 2.0.';
COMMENT ON COLUMN document_generation_requests.draft_id IS 'The draft this request renders, live, bound when it was accepted through an environment override. NULL when it renders a release.';
COMMENT ON COLUMN document_generation_requests.draft_label IS 'The draft''s name when the request was accepted';
COMMENT ON COLUMN document_generation_requests.catalog_version_id IS 'Id (version_key) of the catalog version rendered: the draft''s (draft_id), or the release''s; null only for requests from before 2.0.';
COMMENT ON COLUMN document_generation_requests.recoveries IS 'Times the request was taken back from a node that stopped while rendering it (stale-job recovery); at the limit it fails instead of rendering again';
COMMENT ON COLUMN documents.version_key IS 'The template version this document was rendered from, for documents generated before 2.0. NULL from 2.0 on, where release_version says what was rendered.';
COMMENT ON COLUMN documents.release_version IS 'The catalog release this document was rendered from. NULL for documents generated before 2.0, which name a version_key instead.';
COMMENT ON COLUMN documents.draft_id IS 'The draft this document was rendered from, live, through an environment override. NULL when rendered from a release.';
COMMENT ON COLUMN documents.draft_label IS 'The draft''s name when the document was rendered';
COMMENT ON COLUMN documents.catalog_version_id IS 'Id (version_key) of the catalog version rendered, copied from its request; null only for documents from before 2.0.';
COMMENT ON COLUMN generation_results.release_version IS 'The catalog release the request rendered; null for results of requests accepted before 2.0.';
COMMENT ON COLUMN generation_results.draft_id IS 'The draft rendered, live, through an environment that follows it. NULL when rendered from a release.';
COMMENT ON COLUMN generation_results.draft_label IS 'The draft''s name when it was rendered';
COMMENT ON COLUMN generation_results.catalog_version_id IS 'Id (version_key) of the catalog version rendered, copied from its request.';

-- History names its catalog in full (contract ADR 0003): registry, namespace and key. A row whose
-- catalog no longer exists keeps its key and is named under `local` in its tenant's namespace,
-- which is what an authored catalog of that tenant was named on the move to 2.0 (step 1).
ALTER TABLE documents ADD COLUMN registry TEXT, ADD COLUMN namespace TEXT;
ALTER TABLE document_generation_requests ADD COLUMN registry TEXT, ADD COLUMN namespace TEXT;

UPDATE documents d SET registry = COALESCE(c.registry, 'local'),
                       namespace = COALESCE(c.namespace, CASE WHEN length(d.tenant_key) < 3 THEN rpad(d.tenant_key, 3, '0') ELSE d.tenant_key END)
FROM (SELECT DISTINCT tenant_key, catalog_key FROM documents) k
LEFT JOIN catalogs c ON c.tenant_key = k.tenant_key AND c.catalog_key = k.catalog_key
WHERE d.tenant_key = k.tenant_key AND d.catalog_key = k.catalog_key;

UPDATE document_generation_requests d SET registry = COALESCE(c.registry, 'local'),
                       namespace = COALESCE(c.namespace, CASE WHEN length(d.tenant_key) < 3 THEN rpad(d.tenant_key, 3, '0') ELSE d.tenant_key END)
FROM (SELECT DISTINCT tenant_key, catalog_key FROM document_generation_requests) k
LEFT JOIN catalogs c ON c.tenant_key = k.tenant_key AND c.catalog_key = k.catalog_key
WHERE d.tenant_key = k.tenant_key AND d.catalog_key = k.catalog_key;

ALTER TABLE documents ALTER COLUMN registry SET NOT NULL, ALTER COLUMN namespace SET NOT NULL;
ALTER TABLE document_generation_requests ALTER COLUMN registry SET NOT NULL, ALTER COLUMN namespace SET NOT NULL;

-- A collected result names its catalog as its request does (contract `GenerationResult`): from its
-- request while it is kept, else from its document.
ALTER TABLE generation_results ADD COLUMN registry TEXT, ADD COLUMN namespace TEXT, ADD COLUMN catalog_key TEXT;

UPDATE generation_results r
SET registry = q.registry, namespace = q.namespace, catalog_key = q.catalog_key
FROM document_generation_requests q
WHERE q.id = r.request_id AND r.catalog_key IS NULL;

UPDATE generation_results r
SET registry = d.registry, namespace = d.namespace, catalog_key = d.catalog_key
FROM documents d
WHERE d.id = r.document_id AND d.tenant_key = r.tenant_key AND r.catalog_key IS NULL;

COMMENT ON COLUMN generation_results.registry IS 'Registry of the catalog the result''s template was rendered from, copied from its request.';
COMMENT ON COLUMN generation_results.namespace IS 'Namespace of that catalog, copied from its request.';
COMMENT ON COLUMN generation_results.catalog_key IS 'Key of that catalog, copied from its request.';
