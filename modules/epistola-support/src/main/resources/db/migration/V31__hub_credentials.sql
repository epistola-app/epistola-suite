-- Singleton table holding hub-issued credentials for this Suite installation.
-- The CHECK on id enforces single-row semantics: any INSERT with a value
-- other than 1 fails; an INSERT with id=1 collides on PRIMARY KEY and goes
-- to ON CONFLICT.
CREATE TABLE epistola_support_hub_credentials (
    id              SMALLINT PRIMARY KEY CHECK (id = 1),
    installation_id UUID        NOT NULL,
    api_key         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);

COMMENT ON TABLE  epistola_support_hub_credentials
    IS 'Hub-issued credentials for the Epistola Hub integration. Singleton (id=1). Sensitive.';
COMMENT ON COLUMN epistola_support_hub_credentials.api_key
    IS 'Plaintext ek_* API key issued by the hub. Encrypt at rest before going to production.';
