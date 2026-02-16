-- V8: Load Test Infrastructure
--
-- This migration adds support for embedded load testing of document generation.
-- It includes:
-- - Load test run tracking with aggregated metrics
-- - Per-request timing and result tracking
-- - Support for high concurrency (100+ concurrent requests)
-- - Automatic cleanup of test documents
--
-- IDs are client-provided UUIDv7 for better testability and distributed system properties

-- ============================================================================
-- APPLICATION TABLES: LOAD TEST RUNS
-- ============================================================================

-- Track load test runs with aggregated results
CREATE TABLE load_test_runs (
    id UUID PRIMARY KEY,
    batch_id UUID UNIQUE,                       -- Links to document_generation_batches and document_generation_requests
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id VARCHAR(50) NOT NULL,
    variant_id VARCHAR(50) NOT NULL,
    version_id INTEGER,  -- NULL = use environment
    environment_id VARCHAR(30),

    -- Configuration
    target_count INTEGER NOT NULL CHECK (target_count BETWEEN 1 AND 10000),
    concurrency_level INTEGER NOT NULL CHECK (concurrency_level BETWEEN 1 AND 500),
    test_data JSONB NOT NULL,

    -- Status
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    claimed_by VARCHAR(255),                    -- Instance identifier that claimed this run
    claimed_at TIMESTAMP WITH TIME ZONE,        -- When the run was claimed

    -- Aggregated results (populated after completion)
    completed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    total_duration_ms BIGINT,
    avg_response_time_ms DOUBLE PRECISION,
    min_response_time_ms BIGINT,
    max_response_time_ms BIGINT,
    p50_response_time_ms BIGINT,
    p95_response_time_ms BIGINT,
    p99_response_time_ms BIGINT,
    requests_per_second DOUBLE PRECISION,
    success_rate_percent DOUBLE PRECISION,
    error_summary JSONB,  -- {"ErrorType": count}
    metrics JSONB,        -- Detailed metrics: {"avg_ms": 1234, "min_ms": 500, "max_ms": 5000, "p50_ms": 1100, "p95_ms": 2500, "p99_ms": 3200, "rps": 245.5}

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,

    -- Ensure either version_id OR environment_id is set, not both
    CONSTRAINT chk_load_test_version_or_environment CHECK (
        (version_id IS NOT NULL AND environment_id IS NULL)
        OR (version_id IS NULL AND environment_id IS NOT NULL)
    ),
    CONSTRAINT chk_ltr_completed_count_non_negative CHECK (completed_count >= 0),
    CONSTRAINT chk_ltr_failed_count_non_negative CHECK (failed_count >= 0),
    CONSTRAINT chk_ltr_count_sum CHECK (completed_count + failed_count <= target_count),
    FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, environment_id) REFERENCES environments(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id, version_id) REFERENCES template_versions(tenant_id, variant_id, id) ON DELETE CASCADE
);

-- Indexes for load test run queries
CREATE INDEX idx_load_test_runs_tenant_id ON load_test_runs(tenant_id);
CREATE INDEX idx_load_test_runs_status ON load_test_runs(status);
CREATE INDEX idx_load_test_runs_created_at ON load_test_runs(created_at DESC);
CREATE INDEX idx_load_test_runs_batch_id ON load_test_runs(batch_id) WHERE batch_id IS NOT NULL;

-- Index for efficient polling: find PENDING runs ordered by creation time
CREATE INDEX idx_ltr_pending_poll ON load_test_runs(status, created_at)
    WHERE status = 'PENDING';

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE load_test_runs IS 'Load test runs with aggregated performance metrics. IDs are client-provided UUIDv7. Request details queried from document_generation_requests via batch_id (no duplication).';

COMMENT ON COLUMN load_test_runs.batch_id IS 'Links to document_generation_batches.id and document_generation_requests.batch_id. Used to query request details directly from source (single source of truth).';
COMMENT ON COLUMN load_test_runs.claimed_by IS 'Instance identifier (hostname-pid) that claimed this run for processing.';
COMMENT ON COLUMN load_test_runs.claimed_at IS 'Timestamp when the run was claimed. Used for stale job recovery.';
COMMENT ON COLUMN load_test_runs.test_data IS 'JSON data used for all document generation requests in this test.';
COMMENT ON COLUMN load_test_runs.error_summary IS 'Map of error types to occurrence counts. Example: {"VALIDATION": 5, "TIMEOUT": 2}';
COMMENT ON COLUMN load_test_runs.metrics IS 'Detailed performance metrics stored as JSONB. Example: {"avg_ms": 1234, "min_ms": 500, "max_ms": 5000, "p50_ms": 1100, "p95_ms": 2500, "p99_ms": 3200, "rps": 245.5, "success_rate_percent": 98.5}';
