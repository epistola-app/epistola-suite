-- Document templates table with tenant isolation
CREATE TABLE document_templates (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    template_model jsonb,
    schema JSONB,
    data_model jsonb,
    data_examples jsonb DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_templates_tenant_id ON document_templates(tenant_id);
CREATE INDEX idx_document_templates_last_modified ON document_templates(last_modified DESC);
