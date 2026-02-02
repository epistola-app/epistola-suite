-- Add default theme reference to document templates
-- This is the template-level default theme; variants can override via TemplateModel.themeId

ALTER TABLE document_templates
    ADD COLUMN theme_id UUID REFERENCES themes(id) ON DELETE SET NULL;

-- Index for efficient lookup by theme
CREATE INDEX idx_document_templates_theme_id ON document_templates(theme_id) WHERE theme_id IS NOT NULL;

COMMENT ON COLUMN document_templates.theme_id IS 'Default theme for this template. Variants can override via TemplateModel.themeId.';
