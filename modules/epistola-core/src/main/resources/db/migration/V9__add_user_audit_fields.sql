-- V9: Add User Audit Fields
--
-- This migration updates existing audit fields to use UUID user IDs.
-- Previous migrations had VARCHAR created_by fields that were placeholders.
-- Now we properly link them to the users table.

-- ============================================================================
-- UPDATE DOCUMENTS TABLE
-- ============================================================================

-- Update documents table to use UUID user IDs
ALTER TABLE documents
    ALTER COLUMN created_by TYPE UUID USING NULL,
    ADD CONSTRAINT fk_documents_created_by FOREIGN KEY (created_by) REFERENCES users(id);

COMMENT ON COLUMN documents.created_by IS 'User who created this document';

-- ============================================================================
-- ADD AUDIT FIELDS TO OTHER TABLES
-- ============================================================================

-- Add audit fields to document_templates (created_at and last_modified already exist)
ALTER TABLE document_templates
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS last_modified_by UUID REFERENCES users(id);

COMMENT ON COLUMN document_templates.created_by IS 'User who created this template';
COMMENT ON COLUMN document_templates.last_modified_by IS 'User who last modified this template';

-- Add audit fields to themes (created_at and last_modified already exist)
ALTER TABLE themes
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS last_modified_by UUID REFERENCES users(id);

COMMENT ON COLUMN themes.created_by IS 'User who created this theme';
COMMENT ON COLUMN themes.last_modified_by IS 'User who last modified this theme';

-- Add audit fields to template_variants (created_at already exists)
ALTER TABLE template_variants
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id);

COMMENT ON COLUMN template_variants.created_by IS 'User who created this variant';

-- Add audit fields to template_versions (created_at already exists)
ALTER TABLE template_versions
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id);

COMMENT ON COLUMN template_versions.created_by IS 'User who created this version';

-- Add audit fields to environments (created_at and last_modified already exist)
ALTER TABLE environments
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS last_modified_by UUID REFERENCES users(id);

COMMENT ON COLUMN environments.created_by IS 'User who created this environment';
COMMENT ON COLUMN environments.last_modified_by IS 'User who last modified this environment';
