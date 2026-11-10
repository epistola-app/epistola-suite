-- rollback-compatible: true
--
-- Every catalog can use the system catalog from the start: a new catalog declares it when it is
-- created, and a picker offers only what a catalog version can use (its own resources and those of
-- the catalogs it declares, contract ADR 0003). So each open draft of a catalog the suite does not
-- supply, which does not declare its tenant's system catalog yet, declares it here at that catalog's
-- highest release, as a write that starts using it would (UsedCatalogDeclarer). Without this, a
-- draft that never used a system font, locale or theme would no longer be offered them.
--
-- Data-preserving: it only adds declarations. Released versions are frozen (ADR 0036) and are left
-- as they are. A draft that already uses the alias `system` for another catalog is left alone; its
-- author declares the system catalog under another alias if they want it. Older code reads an extra
-- declaration like any other, so this rolls back.

WITH system_catalog AS (
    SELECT c.tenant_key, c.catalog_id, c.registry, c.namespace, c.catalog_key,
           r.version, r.fingerprint, r.version_id
    FROM catalogs c
    JOIN LATERAL (
        -- Its highest release a pin can take: a release number and a fingerprint naming its algorithm.
        SELECT v.version, v.fingerprint, v.version_id
        FROM catalog_versions v
        WHERE v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.state = 'released'
          AND v.version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'
          AND v.fingerprint ~ '^v[0-9]+:'
        ORDER BY string_to_array(v.version, '.')::int[] DESC
        LIMIT 1
    ) r ON TRUE
    WHERE c.namespace = 'epistola' AND c.catalog_key = 'system' AND c.source_url LIKE 'classpath:%'
)
INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias,
                                          position, release_version, release_fingerprint, target_version_id)
SELECT d.tenant_key, d.catalog_id, d.version_id, s.registry, s.namespace, s.catalog_key, s.catalog_key,
       COALESCE((SELECT MAX(x.position) + 1 FROM catalog_version_dependencies x
                 WHERE x.tenant_key = d.tenant_key AND x.version_id = d.version_id), 0),
       s.version, s.fingerprint, s.version_id
FROM catalog_versions d
JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
JOIN system_catalog s ON s.tenant_key = d.tenant_key
WHERE d.state = 'draft'
  AND c.catalog_id <> s.catalog_id
  AND c.catalog_key <> s.catalog_key
  AND (c.source_url IS NULL OR c.source_url NOT LIKE 'classpath:%')
  AND NOT EXISTS (
      SELECT 1 FROM catalog_version_dependencies x
      WHERE x.tenant_key = d.tenant_key AND x.version_id = d.version_id
        AND (x.alias = s.catalog_key
             OR (x.registry = s.registry AND x.namespace = s.namespace AND x.dependency_key = s.catalog_key))
  );
