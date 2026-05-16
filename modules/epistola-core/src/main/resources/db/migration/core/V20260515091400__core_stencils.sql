-- Stencils: reusable, versioned template components
-- Follows the same patterns as templates/variants/versions:
--   - Composite PKs for tenant and catalog isolation
--   - JSONB for content (template model fragments)
--   - Version lifecycle: draft -> published -> archived
--   - At most one draft per stencil

-- Domain type for stencil identifiers (reuses slug pattern)
CREATE DOMAIN STENCIL_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- ============================================================================
-- STENCILS
-- ============================================================================

CREATE TABLE stencils (
    id STENCIL_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    name VARCHAR(255) NOT NULL,
    description TEXT,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (tenant_key, catalog_key, id),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs(tenant_key, id) ON DELETE CASCADE
);

CREATE INDEX idx_stencils_tenant_updated_at ON stencils(tenant_key, updated_at DESC);

-- GIN index for tag-based filtering
CREATE INDEX idx_stencils_tags_gin ON stencils USING GIN (tags);

COMMENT ON TABLE stencils IS 'Reusable template components that can be inserted into any template. Each stencil has versioned content following the same lifecycle as template versions.';
COMMENT ON COLUMN stencils.id IS 'URL-safe slug identifier unique within the catalog (e.g., corporate-header)';
COMMENT ON COLUMN stencils.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN stencils.catalog_key IS 'Catalog this stencil belongs to';
COMMENT ON COLUMN stencils.name IS 'Display name of the stencil';
COMMENT ON COLUMN stencils.description IS 'Optional description of what this stencil provides';
COMMENT ON COLUMN stencils.tags IS 'JSON array of tags for categorization and search';
COMMENT ON COLUMN stencils.created_at IS 'When the stencil was created';
COMMENT ON COLUMN stencils.updated_at IS 'When the stencil was last modified';
COMMENT ON COLUMN stencils.created_by IS 'User who created this stencil (NULL if the user was deleted)';
COMMENT ON COLUMN stencils.updated_by IS 'User who last modified this stencil (NULL if the user was deleted)';

-- ============================================================================
-- STENCIL VERSIONS
-- ============================================================================

-- parameter_schema describes the inputs a consumer must bind when inserting
-- this version into a template (JSON Schema; NULL = no parameters).
CREATE TABLE stencil_versions (
    id INTEGER NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    stencil_key STENCIL_KEY NOT NULL,
    content JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    parameter_schema JSONB,
    PRIMARY KEY (tenant_key, catalog_key, stencil_key, id),
    FOREIGN KEY (tenant_key, catalog_key, stencil_key) REFERENCES stencils(tenant_key, catalog_key, id) ON DELETE CASCADE,
    CHECK (status IN ('draft', 'published', 'archived')),
    CHECK (id BETWEEN 1 AND 200)
);

CREATE INDEX idx_stencil_versions_status ON stencil_versions(status);

-- GIN index for content traversal (e.g., detecting nested stencil refs)
CREATE INDEX idx_stencil_versions_content_gin
    ON stencil_versions USING GIN (content);

-- Enforce at most one draft per stencil
CREATE UNIQUE INDEX idx_one_draft_per_stencil
    ON stencil_versions (tenant_key, catalog_key, stencil_key)
    WHERE status = 'draft';

COMMENT ON TABLE stencil_versions IS 'Versioned content for stencils. Lifecycle: draft -> published -> archived. At most one draft per stencil.';
COMMENT ON COLUMN stencil_versions.id IS 'Sequential version number (1-200) within the stencil';
COMMENT ON COLUMN stencil_versions.tenant_key IS 'Owning tenant';
COMMENT ON COLUMN stencil_versions.catalog_key IS 'Catalog this stencil version belongs to';
COMMENT ON COLUMN stencil_versions.stencil_key IS 'Parent stencil this version belongs to';
COMMENT ON COLUMN stencil_versions.content IS 'Template document fragment (nodes + slots subgraph) as JSON. Copied into templates on insert.';
COMMENT ON COLUMN stencil_versions.status IS 'Lifecycle state: draft (editable), published (frozen, insertable), or archived (read-only)';
COMMENT ON COLUMN stencil_versions.created_at IS 'When the version was created';
COMMENT ON COLUMN stencil_versions.published_at IS 'When the version was published (frozen). NULL while in draft.';
COMMENT ON COLUMN stencil_versions.archived_at IS 'When the version was archived. NULL while draft or published.';
COMMENT ON COLUMN stencil_versions.created_by IS 'User who created this version (NULL if the user was deleted)';
COMMENT ON COLUMN stencil_versions.parameter_schema IS
    'Optional JSON Schema (object, properties+required) describing parameters '
    'consumers must bind when inserting this stencil version into a template. '
    'NULL = no parameters.';
