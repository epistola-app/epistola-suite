-- What content refers to, by content hash (ADR 0033: `resource_references`).
--
-- Content is immutable, so what it refers to is extracted once, when it is stored, and shared by
-- every release and draft that holds it. Usages and the resource graph of any version become a join
-- of its resources to these rows (each resource type switches to it in its own step).
--
-- A reference names the catalog it is in by the alias the referring catalog declares it under, or
-- names none (`alias` NULL): the referring content's own catalog. It is never a foreign key: content
-- may refer to what a version does not hold, which a write checks and the graph reports.
--
-- No backfill here. References sit in the JSON in several shapes (a stencil node's props, a theme
-- override, a font family in styles, a template's theme, an attribute's code list), which the suite
-- reads with one walker; repeating it in SQL would be a second definition. Content stored again
-- records its references then, and each type's conversion (ADR 0033, step 1b) records them for the
-- content it writes.
CREATE TABLE resource_references (
    tenant_key    TENANT_KEY  NOT NULL,
    content_hash  CHAR(64)    NOT NULL,
    alias         VARCHAR(50),
    resource_type VARCHAR(20) NOT NULL,
    resource_key  TEXT        NOT NULL,
    CONSTRAINT fk_resource_references_content FOREIGN KEY (tenant_key, content_hash)
        REFERENCES resource_contents (tenant_key, content_hash) ON DELETE CASCADE,
    CONSTRAINT fk_resource_references_type FOREIGN KEY (resource_type)
        REFERENCES catalog_resource_types (resource_type)
);

COMMENT ON TABLE resource_references IS
    'What a content refers to: the alias of the catalog it names (NULL for its own catalog), the resource type and key. Extracted when the content is stored; collected with it.';

-- One row per distinct reference of a content; the alias of the own catalog is NULL, so it is
-- compared as the empty string.
CREATE UNIQUE INDEX uq_resource_references ON resource_references
    (tenant_key, content_hash, COALESCE(alias, ''), resource_type, resource_key);

-- What refers to a resource: usages and the graph.
CREATE INDEX idx_resource_references_target ON resource_references (tenant_key, resource_type, resource_key);
