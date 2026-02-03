-- V5: Document Generation Infrastructure
--
-- This migration adds support for asynchronous document generation.
-- It includes:
-- - Custom tables for document storage and generation request management
-- - Multi-tenant support with proper isolation
-- - BYTEA storage for generated PDFs
-- - Polling-based job execution with instance claiming
--
-- IDs are client-provided UUIDv7 for better testability and distributed system properties

-- ============================================================================
-- APPLICATION TABLES: DOCUMENT STORAGE
-- ============================================================================

-- Generated documents stored in PostgreSQL BYTEA
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id VARCHAR(50) NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES template_versions(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),  -- Client-provided ID for tracking documents across systems
    content_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    size_bytes BIGINT NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),  -- Future: user ID from Keycloak

    CONSTRAINT chk_documents_filename_not_empty CHECK (LENGTH(filename) > 0),
    CONSTRAINT chk_documents_size_positive CHECK (size_bytes > 0)
);

-- Indexes for document queries
CREATE INDEX idx_documents_tenant_id ON documents(tenant_id);
CREATE INDEX idx_documents_template_id ON documents(template_id);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX idx_documents_correlation_id ON documents(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- ============================================================================
-- APPLICATION TABLES: GENERATION REQUEST TRACKING
-- ============================================================================

-- Track document generation jobs (single or batch)
CREATE TABLE document_generation_requests (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    job_type VARCHAR(20) NOT NULL CHECK (job_type IN ('SINGLE', 'BATCH')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    claimed_by VARCHAR(255),                    -- Instance identifier that claimed this job
    claimed_at TIMESTAMP WITH TIME ZONE,        -- When the job was claimed
    total_count INTEGER NOT NULL DEFAULT 1,
    completed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,  -- For auto-cleanup

    CONSTRAINT chk_requests_total_count_positive CHECK (total_count > 0),
    CONSTRAINT chk_requests_completed_count_non_negative CHECK (completed_count >= 0),
    CONSTRAINT chk_requests_failed_count_non_negative CHECK (failed_count >= 0),
    CONSTRAINT chk_requests_count_sum CHECK (completed_count + failed_count <= total_count)
);

-- Indexes for request queries
CREATE INDEX idx_generation_requests_tenant_id ON document_generation_requests(tenant_id);
CREATE INDEX idx_generation_requests_status ON document_generation_requests(status);
CREATE INDEX idx_generation_requests_expires_at ON document_generation_requests(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_generation_requests_created_at ON document_generation_requests(created_at DESC);

-- Index for efficient polling: find PENDING requests ordered by creation time
CREATE INDEX idx_dgr_pending_poll ON document_generation_requests(status, created_at)
    WHERE status = 'PENDING';

-- ============================================================================
-- APPLICATION TABLES: BATCH GENERATION ITEMS
-- ============================================================================

-- Individual items in a batch generation request
CREATE TABLE document_generation_items (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES document_generation_requests(id) ON DELETE CASCADE,
    template_id VARCHAR(50) NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id UUID REFERENCES template_versions(id) ON DELETE CASCADE,  -- NULL = use environment to determine version
    environment_id UUID REFERENCES environments(id) ON DELETE CASCADE,    -- NULL = use version_id directly
    data JSONB NOT NULL,
    filename VARCHAR(255),
    correlation_id VARCHAR(255),  -- Client-provided ID for tracking documents across systems
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,

    -- Ensure either version_id OR environment_id is set, not both
    CONSTRAINT chk_items_version_or_environment CHECK (
        (version_id IS NOT NULL AND environment_id IS NULL)
        OR (version_id IS NULL AND environment_id IS NOT NULL)
    )
);

-- Indexes for item queries
CREATE INDEX idx_generation_items_request_id ON document_generation_items(request_id);
CREATE INDEX idx_generation_items_status ON document_generation_items(status);
CREATE INDEX idx_generation_items_document_id ON document_generation_items(document_id) WHERE document_id IS NOT NULL;

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE documents IS 'Generated documents stored as BYTEA. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_requests IS 'Track async document generation jobs. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_items IS 'Individual items in a batch generation request. IDs are client-provided UUIDv7.';

COMMENT ON COLUMN documents.content IS 'PDF content stored as BYTEA. Future: migrate to object storage.';
COMMENT ON COLUMN documents.created_by IS 'User ID from Keycloak. Not yet implemented.';

COMMENT ON COLUMN document_generation_requests.claimed_by IS 'Instance identifier (hostname-pid) that claimed this job for processing.';
COMMENT ON COLUMN document_generation_requests.claimed_at IS 'Timestamp when the job was claimed. Used for stale job recovery.';

COMMENT ON COLUMN document_generation_items.version_id IS 'Explicit version to use. Mutually exclusive with environment_id.';
COMMENT ON COLUMN document_generation_items.environment_id IS 'Environment to determine version from. Mutually exclusive with version_id.';
COMMENT ON COLUMN document_generation_items.correlation_id IS 'Client-provided ID for tracking documents across systems. Must be unique within a batch.';

COMMENT ON COLUMN documents.correlation_id IS 'Client-provided ID for tracking documents across systems. Propagated from generation item.';
