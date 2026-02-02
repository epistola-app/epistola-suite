-- Tenants table for multi-tenancy support
-- IDs are client-provided UUIDv7 for better testability and distributed system properties
CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    default_theme_id UUID,  -- FK added in V6 after themes table exists
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);
