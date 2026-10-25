-- ADR 0033, step 2: code lists move to JSON storage. A code list's content is one JSON per version,
-- draft and release alike: `{ "resource": <its wire form>, "source": { type, url, auth, credential } }`,
-- the released form plus the settings only this suite has beside it (`source` is left out for an
-- INLINE list). Two things are not content and get a table of their own here.

-- 1. A credential a source authenticates with: its content names it by id, so the secret itself is
--    never part of what is hashed, shared between releases or exported. Encrypted at rest
--    (CoreEncryptedCredentialColumns); rotating the key re-encrypts it in place, and no content
--    changes. A new credential is a new row; the content collector removes one no content names.
CREATE TABLE resource_credentials (
    tenant_key    tenant_key  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    credential_id UUID        NOT NULL,
    credential    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, credential_id)
);

COMMENT ON TABLE resource_credentials IS
    'Secrets a resource''s source authenticates with (ADR 0033), named by id from its content (source.credential); encrypted at rest.';

-- 2. When a draft's code list was last refreshed from its source, and what went wrong: the state of
--    the draft's copy, not content and not versioned. A draft started from another carries it over
--    (copy_catalog_version_content); discarding the draft discards it.
CREATE TABLE code_list_refreshes (
    tenant_key         tenant_key  NOT NULL,
    version_id         UUID        NOT NULL,
    resource_id        UUID        NOT NULL,
    last_refreshed_at  TIMESTAMPTZ,
    last_refresh_error TEXT,
    PRIMARY KEY (tenant_key, version_id, resource_id),
    FOREIGN KEY (tenant_key, version_id) REFERENCES catalog_versions (tenant_key, version_id) ON DELETE CASCADE
);

COMMENT ON TABLE code_list_refreshes IS
    'A draft code list''s last refresh from its source (ADR 0033): operational state of the draft, not content.';
