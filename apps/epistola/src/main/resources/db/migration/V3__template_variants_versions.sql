-- Template variants, versions, and environments
-- Enables template lifecycle management with draft/published/archived states

-- Tenant environments (staging, production, etc.)
CREATE TABLE environments (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_environments_tenant_id ON environments(tenant_id);

-- Template variants (language, brand, audience variations)
CREATE TABLE template_variants (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    tags JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_template_variants_template_id ON template_variants(template_id);

-- Version history with lifecycle states
CREATE TABLE template_versions (
    id BIGSERIAL PRIMARY KEY,
    variant_id BIGINT NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
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
    environment_id BIGINT NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    variant_id BIGINT NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id BIGINT NOT NULL REFERENCES template_versions(id) ON DELETE CASCADE,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (environment_id, variant_id)
);

-- Migrate existing templates to new structure:
-- 1. Create default variant for each template
-- 2. Create draft version with existing templateModel

-- Step 1: Create default variants for all existing templates
INSERT INTO template_variants (template_id, tags, created_at, last_modified)
SELECT id, '{}'::jsonb, created_at, last_modified
FROM document_templates;

-- Step 2: Create draft versions with existing content
INSERT INTO template_versions (variant_id, version_number, template_model, status, created_at)
SELECT tv.id, NULL, dt.template_model, 'draft', dt.last_modified
FROM template_variants tv
JOIN document_templates dt ON tv.template_id = dt.id
WHERE dt.template_model IS NOT NULL;

-- Step 3: Remove templateModel from document_templates (content now lives in versions)
ALTER TABLE document_templates DROP COLUMN template_model;
