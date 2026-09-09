-- backup-restore-compatibility: backward=false forward=false
-- reason: Re-keys five resource types onto their identities and drops the address columns their
-- dependants carried. A backup taken either side carries a different column set for eight of the
-- backed-up tables.

-- Every resource type except templates, keyed by identity rather than by where it lives.
--
-- Five types, one recipe, applied in turn: add the identity column to whatever referenced the
-- address -> backfill it, asserting nothing was missed -> release the address by dropping the
-- foreign keys that hold the old primary key in place -> swap the primary key onto
-- (tenant_key, resource_id), keeping the address as a unique constraint -> point the dependants at
-- the identity and drop their copies of the address.
--
-- They are one migration because they are one change: a resource is identified by what it is, not
-- by where it sits. Templates are the sixth and are their own migration -- their address reaches
-- into two other modules, which have to rebuild their own foreign keys after it.
--
-- ON DELETE behaviour is preserved exactly in every case; the comments below say where that
-- mattered.

-- ------------------------------------------------------------------------------------------------
-- Attributes
-- ------------------------------------------------------------------------------------------------
-- First table re-keyed onto its stable identity, per
-- docs/catalog-resource-identity-migration.md.
--
-- variant_attribute_definitions is the simplest case in the suite: nothing holds a foreign key to
-- it, and it owns no child tables. That makes it the right place to prove the recipe before a table
-- with dependants relies on the pattern being correct.
--
-- After this, (tenant_key, resource_id) identifies the attribute and (tenant_key, catalog_key, id)
-- is merely its current address -- unique, but no longer identity. Moving the attribute to another
-- catalog becomes an ordinary column update.

ALTER TABLE variant_attribute_definitions
    -- Redundant once (tenant_key, resource_id) is the primary key.
    DROP CONSTRAINT uq_variant_attributes_resource_id,
    DROP CONSTRAINT variant_attribute_definitions_pkey,
    ADD CONSTRAINT variant_attribute_definitions_pkey PRIMARY KEY (tenant_key, resource_id),
    -- The address stays unambiguous; it is just not what identifies the row any more.
    ADD CONSTRAINT uq_variant_attributes_address UNIQUE (tenant_key, catalog_key, id);

COMMENT ON COLUMN variant_attribute_definitions.resource_id IS
    'Stable identity. Survives a move between catalogs; never exported.';
COMMENT ON COLUMN variant_attribute_definitions.catalog_key IS
    'Current catalog. Mutable: a relocation updates it and the address changes with it.';

-- ------------------------------------------------------------------------------------------------
-- Code lists
-- ------------------------------------------------------------------------------------------------
-- Code lists keyed by identity
--
-- The address (catalog_key, slug) stops being the key and becomes what it is: a mutable,
-- human-readable name. Dependants reference the code list by resource_id, so moving or renaming
-- one is a single-row update with nothing to cascade and nothing to rewrite.
--
-- Queries that need the address compute it by joining code_lists, which is what keeps the public
-- shape (catalog + slug) unchanged for REST, export and the UI while storage stops repeating it.

-- 1. Add ---------------------------------------------------------------------------------------
ALTER TABLE code_list_entries ADD COLUMN code_list_resource_id UUID;
ALTER TABLE variant_attribute_definitions ADD COLUMN code_list_resource_id UUID;

-- 2. Backfill, asserting every row was matched ---------------------------------------------------
UPDATE code_list_entries entries
SET code_list_resource_id = lists.resource_id
FROM code_lists lists
WHERE lists.tenant_key = entries.tenant_key
  AND lists.catalog_key = entries.catalog_key
  AND lists.slug = entries.code_list_slug;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM code_list_entries WHERE code_list_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% code_list_entries rows did not match a code list', unmatched;
    END IF;
END $$;

UPDATE variant_attribute_definitions attributes
SET code_list_resource_id = lists.resource_id
FROM code_lists lists
WHERE lists.tenant_key = attributes.tenant_key
  AND lists.catalog_key = attributes.code_list_catalog_key
  AND lists.slug = attributes.code_list_slug;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM variant_attribute_definitions
    WHERE code_list_slug IS NOT NULL AND code_list_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% bound attributes did not match a code list', unmatched;
    END IF;
END $$;

-- 3. Constrain ----------------------------------------------------------------------------------
ALTER TABLE code_list_entries ALTER COLUMN code_list_resource_id SET NOT NULL;

-- 4. Release the address ------------------------------------------------------------------------
-- Both dependants reference code_lists by its address, so they hold the primary key in place and
-- have to let go before it can be swapped.
ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_tenant_key_catalog_key_code_list_slug_fkey;
ALTER TABLE variant_attribute_definitions
    DROP CONSTRAINT attr_code_list_fk;

-- 5. Swap the key -------------------------------------------------------------------------------
-- The address keeps its uniqueness; it is simply no longer what anything references.
ALTER TABLE code_lists
    DROP CONSTRAINT code_lists_pkey,
    -- The unique constraint that stood in for the primary key while the address still held it
    -- is now an exact duplicate of it. Dropped here, before the dependants' foreign keys are
    -- added below, so those bind to the primary key rather than to a second identical index
    -- that every write would then have to maintain.
    DROP CONSTRAINT uq_code_lists_resource_id,
    ADD CONSTRAINT code_lists_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_code_lists_address UNIQUE (tenant_key, catalog_key, slug);

-- 6. Reference by identity, and drop the address copies -------------------------------------------
ALTER TABLE code_list_entries
    DROP CONSTRAINT code_list_entries_pkey,
    ADD CONSTRAINT code_list_entries_pkey PRIMARY KEY (tenant_key, code_list_resource_id, code),
    ADD CONSTRAINT code_list_entries_code_list_fkey
        FOREIGN KEY (tenant_key, code_list_resource_id)
        REFERENCES code_lists(tenant_key, resource_id) ON DELETE CASCADE,
    DROP COLUMN catalog_key,
    DROP COLUMN code_list_slug;

DROP INDEX IF EXISTS code_list_entries_visible;
CREATE INDEX code_list_entries_visible
    ON code_list_entries(tenant_key, code_list_resource_id, sort_order, code)
    WHERE NOT hidden;

ALTER TABLE variant_attribute_definitions
    DROP CONSTRAINT attr_code_list_columns_consistent,
    DROP CONSTRAINT attr_constraint_kind_xor,
    DROP COLUMN code_list_catalog_key,
    DROP COLUMN code_list_slug,
    -- RESTRICT preserved: it is what stops a bound code list being deleted.
    ADD CONSTRAINT attr_code_list_fk
        FOREIGN KEY (tenant_key, code_list_resource_id)
        REFERENCES code_lists(tenant_key, resource_id) ON DELETE RESTRICT,
    -- One column now, so "consistent" has nothing left to state; the XOR still does.
    ADD CONSTRAINT attr_constraint_kind_xor
        CHECK (code_list_resource_id IS NULL OR jsonb_array_length(allowed_values) = 0);

COMMENT ON COLUMN variant_attribute_definitions.code_list_resource_id IS
    'Identity of the bound code list, in any catalog of the same tenant; NULL when free-format or using inline allowed_values.';
COMMENT ON COLUMN code_list_entries.code_list_resource_id IS
    'Identity of the owning code list. The address it lives at is read from code_lists when needed.';

-- ------------------------------------------------------------------------------------------------
-- Fonts and assets
-- ------------------------------------------------------------------------------------------------
-- Fonts and assets keyed by identity
--
-- font_variants.catalog_key served two foreign keys at once: the font family's catalog and,
-- through (tenant_key, catalog_key, asset_key), the backing asset's. One column could not follow
-- two parents, so moving either resource would have dragged the other's reference with it.
--
-- Naming each parent by its identity dissolves that: a face points at its family and at its asset,
-- and neither pointer mentions a catalog at all. Moving either resource updates one row and the
-- face is untouched. It also lifts an undocumented restriction that a face's asset had to live in
-- the font's own catalog, which was a consequence of the column sharing rather than a rule anyone
-- chose.
--
-- ON DELETE is preserved exactly: CASCADE for the owned faces, and NO ACTION DEFERRABLE for the
-- asset so DeleteFont can still drop the family first and release its assets within one
-- transaction (see DeleteFont's KDoc).

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE font_variants ADD COLUMN font_resource_id UUID;
ALTER TABLE font_variants ADD COLUMN asset_resource_id UUID;

-- 2. Backfill, asserting every reference was matched -----------------------------------------------
UPDATE font_variants faces
SET font_resource_id = families.resource_id
FROM fonts families
WHERE families.tenant_key = faces.tenant_key
  AND families.catalog_key = faces.catalog_key
  AND families.slug = faces.font_slug;

-- A CLASSPATH face carries no asset, so it is legitimately left NULL here.
UPDATE font_variants faces
SET asset_resource_id = binaries.resource_id
FROM assets binaries
WHERE binaries.tenant_key = faces.tenant_key
  AND binaries.catalog_key = faces.catalog_key
  AND binaries.id = faces.asset_key;

-- The face -> asset foreign key is DEFERRABLE INITIALLY DEFERRED, so the updates above leave a
-- check queued for every row they touched, and Postgres refuses to ALTER a table with pending
-- trigger events. Firing them now clears the queue; they pass, since neither address changed.
SET CONSTRAINTS ALL IMMEDIATE;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM font_variants WHERE font_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% font faces belong to a family that does not exist', unmatched;
    END IF;

    SELECT count(*) INTO unmatched FROM font_variants
    WHERE asset_key IS NOT NULL AND asset_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% font faces reference an asset that does not exist', unmatched;
    END IF;
END $$;

ALTER TABLE font_variants ALTER COLUMN font_resource_id SET NOT NULL;

-- 3. Release the addresses -------------------------------------------------------------------------
-- Both foreign keys hold their parent's primary key in place, so they let go before it is swapped.
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_font_slug_fkey,
    DROP CONSTRAINT font_variants_tenant_key_catalog_key_asset_key_fkey,
    DROP CONSTRAINT chk_font_variant_source;

-- 4. Swap the keys ---------------------------------------------------------------------------------
ALTER TABLE fonts
    DROP CONSTRAINT fonts_pkey,
    -- The unique constraint that stood in for the primary key while the address still held it
    -- is now an exact duplicate of it. Dropped here, before the dependants' foreign keys are
    -- added below, so those bind to the primary key rather than to a second identical index
    -- that every write would then have to maintain.
    DROP CONSTRAINT uq_fonts_resource_id,
    ADD CONSTRAINT fonts_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_fonts_address UNIQUE (tenant_key, catalog_key, slug);

ALTER TABLE assets
    DROP CONSTRAINT assets_pkey,
    -- The unique constraint that stood in for the primary key while the address still held it
    -- is now an exact duplicate of it. Dropped here, before the dependants' foreign keys are
    -- added below, so those bind to the primary key rather than to a second identical index
    -- that every write would then have to maintain.
    DROP CONSTRAINT uq_assets_resource_id,
    ADD CONSTRAINT assets_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_assets_address UNIQUE (tenant_key, catalog_key, id);

-- 5. Reference by identity, and drop the address copies ----------------------------------------------
-- A face is identified by its family and its shape, which is what the old key said through the
-- family's address.
ALTER TABLE font_variants
    DROP CONSTRAINT font_variants_pkey,
    ADD CONSTRAINT font_variants_pkey PRIMARY KEY (tenant_key, font_resource_id, weight, italic),
    DROP COLUMN catalog_key,
    DROP COLUMN font_slug,
    DROP COLUMN asset_key,
    ADD CONSTRAINT font_variants_font_fkey
        FOREIGN KEY (tenant_key, font_resource_id)
        REFERENCES fonts(tenant_key, resource_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT font_variants_asset_fkey
        FOREIGN KEY (tenant_key, asset_resource_id)
        REFERENCES assets(tenant_key, resource_id)
        ON DELETE NO ACTION
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT chk_font_variant_source CHECK (
        (source = 'ASSET'     AND asset_resource_id IS NOT NULL AND classpath_location IS NULL) OR
        (source = 'CLASSPATH' AND classpath_location IS NOT NULL AND asset_resource_id IS NULL)
    );

CREATE INDEX idx_font_variants_asset_resource_id ON font_variants(asset_resource_id)
    WHERE asset_resource_id IS NOT NULL;

COMMENT ON COLUMN font_variants.font_resource_id IS
    'Identity of the family this face belongs to. Replaces the family''s address, so a family move leaves its faces untouched.';
COMMENT ON COLUMN font_variants.asset_resource_id IS
    'Identity of this face''s backing binary; NULL for CLASSPATH faces. Independent of the family''s catalog, so a font and its asset relocate separately.';

-- ------------------------------------------------------------------------------------------------
-- Themes
-- ------------------------------------------------------------------------------------------------
-- Themes keyed by identity
--
-- A theme is referenced three ways: from a template's own binding, from the tenant-wide default,
-- and from content (`themeRef`). The two relational ones now name the theme itself, so moving or
-- renaming one updates a single row. Content references keep naming an address -- that is what
-- travels in an export -- and resolve through the alias when the theme has moved.
--
-- ON DELETE is preserved exactly: deleting a theme clears the binding and falls the template back
-- to the tenant default, rather than deleting the template.

-- 1. Add ---------------------------------------------------------------------------------------
ALTER TABLE document_templates ADD COLUMN theme_resource_id UUID;
ALTER TABLE tenants ADD COLUMN default_theme_resource_id UUID;

-- 2. Backfill, asserting every bound row was matched ---------------------------------------------
UPDATE document_templates templates
SET theme_resource_id = themes.resource_id
FROM themes
WHERE themes.tenant_key = templates.tenant_key
  AND themes.catalog_key = templates.theme_catalog_key
  AND themes.id = templates.theme_key;

UPDATE tenants
SET default_theme_resource_id = themes.resource_id
FROM themes
WHERE themes.tenant_key = tenants.id
  AND themes.catalog_key = tenants.default_theme_catalog_key
  AND themes.id = tenants.default_theme_key;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM document_templates
    WHERE theme_key IS NOT NULL AND theme_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% templates reference a theme that does not exist', unmatched;
    END IF;

    SELECT count(*) INTO unmatched FROM tenants
    WHERE default_theme_key IS NOT NULL AND default_theme_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% tenants default to a theme that does not exist', unmatched;
    END IF;
END $$;

-- 3. Release the address --------------------------------------------------------------------------
-- Both referencing constraints hold the primary key in place, so they let go before it is swapped.
ALTER TABLE document_templates
    DROP CONSTRAINT document_templates_tenant_key_theme_catalog_key_theme_key_fkey;
ALTER TABLE tenants
    DROP CONSTRAINT fk_tenants_default_theme;

-- 4. Swap the key ---------------------------------------------------------------------------------
ALTER TABLE themes
    DROP CONSTRAINT themes_pkey,
    -- The unique constraint that stood in for the primary key while the address still held it
    -- is now an exact duplicate of it. Dropped here, before the dependants' foreign keys are
    -- added below, so those bind to the primary key rather than to a second identical index
    -- that every write would then have to maintain.
    DROP CONSTRAINT uq_themes_resource_id,
    ADD CONSTRAINT themes_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_themes_address UNIQUE (tenant_key, catalog_key, id);

-- 5. Reference by identity, and drop the address copies ---------------------------------------------
ALTER TABLE document_templates
    DROP COLUMN theme_catalog_key,
    DROP COLUMN theme_key,
    ADD CONSTRAINT document_templates_theme_fkey
        FOREIGN KEY (tenant_key, theme_resource_id)
        REFERENCES themes(tenant_key, resource_id)
        ON DELETE SET NULL (theme_resource_id);

ALTER TABLE tenants
    DROP COLUMN default_theme_catalog_key,
    DROP COLUMN default_theme_key,
    ADD CONSTRAINT fk_tenants_default_theme
        FOREIGN KEY (id, default_theme_resource_id)
        REFERENCES themes(tenant_key, resource_id);

-- The address indexes went with their columns; both lookups (which templates use this theme,
-- which tenant defaults to it) now go through the identity.
CREATE INDEX idx_document_templates_theme_resource_id ON document_templates(theme_resource_id)
    WHERE theme_resource_id IS NOT NULL;
CREATE INDEX idx_tenants_default_theme_resource_id ON tenants(default_theme_resource_id)
    WHERE default_theme_resource_id IS NOT NULL;

COMMENT ON COLUMN document_templates.theme_resource_id IS
    'Identity of this template''s theme, in any catalog of the same tenant; NULL falls back to the tenant default.';
COMMENT ON COLUMN tenants.default_theme_resource_id IS
    'Identity of the tenant-wide default theme; NULL means the built-in default.';

-- ------------------------------------------------------------------------------------------------
-- Stencils
-- ------------------------------------------------------------------------------------------------
-- Stencils keyed by identity
--
-- A stencil owns its versions and is named from template and stencil content. The versions follow
-- it, so they name the stencil itself; content keeps naming an address -- that is what travels in
-- an export -- and resolves through the alias when the stencil has moved.
--
-- The version's copy of the parent address goes with it. Two columns describing where the parent
-- lives, alongside a pointer to the parent, is a fact stated twice: nothing forced them to agree,
-- and roughly ten queries filtered on the copy rather than the pointer. They read through the
-- parent now, so there is one statement of where a stencil lives and it is the stencil's own row.
--
-- ON DELETE is preserved exactly: deleting a stencil deletes its versions.

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE stencil_versions ADD COLUMN stencil_resource_id UUID;

-- 2. Backfill, asserting every version found its parent ---------------------------------------------
UPDATE stencil_versions versions
SET stencil_resource_id = stencils.resource_id
FROM stencils
WHERE stencils.tenant_key = versions.tenant_key
  AND stencils.catalog_key = versions.catalog_key
  AND stencils.id = versions.stencil_key;

DO $$
DECLARE unmatched BIGINT;
BEGIN
    SELECT count(*) INTO unmatched FROM stencil_versions WHERE stencil_resource_id IS NULL;
    IF unmatched > 0 THEN
        RAISE EXCEPTION '% stencil versions belong to a stencil that does not exist', unmatched;
    END IF;
END $$;

ALTER TABLE stencil_versions ALTER COLUMN stencil_resource_id SET NOT NULL;

-- 3. Release the address ----------------------------------------------------------------------------
-- The versions' foreign key holds the stencils primary key in place, so it lets go before the swap.
ALTER TABLE stencil_versions
    DROP CONSTRAINT stencil_versions_tenant_key_catalog_key_stencil_key_fkey;

-- 4. Swap the key -----------------------------------------------------------------------------------
ALTER TABLE stencils
    DROP CONSTRAINT stencils_pkey,
    -- The unique constraint that stood in for the primary key while the address still held it
    -- is now an exact duplicate of it. Dropped here, before the dependants' foreign keys are
    -- added below, so those bind to the primary key rather than to a second identical index
    -- that every write would then have to maintain.
    DROP CONSTRAINT uq_stencils_resource_id,
    ADD CONSTRAINT stencils_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_stencils_address UNIQUE (tenant_key, catalog_key, id);

-- 5. Reference by identity, and drop the address copies -----------------------------------------------
-- A version is identified by its stencil and its number, which is what the old key said through the
-- stencil's address.
ALTER TABLE stencil_versions
    DROP CONSTRAINT stencil_versions_pkey,
    ADD CONSTRAINT stencil_versions_pkey PRIMARY KEY (tenant_key, stencil_resource_id, id),
    DROP COLUMN catalog_key,
    DROP COLUMN stencil_key,
    ADD CONSTRAINT fk_stencil_versions_parent
        FOREIGN KEY (tenant_key, stencil_resource_id)
        REFERENCES stencils(tenant_key, resource_id)
        ON DELETE CASCADE;

-- The at-most-one-draft rule was stated over the parent's address; it is stated over the parent.
CREATE UNIQUE INDEX idx_one_draft_per_stencil
    ON stencil_versions (tenant_key, stencil_resource_id)
    WHERE status = 'draft';

COMMENT ON COLUMN stencil_versions.stencil_resource_id IS
    'Identity of the stencil this version belongs to. Replaces the parent''s address, so a stencil move leaves its versions untouched.';
