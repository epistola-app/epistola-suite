-- PDF/A is now enabled by default for new templates.
-- Existing rows are intentionally left untouched (tenant choice is preserved);
-- only the column default for future INSERTs that omit pdfa_enabled changes.
ALTER TABLE document_templates ALTER COLUMN pdfa_enabled SET DEFAULT true;

COMMENT ON COLUMN document_templates.pdfa_enabled IS 'Whether PDF/A-2b archival output is enabled for this template. Defaults to true for new templates.';
