-- Domain type for variant identifiers (slugs)
CREATE DOMAIN VARIANT_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Domain type for attribute identifiers (slugs)
CREATE DOMAIN ATTRIBUTE_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Template variants, versions, and activations
-- Enables template lifecycle management with draft/published/archived states
-- All tenant-owned entities use composite PKs (tenant_key, catalog_key, ...) for catalog isolation

-- ============================================================================
-- TEMPLATE VARIANTS
-- ============================================================================

-- Template variants (language, brand, audience variations)
-- Composite PK (tenant_key, catalog_key, template_key, id) ensures variants are unique per template within a catalog
CREATE TABLE template_variants (
    id VARIANT_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    template_key TEMPLATE_KEY NOT NULL,
    title VARCHAR(255),
    description TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (tenant_key, catalog_key, template_key, id),
    FOREIGN KEY (tenant_key, catalog_key, template_key) REFERENCES document_templates(tenant_key, catalog_key, id) ON DELETE CASCADE
);

-- Enforce exactly one default variant per template
CREATE UNIQUE INDEX idx_one_default_variant_per_template
    ON template_variants (tenant_key, catalog_key, template_key) WHERE is_default = true;

-- GIN index for attribute filtering queries (e.g. checking attribute values in use)
CREATE INDEX idx_template_variants_attributes_gin
    ON template_variants USING GIN (attributes);

COMMENT ON TABLE template_variants IS 'Variations of a template (e.g., by language, brand, audience). Each variant has its own version history.';
COMMENT ON COLUMN template_variants.id IS 'URL-safe slug identifier unique within the template';
COMMENT ON COLUMN template_variants.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN template_variants.catalog_key IS 'Catalog this variant belongs to';
COMMENT ON COLUMN template_variants.template_key IS 'Parent template this variant belongs to';
COMMENT ON COLUMN template_variants.title IS 'Human-readable title (optional, for display)';
COMMENT ON COLUMN template_variants.description IS 'Free-text description of what this variant is for';
COMMENT ON COLUMN template_variants.attributes IS 'Key-value attribute tags for filtering and resolution (e.g., {"language": "nl", "brand": "premium"})';
COMMENT ON COLUMN template_variants.is_default IS 'Whether this is the fallback variant when no attributes match. Exactly one per template.';
COMMENT ON COLUMN template_variants.created_at IS 'When the variant was created';
COMMENT ON COLUMN template_variants.updated_at IS 'When the variant was last updated';
COMMENT ON COLUMN template_variants.created_by IS 'User who created this variant (NULL if the user was deleted)';
COMMENT ON COLUMN template_variants.updated_by IS 'User who last modified this variant (NULL if the user was deleted)';

-- ============================================================================
-- TEMPLATE VERSIONS
-- ============================================================================

-- Version history with lifecycle states
-- id is the version number (1-200) per variant, not UUID
-- Composite PK (tenant_key, catalog_key, template_key, variant_key, id) matches template_variants hierarchy
--
-- rendering_defaults_version + resolved_theme snapshot the deterministic
-- rendering inputs at publish; contract_version + referenced_paths link the
-- version to its data contract. The contract_version FK is added in
-- V20260515090800__core_contract_versions.sql once that table exists.
CREATE TABLE template_versions (
    id INTEGER NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    template_key TEMPLATE_KEY NOT NULL,
    variant_key VARIANT_KEY NOT NULL,
    template_model JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    rendering_defaults_version INTEGER,
    resolved_theme JSONB,
    contract_version INTEGER,
    referenced_paths JSONB NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (tenant_key, catalog_key, template_key, variant_key, id),
    FOREIGN KEY (tenant_key, catalog_key, template_key, variant_key) REFERENCES template_variants(tenant_key, catalog_key, template_key, id) ON DELETE CASCADE,
    CHECK (status IN ('draft', 'published', 'archived')),
    CHECK (id BETWEEN 1 AND 200)
);

CREATE INDEX idx_template_versions_status ON template_versions(status);

-- GIN index for JSON traversal queries on template model (e.g. asset usage search)
CREATE INDEX idx_template_versions_template_model_gin
    ON template_versions USING GIN (template_model);

-- Enforce at most one draft per variant
CREATE UNIQUE INDEX idx_one_draft_per_variant
    ON template_versions (tenant_key, catalog_key, template_key, variant_key)
    WHERE status = 'draft';

COMMENT ON TABLE template_versions IS 'Immutable version snapshots of a variant. Lifecycle: draft -> published -> archived. At most one draft per variant.';
COMMENT ON COLUMN template_versions.id IS 'Sequential version number (1-200) within the variant';
COMMENT ON COLUMN template_versions.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN template_versions.catalog_key IS 'Catalog this version belongs to';
COMMENT ON COLUMN template_versions.template_key IS 'Parent template (part of composite key)';
COMMENT ON COLUMN template_versions.variant_key IS 'Parent variant this version belongs to';
COMMENT ON COLUMN template_versions.template_model IS 'Full template document (blocks, layout, styles) as ProseMirror-compatible JSON';
COMMENT ON COLUMN template_versions.status IS 'Lifecycle state: draft (editable), published (frozen, deployable), or archived (read-only)';
COMMENT ON COLUMN template_versions.created_at IS 'When the version was created';
COMMENT ON COLUMN template_versions.published_at IS 'When the version was published (frozen). NULL while in draft.';
COMMENT ON COLUMN template_versions.archived_at IS 'When the version was archived. NULL while draft or published.';
COMMENT ON COLUMN template_versions.created_by IS 'User who created this version (NULL if the user was deleted)';
COMMENT ON COLUMN template_versions.contract_version IS 'Contract version this template version is associated with. NULL if the template has no contract.';
COMMENT ON COLUMN template_versions.referenced_paths IS 'Data contract paths referenced by expressions in the template model. Computed on save. Used for precise contract compatibility checking.';

-- ============================================================================
-- ENVIRONMENT ACTIVATIONS
-- ============================================================================

-- Environment activations (which version is active per environment per variant)
-- Composite PK includes catalog_key and template_key to match template_variants hierarchy
CREATE TABLE environment_activations (
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    environment_key ENVIRONMENT_KEY NOT NULL,
    template_key TEMPLATE_KEY NOT NULL,
    variant_key VARIANT_KEY NOT NULL,
    version_key INTEGER NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, catalog_key, environment_key, template_key, variant_key),
    FOREIGN KEY (tenant_key, environment_key) REFERENCES environments(tenant_key, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, catalog_key, template_key, variant_key) REFERENCES template_variants(tenant_key, catalog_key, template_key, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, catalog_key, template_key, variant_key, version_key) REFERENCES template_versions(tenant_key, catalog_key, template_key, variant_key, id) ON DELETE CASCADE
);

COMMENT ON TABLE environment_activations IS 'Tracks which published version is active per environment per variant. One active version per (environment, variant) pair.';
COMMENT ON COLUMN environment_activations.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN environment_activations.catalog_key IS 'Catalog scope for the activation';
COMMENT ON COLUMN environment_activations.environment_key IS 'Target environment (e.g., staging, production)';
COMMENT ON COLUMN environment_activations.template_key IS 'Parent template (part of composite key)';
COMMENT ON COLUMN environment_activations.variant_key IS 'Variant being deployed';
COMMENT ON COLUMN environment_activations.version_key IS 'Published version number that is active';
COMMENT ON COLUMN environment_activations.activated_at IS 'When this version was activated in the environment';

-- ============================================================================
-- VARIANT ATTRIBUTE DEFINITIONS
-- ============================================================================

-- Variant attribute definitions registry (tenant-scoped, catalog-scoped)
-- Defines which attribute keys are allowed on variants, with optional value constraints
-- Composite PK (tenant_key, catalog_key, id) ensures attribute names are unique per catalog
--
-- Binding to a code list (code_list_catalog_key / code_list_slug columns + the
-- attr_* constraints) is added in V20260515091700__core_code_lists.sql, once
-- the CODE_LIST_KEY domain and code_lists table exist.
CREATE TABLE variant_attribute_definitions (
    id ATTRIBUTE_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    display_name VARCHAR(100) NOT NULL,
    allowed_values JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, catalog_key, id),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE
);

COMMENT ON TABLE variant_attribute_definitions IS 'Registry of allowed attribute keys for variants, with optional value constraints. Tenant and catalog scoped.';
COMMENT ON COLUMN variant_attribute_definitions.id IS 'Attribute key used in variant attributes JSON (e.g., language, brand)';
COMMENT ON COLUMN variant_attribute_definitions.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN variant_attribute_definitions.catalog_key IS 'Catalog this attribute definition belongs to';
COMMENT ON COLUMN variant_attribute_definitions.display_name IS 'Human-readable label shown in the UI';
COMMENT ON COLUMN variant_attribute_definitions.allowed_values IS 'Permitted values for this attribute. Empty array means any value is allowed.';
COMMENT ON COLUMN variant_attribute_definitions.created_at IS 'When the definition was created';
COMMENT ON COLUMN variant_attribute_definitions.updated_at IS 'When the definition was last updated';

-- updated_at is DB-enforced by the shared set_updated_at() trigger function.
CREATE TRIGGER trg_template_variants_updated_at
    BEFORE UPDATE ON template_variants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- updated_at is DB-enforced by the shared set_updated_at() trigger function.
CREATE TRIGGER trg_variant_attribute_definitions_updated_at
    BEFORE UPDATE ON variant_attribute_definitions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
