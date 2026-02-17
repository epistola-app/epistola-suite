-- Theme system for reusable styling across templates
-- Themes define document-level styles and named block style presets that can be shared
--
-- Uses composite PK (tenant_id, id) consistent with all other tenant-scoped tables.

CREATE TABLE themes (
    id VARCHAR(20) NOT NULL CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    document_styles JSONB NOT NULL DEFAULT '{}'::jsonb,
    page_settings JSONB,
    block_style_presets JSONB,  -- Named style presets for blocks (like CSS classes)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    last_modified_by UUID REFERENCES users(id),

    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX idx_themes_last_modified ON themes(last_modified DESC);

COMMENT ON COLUMN themes.created_by IS 'User who created this theme';
COMMENT ON COLUMN themes.last_modified_by IS 'User who last modified this theme';

-- ============================================================================
-- FK: tenants.default_theme_id -> themes
-- ============================================================================
-- Maps tenants.id -> themes.tenant_id, enforcing a tenant can only reference its own themes.

ALTER TABLE tenants ADD CONSTRAINT fk_tenants_default_theme
    FOREIGN KEY (id, default_theme_id) REFERENCES themes(tenant_id, id);

CREATE INDEX idx_tenants_default_theme_id ON tenants(default_theme_id)
    WHERE default_theme_id IS NOT NULL;
