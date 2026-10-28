-- The 2.0 upgrade, step 1 of 16 (docs/migrations.md, "The 2.0 chain"): a catalog's identity.
--
-- A catalog is identified by registry, namespace and key (contract ADR 0003). Until 2.0 it had only
-- its key. It gets an opaque identity, `catalog_id`, and every live table references it by that, as
-- resources already reference each other by `resource_id`, so a catalog's name lives in one row.
-- History (generated documents, the deployment log) keeps the name the catalog had.
--
-- The 1.x content tables (themes, templates, stencils and the rest) are only read from here on, by
-- the steps that convert them (5 to 11), and dropped in step 14. Their references to a catalog by key
-- and their triggers go now: nothing writes them any more.

-- 1. The 1.x content tables are read only ---------------------------------------------------------

DROP TRIGGER trg_assets_resource_identity ON assets;
DROP TRIGGER trg_assets_delete_resource_identity ON assets;
DROP TRIGGER trg_code_lists_resource_identity ON code_lists;
DROP TRIGGER trg_code_lists_delete_resource_identity ON code_lists;
DROP TRIGGER trg_code_lists_updated_at ON code_lists;
DROP TRIGGER trg_document_templates_resource_identity ON document_templates;
DROP TRIGGER trg_document_templates_delete_resource_identity ON document_templates;
DROP TRIGGER trg_document_templates_updated_at ON document_templates;
DROP TRIGGER trg_fonts_resource_identity ON fonts;
DROP TRIGGER trg_fonts_delete_resource_identity ON fonts;
DROP TRIGGER trg_fonts_updated_at ON fonts;
DROP TRIGGER trg_stencils_resource_identity ON stencils;
DROP TRIGGER trg_stencils_delete_resource_identity ON stencils;
DROP TRIGGER trg_stencils_updated_at ON stencils;
DROP TRIGGER trg_template_variants_updated_at ON template_variants;
DROP TRIGGER trg_themes_resource_identity ON themes;
DROP TRIGGER trg_themes_delete_resource_identity ON themes;
DROP TRIGGER trg_themes_updated_at ON themes;
DROP TRIGGER trg_variant_attributes_resource_identity ON variant_attribute_definitions;
DROP TRIGGER trg_variant_attributes_delete_resource_identity ON variant_attribute_definitions;
DROP TRIGGER trg_variant_attribute_definitions_updated_at ON variant_attribute_definitions;

ALTER TABLE assets DROP CONSTRAINT assets_tenant_key_catalog_key_fkey;
ALTER TABLE catalog_releases DROP CONSTRAINT catalog_releases_tenant_key_catalog_key_fkey;
ALTER TABLE code_lists DROP CONSTRAINT code_lists_tenant_key_catalog_key_fkey;
ALTER TABLE document_templates DROP CONSTRAINT document_templates_tenant_key_catalog_key_fkey;
ALTER TABLE fonts DROP CONSTRAINT fonts_tenant_key_catalog_key_fkey;
ALTER TABLE stencils DROP CONSTRAINT stencils_tenant_key_catalog_key_fkey;
ALTER TABLE themes DROP CONSTRAINT themes_tenant_key_catalog_key_fkey;
ALTER TABLE variant_attribute_definitions DROP CONSTRAINT variant_attribute_definitions_tenant_key_catalog_key_fkey;

-- Their rows' identities stay registered in catalog_resources, where the converted rows adopt them;
-- an identity a conversion leaves no row for (a file that only held a font face) goes then.
ALTER TABLE assets DROP CONSTRAINT fk_assets_resource_identity;
ALTER TABLE code_lists DROP CONSTRAINT fk_code_lists_resource_identity;
ALTER TABLE document_templates DROP CONSTRAINT fk_document_templates_resource_identity;
ALTER TABLE fonts DROP CONSTRAINT fk_fonts_resource_identity;
ALTER TABLE stencils DROP CONSTRAINT fk_stencils_resource_identity;
ALTER TABLE themes DROP CONSTRAINT fk_themes_resource_identity;
ALTER TABLE variant_attribute_definitions DROP CONSTRAINT fk_variant_attributes_resource_identity;

-- 2. A catalog's name and identity ----------------------------------------------------------------

-- Existing catalogs are named under `local` (ADR 0034, section 7), in their tenant's key, padded with
-- `0` to the 3 characters a namespace needs, as the contract pads a short key: until now a catalog
-- never left its installation but through the Exchange, so a name valid only within the organisation
-- is the one it had. Only Exchange names a catalog on exchange.epistola.app: in the namespace
-- `epistola` for the catalogs Epistola ships (installed from the classpath, as their manifests name
-- them); in their publisher's for one installed from Exchange (`exchange:<namespace>/<key>`); in the
-- Exchange namespace a catalog is bound to for publishing, which the registry granted. A 1.x catalog
-- subscribed to from a URL takes the name its source gives it at the first boot
-- (`ReceiveOneXSubscribedReleases`).
ALTER TABLE catalogs
    ADD COLUMN registry TEXT,
    ADD COLUMN namespace TEXT,
    ADD COLUMN catalog_id UUID NOT NULL DEFAULT epistola_uuidv7();

UPDATE catalogs c
SET registry  = CASE
        WHEN c.source_url LIKE 'classpath:epistola/catalogs/%' OR c.source_url LIKE 'exchange:%/%'
            OR EXISTS (SELECT 1 FROM catalog_exchange_bindings b WHERE b.tenant_key = c.tenant_key AND b.catalog_key = c.id)
            THEN 'exchange.epistola.app'
        ELSE 'local'
    END,
    namespace = CASE
        WHEN c.source_url LIKE 'classpath:epistola/catalogs/%' THEN 'epistola'
        WHEN c.source_url LIKE 'exchange:%/%' THEN split_part(substring(c.source_url FROM length('exchange:') + 1), '/', 1)
        WHEN EXISTS (SELECT 1 FROM catalog_exchange_bindings b WHERE b.tenant_key = c.tenant_key AND b.catalog_key = c.id)
            THEN (SELECT b.namespace FROM catalog_exchange_bindings b WHERE b.tenant_key = c.tenant_key AND b.catalog_key = c.id)
        WHEN length(c.tenant_key) < 3 THEN rpad(c.tenant_key, 3, '0')
        ELSE c.tenant_key
    END;

ALTER TABLE catalogs
    ALTER COLUMN registry SET NOT NULL,
    ALTER COLUMN namespace SET NOT NULL;

-- A catalog has no type since ADR 0032; the column stays for this upgrade, which reasons about 1.x
-- data, and a catalog created since gets the default.
ALTER TABLE catalogs ALTER COLUMN type SET DEFAULT 'AUTHORED';

COMMENT ON COLUMN catalogs.registry IS
    'The registry the catalog is named on (contract ADR 0003): a DNS name, written in full.';
COMMENT ON COLUMN catalogs.namespace IS
    'The catalog''s namespace on its registry (contract ADR 0003). An authored catalog from before 2.0 is local/<tenant key>; fixed once the catalog is published.';
COMMENT ON COLUMN catalogs.catalog_id IS
    'Opaque identity of the catalog. Never shown and never a name: a catalog is named by (registry, namespace, catalog_key).';
COMMENT ON COLUMN catalogs.type IS
    'Unused since ADR 0032 (catalogs have no type); kept for the 1.x-to-2.0 upgrade and removed in a later major release. Defaults to ''AUTHORED'' for catalogs created since.';

-- 3. Live tables reference a catalog by its identity ---------------------------------------------

ALTER TABLE catalog_resources DROP CONSTRAINT fk_catalog_resources_catalog, DROP CONSTRAINT uq_catalog_resources_address;
ALTER TABLE catalog_upstream_checks DROP CONSTRAINT catalog_upstream_checks_tenant_key_catalog_key_fkey, DROP CONSTRAINT catalog_upstream_checks_pkey;
ALTER TABLE catalog_release_publications DROP CONSTRAINT fk_catalog_release_publications_release, DROP CONSTRAINT uq_catalog_release_publications_release;

DO $$
DECLARE
    t TEXT;
    unmatched BIGINT;
BEGIN
    FOREACH t IN ARRAY ARRAY['catalog_resources', 'catalog_upstream_checks', 'catalog_release_publications'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN catalog_id UUID', t);
        EXECUTE format('UPDATE %I r SET catalog_id = c.catalog_id FROM catalogs c WHERE c.tenant_key = r.tenant_key AND c.id = r.catalog_key', t);
        EXECUTE format('SELECT count(*) FROM %I WHERE catalog_id IS NULL', t) INTO unmatched;
        IF unmatched > 0 THEN
            RAISE EXCEPTION '% row(s) of % name a catalog that does not exist', unmatched, t;
        END IF;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN catalog_id SET NOT NULL, DROP COLUMN catalog_key', t);
    END LOOP;
END $$;

ALTER TABLE catalogs DROP CONSTRAINT catalogs_pkey;
ALTER TABLE catalogs RENAME COLUMN id TO catalog_key;
ALTER TABLE catalogs ADD CONSTRAINT catalogs_pkey PRIMARY KEY (tenant_key, catalog_id);
ALTER TABLE catalogs ADD CONSTRAINT uq_catalogs_name UNIQUE (tenant_key, registry, namespace, catalog_key);
-- Every key was unique in its tenant, so every namespace and key is too.
ALTER TABLE catalogs ADD CONSTRAINT uq_catalogs_namespace_key UNIQUE (tenant_key, namespace, catalog_key);

COMMENT ON COLUMN catalogs.catalog_key IS
    'The catalog''s key within its namespace (contract ADR 0003). Unique only together with registry and namespace.';
COMMENT ON CONSTRAINT uq_catalogs_namespace_key ON catalogs IS
    'Within a tenant a catalog is named by namespace and key (ADR 0034, section 2); the full name, with its authority, stays unique too (uq_catalogs_name).';

ALTER TABLE catalog_resources
    ADD CONSTRAINT fk_catalog_resources_catalog FOREIGN KEY (tenant_key, catalog_id)
        REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE DEFERRABLE,
    ADD CONSTRAINT uq_catalog_resources_address UNIQUE (tenant_key, resource_type, catalog_id, resource_key);

ALTER TABLE catalog_upstream_checks
    ADD CONSTRAINT catalog_upstream_checks_pkey PRIMARY KEY (tenant_key, catalog_id),
    ADD CONSTRAINT fk_catalog_upstream_checks_catalog FOREIGN KEY (tenant_key, catalog_id)
        REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE;

-- A publication names the release it publishes, a row of catalog_versions (step 2). Release numbers
-- grow to 128 characters (a patch release labelled with a namespace, ADR 0034), and a fingerprint is
-- written `<algorithm>:<hex>` (wire v8).
ALTER TABLE catalog_release_publications
    ALTER COLUMN version TYPE VARCHAR(128),
    ALTER COLUMN fingerprint TYPE VARCHAR(80),
    ADD CONSTRAINT uq_catalog_release_publications_release UNIQUE (tenant_key, catalog_id, version);

-- The catalog a caller names by its address, namespace and key.
CREATE FUNCTION epistola_catalog_id(p_tenant TEXT, p_namespace TEXT, p_key TEXT) RETURNS UUID
    LANGUAGE sql STABLE
AS $$
    SELECT c.catalog_id FROM catalogs c WHERE c.tenant_key = p_tenant AND c.namespace = p_namespace AND c.catalog_key = p_key
$$;

COMMENT ON FUNCTION epistola_catalog_id(TEXT, TEXT, TEXT) IS
    'The catalog_id of the catalog a caller names by its address, namespace and key; NULL when the tenant has none there.';

-- 4. A catalog's metadata in wire v8 -------------------------------------------------------------
-- Catalog metadata is stored in the catalog wire shape, and wire v8 renamed three of its members
-- (contract CatalogKeyRenames): the presentation's iconAssetSlug and imageAssetSlugs became
-- iconImageKey and imageKeys, and an attribute assignment's catalog became catalogKey. Binding reads
-- either name, but SQL reads members by name (an image is in use when a catalog presents it), so
-- rename them in place, keeping each value: every stored row then has the one shape v8 writes.

CREATE FUNCTION pg_temp.renamed_member(member_owner JSONB, old_name TEXT, new_name TEXT) RETURNS JSONB
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE
           WHEN jsonb_typeof(member_owner) <> 'object' OR NOT member_owner ? old_name THEN member_owner
           -- A member already under its new name wins; the old one is dropped either way.
           ELSE (member_owner - old_name) || jsonb_build_object(new_name, COALESCE(member_owner -> new_name, member_owner -> old_name))
       END
$$;

UPDATE catalogs
SET catalog_metadata = jsonb_set(
        catalog_metadata, '{presentation}',
        pg_temp.renamed_member(
            pg_temp.renamed_member(catalog_metadata -> 'presentation', 'iconAssetSlug', 'iconImageKey'),
            'imageAssetSlugs', 'imageKeys'))
WHERE jsonb_typeof(catalog_metadata -> 'presentation') = 'object';

UPDATE catalogs
SET catalog_metadata = jsonb_set(
        catalog_metadata, '{attributes}',
        (SELECT COALESCE(jsonb_agg(pg_temp.renamed_member(assignment, 'catalog', 'catalogKey') ORDER BY position), '[]'::jsonb)
         FROM jsonb_array_elements(catalog_metadata -> 'attributes') WITH ORDINALITY AS a(assignment, position)))
WHERE jsonb_typeof(catalog_metadata -> 'attributes') = 'array';

-- 5. The namespaces each tenant holds (ADR 0034, section 3) --------------------------------------
-- A tenant names a new catalog, and issues releases of it, only in a namespace it holds, under an
-- authority: the registry it was granted on. The authority `local` (ADR 0034, section 7) is never
-- granted: any tenant may name catalogs under it, and they never leave the organisation.

CREATE TABLE namespace_grant_sources (
    code        VARCHAR(32) PRIMARY KEY,
    description TEXT NOT NULL
);

INSERT INTO namespace_grant_sources (code, description) VALUES
    ('EXCHANGE_CONNECTION', 'Granted through the tenant''s Epistola Exchange connection'),
    ('ADMINISTRATOR', 'Granted by a platform administrator'),
    ('EXISTING', 'Held since before namespaces were granted: catalogs the tenant authored were named in it');

CREATE TABLE tenant_namespace_grants (
    tenant_key      TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    authority       TEXT        NOT NULL,
    namespace       TEXT        NOT NULL,
    granted_through VARCHAR(32) NOT NULL REFERENCES namespace_grant_sources (code),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- users.id of the administrator who granted it; NULL for one granted by the Exchange connection,
    -- by the system, or held from before. No FK, as for the other audit columns.
    granted_by      UUID,
    PRIMARY KEY (tenant_key, authority, namespace)
);

COMMENT ON TABLE tenant_namespace_grants IS
    'The namespaces each tenant holds (ADR 0034, section 3): a new catalog is named, and a release issued, only in one of them, or under the local authority.';
COMMENT ON COLUMN tenant_namespace_grants.authority IS
    'The domain the namespace is granted under: the contract''s registry member. Never local, which needs no grant.';
COMMENT ON COLUMN tenant_namespace_grants.granted_through IS
    'How it came to be held: namespace_grant_sources.code.';

-- What a 1.x tenant held: the namespaces its Exchange connection grants, under the Exchange's
-- authority, and those of the catalogs it authored. A 1.x catalog was authored by its tenant when it
-- has no source (`classpath:`, `exchange:`, `oci:` and a manifest URL are all received) and was not a
-- subscribed copy. A 1.x release named no installation, so each counts as cut here.
INSERT INTO tenant_namespace_grants (tenant_key, authority, namespace, granted_through, granted_at)
SELECT DISTINCT x.tenant_key, 'exchange.epistola.app', ns, 'EXCHANGE_CONNECTION', x.updated_at
FROM exchange_tenant_connections x
CROSS JOIN LATERAL unnest(x.namespaces) AS ns
WHERE length(ns) BETWEEN 3 AND 63 AND ns ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
ON CONFLICT DO NOTHING;

INSERT INTO tenant_namespace_grants (tenant_key, authority, namespace, granted_through, granted_at)
SELECT DISTINCT c.tenant_key, c.registry, c.namespace, 'EXISTING', NOW()
FROM catalogs c
WHERE c.source_url IS NULL
  AND c.type IS DISTINCT FROM 'SUBSCRIBED'
  AND c.registry <> 'local'
ON CONFLICT DO NOTHING;
