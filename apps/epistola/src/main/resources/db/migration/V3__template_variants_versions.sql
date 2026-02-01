-- Template variants, versions, and environments
-- Enables template lifecycle management with draft/published/archived states
-- IDs are client-provided UUIDv7 for better testability and distributed system properties

-- Tenant environments (staging, production, etc.)
CREATE TABLE environments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_environments_tenant_id ON environments(tenant_id);

-- Template variants (language, brand, audience variations)
CREATE TABLE template_variants (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    title VARCHAR(255),
    description TEXT,
    tags JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_template_variants_template_id ON template_variants(template_id);

-- Version history with lifecycle states
CREATE TABLE template_versions (
    id UUID PRIMARY KEY,
    variant_id UUID NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_number INTEGER,  -- NULL for draft, assigned on publish
    template_model JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (variant_id, version_number),
    CHECK (status IN ('draft', 'published', 'archived')),
    CHECK ((status = 'draft' AND version_number IS NULL) OR (status != 'draft' AND version_number IS NOT NULL))
);

CREATE INDEX idx_template_versions_variant_id ON template_versions(variant_id);
CREATE INDEX idx_template_versions_status ON template_versions(status);

-- Enforce at most one draft per variant (UNIQUE constraint doesn't work with NULLs in PostgreSQL)
CREATE UNIQUE INDEX idx_one_draft_per_variant
    ON template_versions (variant_id)
    WHERE status = 'draft';

-- Environment activations (which version is active per environment)
CREATE TABLE environment_activations (
    environment_id UUID NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES template_versions(id) ON DELETE CASCADE,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (environment_id, variant_id)
);
