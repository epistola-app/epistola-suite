-- Replace catalog_templates with generic catalog_resources table.
-- Supports all resource types: templates, themes, stencils, attributes, assets.

-- Migrate existing data from catalog_templates to the new table.
CREATE TABLE catalog_resources (
    tenant_key            TENANT_KEY NOT NULL,
    catalog_key           VARCHAR(50) NOT NULL,
    resource_type         VARCHAR(20) NOT NULL,
    resource_slug         VARCHAR(100) NOT NULL,

    PRIMARY KEY (tenant_key, catalog_key, resource_type, resource_slug),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE,
    CHECK (resource_type IN ('template', 'theme', 'stencil', 'attribute', 'asset'))
);

COMMENT ON TABLE catalog_resources IS 'Tracks resources installed from a catalog. A resource is identified by (type, slug) within a catalog.';
COMMENT ON COLUMN catalog_resources.resource_type IS 'Resource type: template, theme, stencil, attribute, or asset.';
COMMENT ON COLUMN catalog_resources.resource_slug IS 'The slug of this resource in the origin catalog.';

-- Migrate existing catalog_templates data
INSERT INTO catalog_resources (tenant_key, catalog_key, resource_type, resource_slug)
SELECT tenant_key, catalog_key, 'template', catalog_resource_slug
FROM catalog_templates;

-- Drop old table
DROP TABLE catalog_templates;
