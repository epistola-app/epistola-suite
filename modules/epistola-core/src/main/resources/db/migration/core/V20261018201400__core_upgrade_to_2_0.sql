-- The catalogs of a 1.x installation, queued for what 2.0 needs of them and SQL cannot do; and the
-- alpha toggle 2.0 retires.

-- The catalogs of a 1.x installation, queued for what 2.0 needs of them and SQL cannot do: which
-- catalogs a catalog references is read from its content by application code, and a release is cut
-- or recorded by it. For an authored catalog:
--
-- - Declarations: a 1.x catalog references other catalogs (the system catalog's fonts and
--   attributes, a shared theme) without declaring them, and 2.0 refuses to release it until it does
--   (contract ADR 0003).
-- - A first release: no 1.x release kept its content, so nothing could render. Each catalog gets one,
--   cut from what was last published, numbered the patch after its highest 1.x release (or 1.0.0),
--   and deployed nowhere.
--
-- For a subscribed catalog with an installed release: that release recorded as one it can render
-- from, with the content the install put in place. 1.x kept none, so its templates could not render.
--
-- CatalogUpgradeRunner does both and removes the catalog from the queue; a catalog it cannot do yet
-- (a dependency not released) stays, with the reason. A catalog created under 2.0 is never queued.
CREATE TABLE catalog_upgrade_queue (
    tenant_key TENANT_KEY NOT NULL,
    catalog_id UUID       NOT NULL,
    last_error TEXT,
    continues_after VARCHAR(50),
    PRIMARY KEY (tenant_key, catalog_id),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE
);

COMMENT ON TABLE catalog_upgrade_queue IS
    'Catalogs from before 2.0 still to be given what 2.0 needs (declarations and a first release, or a recorded installed release); emptied by CatalogUpgradeRunner.';
COMMENT ON COLUMN catalog_upgrade_queue.last_error IS
    'Why the last attempt did not finish; the runner tries again.';
COMMENT ON COLUMN catalog_upgrade_queue.continues_after IS
    'The highest SemVer release of the catalog in 1.x; its first 2.0 release is the patch after it, so numbering does not go back.';

INSERT INTO catalog_upgrade_queue (tenant_key, catalog_id, continues_after)
SELECT c.tenant_key, c.catalog_id, latest.version
FROM catalogs c
LEFT JOIN LATERAL (
    SELECT r.version
    FROM catalog_releases r
    WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.version_major IS NOT NULL
    ORDER BY r.version_major DESC, r.version_minor DESC, r.version_patch DESC
    LIMIT 1
) latest ON TRUE
WHERE c.type = 'AUTHORED' OR (c.type = 'SUBSCRIBED' AND c.installed_release_version IS NOT NULL);

-- The 1.x releases go. A 1.x release is a number, a fingerprint and notes: it kept no content, so
-- nothing can render, export or deploy it, and Exchange, the one place its number could matter, was
-- not in use. Each catalog's first 2.0 release (above) replaces them. A deliberate exception to
-- "no destructive migrations" (docs/migrations.md), decided for the 2.0 upgrade. Every row in the
-- table is one: 2.0 has written none yet.
DELETE FROM catalog_releases;

-- And with them the catalog's pointer at its current release, which named one of them. The first 2.0
-- release sets it again.
UPDATE catalogs
SET released_version = NULL, released_fingerprint = NULL, released_at = NULL
WHERE released_version IS NOT NULL OR released_fingerprint IS NOT NULL OR released_at IS NOT NULL;

-- The `catalog-context` feature toggle is retired: in 2.0 every catalog page states the catalog
-- version it shows, always (docs/catalog-workspace-navigation.md). Delete any tenant override rows
-- so no dangling state remains.
DELETE FROM feature_toggles
WHERE feature_key = 'catalog-context';

-- The 1.x versions -------------------------------------------------------------------------------
-- 1.x kept versions per template variant, data contract and stencil, and activated template versions
-- per environment. 2.0 keeps none of that: the working copy holds what each author was working on
-- (the working-copy migration), and each catalog's first 2.0 release is cut from what was last
-- published. So the tables go, and two things are kept:
--
-- 1. What the first release is cut from, until it is: each variant's, data contract's and stencil's
--    latest published content, in v8 already (the template-content migration converted it). The
--    upgrade removes a catalog's rows once it has released it, so the table ends up empty.
-- 2. A summary of the history, as one record in app_metadata (`upgrade-1x-history`): every version's
--    number, status and dates, and what each environment had activated, so documents and load-test
--    runs that name a 1.x version number can still be traced. Content of older versions is not kept.

CREATE TABLE catalog_upgrade_published (
    tenant_key  TENANT_KEY  NOT NULL,
    resource_id UUID        NOT NULL,
    kind        VARCHAR(10) NOT NULL,
    part        TEXT        NOT NULL DEFAULT '',
    payload     JSONB       NOT NULL,
    PRIMARY KEY (tenant_key, resource_id, kind, part),
    FOREIGN KEY (tenant_key, resource_id) REFERENCES catalog_resources (tenant_key, resource_id) ON DELETE CASCADE,
    CONSTRAINT chk_catalog_upgrade_published_kind CHECK (kind IN ('variant', 'contract', 'stencil'))
);

COMMENT ON TABLE catalog_upgrade_published IS
    'What each 1.x resource last published, which the 2.0 upgrade cuts a catalog''s first release from; emptied per catalog by CatalogUpgradeRunner.';
COMMENT ON COLUMN catalog_upgrade_published.kind IS
    '''variant'': a template variant''s model (part = the variant key); ''contract'': a template''s data contract; ''stencil'': a stencil''s content.';
COMMENT ON COLUMN catalog_upgrade_published.payload IS
    'variant: {templateModel}; contract: {dataModel, dataExamples}; stencil: {content, parameterSchema}.';

INSERT INTO catalog_upgrade_published (tenant_key, resource_id, kind, part, payload)
SELECT DISTINCT ON (tv.tenant_key, tv.template_resource_id, tv.variant_key)
       tv.tenant_key, tv.template_resource_id, 'variant', tv.variant_key, jsonb_build_object('templateModel', tv.template_model)
FROM template_versions tv
WHERE tv.status = 'published'
ORDER BY tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.id DESC;

INSERT INTO catalog_upgrade_published (tenant_key, resource_id, kind, payload)
SELECT DISTINCT ON (cv.tenant_key, cv.template_resource_id)
       cv.tenant_key, cv.template_resource_id, 'contract',
       jsonb_build_object('dataModel', cv.data_model, 'dataExamples', COALESCE(cv.data_examples, '[]'::jsonb))
FROM contract_versions cv
WHERE cv.status = 'published'
ORDER BY cv.tenant_key, cv.template_resource_id, cv.id DESC;

INSERT INTO catalog_upgrade_published (tenant_key, resource_id, kind, payload)
SELECT DISTINCT ON (sv.tenant_key, sv.stencil_resource_id)
       sv.tenant_key, sv.stencil_resource_id, 'stencil',
       jsonb_build_object('content', sv.content, 'parameterSchema', sv.parameter_schema)
FROM stencil_versions sv
WHERE sv.status = 'published'
ORDER BY sv.tenant_key, sv.stencil_resource_id, sv.id DESC;

INSERT INTO app_metadata (key, value)
SELECT 'upgrade-1x-history', jsonb_build_object(
    'description', 'The template, data contract and stencil versions of 1.x and what each environment had activated, as numbers and dates; their content is not kept.',
    'templateVersions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', tv.tenant_key, 'catalog', c.catalog_key, 'template', r.resource_key, 'variant', tv.variant_key,
                   'version', tv.id, 'status', tv.status, 'createdAt', tv.created_at, 'publishedAt', tv.published_at, 'archivedAt', tv.archived_at)
                   ORDER BY tv.tenant_key, c.catalog_key, r.resource_key, tv.variant_key, tv.id)
        FROM template_versions tv
        JOIN catalog_resources r ON r.tenant_key = tv.tenant_key AND r.resource_id = tv.template_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb),
    'contractVersions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', cv.tenant_key, 'catalog', c.catalog_key, 'template', r.resource_key,
                   'version', cv.id, 'status', cv.status, 'createdAt', cv.created_at, 'publishedAt', cv.published_at)
                   ORDER BY cv.tenant_key, c.catalog_key, r.resource_key, cv.id)
        FROM contract_versions cv
        JOIN catalog_resources r ON r.tenant_key = cv.tenant_key AND r.resource_id = cv.template_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb),
    'stencilVersions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', sv.tenant_key, 'catalog', c.catalog_key, 'stencil', r.resource_key,
                   'version', sv.id, 'status', sv.status, 'createdAt', sv.created_at, 'publishedAt', sv.published_at, 'archivedAt', sv.archived_at)
                   ORDER BY sv.tenant_key, c.catalog_key, r.resource_key, sv.id)
        FROM stencil_versions sv
        JOIN catalog_resources r ON r.tenant_key = sv.tenant_key AND r.resource_id = sv.stencil_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb),
    'activations', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                   'tenant', a.tenant_key, 'environment', a.environment_key, 'catalog', c.catalog_key, 'template', r.resource_key,
                   'variant', a.variant_key, 'version', a.version_key, 'activatedAt', a.activated_at)
                   ORDER BY a.tenant_key, a.environment_key, c.catalog_key, r.resource_key, a.variant_key)
        FROM environment_activations a
        JOIN catalog_resources r ON r.tenant_key = a.tenant_key AND r.resource_id = a.template_resource_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id), '[]'::jsonb))
WHERE EXISTS (SELECT 1 FROM template_versions) OR EXISTS (SELECT 1 FROM contract_versions)
   OR EXISTS (SELECT 1 FROM stencil_versions) OR EXISTS (SELECT 1 FROM environment_activations);

-- Destructive by decision, like the 1.x releases above (docs/migrations.md): what is worth keeping is
-- kept above, and nothing in 2.0 reads these tables. The load-test module dropped its reference to
-- template_versions first (V20261018200800). The trigger that removed a deleted variant's versions
-- and activations goes with them.
DROP TRIGGER trg_template_variants_history ON template_variants;
DROP FUNCTION variant_history_follow_variant();
DROP TABLE environment_activations;
DROP TABLE template_versions;
DROP TABLE contract_versions;
DROP TABLE stencil_versions;
