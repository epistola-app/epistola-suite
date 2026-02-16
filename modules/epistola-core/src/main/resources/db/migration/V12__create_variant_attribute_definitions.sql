CREATE TABLE variant_attribute_definitions (
    id          VARCHAR(50) NOT NULL,
    tenant_id   VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL,
    allowed_values JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id)
);
