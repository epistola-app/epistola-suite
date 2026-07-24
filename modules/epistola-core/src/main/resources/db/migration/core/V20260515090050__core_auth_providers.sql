-- Authentication provider reference table.
--
-- `users.provider` is a foreign key to this table. Modelled as a lookup table
-- (not a CHECK / enum) so a new provider is added by inserting a row — a data
-- migration, no schema or enum change. Created before `users` so the FK is
-- inline in the users CREATE (no ALTER).
--
-- The ids mirror the application `AuthProvider` enum names exactly.
CREATE TABLE auth_providers (
    id          VARCHAR(50) PRIMARY KEY,
    description TEXT NOT NULL
);

COMMENT ON TABLE auth_providers IS 'Reference list of authentication provider identifiers. New providers are added by inserting a row; users.provider is a FK to this table.';
COMMENT ON COLUMN auth_providers.id IS 'Provider key, matching the application AuthProvider enum name';
COMMENT ON COLUMN auth_providers.description IS 'Human-readable description of the provider';

INSERT INTO auth_providers (id, description) VALUES
    ('KEYCLOAK', 'Keycloak OAuth2/OIDC provider'),
    ('LOCAL', 'In-memory / configuration users for local development'),
    ('GENERIC_OIDC', 'Generic OIDC provider (Google, Azure AD, etc.)'),
    ('API_KEY', 'Non-human service identity backing an API key');
