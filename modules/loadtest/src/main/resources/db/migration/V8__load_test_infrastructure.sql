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
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id VARCHAR(50) NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id INTEGER,  -- NULL = use environment
    environment_id VARCHAR(30) REFERENCES environments(id) ON DELETE CASCADE,

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
    FOREIGN KEY (variant_id, version_id) REFERENCES template_versions(variant_id, id) ON DELETE CASCADE
);

-- Indexes for load test run queries
CREATE INDEX idx_load_test_runs_tenant_id ON load_test_runs(tenant_id);
CREATE INDEX idx_load_test_runs_status ON load_test_runs(status);
CREATE INDEX idx_load_test_runs_created_at ON load_test_runs(created_at DESC);

-- Index for efficient polling: find PENDING runs ordered by creation time
CREATE INDEX idx_ltr_pending_poll ON load_test_runs(status, created_at)
    WHERE status = 'PENDING';

-- ============================================================================
-- APPLICATION TABLES: LOAD TEST REQUESTS
-- ============================================================================

-- Track individual requests within a load test run (for debugging and detailed analysis)
CREATE TABLE load_test_requests (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES load_test_runs(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,

    -- Timing
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,

    -- Result
    success BOOLEAN NOT NULL,
    error_message TEXT,
    error_type VARCHAR(100),
    document_id UUID,  -- Reference to generated document (will be deleted after test)

    CONSTRAINT chk_ltr_duration CHECK (
        (completed_at IS NOT NULL AND duration_ms IS NOT NULL)
        OR (completed_at IS NULL AND duration_ms IS NULL)
    ),
    CONSTRAINT chk_ltr_sequence_positive CHECK (sequence_number > 0)
);

-- Indexes for load test request queries
CREATE INDEX idx_load_test_requests_run_id ON load_test_requests(run_id);
CREATE INDEX idx_ltr_duration ON load_test_requests(run_id, duration_ms) WHERE duration_ms IS NOT NULL;
CREATE INDEX idx_ltr_sequence ON load_test_requests(run_id, sequence_number);

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE load_test_runs IS 'Load test runs with aggregated performance metrics. IDs are client-provided UUIDv7.';
COMMENT ON TABLE load_test_requests IS 'Individual requests within a load test run. Auto-deleted after 7 days.';

COMMENT ON COLUMN load_test_runs.claimed_by IS 'Instance identifier (hostname-pid) that claimed this run for processing.';
COMMENT ON COLUMN load_test_runs.claimed_at IS 'Timestamp when the run was claimed. Used for stale job recovery.';
COMMENT ON COLUMN load_test_runs.test_data IS 'JSON data used for all document generation requests in this test.';
COMMENT ON COLUMN load_test_runs.error_summary IS 'Map of error types to occurrence counts. Example: {"VALIDATION": 5, "TIMEOUT": 2}';

COMMENT ON COLUMN load_test_requests.sequence_number IS '1-based sequence number within the test run (1 to target_count).';
COMMENT ON COLUMN load_test_requests.document_id IS 'NULL for load tests (PDFs rendered to memory only, not saved to database).';
