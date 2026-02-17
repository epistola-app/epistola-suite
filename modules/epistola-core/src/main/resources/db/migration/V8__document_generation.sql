-- Document Generation Infrastructure
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
-- Partitioned by created_at for efficient TTL enforcement via partition dropping
CREATE TABLE documents (
    id UUID NOT NULL,
    tenant_id TENANT_ID NOT NULL,
    template_id TEMPLATE_ID NOT NULL,
    variant_id VARIANT_ID NOT NULL,
    version_id INTEGER NOT NULL,
    filename VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),  -- Client-provided ID for tracking documents across systems
    content_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    size_bytes BIGINT NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    PRIMARY KEY (id, created_at),

    CONSTRAINT chk_documents_filename_not_empty CHECK (LENGTH(filename) > 0),
    CONSTRAINT chk_documents_size_positive CHECK (size_bytes > 0),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id, version_id) REFERENCES template_versions(tenant_id, variant_id, id) ON DELETE CASCADE
) PARTITION BY RANGE (created_at);

-- No initial partitions created - PartitionMaintenanceScheduler creates them at startup
-- This avoids hardcoded dates and makes migrations truly date-agnostic

-- Indexes for document queries
CREATE INDEX idx_documents_tenant_id ON documents(tenant_id);
CREATE INDEX idx_documents_template_id ON documents(tenant_id, template_id);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX idx_documents_correlation_id ON documents(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- ============================================================================
-- APPLICATION TABLES: GENERATION REQUEST TRACKING (FLATTENED STRUCTURE)
-- ============================================================================

-- Track document generation jobs.
-- Each request represents ONE document to generate.
-- Multiple requests can be grouped via batch_id for batch operations.
-- Partitioned by created_at for efficient TTL enforcement via partition dropping
CREATE TABLE document_generation_requests (
    id UUID NOT NULL,
    batch_id UUID,  -- Groups related requests. NULL for single-document requests.
    tenant_id TENANT_ID NOT NULL,
    template_id TEMPLATE_ID NOT NULL,
    variant_id VARIANT_ID NOT NULL,
    version_id INTEGER,  -- NULL = use environment to determine version
    environment_id ENVIRONMENT_ID,
    data JSONB NOT NULL,
    filename VARCHAR(255),
    correlation_id VARCHAR(255),  -- Client-provided ID for tracking documents across systems
    document_id UUID,  -- Note: FK to documents removed due to composite PK complexity
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    claimed_by VARCHAR(255),                    -- Instance identifier that claimed this job
    claimed_at TIMESTAMP WITH TIME ZONE,        -- When the job was claimed
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,  -- For auto-cleanup
    PRIMARY KEY (id, created_at),

    -- Ensure either version_id OR environment_id is set, not both
    CONSTRAINT chk_requests_version_or_environment CHECK (
        (version_id IS NOT NULL AND environment_id IS NULL)
        OR (version_id IS NULL AND environment_id IS NOT NULL)
    ),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, environment_id) REFERENCES environments(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_requests_variant_version
        FOREIGN KEY (tenant_id, variant_id, version_id)
        REFERENCES template_versions(tenant_id, variant_id, id)
        ON DELETE CASCADE
) PARTITION BY RANGE (created_at);

-- No initial partitions created - PartitionMaintenanceScheduler creates them at startup
-- This avoids hardcoded dates and makes migrations truly date-agnostic

-- Indexes for request queries
CREATE INDEX idx_generation_requests_tenant_id ON document_generation_requests(tenant_id);
CREATE INDEX idx_generation_requests_status ON document_generation_requests(status);
CREATE INDEX idx_generation_requests_batch_id ON document_generation_requests(batch_id)
    WHERE batch_id IS NOT NULL;
CREATE INDEX idx_generation_requests_template_id ON document_generation_requests(tenant_id, template_id);
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

-- Index for efficient batch counter queries (used for calculated counters)
CREATE INDEX idx_dgr_batch_status ON document_generation_requests(batch_id, status)
    WHERE batch_id IS NOT NULL;

-- ============================================================================
-- APPLICATION TABLES: BATCH METADATA (FOR AGGREGATIONS)
-- ============================================================================

-- Track batch-level metadata.
-- Counts are calculated on-demand for in-progress batches, finalized when batch completes.
CREATE TABLE document_generation_batches (
    id UUID PRIMARY KEY,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    total_count INTEGER NOT NULL,
    final_completed_count INTEGER,  -- Set when batch completes. NULL for in-progress batches.
    final_failed_count INTEGER,     -- Set when batch completes. NULL for in-progress batches.
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_batches_total_count_positive CHECK (total_count > 0)
);

CREATE INDEX idx_generation_batches_tenant_id ON document_generation_batches(tenant_id);
CREATE INDEX idx_generation_batches_created_at ON document_generation_batches(created_at DESC);

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE documents IS 'Generated documents stored as BYTEA. Partitioned by created_at for efficient TTL enforcement via partition dropping. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_requests IS 'Document generation requests. Partitioned by created_at for efficient TTL enforcement via partition dropping. Each request generates ONE document. Use batch_id to group related requests. IDs are client-provided UUIDv7.';
COMMENT ON TABLE document_generation_batches IS 'Batch metadata. Counts are calculated on-demand for in-progress batches, finalized when batch completes.';

COMMENT ON COLUMN documents.content IS 'PDF content stored as BYTEA. Future: migrate to object storage.';
COMMENT ON COLUMN documents.created_by IS 'User who created this document';
COMMENT ON COLUMN documents.correlation_id IS 'Client-provided ID for tracking documents across systems. Propagated from generation request.';

COMMENT ON COLUMN document_generation_requests.batch_id IS 'Groups related requests together. NULL for single-document requests. Used to track batch progress.';
COMMENT ON COLUMN document_generation_requests.version_id IS 'Explicit version to use. Mutually exclusive with environment_id.';
COMMENT ON COLUMN document_generation_requests.environment_id IS 'Environment to determine version from. Mutually exclusive with version_id.';
COMMENT ON COLUMN document_generation_requests.correlation_id IS 'Client-provided ID for tracking documents across systems.';
COMMENT ON COLUMN document_generation_requests.document_id IS 'Generated document ID. Note: No FK due to documents table composite PK (id, created_at). Referential integrity enforced at application level.';
COMMENT ON COLUMN document_generation_requests.claimed_by IS 'Instance identifier (hostname-pid) that claimed this job for processing.';
COMMENT ON COLUMN document_generation_requests.claimed_at IS 'Timestamp when the job was claimed. Used for stale job recovery.';

COMMENT ON COLUMN document_generation_batches.final_completed_count IS 'Final count of completed requests. Set when batch completes. NULL for in-progress batches.';
COMMENT ON COLUMN document_generation_batches.final_failed_count IS 'Final count of failed requests. Set when batch completes. NULL for in-progress batches.';
