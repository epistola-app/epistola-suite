-- ADR 0034, section 3: an installation records which namespaces each tenant holds. A tenant names a
-- new catalog, and issues releases of it, only in a namespace it holds. Several tenants may hold the
-- same namespace (departments as tenants); a tenant may hold several (departments as namespaces).
--
-- A namespace is held under an authority: the contract's `registry` member (contract ADR 0003,
-- amendment of 2026-10-22), the domain the namespace was granted under, `exchange.epistola.app` for
-- the Exchange's or an organisation's own. The authority `local` (ADR 0034, section 7) is never
-- granted: any tenant may name catalogs under it, and they never leave the organisation.
--
-- The row is the full name, so one tenant may hold the same namespace token under two authorities
-- in the database; refusing that for new grants is an application rule, which can be relaxed later
-- without a migration (ADR 0034, section 2). Existing data may already have it, and keeps it.

-- How a namespace came to be held: a lookup table, so another way is an insert.
CREATE TABLE namespace_grant_sources (
    code        VARCHAR(32) PRIMARY KEY,
    description TEXT NOT NULL
);

INSERT INTO namespace_grant_sources (code, description) VALUES
    ('EXCHANGE_CONNECTION', 'Granted through the tenant''s Epistola Exchange connection'),
    ('ADMINISTRATOR', 'Granted by a platform administrator'),
    ('EXISTING', 'Held since before namespaces were granted: catalogs the tenant authored were named in it');

CREATE TABLE tenant_namespace_grants (
    tenant_key      TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    authority       TEXT        NOT NULL,
    namespace       TEXT        NOT NULL,
    granted_through VARCHAR(32) NOT NULL REFERENCES namespace_grant_sources (code),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- users.id of the administrator who granted it; NULL for one granted by the Exchange connection,
    -- by the system, or held from before. No FK, as for the other audit columns.
    granted_by      UUID,
    PRIMARY KEY (tenant_key, authority, namespace)
);

COMMENT ON TABLE tenant_namespace_grants IS
    'The namespaces each tenant holds (ADR 0034, section 3): a new catalog is named, and a release issued, only in one of them, or under the local authority.';
COMMENT ON COLUMN tenant_namespace_grants.authority IS
    'The domain the namespace is granted under: the contract''s registry member. Never local, which needs no grant.';
COMMENT ON COLUMN tenant_namespace_grants.granted_through IS
    'How it came to be held: namespace_grant_sources.code.';

-- Existing data, 1: the namespaces the tenant's Exchange connection grants, under the Exchange's
-- authority (the default registry, as the 2.0 identity migration named Exchange catalogs).
INSERT INTO tenant_namespace_grants (tenant_key, authority, namespace, granted_through, granted_at)
SELECT DISTINCT x.tenant_key, 'exchange.epistola.app', ns, 'EXCHANGE_CONNECTION', x.updated_at
FROM exchange_tenant_connections x
CROSS JOIN LATERAL unnest(x.namespaces) AS ns
WHERE length(ns) BETWEEN 3 AND 63 AND ns ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
ON CONFLICT DO NOTHING;

-- Existing data, 2: the namespaces of the catalogs each tenant authors (ADR 0032, sections 2 and 7).
-- A catalog is authored by its tenant when it
--   * has no source: one Epistola supplies (`classpath:`) is the suite's, and one installed from
--     the Exchange (`exchange:`), a container registry (`oci:`) or a manifest URL is received;
--   * was not a 1.x subscribed copy (`type`, otherwise unread since ADR 0032), which received too;
--   * and was not received as a file: none of its releases says another installation cut it, or one
--     was cut here. A release recorded before releases named their installation says nothing, and
--     counts as cut here: every such catalog could be released here until now.
INSERT INTO tenant_namespace_grants (tenant_key, authority, namespace, granted_through, granted_at)
SELECT DISTINCT c.tenant_key, c.registry, c.namespace, 'EXISTING', NOW()
FROM catalogs c
WHERE c.source_url IS NULL
  AND c.type IS DISTINCT FROM 'SUBSCRIBED'
  AND c.registry <> 'local'
  AND (
      NOT EXISTS (
          SELECT 1 FROM catalog_releases r
          WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id
            AND r.released_by_installation IS NOT NULL
            AND r.released_by_installation IS DISTINCT FROM (SELECT (m.value ->> 'id')::uuid FROM app_metadata m WHERE m.key = 'installation')
      )
      OR EXISTS (
          SELECT 1 FROM catalog_releases r
          WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id
            AND r.released_by_installation = (SELECT (m.value ->> 'id')::uuid FROM app_metadata m WHERE m.key = 'installation')
      )
  )
ON CONFLICT DO NOTHING;
