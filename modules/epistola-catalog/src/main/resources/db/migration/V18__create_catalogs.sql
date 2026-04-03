-- Catalogs for organizing and exchanging templates between Epistola instances.
-- A catalog can be LOCAL (created here) or IMPORTED (sourced from a remote URL).

CREATE TABLE catalogs (
    id                        VARCHAR(50) NOT NULL,
    tenant_key                TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                      VARCHAR(255) NOT NULL,
    description               TEXT,
    type                      VARCHAR(20) NOT NULL DEFAULT 'IMPORTED',
    source_url                TEXT,
    source_auth_type          VARCHAR(20) DEFAULT 'NONE',
    source_auth_credential    TEXT,
    installed_release_version VARCHAR(50),
    installed_at              TIMESTAMP WITH TIME ZONE,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, id),
    CHECK (type IN ('LOCAL', 'IMPORTED')),
    CHECK (source_auth_type IS NULL OR source_auth_type IN ('NONE', 'API_KEY', 'BEARER'))
);

COMMENT ON TABLE catalogs IS 'Catalogs group templates for exchange between Epistola instances.';
COMMENT ON COLUMN catalogs.type IS 'LOCAL = created in this instance, IMPORTED = sourced from a remote URL.';
COMMENT ON COLUMN catalogs.source_url IS 'Remote catalog manifest URL. Required for IMPORTED, null for LOCAL.';
COMMENT ON COLUMN catalogs.installed_release_version IS 'Version label of the currently installed release. For IMPORTED only.';

-- Join table linking templates to catalogs.
-- A template can belong to at most one catalog.

CREATE TABLE catalog_templates (
    tenant_key            TENANT_KEY NOT NULL,
    catalog_key           VARCHAR(50) NOT NULL,
    template_key          VARCHAR(50) NOT NULL,
    catalog_resource_slug TEXT NOT NULL,

    PRIMARY KEY (tenant_key, catalog_key, template_key),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, template_key) REFERENCES document_templates(tenant_key, id) ON DELETE CASCADE,
    UNIQUE (tenant_key, template_key)
);

COMMENT ON TABLE catalog_templates IS 'Links templates to their parent catalog. A template belongs to at most one catalog.';
COMMENT ON COLUMN catalog_templates.catalog_resource_slug IS 'The slug of this template in the origin catalog. May differ from the local template slug if remapped on install.';
