-- The 2.0 upgrade, step 13 of 16 (docs/migrations.md, "The 2.0 chain"): Exchange bindings by address.
--
-- An Exchange binding is a catalog's address that reached Exchange (ADR 0034, section 9): by
-- namespace and key, so two catalogs with one key keep a binding each. It stays a name, not a
-- catalog_id: it outlives the local catalog, as Exchange keeps what was published under that name.
-- A 1.x binding already names its namespace, which step 1 named the catalog in.
ALTER TABLE catalog_exchange_bindings DROP CONSTRAINT catalog_exchange_bindings_pkey;
ALTER TABLE catalog_exchange_bindings ADD CONSTRAINT catalog_exchange_bindings_pkey PRIMARY KEY (tenant_key, namespace, catalog_key);
