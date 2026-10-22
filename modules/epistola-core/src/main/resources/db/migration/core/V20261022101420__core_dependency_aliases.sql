-- Content names another catalog by an alias (contract ADR 0003, amendment of 2026-10-22; ADR 0034).
--
-- A catalog's dependency list maps each alias to the catalog it stands for (registry, namespace,
-- key) and the release it pins; every reference into another catalog names the alias and a
-- resource key, never a registry or namespace. The alias is by default the depended-on catalog's
-- key, and this installation, which tells catalogs apart by key within a tenant until ADR 0034's
-- phase 4, uses exactly that: every declaration declares its catalog under its key, which is what
-- the stored references already name. Nothing a reference resolves to changes.
--
-- Both the declarations of each draft and those each release was cut with get the alias. The
-- backfill of a draft's declarations is not a change of the draft: its revision (the ETag of the
-- draft's settings) is left alone.

ALTER TABLE catalog_dependencies ADD COLUMN alias VARCHAR(50);

ALTER TABLE catalog_dependencies DISABLE TRIGGER trg_catalog_dependencies_version_revision;
UPDATE catalog_dependencies SET alias = dependency_key;
ALTER TABLE catalog_dependencies ENABLE TRIGGER trg_catalog_dependencies_version_revision;

ALTER TABLE catalog_dependencies ALTER COLUMN alias SET NOT NULL;
ALTER TABLE catalog_dependencies
    ADD CONSTRAINT uq_catalog_dependencies_alias UNIQUE (tenant_key, version_id, alias);

COMMENT ON COLUMN catalog_dependencies.alias IS
    'What the declaring version''s content names the dependency by (wire v8 catalogDependencies[].alias, '
    'contract ADR 0003). Unique per version; this installation declares every dependency under its key.';

ALTER TABLE catalog_release_dependencies ADD COLUMN alias VARCHAR(50);
UPDATE catalog_release_dependencies SET alias = dependency_key;
ALTER TABLE catalog_release_dependencies ALTER COLUMN alias SET NOT NULL;
ALTER TABLE catalog_release_dependencies
    ADD CONSTRAINT uq_catalog_release_dependencies_alias UNIQUE (tenant_key, catalog_id, version, alias);

COMMENT ON COLUMN catalog_release_dependencies.alias IS
    'What the release''s content names the dependency by (wire v8 catalogDependencies[].alias, contract ADR 0003).';
