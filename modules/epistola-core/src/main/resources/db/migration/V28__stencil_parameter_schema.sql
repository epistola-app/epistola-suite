-- Stencil parameters: per-version schema describing the inputs a consumer
-- must bind when inserting this stencil into a template. Stored as a JSON
-- Schema (object, properties + required); NULL means the version takes no
-- parameters. The schema is snapshotted onto the consuming stencil node's
-- props at insert time (see StencilContentReplacer) so the renderer doesn't
-- need a DB lookup.

ALTER TABLE stencil_versions ADD COLUMN parameter_schema JSONB;

COMMENT ON COLUMN stencil_versions.parameter_schema IS
    'Optional JSON Schema (object, properties+required) describing parameters '
    'consumers must bind when inserting this stencil version into a template. '
    'NULL = no parameters.';
