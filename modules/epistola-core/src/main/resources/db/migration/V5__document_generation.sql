-- V5: Document Generation Infrastructure
--
-- This migration adds support for asynchronous document generation with a flattened structure.
--
-- Key design decisions:
-- - Each request represents ONE document (not a container for N items)
-- - batch_id column groups related requests together for batch operations
-- - Enables true horizontal scaling (each request can be claimed independently by any instance)
-- - IDs are client-provided UUIDv7 for better testability and distributed system properties
--
-- Performance characteristics:
-- Example: 10,000-document batch = 10,000 requests with same batch_id
-- All instances can claim requests in parallel (not bottlenecked to single instance)

-- ============================================================================
-- APPLICATION TABLES: DOCUMENT STORAGE
-- ============================================================================

-- Generated documents stored in PostgreSQL BYTEA
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id VARCHAR(50) NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id INTEGER NOT NULL,
    filename VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),  -- Client-provided ID for tracking documents across systems
    content_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    size_bytes BIGINT NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),  -- Future: user ID from Keycloak

    CONSTRAINT chk_documents_filename_not_empty CHECK (LENGTH(filename) > 0),
    CONSTRAINT chk_documents_size_positive CHECK (size_bytes > 0),
    FOREIGN KEY (variant_id, version_id) REFERENCES template_versions(variant_id, id) ON DELETE CASCADE
);

-- Indexes for document queries
CREATE INDEX idx_documents_tenant_id ON documents(tenant_id);
CREATE INDEX idx_documents_template_id ON documents(template_id);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX idx_documents_correlation_id ON documents(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- ============================================================================
-- APPLICATION TABLES: GENERATION REQUEST TRACKING (FLATTENED STRUCTURE)
-- ============================================================================

-- Track document generation jobs.
-- Each request represents ONE document to generate.
-- Multiple requests can be grouped via batch_id for batch operations.
CREATE TABLE document_generation_requests (
    id UUID PRIMARY KEY,
    batch_id UUID,  -- Groups related requests. NULL for single-document requests.
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id VARCHAR(50) NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id INTEGER,  -- NULL = use environment to determine version
    environment_id VARCHAR(30) REFERENCES environments(id) ON DELETE CASCADE,  -- NULL = use version_id directly
    data JSONB NOT NULL,
    filename VARCHAR(255),
    correlation_id VARCHAR(255),  -- Client-provided ID for tracking documents across systems
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    claimed_by VARCHAR(255),                    -- Instance identifier that claimed this job
    claimed_at TIMESTAMP WITH TIME ZONE,        -- When the job was claimed
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,  -- For auto-cleanup

    -- Ensure either version_id OR environment_id is set, not both
    CONSTRAINT chk_requests_version_or_environment CHECK (
        (version_id IS NOT NULL AND environment_id IS NULL)
        OR (version_id IS NULL AND environment_id IS NOT NULL)
    ),
    -- Foreign key to template_versions when version_id is specified
    CONSTRAINT fk_requests_variant_version
        FOREIGN KEY (variant_id, version_id)
        REFERENCES template_versions(variant_id, id)
        ON DELETE CASCADE
);

-- Indexes for request queries
CREATE INDEX idx_generation_requests_tenant_id ON document_generation_requests(tenant_id);
CREATE INDEX idx_generation_requests_status ON document_generation_requests(status);
CREATE INDEX idx_generation_requests_batch_id ON document_generation_requests(batch_id)
    WHERE batch_id IS NOT NULL;
CREATE INDEX idx_generation_requests_template_id ON document_generation_requests(template_id);
CREATE INDEX idx_generation_requests_correlation_id ON document_generation_requests(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_generation_requests_document_id ON document_generation_requests(document_id)
    WHERE document_id IS NOT NULL;
CREATE INDEX idx_generation_requests_expires_at ON document_generation_requests(expires_at)
    WHERE expires_at IS NOT NULL;
CREATE INDEX idx_generation_requests_created_at ON document_generation_requests(created_at DESC);

-- Index for efficient polling: find PENDING requests ordered by creation time
CREATE INDEX idx_dgr_pending_poll ON document_generation_requests(status, created_at)
    WHERE status = 'PENDING';

-- ============================================================================
-- APPLICATION TABLES: BATCH METADATA (FOR AGGREGATIONS)
-- ============================================================================

-- Track batch-level metadata and aggregated counts.
-- Updated atomically as individual requests complete.
CREATE TABLE document_generation_batches (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    total_count INTEGER NOT NULL,
    completed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_batches_total_count_positive CHECK (total_count > 0),
    CONSTRAINT chk_batches_completed_count_non_negative CHECK (completed_count >= 0),
    CONSTRAINT chk_batches_failed_count_non_negative CHECK (failed_count >= 0),
    CONSTRAINT chk_batches_count_sum CHECK (completed_count + failed_count <= total_count)
);

CREATE INDEX idx_generation_batches_tenant_id ON document_generation_batches(tenant_id);
CREATE INDEX idx_generation_batches_created_at ON document_generation_batches(created_at DESC);

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE documents IS 'Generated documents stored as BYTEA. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_requests IS 'Document generation requests. Each request generates ONE document. Use batch_id to group related requests. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_batches IS 'Batch metadata and aggregated counts. Updated as individual requests complete.';

COMMENT ON COLUMN documents.content IS 'PDF content stored as BYTEA. Future: migrate to object storage.';
COMMENT ON COLUMN documents.created_by IS 'User ID from Keycloak. Not yet implemented.';
COMMENT ON COLUMN documents.correlation_id IS 'Client-provided ID for tracking documents across systems. Propagated from generation request.';

COMMENT ON COLUMN document_generation_requests.batch_id IS 'Groups related requests together. NULL for single-document requests. Used to track batch progress.';
COMMENT ON COLUMN document_generation_requests.version_id IS 'Explicit version to use. Mutually exclusive with environment_id.';
COMMENT ON COLUMN document_generation_requests.environment_id IS 'Environment to determine version from. Mutually exclusive with version_id.';
COMMENT ON COLUMN document_generation_requests.correlation_id IS 'Client-provided ID for tracking documents across systems.';
COMMENT ON COLUMN document_generation_requests.document_id IS 'Generated document. Set when request completes successfully.';
COMMENT ON COLUMN document_generation_requests.claimed_by IS 'Instance identifier (hostname-pid) that claimed this job for processing.';
COMMENT ON COLUMN document_generation_requests.claimed_at IS 'Timestamp when the job was claimed. Used for stale job recovery.';
