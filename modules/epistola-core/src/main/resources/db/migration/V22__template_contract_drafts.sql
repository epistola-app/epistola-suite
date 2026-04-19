ALTER TABLE document_templates
    ADD COLUMN draft_data_model JSONB,
    ADD COLUMN draft_data_examples JSONB;

COMMENT ON COLUMN document_templates.draft_data_model IS 'Draft JSON Schema for the template data contract. NULL means no unpublished draft schema exists.';
COMMENT ON COLUMN document_templates.draft_data_examples IS 'Draft sample data aligned with the draft data contract. NULL means no unpublished draft examples exist.';
