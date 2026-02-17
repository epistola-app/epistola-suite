-- Domain types for environment and variant identifiers (slugs)
CREATE DOMAIN ENVIRONMENT_ID AS VARCHAR(30)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

CREATE DOMAIN VARIANT_ID AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Template variants, versions, and environments
-- Enables template lifecycle management with draft/published/archived states
-- All tenant-owned entities use composite PKs (tenant_id, id) for tenant isolation

-- Tenant environments (staging, production, etc.)
CREATE TABLE environments (
    id ENVIRONMENT_ID NOT NULL,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    last_modified_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name)
);

COMMENT ON COLUMN environments.created_by IS 'User who created this environment';
COMMENT ON COLUMN environments.last_modified_by IS 'User who last modified this environment';

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

COMMENT ON COLUMN template_variants.created_by IS 'User who created this variant';

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

COMMENT ON COLUMN template_versions.created_by IS 'User who created this version';

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
