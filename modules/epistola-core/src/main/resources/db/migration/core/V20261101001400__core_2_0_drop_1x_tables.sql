-- The 2.0 upgrade, step 14 of 16 (docs/migrations.md, "The 2.0 chain"): the 1.x tables go.
--
-- Destructive by decision, for the 2.0 upgrade (docs/migrations.md, ADR 0035): what is worth keeping
-- was converted by the steps before, and nothing in 2.0 reads these.
-- - The content tables (themes, code lists, attributes, fonts, images, stencils, templates): every
--   row became JSON storage (steps 6 to 8), with its identity, revision, authors and timestamps.
-- - The version tables and the activations: each working copy and what each last published are
--   drafts (steps 7 to 9); the rest is summarised in `upgrade-1x-history` (step 11).
-- - The 1.x releases: a number, a fingerprint and notes, without content, so nothing could render,
--   export or deploy one; each draft names its catalog's last as its base (step 4).
-- - The shared content store: every blob whose owner exists moved (step 5); what is left names
--   nothing that exists any more (a document deleted before 1.x moved its blob, an asset removed since).
-- The quality and load-test modules re-pointed their references before (their 2.0 migrations).

DO $$
DECLARE
    total INTEGER;
    orphaned INTEGER;
BEGIN
    SELECT count(*) INTO total FROM content_store;
    SELECT count(*) INTO orphaned FROM content_store cs
    WHERE NOT EXISTS (SELECT 1 FROM document_content dc WHERE dc.key = cs.key)
      AND NOT EXISTS (
          SELECT 1 FROM assets a
          WHERE cs.key = 'assets/' || a.tenant_key || '/' || a.id
            AND EXISTS (SELECT 1 FROM asset_content ac WHERE ac.content_hash = CAST(a.content_hash AS CHAR(64)))
      );
    IF total > 0 THEN
        RAISE NOTICE 'Dropping content_store: % row(s), of which % named nothing that exists any more', total, orphaned;
    END IF;
END $$;

-- A tenant's house theme is a theme's identity, whichever version holds it.
ALTER TABLE tenants DROP CONSTRAINT fk_tenants_default_theme;
ALTER TABLE tenants ADD CONSTRAINT fk_tenants_default_theme
    FOREIGN KEY (id, default_theme_resource_id) REFERENCES catalog_resources (tenant_key, resource_id);

DROP TABLE environment_activations, template_versions, contract_versions, template_variants, document_templates,
    stencil_versions, stencils, variant_attribute_definitions, code_list_entries, code_lists, font_variants, fonts,
    assets, themes, content_store, catalog_releases;
-- Their identity triggers' function: a version's rows keep identities through
-- sync_draft_resource_identity (step 16).
DROP FUNCTION sync_catalog_resource_identity();

-- A catalog's pointers at its 1.x releases, which named rows that are gone: a release is a row of
-- catalog_versions now.
ALTER TABLE catalogs
    DROP COLUMN installed_release_version,
    DROP COLUMN installed_fingerprint,
    DROP COLUMN installed_resource_fingerprints,
    DROP COLUMN installed_at,
    DROP COLUMN released_version,
    DROP COLUMN released_fingerprint,
    DROP COLUMN released_at;

-- The `catalog-context` feature toggle is retired: in 2.0 every catalog page states the catalog
-- version it shows, always (docs/catalog-workspace-navigation.md). So are the toggles of two features
-- withdrawn before 2.0: tenant backups (#1088) and the compatibility check, with tenant snapshots (#1219).
DELETE FROM feature_toggles WHERE feature_key IN ('catalog-context', 'support-backups', 'support-compatibility-check');

-- Tenant backups were withdrawn before 2.0 (#1088): nothing reads their table any more, and the
-- artifacts it holds are restorable by no version of 2.0. Its migration stays where it is, in core.
DROP TABLE tenant_backups;
