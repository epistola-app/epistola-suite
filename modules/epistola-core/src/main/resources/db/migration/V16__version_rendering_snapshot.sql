-- Add rendering defaults version and resolved theme snapshot to template_versions.
-- These columns enable deterministic PDF rendering for published versions:
--   rendering_defaults_version: locks the rendering constants (font sizes, margins, etc.)
--   resolved_theme: snapshots the full theme cascade at publish time
--
-- Both are NULL for draft versions and legacy published versions (pre-V16).
-- At publish time, the system populates both columns.

ALTER TABLE template_versions
    ADD COLUMN rendering_defaults_version INTEGER,
    ADD COLUMN resolved_theme JSONB;

-- Backfill existing published versions with defaults version 1
-- (all existing published content was rendered with the V1 constants)
UPDATE template_versions
SET rendering_defaults_version = 1
WHERE status = 'published';
