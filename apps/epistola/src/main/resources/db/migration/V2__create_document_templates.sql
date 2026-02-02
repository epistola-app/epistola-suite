-- Document templates table with tenant isolation
-- IDs are client-provided UUIDv7 for better testability and distributed system properties
CREATE TABLE document_templates (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_templates_tenant_id ON document_templates(tenant_id);
CREATE INDEX idx_document_templates_last_modified ON document_templates(last_modified DESC);
