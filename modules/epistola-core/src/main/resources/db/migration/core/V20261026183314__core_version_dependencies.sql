-- ADR 0036, step (c): one table for what a version depends on.
--
-- A draft's declarations lived in catalog_dependencies (by the draft's row, possibly naming another
-- catalog's draft), a release's pins in catalog_release_dependencies (by the release's number). Every
-- version is a row now (step (a)), so both are rows of catalog_version_dependencies, by the version
-- that declares them. A dependency is named by registry, namespace and key, as the contract names it
-- (it may be a catalog this installation does not have), pinned by number and hash, and, when the
-- version depended on is here, by its row: a draft that declaration names, or the release it pins.
-- A declaration on another catalog's draft names that row, so when the draft is released it names
-- the release (contract ADR 0002; ADR 0036). catalog_release_dependencies stays until step (e).

ALTER TABLE catalog_dependencies RENAME TO catalog_version_dependencies;
ALTER TABLE catalog_version_dependencies RENAME COLUMN dependency_version_id TO target_version_id;
ALTER TABLE catalog_version_dependencies RENAME CONSTRAINT catalog_dependencies_pkey TO catalog_version_dependencies_pkey;
ALTER TABLE catalog_version_dependencies RENAME CONSTRAINT uq_catalog_dependencies_alias TO uq_catalog_version_dependencies_alias;
ALTER TABLE catalog_version_dependencies RENAME CONSTRAINT chk_catalog_dependencies_locked TO chk_catalog_version_dependencies_pinned;
ALTER TABLE catalog_version_dependencies RENAME CONSTRAINT fk_catalog_dependencies_version TO fk_catalog_version_dependencies_version;
ALTER TABLE catalog_version_dependencies RENAME CONSTRAINT catalog_dependencies_tenant_key_catalog_id_fkey TO fk_catalog_version_dependencies_catalog;
ALTER TRIGGER trg_catalog_dependencies_version_revision ON catalog_version_dependencies RENAME TO trg_catalog_version_dependencies_revision;

-- A pin names its release's row as well as its number: the two are no longer exclusive.
ALTER TABLE catalog_version_dependencies DROP CONSTRAINT chk_catalog_dependencies_release_or_draft;
-- The version depended on is lineage of the declaration, not its owner: a release deleted under a
-- draft's pin leaves the pin by number (it no longer holds, as before), and a named draft is not
-- discarded while a declaration names it (CatalogDependencyStore.namedBy).
ALTER TABLE catalog_version_dependencies DROP CONSTRAINT fk_catalog_dependencies_dependency_version;
ALTER TABLE catalog_version_dependencies ADD CONSTRAINT fk_catalog_version_dependencies_target
    FOREIGN KEY (tenant_key, target_version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE SET NULL (target_version_id);
CREATE INDEX idx_catalog_version_dependencies_target ON catalog_version_dependencies (tenant_key, target_version_id)
    WHERE target_version_id IS NOT NULL;

COMMENT ON TABLE catalog_version_dependencies IS
    'What each catalog version depends on (ADR 0036): a draft''s declarations and a release''s pins, by the version that declares them.';
COMMENT ON COLUMN catalog_version_dependencies.target_version_id IS
    'The version depended on, when it is here: a draft a declaration names, or the release it pins.';

-- A release's pins become its row's declarations, each naming the pinned release's row when it is here.
INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position,
                                          release_version, release_fingerprint, target_version_id)
SELECT d.tenant_key, d.catalog_id, v.version_id, d.registry, d.namespace, d.dependency_key, d.alias, d.position,
       d.release_version, d.release_fingerprint,
       (SELECT t.version_id FROM catalogs tc JOIN catalog_versions t ON t.tenant_key = tc.tenant_key AND t.catalog_id = tc.catalog_id
        WHERE tc.tenant_key = d.tenant_key AND tc.registry = d.registry AND tc.namespace = d.namespace AND tc.catalog_key = d.dependency_key
          AND t.state = 'released' AND t.version = d.release_version)
FROM catalog_release_dependencies d
JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.catalog_id = d.catalog_id AND v.version = d.version AND v.state = 'released';

-- A draft's pins name the pinned release's row too.
UPDATE catalog_version_dependencies d SET target_version_id = t.version_id
FROM catalogs tc JOIN catalog_versions t ON t.tenant_key = tc.tenant_key AND t.catalog_id = tc.catalog_id
WHERE d.target_version_id IS NULL AND d.release_version IS NOT NULL
  AND tc.tenant_key = d.tenant_key AND tc.registry = d.registry AND tc.namespace = d.namespace AND tc.catalog_key = d.dependency_key
  AND t.state = 'released' AND t.version = d.release_version;

-- Starting a draft copies the version's declarations, as before.
CREATE OR REPLACE FUNCTION copy_catalog_version_content(p_tenant TEXT, p_from UUID, p_to UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'resource_ready_marks', 'code_list_refreshes', 'catalog_version_dependencies']
    LOOP
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) INTO columns
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t AND is_generated = 'NEVER' AND column_name <> 'version_id';
        EXECUTE format('INSERT INTO %I (%s, version_id) SELECT %s, $2 FROM %I WHERE tenant_key = $1 AND version_id = $3', t, columns, columns, t)
            USING p_tenant, p_to, p_from;
    END LOOP;
END;
$$;

-- An alias resolves through what the version read declares: a draft's declarations or a release's
-- pins are both its row's (one branch, ADR 0036 section 7).
CREATE OR REPLACE FUNCTION resolve_catalog_alias(p_tenant TEXT, p_catalog UUID, p_version_id UUID, p_version TEXT, p_alias TEXT)
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
SELECT CASE
    WHEN p_alias IS NULL OR p_alias = (SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = p_tenant AND c.catalog_id = p_catalog)
        THEN p_catalog
    ELSE (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_version_dependencies d
          WHERE d.tenant_key = p_tenant AND d.alias = p_alias
            AND d.version_id = COALESCE(p_version_id, (SELECT v.version_id FROM catalog_versions v
                                                       WHERE v.tenant_key = p_tenant AND v.catalog_id = p_catalog
                                                         AND v.version = p_version AND v.state = 'released')))
END
$$;
