-- Catalogs for organizing and exchanging templates between Epistola instances.
-- A catalog can be AUTHORED (created here) or SUBSCRIBED (sourced from a remote URL).
-- Every resource (template, theme, stencil, attribute, asset) belongs to exactly one catalog.
-- The 'default' catalog is created automatically with each tenant.

CREATE TABLE catalogs (
    id                        VARCHAR(50) NOT NULL,
    tenant_key                TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                      VARCHAR(255) NOT NULL,
    description               TEXT,
    type                      VARCHAR(20) NOT NULL DEFAULT 'SUBSCRIBED',
    source_url                TEXT,
    source_auth_type          VARCHAR(20) DEFAULT 'NONE',
    source_auth_credential    TEXT,
    installed_release_version VARCHAR(50),
    installed_at              TIMESTAMP WITH TIME ZONE,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, id),
    CHECK (type IN ('AUTHORED', 'SUBSCRIBED')),
    CHECK (source_auth_type IS NULL OR source_auth_type IN ('NONE', 'API_KEY', 'BEARER'))
);

COMMENT ON TABLE catalogs IS 'Catalogs group resources for exchange between Epistola instances. Every resource belongs to exactly one catalog.';
COMMENT ON COLUMN catalogs.type IS 'AUTHORED = created in this instance, SUBSCRIBED = sourced from a remote URL.';
COMMENT ON COLUMN catalogs.source_url IS 'Remote catalog manifest URL. Required for SUBSCRIBED, null for AUTHORED.';
COMMENT ON COLUMN catalogs.installed_release_version IS 'Version label of the currently installed release. For SUBSCRIBED only.';
