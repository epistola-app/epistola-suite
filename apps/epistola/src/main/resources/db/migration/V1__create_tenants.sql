-- Tenants table for multi-tenancy support
-- IDs are client-provided slugs (e.g., "acme-corp") for human-readable, URL-safe identifiers
CREATE TABLE tenants (
    id VARCHAR(63) PRIMARY KEY
        CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name VARCHAR(255) NOT NULL,
    default_theme_id VARCHAR(20),  -- FK added in V6 after themes table exists
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);
