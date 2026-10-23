-- ADR 0034, phase 4: within a tenant a catalog is unique by namespace and key, so two catalogs called
-- `letters` from different namespaces live side by side. The key alone no longer names one.
--
-- Data is untouched: every key was unique in its tenant, so every namespace and key is too.

ALTER TABLE catalogs DROP CONSTRAINT uq_catalogs_key_until_named;
ALTER TABLE catalogs ADD CONSTRAINT uq_catalogs_namespace_key UNIQUE (tenant_key, namespace, catalog_key);

COMMENT ON CONSTRAINT uq_catalogs_namespace_key ON catalogs IS
    'Within a tenant a catalog is named by namespace and key (ADR 0034, section 2); the full name, with its authority, stays unique too (uq_catalogs_name).';

-- The catalog a caller names by key (CatalogSql.catalogAt and every lookup by key go through this).
-- A caller that knows which namespace it means says so for its transaction in
-- `epistola.catalog_names`, a JSON object from key to namespace (CatalogNames in the suite); a key
-- it names there is looked up in that namespace. Otherwise the key must name one catalog of the
-- tenant: none is NULL, which matches no row on a read and violates NOT NULL on a write, and several
-- are refused (SQLSTATE EP409), naming them, so nothing silently picks one.
CREATE FUNCTION epistola_catalog_id(p_tenant TEXT, p_key TEXT) RETURNS UUID
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    v_names     TEXT := NULLIF(current_setting('epistola.catalog_names', TRUE), '');
    v_namespace TEXT;
    v_found     UUID[];
    v_named     TEXT[];
BEGIN
    IF v_names IS NOT NULL THEN
        v_namespace := v_names::jsonb ->> p_key;
    END IF;
    IF v_namespace IS NOT NULL THEN
        RETURN (SELECT c.catalog_id FROM catalogs c WHERE c.tenant_key = p_tenant AND c.namespace = v_namespace AND c.catalog_key = p_key);
    END IF;
    SELECT array_agg(c.catalog_id ORDER BY c.namespace), array_agg(c.namespace || '/' || c.catalog_key ORDER BY c.namespace)
    INTO v_found, v_named
    FROM catalogs c WHERE c.tenant_key = p_tenant AND c.catalog_key = p_key;
    IF v_found IS NULL THEN
        RETURN NULL;
    END IF;
    IF array_length(v_found, 1) > 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'EP409',
            MESSAGE = format('The catalog key %s names several catalogs of this tenant (%s): name its namespace.', p_key, array_to_string(v_named, ', '));
    END IF;
    RETURN v_found[1];
END;
$$;

COMMENT ON FUNCTION epistola_catalog_id(TEXT, TEXT) IS
    'The catalog_id of the catalog a caller names by key: in the namespace epistola.catalog_names gives for it, else the one catalog of the tenant with that key; several are refused (SQLSTATE EP409).';
