-- Domain type for variant identifiers (slugs)
CREATE DOMAIN VARIANT_ID AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Template variants, versions, and activations
-- Enables template lifecycle management with draft/published/archived states
-- All tenant-owned entities use composite PKs (tenant_id, id) for tenant isolation

-- ============================================================================
-- TEMPLATE VARIANTS
-- ============================================================================

-- Template variants (language, brand, audience variations)
CREATE TABLE template_variants (
    id VARIANT_ID NOT NULL,
    tenant_id TENANT_ID NOT NULL,
    template_id TEMPLATE_ID NOT NULL,
    title VARCHAR(255),
    description TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_template_variants_template ON template_variants(tenant_id, template_id);

-- Enforce exactly one default variant per template
CREATE UNIQUE INDEX idx_one_default_variant_per_template
    ON template_variants (tenant_id, template_id) WHERE is_default = TRUE;

COMMENT ON TABLE template_variants IS 'Variations of a template (e.g., by language, brand, audience). Each variant has its own version history.';
COMMENT ON COLUMN template_variants.id IS 'URL-safe slug identifier unique within the tenant';
COMMENT ON COLUMN template_variants.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN template_variants.template_id IS 'Parent template this variant belongs to';
COMMENT ON COLUMN template_variants.title IS 'Human-readable title (optional, for display)';
COMMENT ON COLUMN template_variants.description IS 'Free-text description of what this variant is for';
COMMENT ON COLUMN template_variants.attributes IS 'Key-value attribute tags for filtering and resolution (e.g., {"language": "nl", "brand": "premium"})';
COMMENT ON COLUMN template_variants.is_default IS 'Whether this is the fallback variant when no attributes match. Exactly one per template.';
COMMENT ON COLUMN template_variants.created_at IS 'When the variant was created';
COMMENT ON COLUMN template_variants.last_modified IS 'When the variant was last updated';
COMMENT ON COLUMN template_variants.created_by IS 'User who created this variant';

-- ============================================================================
-- TEMPLATE VERSIONS
-- ============================================================================

-- Version history with lifecycle states
-- id is the version number (1-200) per variant, not UUID
CREATE TABLE template_versions (
    id INTEGER NOT NULL,
    tenant_id TENANT_ID NOT NULL,
    variant_id VARIANT_ID NOT NULL,
    template_model JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    created_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_id, variant_id, id),
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    CHECK (status IN ('draft', 'published', 'archived')),
    CHECK (id BETWEEN 1 AND 200)
);

CREATE INDEX idx_template_versions_variant ON template_versions(tenant_id, variant_id);
CREATE INDEX idx_template_versions_status ON template_versions(status);

-- Enforce at most one draft per variant
CREATE UNIQUE INDEX idx_one_draft_per_variant
    ON template_versions (tenant_id, variant_id)
    WHERE status = 'draft';

COMMENT ON TABLE template_versions IS 'Immutable version snapshots of a variant. Lifecycle: draft -> published -> archived. At most one draft per variant.';
COMMENT ON COLUMN template_versions.id IS 'Sequential version number (1-200) within the variant';
COMMENT ON COLUMN template_versions.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN template_versions.variant_id IS 'Parent variant this version belongs to';
COMMENT ON COLUMN template_versions.template_model IS 'Full template document (blocks, layout, styles) as ProseMirror-compatible JSON';
COMMENT ON COLUMN template_versions.status IS 'Lifecycle state: draft (editable), published (frozen, deployable), or archived (read-only)';
COMMENT ON COLUMN template_versions.created_at IS 'When the version was created';
COMMENT ON COLUMN template_versions.published_at IS 'When the version was published (frozen). NULL while in draft.';
COMMENT ON COLUMN template_versions.archived_at IS 'When the version was archived. NULL while draft or published.';
COMMENT ON COLUMN template_versions.created_by IS 'User who created this version';

-- ============================================================================
-- ENVIRONMENT ACTIVATIONS
-- ============================================================================

-- Environment activations (which version is active per environment per variant)
CREATE TABLE environment_activations (
    tenant_id TENANT_ID NOT NULL,
    environment_id ENVIRONMENT_ID NOT NULL,
    variant_id VARIANT_ID NOT NULL,
    version_id INTEGER NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, environment_id, variant_id),
    FOREIGN KEY (tenant_id, environment_id) REFERENCES environments(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id, version_id) REFERENCES template_versions(tenant_id, variant_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE environment_activations IS 'Tracks which published version is active per environment per variant. One active version per (environment, variant) pair.';
COMMENT ON COLUMN environment_activations.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN environment_activations.environment_id IS 'Target environment (e.g., staging, production)';
COMMENT ON COLUMN environment_activations.variant_id IS 'Variant being deployed';
COMMENT ON COLUMN environment_activations.version_id IS 'Published version number that is active';
COMMENT ON COLUMN environment_activations.activated_at IS 'When this version was activated in the environment';

-- ============================================================================
-- VARIANT ATTRIBUTE DEFINITIONS
-- ============================================================================

-- Variant attribute definitions registry (tenant-scoped)
-- Defines which attribute keys are allowed on variants, with optional value constraints
CREATE TABLE variant_attribute_definitions (
    id VARCHAR(50) NOT NULL,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL,
    allowed_values JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id)
);

COMMENT ON TABLE variant_attribute_definitions IS 'Registry of allowed attribute keys for variants, with optional value constraints. Tenant-scoped.';
COMMENT ON COLUMN variant_attribute_definitions.id IS 'Attribute key used in variant attributes JSON (e.g., language, brand)';
COMMENT ON COLUMN variant_attribute_definitions.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN variant_attribute_definitions.display_name IS 'Human-readable label shown in the UI';
COMMENT ON COLUMN variant_attribute_definitions.allowed_values IS 'Permitted values for this attribute. Empty array means any value is allowed.';
COMMENT ON COLUMN variant_attribute_definitions.created_at IS 'When the definition was created';
COMMENT ON COLUMN variant_attribute_definitions.last_modified IS 'When the definition was last updated';
