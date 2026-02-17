-- Document Generation Infrastructure
--
-- Supports asynchronous document generation with a flattened structure.
-- Each request represents ONE document. batch_id groups related requests.
-- Enables horizontal scaling: any instance can claim requests independently.
-- IDs are client-provided UUIDv7 for testability and distributed system properties.

-- ============================================================================
-- DOCUMENTS
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
    correlation_id VARCHAR(255),
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

CREATE INDEX idx_documents_tenant_id ON documents(tenant_id);
CREATE INDEX idx_documents_template_id ON documents(tenant_id, template_id);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX idx_documents_correlation_id ON documents(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

COMMENT ON TABLE documents IS 'Generated PDF documents stored as BYTEA. Partitioned by created_at for TTL enforcement via partition dropping.';
COMMENT ON COLUMN documents.id IS 'Client-provided UUIDv7';
COMMENT ON COLUMN documents.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN documents.template_id IS 'Template used for generation';
COMMENT ON COLUMN documents.variant_id IS 'Variant used for generation';
COMMENT ON COLUMN documents.version_id IS 'Version number used for generation';
COMMENT ON COLUMN documents.filename IS 'Output filename (e.g., invoice-2026-001.pdf)';
COMMENT ON COLUMN documents.correlation_id IS 'Client-provided ID for tracking documents across external systems';
COMMENT ON COLUMN documents.content_type IS 'MIME type of the generated document';
COMMENT ON COLUMN documents.size_bytes IS 'Document size in bytes';
COMMENT ON COLUMN documents.content IS 'Raw document bytes (PDF). Future: migrate to object storage.';
COMMENT ON COLUMN documents.created_at IS 'When the document was generated';
COMMENT ON COLUMN documents.created_by IS 'User who triggered the generation';

-- ============================================================================
-- GENERATION REQUESTS
-- ============================================================================

-- Each request represents ONE document to generate.
-- Partitioned by created_at for efficient TTL enforcement via partition dropping.
CREATE TABLE document_generation_requests (
    id UUID NOT NULL,
    batch_id UUID,
    tenant_id TENANT_ID NOT NULL,
    template_id TEMPLATE_ID NOT NULL,
    variant_id VARIANT_ID NOT NULL,
    version_id INTEGER,
    environment_id ENVIRONMENT_ID,
    data JSONB NOT NULL,
    filename VARCHAR(255),
    correlation_id VARCHAR(255),
    document_id UUID,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    claimed_by VARCHAR(255),
    claimed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
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
CREATE INDEX idx_dgr_pending_poll ON document_generation_requests(status, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_dgr_batch_status ON document_generation_requests(batch_id, status)
    WHERE batch_id IS NOT NULL;

COMMENT ON TABLE document_generation_requests IS 'Async document generation jobs. One request = one document. Partitioned by created_at for TTL enforcement.';
COMMENT ON COLUMN document_generation_requests.id IS 'Client-provided UUIDv7';
COMMENT ON COLUMN document_generation_requests.batch_id IS 'Groups related requests for batch tracking. NULL for single-document requests.';
COMMENT ON COLUMN document_generation_requests.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN document_generation_requests.template_id IS 'Template to generate from';
COMMENT ON COLUMN document_generation_requests.variant_id IS 'Variant to generate from';
COMMENT ON COLUMN document_generation_requests.version_id IS 'Explicit version number. Mutually exclusive with environment_id.';
COMMENT ON COLUMN document_generation_requests.environment_id IS 'Environment to resolve the active version from. Mutually exclusive with version_id.';
COMMENT ON COLUMN document_generation_requests.data IS 'Input data for template rendering (must conform to template schema)';
COMMENT ON COLUMN document_generation_requests.filename IS 'Requested output filename. NULL uses a generated default.';
COMMENT ON COLUMN document_generation_requests.correlation_id IS 'Client-provided ID for tracking across external systems';
COMMENT ON COLUMN document_generation_requests.document_id IS 'Generated document ID. No FK due to composite PK on documents. Enforced at application level.';
COMMENT ON COLUMN document_generation_requests.status IS 'Job state: PENDING -> IN_PROGRESS -> COMPLETED/FAILED/CANCELLED';
COMMENT ON COLUMN document_generation_requests.claimed_by IS 'Instance identifier (hostname-pid) that claimed this job';
COMMENT ON COLUMN document_generation_requests.claimed_at IS 'When the job was claimed. Used for stale job detection.';
COMMENT ON COLUMN document_generation_requests.error_message IS 'Error details when status is FAILED';
COMMENT ON COLUMN document_generation_requests.created_at IS 'When the request was submitted';
COMMENT ON COLUMN document_generation_requests.started_at IS 'When generation began (status moved to IN_PROGRESS)';
COMMENT ON COLUMN document_generation_requests.completed_at IS 'When generation finished (status moved to COMPLETED/FAILED)';
COMMENT ON COLUMN document_generation_requests.expires_at IS 'Auto-cleanup timestamp. Requests past this time may be purged.';

-- ============================================================================
-- GENERATION BATCHES
-- ============================================================================

-- Batch-level metadata for grouped generation requests.
CREATE TABLE document_generation_batches (
    id UUID PRIMARY KEY,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    total_count INTEGER NOT NULL,
    final_completed_count INTEGER,
    final_failed_count INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_batches_total_count_positive CHECK (total_count > 0)
);

CREATE INDEX idx_generation_batches_tenant_id ON document_generation_batches(tenant_id);
CREATE INDEX idx_generation_batches_created_at ON document_generation_batches(created_at DESC);

COMMENT ON TABLE document_generation_batches IS 'Batch metadata for grouped generation requests. Counts are calculated on-demand while in progress, finalized on completion.';
COMMENT ON COLUMN document_generation_batches.id IS 'Batch ID matching document_generation_requests.batch_id';
COMMENT ON COLUMN document_generation_batches.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN document_generation_batches.total_count IS 'Total number of requests in this batch';
COMMENT ON COLUMN document_generation_batches.final_completed_count IS 'Final count of successful requests. NULL while batch is in progress.';
COMMENT ON COLUMN document_generation_batches.final_failed_count IS 'Final count of failed requests. NULL while batch is in progress.';
COMMENT ON COLUMN document_generation_batches.created_at IS 'When the batch was created';
COMMENT ON COLUMN document_generation_batches.completed_at IS 'When all requests in the batch finished. NULL while in progress.';
