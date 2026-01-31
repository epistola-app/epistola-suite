-- V6: Migrate all entity IDs to UUIDv7
--
-- This migration drops and recreates all tables with UUID primary keys instead of BIGSERIAL.
-- Client-provided UUIDv7 IDs enable better testability and predictability.
--
-- NOTE: This is a breaking change. Only acceptable because the project is NOT yet in production.

-- ============================================================================
-- DROP ALL TABLES (reverse dependency order)
-- ============================================================================

DROP TABLE IF EXISTS document_generation_items CASCADE;
DROP TABLE IF EXISTS document_generation_requests CASCADE;
DROP TABLE IF EXISTS documents CASCADE;
DROP TABLE IF EXISTS environment_activations CASCADE;
DROP TABLE IF EXISTS template_versions CASCADE;
DROP TABLE IF EXISTS template_variants CASCADE;
DROP TABLE IF EXISTS environments CASCADE;
DROP TABLE IF EXISTS document_templates CASCADE;
DROP TABLE IF EXISTS tenants CASCADE;
-- Note: app_metadata does not have entity IDs, keeping it unchanged

-- ============================================================================
-- RECREATE TABLES WITH UUID PRIMARY KEYS
-- ============================================================================

-- Tenants table for multi-tenancy support
CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);

-- Document templates table with tenant isolation
CREATE TABLE document_templates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_templates_tenant_id ON document_templates(tenant_id);
CREATE INDEX idx_document_templates_last_modified ON document_templates(last_modified DESC);

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

-- Enforce at most one draft per variant
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

-- Generated documents stored in PostgreSQL BYTEA
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES template_versions(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),
    content_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    size_bytes BIGINT NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),

    CONSTRAINT chk_documents_filename_not_empty CHECK (LENGTH(filename) > 0),
    CONSTRAINT chk_documents_size_positive CHECK (size_bytes > 0)
);

CREATE INDEX idx_documents_tenant_id ON documents(tenant_id);
CREATE INDEX idx_documents_template_id ON documents(template_id);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX idx_documents_correlation_id ON documents(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Track document generation jobs (single or batch)
CREATE TABLE document_generation_requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    job_type VARCHAR(20) NOT NULL CHECK (job_type IN ('SINGLE', 'BATCH')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    claimed_by VARCHAR(255),
    claimed_at TIMESTAMP WITH TIME ZONE,
    total_count INTEGER NOT NULL DEFAULT 1,
    completed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_requests_total_count_positive CHECK (total_count > 0),
    CONSTRAINT chk_requests_completed_count_non_negative CHECK (completed_count >= 0),
    CONSTRAINT chk_requests_failed_count_non_negative CHECK (failed_count >= 0),
    CONSTRAINT chk_requests_count_sum CHECK (completed_count + failed_count <= total_count)
);

CREATE INDEX idx_generation_requests_tenant_id ON document_generation_requests(tenant_id);
CREATE INDEX idx_generation_requests_status ON document_generation_requests(status);
CREATE INDEX idx_generation_requests_expires_at ON document_generation_requests(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_generation_requests_created_at ON document_generation_requests(created_at DESC);
CREATE INDEX idx_dgr_pending_poll ON document_generation_requests(status, created_at)
    WHERE status = 'PENDING';

-- Individual items in a batch generation request
CREATE TABLE document_generation_items (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES document_generation_requests(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id UUID REFERENCES template_versions(id) ON DELETE CASCADE,
    environment_id UUID REFERENCES environments(id) ON DELETE CASCADE,
    data JSONB NOT NULL,
    filename VARCHAR(255),
    correlation_id VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_items_version_or_environment CHECK (
        (version_id IS NOT NULL AND environment_id IS NULL)
        OR (version_id IS NULL AND environment_id IS NOT NULL)
    )
);

CREATE INDEX idx_generation_items_request_id ON document_generation_items(request_id);
CREATE INDEX idx_generation_items_status ON document_generation_items(status);
CREATE INDEX idx_generation_items_document_id ON document_generation_items(document_id) WHERE document_id IS NOT NULL;

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE tenants IS 'Multi-tenant isolation. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_templates IS 'Document templates with schema and data model. IDs are client-provided UUIDv7.';
COMMENT ON TABLE environments IS 'Deployment environments (staging, production, etc.). IDs are client-provided UUIDv7.';
COMMENT ON TABLE template_variants IS 'Template variations (language, brand, etc.). IDs are client-provided UUIDv7.';
COMMENT ON TABLE template_versions IS 'Version history with lifecycle states. IDs are client-provided UUIDv7.';
COMMENT ON TABLE documents IS 'Generated documents stored as BYTEA. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_requests IS 'Async document generation jobs. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_items IS 'Individual items in batch generation. IDs are client-provided UUIDv7.';
