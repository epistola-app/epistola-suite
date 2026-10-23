-- ADR 0034, phase 4, for 2.0: a tenant's catalogs stay unique by key unless the tenant switches on
-- the alpha feature `shared-catalog-keys`. The schema already allows `acme/letters` beside
-- `bureau/letters` (uq_catalogs_namespace_key), but some reads across a tenant still tell catalogs
-- apart by key alone; until those are done (#1125) a second catalog with a key is refused.
--
-- A trigger rather than code in each command, so every way a catalog is made or renamed (create,
-- import, register, install, snapshot restore, tenant creation) is covered. It reads the tenant's
-- override only: the feature has no global default (KnownFeatures.SHARED_CATALOG_KEYS).
-- It raises unique_violation, as the key-only constraint it stands in for did.

CREATE FUNCTION epistola_catalog_key_unshared() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_other TEXT;
BEGIN
    SELECT c.namespace INTO v_other
    FROM catalogs c
    WHERE c.tenant_key = NEW.tenant_key AND c.catalog_key = NEW.catalog_key AND c.catalog_id <> NEW.catalog_id
    LIMIT 1;
    IF v_other IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM feature_toggles f
        WHERE f.tenant_key = NEW.tenant_key AND f.feature_key = 'shared-catalog-keys' AND f.enabled
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'unique_violation',
            CONSTRAINT = 'uq_catalogs_key_unshared',
            MESSAGE = format('This tenant already has a catalog with the key %s (%s/%s).', NEW.catalog_key, v_other, NEW.catalog_key);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_catalogs_key_unshared
    BEFORE INSERT OR UPDATE OF tenant_key, catalog_key ON catalogs
    FOR EACH ROW EXECUTE FUNCTION epistola_catalog_key_unshared();

COMMENT ON FUNCTION epistola_catalog_key_unshared() IS
    'Refuses a second catalog of a tenant with a key it already uses, unless the tenant has switched on shared-catalog-keys (ADR 0034, #1125).';
