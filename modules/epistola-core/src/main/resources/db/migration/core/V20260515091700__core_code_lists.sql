-- Code lists for attribute value constraints
--
-- Code lists — named collections of {code, label, hidden} entries that
-- attribute definitions can bind to as an alternative to inline allowed_values.
-- Code lists are tenant + catalog scoped, mirroring the attributes that bind
-- to them. AUTHORED catalogs only — SUBSCRIBED catalogs are already read-only
-- via requireCatalogEditable.

-- Domain type for code list slugs (matches ATTRIBUTE_KEY pattern, broader length).
CREATE DOMAIN CODE_LIST_KEY AS VARCHAR(64)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Source of a code list's entries.
--   CLASSPATH — entries shipped with Epistola at a classpath resource
--   URL       — entries fetched over HTTPS from a tenant-managed endpoint
--   INLINE    — entries entered directly in the UI
CREATE DOMAIN CODE_LIST_SOURCE AS TEXT
    CHECK (VALUE IN ('CLASSPATH', 'URL', 'INLINE'));

-- Auth type for refreshing a URL-sourced code list. Mirrors the catalog
-- subscription auth model (catalogs.source_auth_type).
CREATE DOMAIN CODE_LIST_AUTH_TYPE AS TEXT
    CHECK (VALUE IN ('NONE', 'API_KEY', 'BEARER'));

CREATE TABLE code_lists (
    slug               CODE_LIST_KEY NOT NULL,
    tenant_key         TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_key        CATALOG_KEY NOT NULL,
    display_name       VARCHAR(100) NOT NULL,
    description        TEXT,
    source_type        CODE_LIST_SOURCE NOT NULL,
    source_url         TEXT,
    auth_type          CODE_LIST_AUTH_TYPE NOT NULL DEFAULT 'NONE',
    credential         TEXT,
    last_refreshed_at  TIMESTAMPTZ,
    last_refresh_error TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, catalog_key, slug),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,
    -- INLINE has no source_url; CLASSPATH and URL must have one.
    CONSTRAINT code_lists_source_url_required
        CHECK (source_type = 'INLINE' OR source_url IS NOT NULL)
);

COMMENT ON TABLE code_lists IS 'Named collections of {code, label} entries an attribute can bind to. Tenant + catalog scoped, mirroring attributes.';
COMMENT ON COLUMN code_lists.slug IS 'URL-safe slug, unique within (tenant_key, catalog_key)';
COMMENT ON COLUMN code_lists.source_type IS 'CLASSPATH = bundled, URL = fetched over HTTPS, INLINE = entries entered in the UI';
COMMENT ON COLUMN code_lists.source_url IS 'classpath:… or https://… ; NULL for INLINE';
COMMENT ON COLUMN code_lists.last_refreshed_at IS 'When entries were last refreshed from source. NULL until first refresh.';
COMMENT ON COLUMN code_lists.last_refresh_error IS 'Error message from the most recent refresh, if any';
COMMENT ON COLUMN code_lists.created_at IS 'When the code list was created';
COMMENT ON COLUMN code_lists.updated_at IS 'When the code list was last updated';

-- Code list entries: code + human-readable label, optionally hidden.
-- Hidden entries are still valid for existing variants but filtered out of
-- pickers — supports deprecation, sunset, and tenant-curated subsets without
-- breaking variants that already use those codes.
CREATE TABLE code_list_entries (
    tenant_key       TENANT_KEY NOT NULL,
    catalog_key      CATALOG_KEY NOT NULL,
    code_list_slug   CODE_LIST_KEY NOT NULL,
    code             VARCHAR(64) NOT NULL,
    label            VARCHAR(200) NOT NULL,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    hidden           BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_key, catalog_key, code_list_slug, code),
    FOREIGN KEY (tenant_key, catalog_key, code_list_slug)
        REFERENCES code_lists(tenant_key, catalog_key, slug) ON DELETE CASCADE
);

COMMENT ON TABLE code_list_entries IS 'Individual {code, label} entries belonging to a code list';
COMMENT ON COLUMN code_list_entries.hidden IS 'When true, entry is filtered from pickers but remains valid for existing variants';
COMMENT ON COLUMN code_list_entries.sort_order IS 'Display order in pickers; ties broken by code ascending';

-- Index for the common case: rendering a dropdown of visible entries in order.
CREATE INDEX code_list_entries_visible
    ON code_list_entries(tenant_key, catalog_key, code_list_slug, sort_order, code)
    WHERE NOT hidden;

-- Extend variant_attribute_definitions to allow binding to a code list.
-- Deferred here (not folded into the table's CREATE in
-- V20260515090700__core_template_variants_versions.sql) because the column
-- type CODE_LIST_KEY and the attr_code_list_fk target both live in this file.
--
-- An attribute is constrained in exactly one of three ways:
--   1. Free format        : allowed_values empty AND code_list_slug NULL
--   2. Inline values      : allowed_values non-empty AND code_list_slug NULL
--   3. Bound to code list : code_list_slug NOT NULL AND allowed_values empty
--
-- code_list_catalog_key is part of the FK so an attribute can bind to a code
-- list in any catalog within the same tenant — same tenant is enforced by the
-- shared tenant_key in the composite FK.
ALTER TABLE variant_attribute_definitions
    ADD COLUMN code_list_catalog_key CATALOG_KEY,
    ADD COLUMN code_list_slug        CODE_LIST_KEY,
    ADD CONSTRAINT attr_code_list_columns_consistent
        CHECK ((code_list_slug IS NULL AND code_list_catalog_key IS NULL)
            OR (code_list_slug IS NOT NULL AND code_list_catalog_key IS NOT NULL)),
    ADD CONSTRAINT attr_constraint_kind_xor
        CHECK (code_list_slug IS NULL OR jsonb_array_length(allowed_values) = 0),
    ADD CONSTRAINT attr_code_list_fk
        FOREIGN KEY (tenant_key, code_list_catalog_key, code_list_slug)
        REFERENCES code_lists(tenant_key, catalog_key, slug) ON DELETE RESTRICT;

COMMENT ON COLUMN variant_attribute_definitions.code_list_catalog_key IS 'Catalog where the bound code list lives (within the same tenant); NULL when not bound';
COMMENT ON COLUMN variant_attribute_definitions.code_list_slug IS 'Slug of the bound code list; NULL when free-format or using inline allowed_values';

-- updated_at is DB-enforced by the shared set_updated_at() trigger function.
CREATE TRIGGER trg_code_lists_updated_at
    BEFORE UPDATE ON code_lists
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
