-- The "Published in 1.x" draft (V20261101000900) left out every stencil 1.x never published, but kept
-- the published templates using one (1.x let a template use a stencil's draft): the draft named a
-- stencil it did not hold, and its release was refused. Each such draft gets the stencils its content
-- uses from its own catalog, and the stencils those use, as its catalog's next draft holds them, with
-- their ready marks there. A draft this repairs that then holds what its next draft holds is removed,
-- as step 9 removes one: it has nothing of its own to release.

CREATE TEMPORARY TABLE published_in_1x_repaired (
    tenant_key TEXT NOT NULL,
    version_id UUID NOT NULL,
    next_id    UUID NOT NULL,
    resource_id UUID NOT NULL
) ON COMMIT DROP;

WITH RECURSIVE named AS (
    SELECT d.tenant_key, d.catalog_id, c.catalog_key, d.version_id, c.next_version_id AS next_id
    FROM catalog_versions d
    JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
    WHERE d.state = 'draft' AND d.name = 'Published in 1.x'
),
-- The stencils of its own catalog a named draft's content uses (alias none, or its own key), and
-- those the next draft's copies of them use in turn.
needed (tenant_key, catalog_key, version_id, next_id, resource_key) AS (
    SELECT n.tenant_key, n.catalog_key, n.version_id, n.next_id, x.resource_key
    FROM named n
    JOIN release_resources r ON r.tenant_key = n.tenant_key AND r.version_id = n.version_id
    JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
    WHERE x.resource_type = 'stencil' AND (x.alias IS NULL OR x.alias = n.catalog_key)
    UNION
    SELECT n.tenant_key, n.catalog_key, n.version_id, n.next_id, x.resource_key
    FROM needed n
    JOIN release_resources s ON s.tenant_key = n.tenant_key AND s.version_id = n.next_id
        AND s.resource_type = 'stencil' AND s.resource_key = n.resource_key AND s.parent_id IS NULL
    JOIN resource_references x ON x.tenant_key = s.tenant_key AND x.content_hash = s.content_hash
    WHERE x.resource_type = 'stencil' AND (x.alias IS NULL OR x.alias = n.catalog_key)
),
added AS (
    INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, parent_id, content_hash,
        fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
    SELECT s.tenant_key, s.catalog_id, n.version_id, s.resource_type, s.resource_key, s.resource_id, s.parent_id, s.content_hash,
        s.fingerprint, s.name, s.description, s.revision, s.created_at, s.updated_at, s.created_by, s.updated_by
    FROM (SELECT DISTINCT tenant_key, version_id, next_id, resource_key FROM needed) n
    JOIN release_resources s ON s.tenant_key = n.tenant_key AND s.version_id = n.next_id
        AND s.resource_type = 'stencil' AND s.resource_key = n.resource_key AND s.parent_id IS NULL
    WHERE NOT EXISTS (
        SELECT 1 FROM release_resources h
        WHERE h.tenant_key = n.tenant_key AND h.version_id = n.version_id AND h.resource_type = 'stencil' AND h.resource_key = n.resource_key
    )
    RETURNING tenant_key, version_id, resource_id
)
INSERT INTO published_in_1x_repaired (tenant_key, version_id, next_id, resource_id)
SELECT a.tenant_key, a.version_id, n.next_id, a.resource_id
FROM added a
JOIN (SELECT DISTINCT tenant_key, version_id, next_id FROM named) n ON n.tenant_key = a.tenant_key AND n.version_id = a.version_id;

INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest, marked_at, marked_by)
SELECT m.tenant_key, r.version_id, m.resource_id, m.part, m.digest, m.marked_at, m.marked_by
FROM published_in_1x_repaired r
JOIN resource_ready_marks m ON m.tenant_key = r.tenant_key AND m.version_id = r.next_id AND m.resource_id = r.resource_id;

DELETE FROM catalog_versions d
USING (SELECT DISTINCT tenant_key, version_id, next_id FROM published_in_1x_repaired) r
WHERE d.tenant_key = r.tenant_key AND d.version_id = r.version_id
  AND NOT EXISTS (
      (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.version_id
       EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.next_id)
      UNION ALL
      (SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.next_id
       EXCEPT SELECT resource_type, resource_key, COALESCE(parent_id::text, ''), content_hash FROM release_resources WHERE tenant_key = r.tenant_key AND version_id = r.version_id)
  );
