-- An API key no longer carries roles (ADR 0054): it proves its application, and the application holds
-- the roles. The previous migration gave every key that is not revoked an application with the key's
-- roles, so those live on there; a revoked key cannot authenticate, and its roles are not shown.
--
-- rollback-compatible: false
-- reason: drops api_keys.roles, which releases before applications read for every API-key login and
-- write for every new key.

ALTER TABLE api_keys DROP COLUMN roles;
