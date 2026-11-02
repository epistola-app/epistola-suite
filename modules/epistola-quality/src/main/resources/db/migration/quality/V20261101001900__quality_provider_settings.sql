-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Which quality providers a tenant has switched on (ADR 0047): a provider that sends template text
-- somewhere, such as a LanguageTool server, runs for a tenant only when the tenant switched it on.
-- No row means off. Who switched it and when is kept, because switching one on is a decision about
-- where the tenant's text goes; consent for external providers will be recorded the same way.
CREATE TABLE quality_provider_settings (
    tenant_key  TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider_id VARCHAR(64) NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  UUID        REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (tenant_key, provider_id)
);

COMMENT ON TABLE quality_provider_settings IS 'Quality providers a tenant switched on or off (ADR 0047); no row means off.';
