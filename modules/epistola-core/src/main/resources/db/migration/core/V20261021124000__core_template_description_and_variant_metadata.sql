-- Catalog wire v8 (contract #96, #97): a template carries a plain-text description for its authors,
-- and a template variant carries producer-specific metadata keyed by a reverse-DNS namespace. The
-- suite stored neither, so importing, copying a release into a draft, merging and building content
-- dropped them. Both are additive and nullable: existing rows keep everything they had.
ALTER TABLE document_templates ADD COLUMN description VARCHAR(2000);

COMMENT ON COLUMN document_templates.description IS
    'Plain-text notes on the template for its authors and readers (wire v8 TemplateResource.description): what it is for, design decisions, known gaps. Not rendered; at most 2000 characters; NULL when it has none.';

ALTER TABLE template_variants ADD COLUMN metadata JSONB;

COMMENT ON COLUMN template_variants.metadata IS
    'Producer-specific data the catalog contract carries but does not define (wire v8 VariantEntry.metadata), keyed by a reverse-DNS namespace, each value a JSON object. Kept as received and written back on export; NULL when the variant has none.';
