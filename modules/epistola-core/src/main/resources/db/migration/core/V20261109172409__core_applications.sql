-- Applications: the calling systems of a tenant (ADR 0054). An application owns its API keys and
-- the documents it requested. Every generation request records the application that submitted it,
-- and the document and the generation result copy it, so a restricted application is shown only
-- its own (#749).
--
-- rollback-compatible: true
-- Every new column is nullable without a default, so the previous release inserts and reads as
-- before; a key it creates has no application and behaves exactly as keys did before.

CREATE TABLE applications (
    id                 UUID PRIMARY KEY,
    tenant_key         TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name               VARCHAR(100) NOT NULL,
    description        VARCHAR(500),
    roles              VARCHAR(30)[] NOT NULL,
    sees_all_documents BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by         UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE (tenant_key, id)
);

CREATE INDEX idx_applications_tenant_key ON applications(tenant_key);

COMMENT ON TABLE applications IS 'A calling system in one tenant (ADR 0054). Owns its API keys and the documents it requested.';
COMMENT ON COLUMN applications.id IS 'UUIDv7, never reused, so a later application cannot inherit the documents of a deleted one';
COMMENT ON COLUMN applications.roles IS 'Tenant roles the application acts with. Its API keys carry a copy in api_keys.roles.';
COMMENT ON COLUMN applications.sees_all_documents IS 'True: sees every document of the tenant, as before applications existed. False: only the documents it requested.';

-- A key belongs to an application of its own tenant. Deleting an application is allowed once its
-- keys are revoked; the revoked keys keep their row and lose the link.
ALTER TABLE api_keys ADD COLUMN application_id UUID;
ALTER TABLE api_keys
    ADD CONSTRAINT fk_api_keys_application FOREIGN KEY (tenant_key, application_id)
        REFERENCES applications(tenant_key, id) ON DELETE SET NULL (application_id);
CREATE INDEX idx_api_keys_application_id ON api_keys(application_id) WHERE application_id IS NOT NULL;
COMMENT ON COLUMN api_keys.application_id IS 'The application this key proves. NULL for a revoked key whose application was deleted, or a key created by a release before applications.';

-- Every key that is not revoked becomes an application of its own: its name, its roles, seeing
-- every document. Exactly what the key could do and see before. The application takes the key's
-- id, which is free in this table and keeps the pair recognisable.
INSERT INTO applications (id, tenant_key, name, roles, sees_all_documents, created_at, created_by)
SELECT id, tenant_key, name, roles, true, created_at, created_by
FROM api_keys
WHERE revoked_at IS NULL;

UPDATE api_keys SET application_id = id WHERE revoked_at IS NULL;

-- Ownership of the generation pipeline. No foreign key: these tables are partitioned and high
-- volume, and a document outlives a deleted application.
ALTER TABLE document_generation_requests ADD COLUMN application_id UUID;
ALTER TABLE documents ADD COLUMN application_id UUID;
ALTER TABLE generation_results ADD COLUMN application_id UUID;

COMMENT ON COLUMN document_generation_requests.application_id IS 'The application that submitted the request, from its credential. NULL for a caller without one.';
COMMENT ON COLUMN documents.application_id IS 'The application that requested the document, copied from its generation request.';
COMMENT ON COLUMN generation_results.application_id IS 'The application that requested the result, copied from its generation request.';

-- The collect feed of a restricted application reads only its own rows. Without this index a poll
-- would read past every other application's results after its cursor. Every row is NULL now, and
-- the table keeps a few days of results, so building it is cheap.
CREATE INDEX idx_generation_results_application
    ON generation_results(application_id, partition, sequence)
    WHERE application_id IS NOT NULL;
