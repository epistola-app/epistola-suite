-- A catalog is identified by registry, namespace and key (contract ADR 0003). Until now a catalog
-- had only its key; the namespace existed only once it was bound for Exchange publication.
--
-- Existing catalogs are named on the default registry, exchange.epistola.app, with:
--   1. the namespace already bound for Exchange publication, so a published catalog keeps the
--      name it is published under;
--   2. otherwise `epistola` for the catalogs Epistola ships (installed from the classpath);
--   3. otherwise the namespace an Exchange-installed catalog was installed from
--      (`exchange:<namespace>/<key>`);
--   4. otherwise the tenant's key, padded with `0` to the 3 characters a namespace needs, as the
--      contract pads a short key.
-- The namespace stays changeable until the catalog is first published (catalog_exchange_bindings).
ALTER TABLE catalogs
    ADD COLUMN registry TEXT,
    ADD COLUMN namespace TEXT;

UPDATE catalogs c
SET registry  = 'exchange.epistola.app',
    namespace = COALESCE(
        (SELECT b.namespace FROM catalog_exchange_bindings b
          WHERE b.tenant_key = c.tenant_key AND b.catalog_key = c.id),
        CASE
            WHEN c.source_url LIKE 'classpath:epistola/catalogs/%' THEN 'epistola'
            WHEN c.source_url LIKE 'exchange:%/%' THEN split_part(substring(c.source_url FROM 10), '/', 1)
            WHEN length(c.tenant_key) < 3 THEN rpad(c.tenant_key, 3, '0')
            ELSE c.tenant_key
        END
    );

ALTER TABLE catalogs
    ALTER COLUMN registry SET NOT NULL,
    ALTER COLUMN namespace SET NOT NULL;

COMMENT ON COLUMN catalogs.registry IS
    'The registry the catalog is named on (contract ADR 0003): a DNS name, written in full.';
COMMENT ON COLUMN catalogs.namespace IS
    'The catalog''s namespace on its registry (contract ADR 0003). Defaults to the tenant key for an authored catalog; fixed once the catalog is published.';

-- Wire v8 writes a fingerprint as `<algorithm>:<hex>` (`v5:` + 64 hex characters). A fingerprint
-- recorded before keeps its bare 64-character hex, which the contract reads as "algorithm not
-- recorded".
ALTER TABLE catalogs
    ALTER COLUMN installed_fingerprint TYPE VARCHAR(80),
    ALTER COLUMN released_fingerprint TYPE VARCHAR(80);
ALTER TABLE catalog_releases ALTER COLUMN fingerprint TYPE VARCHAR(80);
ALTER TABLE catalog_release_publications ALTER COLUMN fingerprint TYPE VARCHAR(80);
ALTER TABLE release_entries ALTER COLUMN fingerprint TYPE VARCHAR(80);
