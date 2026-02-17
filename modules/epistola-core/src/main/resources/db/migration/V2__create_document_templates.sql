-- Document templates table with tenant isolation
-- IDs are client-provided slugs for human-readable, URL-safe identifiers
-- Composite PK (tenant_id, id) allows different tenants to reuse the same template slug
CREATE TABLE document_templates (
    id VARCHAR(50) NOT NULL CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX idx_document_templates_last_modified ON document_templates(last_modified DESC);
