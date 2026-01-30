-- V5: Document Generation Infrastructure
--
-- This migration adds support for asynchronous document generation using Spring Batch.
-- It includes:
-- - Spring Batch core tables for job execution tracking
-- - Custom tables for document storage and generation request management
-- - Multi-tenant support with proper isolation
-- - BYTEA storage for generated PDFs

-- ============================================================================
-- SPRING BATCH CORE TABLES
-- ============================================================================
-- Source: https://github.com/spring-projects/spring-batch/blob/main/spring-batch-core/src/main/resources/org/springframework/batch/core/schema-postgresql.sql

CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_NAME VARCHAR(100) NOT NULL,
    JOB_KEY VARCHAR(32) NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_INSTANCE_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL,
    PARAMETER_NAME VARCHAR(100) NOT NULL,
    PARAMETER_TYPE VARCHAR(100) NOT NULL,
    PARAMETER_VALUE VARCHAR(2500),
    IDENTIFYING CHAR(1) NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT NOT NULL,
    STEP_NAME VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    COMMIT_COUNT BIGINT,
    READ_COUNT BIGINT,
    FILTER_COUNT BIGINT,
    WRITE_COUNT BIGINT,
    READ_SKIP_COUNT BIGINT,
    WRITE_SKIP_COUNT BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT BIGINT,
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

-- Spring Batch sequences
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ START WITH 0 MINVALUE 0 MAXVALUE 9223372036854775807 NO CYCLE;

-- ============================================================================
-- APPLICATION TABLES: DOCUMENT STORAGE
-- ============================================================================

-- Generated documents stored in PostgreSQL BYTEA
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id BIGINT NOT NULL REFERENCES document_templates(id),
    variant_id BIGINT NOT NULL REFERENCES template_variants(id),
    version_id BIGINT NOT NULL REFERENCES template_versions(id),
    filename VARCHAR(255) NOT NULL,
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

-- ============================================================================
-- APPLICATION TABLES: GENERATION REQUEST TRACKING
-- ============================================================================

-- Track document generation jobs (single or batch)
CREATE TABLE document_generation_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    job_type VARCHAR(20) NOT NULL CHECK (job_type IN ('SINGLE', 'BATCH')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    batch_job_execution_id BIGINT REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID),
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
CREATE INDEX idx_generation_requests_batch_job_execution_id ON document_generation_requests(batch_job_execution_id) WHERE batch_job_execution_id IS NOT NULL;
CREATE INDEX idx_generation_requests_created_at ON document_generation_requests(created_at DESC);

-- ============================================================================
-- APPLICATION TABLES: BATCH GENERATION ITEMS
-- ============================================================================

-- Individual items in a batch generation request
CREATE TABLE document_generation_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID NOT NULL REFERENCES document_generation_requests(id) ON DELETE CASCADE,
    template_id BIGINT NOT NULL REFERENCES document_templates(id),
    variant_id BIGINT NOT NULL REFERENCES template_variants(id),
    version_id BIGINT REFERENCES template_versions(id),  -- NULL = use environment to determine version
    environment_id BIGINT REFERENCES environments(id),    -- NULL = use version_id directly
    data JSONB NOT NULL,
    filename VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    document_id BIGINT REFERENCES documents(id) ON DELETE SET NULL,
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

COMMENT ON TABLE documents IS 'Generated documents stored as BYTEA. Future: migrate to S3/MinIO.';
COMMENT ON TABLE document_generation_requests IS 'Track async document generation jobs (single or batch).';
COMMENT ON TABLE document_generation_items IS 'Individual items in a batch generation request.';

COMMENT ON COLUMN documents.content IS 'PDF content stored as BYTEA. Future: migrate to object storage.';
COMMENT ON COLUMN documents.created_by IS 'User ID from Keycloak. Not yet implemented.';

COMMENT ON COLUMN document_generation_items.version_id IS 'Explicit version to use. Mutually exclusive with environment_id.';
COMMENT ON COLUMN document_generation_items.environment_id IS 'Environment to determine version from. Mutually exclusive with version_id.';
