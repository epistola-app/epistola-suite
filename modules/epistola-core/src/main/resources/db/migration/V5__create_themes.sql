-- Domain type for theme identifiers (slugs)
CREATE DOMAIN THEME_ID AS VARCHAR(20)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Theme system for reusable styling across templates
-- Themes define document-level styles and named block style presets that can be shared
--
-- Uses composite PK (tenant_id, id) consistent with all other tenant-scoped tables.

CREATE TABLE themes (
    id THEME_ID NOT NULL,
    tenant_id TENANT_ID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    document_styles JSONB NOT NULL DEFAULT '{}'::jsonb,
    page_settings JSONB,
    block_style_presets JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    last_modified_by UUID REFERENCES users(id),

    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX idx_themes_last_modified ON themes(last_modified DESC);

COMMENT ON TABLE themes IS 'Reusable styling definitions shared across templates. Provides document styles, page settings, and named block style presets.';
COMMENT ON COLUMN themes.id IS 'URL-safe slug identifier unique within the tenant';
COMMENT ON COLUMN themes.tenant_id IS 'Owning tenant';
COMMENT ON COLUMN themes.name IS 'Human-readable display name';
COMMENT ON COLUMN themes.description IS 'Optional description of the theme purpose';
COMMENT ON COLUMN themes.document_styles IS 'Document-level CSS-like styles (font, color, alignment defaults). Lowest priority in the style cascade.';
COMMENT ON COLUMN themes.page_settings IS 'Page format, orientation, and margins. NULL inherits renderer defaults.';
COMMENT ON COLUMN themes.block_style_presets IS 'Named style presets for blocks (like CSS classes). JSON object mapping preset name to {label, styles, applicableTo}.';
COMMENT ON COLUMN themes.created_at IS 'When the theme was created';
COMMENT ON COLUMN themes.last_modified IS 'When the theme was last updated';
COMMENT ON COLUMN themes.created_by IS 'User who created this theme';
COMMENT ON COLUMN themes.last_modified_by IS 'User who last modified this theme';

-- ============================================================================
-- FK: tenants.default_theme_id -> themes
-- ============================================================================
-- Maps tenants.id -> themes.tenant_id, enforcing a tenant can only reference its own themes.

ALTER TABLE tenants ALTER COLUMN default_theme_id TYPE THEME_ID;

ALTER TABLE tenants ADD CONSTRAINT fk_tenants_default_theme
    FOREIGN KEY (id, default_theme_id) REFERENCES themes(tenant_id, id);

CREATE INDEX idx_tenants_default_theme_id ON tenants(default_theme_id)
    WHERE default_theme_id IS NOT NULL;
