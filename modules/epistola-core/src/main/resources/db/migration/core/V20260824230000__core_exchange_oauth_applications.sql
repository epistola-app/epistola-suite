-- backup-restore-compatibility: backward=true forward=true
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

ALTER TABLE exchange_tenant_connections
    ADD COLUMN oauth_application_id UUID,
    ADD COLUMN client_secret TEXT;

CREATE TABLE exchange_oauth_authorizations (
    tenant_key TENANT_KEY PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    state_hash CHAR(64) NOT NULL UNIQUE,
    code_verifier TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN exchange_tenant_connections.oauth_application_id IS
    'Exchange OAuth application selected or created during browser authorization.';
COMMENT ON COLUMN exchange_tenant_connections.client_secret IS
    'Encrypted OAuth application credential used only for backchannel token refresh.';
COMMENT ON TABLE exchange_oauth_authorizations IS
    'Short-lived redirect state and encrypted PKCE verifier; excluded from portable backups.';
