-- A stencil has one working copy, versioned with its catalog (contract ADR 0002, ADR 0006): its
-- content and parameter schema live on the stencil. The working copy is the stencil's draft where it
-- has one, else its latest published version -- what each author was working on. stencil_versions is
-- left in place: the 2.0 upgrade still reads the published content from it for the first release.
ALTER TABLE stencils
    ADD COLUMN content JSONB,
    ADD COLUMN parameter_schema JSONB,
    ADD COLUMN reads_template_data BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE stencils s
SET content = v.content,
    parameter_schema = v.parameter_schema
FROM (
    SELECT DISTINCT ON (tenant_key, stencil_resource_id) tenant_key, stencil_resource_id, content, parameter_schema
    FROM stencil_versions
    ORDER BY tenant_key, stencil_resource_id,
             CASE status WHEN 'draft' THEN 0 WHEN 'published' THEN 1 ELSE 2 END,
             id DESC
) v
WHERE v.tenant_key = s.tenant_key AND v.stencil_resource_id = s.resource_id;

-- A stencil that never had a version gets the empty document a new stencil starts with.
UPDATE stencils
SET content = '{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["slot-root"]}}, "slots": {"slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": []}}, "themeRef": {"type": "inherit"}}'::jsonb
WHERE content IS NULL;

ALTER TABLE stencils ALTER COLUMN content SET NOT NULL;

COMMENT ON COLUMN stencils.content IS 'The stencil''s working copy: a template document, without stencil versions.';
COMMENT ON COLUMN stencils.parameter_schema IS 'JSON Schema of the parameters an instance binds; NULL when it takes none.';
COMMENT ON COLUMN stencils.reads_template_data IS
    'The stencil''s content also sees the using template''s data and loop variables (contract ADR 0006, part 5).';
