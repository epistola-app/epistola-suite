-- ADR 0034, section 9: every address names one catalog, by namespace and key, so a key alone no
-- longer resolves. Two catalogs with one key are no longer an alpha feature: the trigger that refused
-- a second one goes, and `epistola_catalog_id` takes the namespace with the key instead of reading it
-- from the transaction (`epistola.catalog_names`) or guessing the one catalog with that key.

DROP TRIGGER trg_catalogs_key_unshared ON catalogs;
DROP FUNCTION epistola_catalog_key_unshared();

DROP FUNCTION epistola_catalog_id(TEXT, TEXT);

CREATE FUNCTION epistola_catalog_id(p_tenant TEXT, p_namespace TEXT, p_key TEXT) RETURNS UUID
    LANGUAGE sql STABLE
AS $$
    SELECT c.catalog_id FROM catalogs c WHERE c.tenant_key = p_tenant AND c.namespace = p_namespace AND c.catalog_key = p_key
$$;

COMMENT ON FUNCTION epistola_catalog_id(TEXT, TEXT, TEXT) IS
    'The catalog_id of the catalog a caller names by its address, namespace and key; NULL when the tenant has none there.';

-- An Exchange binding is a catalog's address that reached Exchange: by namespace and key, so two
-- catalogs with one key keep a binding each. It stays a name, not a catalog_id: it outlives the local
-- catalog, as Exchange keeps what was published under that name.
ALTER TABLE catalog_exchange_bindings DROP CONSTRAINT catalog_exchange_bindings_pkey;
ALTER TABLE catalog_exchange_bindings ADD CONSTRAINT catalog_exchange_bindings_pkey PRIMARY KEY (tenant_key, namespace, catalog_key);
