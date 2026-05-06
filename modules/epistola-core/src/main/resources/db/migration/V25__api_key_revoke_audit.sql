-- Audit columns for API key revocation
ALTER TABLE api_keys
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoked_by UUID REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON COLUMN api_keys.revoked_at IS 'When this API key was revoked (set when enabled flips to false)';
COMMENT ON COLUMN api_keys.revoked_by IS 'User who revoked this API key';
