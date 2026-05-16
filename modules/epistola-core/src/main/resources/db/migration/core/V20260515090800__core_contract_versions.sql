-- Contract version history for template data contracts (schema + examples).
-- Enables a draft/published lifecycle for the data contract, independent of
-- the template visual content versioned in template_versions.

-- ============================================================================
-- CONTRACT VERSIONS
-- ============================================================================

CREATE TABLE contract_versions (
    id INTEGER NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    template_key TEMPLATE_KEY NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_key, catalog_key, template_key, id),
    FOREIGN KEY (tenant_key, catalog_key, template_key)
        REFERENCES document_templates(tenant_key, catalog_key, id) ON DELETE CASCADE,
    CHECK (status IN ('draft', 'published')),
    CHECK (id BETWEEN 1 AND 200)
);

-- At most one draft contract per template
CREATE UNIQUE INDEX idx_one_draft_contract_per_template
    ON contract_versions (tenant_key, catalog_key, template_key)
    WHERE status = 'draft';

-- Index for published contract lookups (latest published version per template)
CREATE INDEX idx_contract_versions_published
    ON contract_versions (tenant_key, catalog_key, template_key, id DESC)
    WHERE status = 'published';

COMMENT ON TABLE contract_versions IS 'Versioned data contracts for templates. Each version holds a JSON Schema, data model, and examples. Lifecycle: draft -> published. At most one draft per template.';
COMMENT ON COLUMN contract_versions.id IS 'Sequential version number (1-200) within the template';
COMMENT ON COLUMN contract_versions.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN contract_versions.catalog_key IS 'Catalog this contract version belongs to';
COMMENT ON COLUMN contract_versions.template_key IS 'Parent template this contract version belongs to';
COMMENT ON COLUMN contract_versions.schema IS 'JSON Schema (2020-12) defining the data contract. NULL means no validation.';
COMMENT ON COLUMN contract_versions.data_model IS 'JSON Schema describing the expected input structure (informational).';
COMMENT ON COLUMN contract_versions.data_examples IS 'Array of named sample data sets conforming to the schema.';
COMMENT ON COLUMN contract_versions.status IS 'Lifecycle state: draft (editable) or published (frozen).';
COMMENT ON COLUMN contract_versions.created_at IS 'When the contract version was created';
COMMENT ON COLUMN contract_versions.published_at IS 'When the contract version was published. NULL while in draft.';
COMMENT ON COLUMN contract_versions.created_by IS 'User who created this contract version';

-- ============================================================================
-- LINK TEMPLATE VERSIONS TO CONTRACT VERSIONS
-- ============================================================================
-- The template_versions.contract_version column lives in
-- V20260515090700__core_template_variants_versions.sql; the FK is deferred
-- here because it targets contract_versions, created just above.

ALTER TABLE template_versions
    ADD CONSTRAINT fk_template_versions_contract_version
    FOREIGN KEY (tenant_key, catalog_key, template_key, contract_version)
    REFERENCES contract_versions(tenant_key, catalog_key, template_key, id);
