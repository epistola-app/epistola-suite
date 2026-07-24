-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- API keys for external system authentication
CREATE TABLE api_keys (
    id           UUID PRIMARY KEY,
    tenant_key   TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    key_hash     VARCHAR(128) NOT NULL UNIQUE,
    key_prefix   VARCHAR(20) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    created_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Least-privilege scope: each key authenticates as exactly this subset of tenant
    -- roles. No column default — CreateApiKey validates a non-empty set, so a bare
    -- INSERT can't silently mint an all-roles key.
    roles        VARCHAR(30)[] NOT NULL
);

CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_tenant_key ON api_keys(tenant_key);

COMMENT ON TABLE api_keys IS 'API keys for external system authentication via the X-API-Key header. Scoped to a single tenant.';
COMMENT ON COLUMN api_keys.id IS 'UUIDv7 primary key';
COMMENT ON COLUMN api_keys.tenant_key IS 'Owning tenant. All requests authenticated with this key are scoped to it.';
COMMENT ON COLUMN api_keys.name IS 'Human-readable label shown in the UI';
COMMENT ON COLUMN api_keys.key_hash IS 'Hash of the secret key value. The plaintext key is shown once at creation and never stored.';
COMMENT ON COLUMN api_keys.key_prefix IS 'Short non-secret prefix used to identify the key in the UI without exposing the secret';
COMMENT ON COLUMN api_keys.enabled IS 'Whether the key can authenticate. Set to false on revocation.';
COMMENT ON COLUMN api_keys.created_at IS 'When the key was created';
COMMENT ON COLUMN api_keys.last_used_at IS 'Most recent successful authentication with this key. NULL until first use.';
COMMENT ON COLUMN api_keys.expires_at IS 'Optional expiry. NULL means the key never expires.';
COMMENT ON COLUMN api_keys.created_by IS 'User who created this API key';
COMMENT ON COLUMN api_keys.revoked_at IS 'When this API key was revoked (set when enabled flips to false)';
COMMENT ON COLUMN api_keys.revoked_by IS 'User who revoked this API key';
COMMENT ON COLUMN api_keys.roles IS
    'Tenant roles granted to this key (least-privilege scope). Subset of TenantRole names; the auth filter grants exactly these for the key''s tenant.';
