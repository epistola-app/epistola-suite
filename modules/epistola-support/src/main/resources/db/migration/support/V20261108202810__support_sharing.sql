-- rollback-compatible: true
-- reason: Adds tables, which code predating them never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- What each tenant shares over each support connection (ADR 0052 §5, ADR 0053), for every shared
-- service alike: support requests, the catalog mirror, usage. `section` is the service's key.

-- What an installation administrator decided beyond a default: NOT_ALLOWED, no tenant shares it;
-- REQUIRED, every tenant does, without a choice. No row: tenants choose, following the default.
CREATE TABLE support_sharing_restriction (
    connection_id UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    section       VARCHAR(64) NOT NULL,
    restriction   VARCHAR(16) NOT NULL CONSTRAINT chk_support_sharing_restriction CHECK (restriction IN ('NOT_ALLOWED', 'REQUIRED')),
    set_at        TIMESTAMPTZ NOT NULL,
    set_by        UUID        REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (connection_id, section)
);

COMMENT ON TABLE support_sharing_restriction IS
    'Per connection and shared service (requests, mirror, usage): NOT_ALLOWED or REQUIRED for every tenant. No row: tenants choose.';

-- A tenant's choice: given (sharing) or withdrawn. A withdrawal keeps the row and stamps
-- withdrawn_at/_by; giving again clears them, and a new grant_id when it was withdrawn. Sharing while
-- withdrawn_at IS NULL. A tenant that chose not to share without ever having shared has a withdrawn
-- row never given. follows_default: written by the installation default rather than the tenant.
-- No row: the tenant follows the default as it stands.
CREATE TABLE support_sharing_choice (
    connection_id           UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    section                 VARCHAR(64) NOT NULL,
    tenant_key              TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    grant_id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    given_at                TIMESTAMPTZ,
    given_by                UUID        REFERENCES users (id) ON DELETE SET NULL,
    withdrawn_at            TIMESTAMPTZ,
    withdrawn_by            UUID        REFERENCES users (id) ON DELETE SET NULL,
    -- When the connection confirmed it holds nothing of the tenant any more, after a withdrawal.
    withdrawal_confirmed_at TIMESTAMPTZ,
    follows_default         BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The version of the statement of what is shared that was agreed to, where the service has one.
    statement_version       VARCHAR(32),
    -- What the service lets a tenant choose beyond sharing or not, e.g. the mirror's {"shareByDefault": true}.
    options                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (connection_id, section, tenant_key),
    CONSTRAINT chk_support_sharing_choice_given_or_withdrawn CHECK (given_at IS NOT NULL OR withdrawn_at IS NOT NULL),
    CONSTRAINT chk_support_sharing_choice_confirmed CHECK (withdrawal_confirmed_at IS NULL OR withdrawn_at IS NOT NULL)
);

COMMENT ON TABLE support_sharing_choice IS
    'A tenant''s choice to share a service over a support connection (ADR 0053). Sharing while withdrawn_at IS NULL; no row: it follows the default.';
COMMENT ON COLUMN support_sharing_choice.grant_id IS 'Identifies one grant: a new one each time sharing is given again after a withdrawal.';

-- The installation's default per connection and service, for tenants without a choice of their own.
-- No row: not shared. from_configuration: set by the deployment configuration (Helm
-- support.tenantDefaults), not a person.
CREATE TABLE support_sharing_default (
    connection_id      UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    section            VARCHAR(64) NOT NULL,
    share              BOOLEAN     NOT NULL,
    statement_version  VARCHAR(32),
    options            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    set_at             TIMESTAMPTZ NOT NULL,
    set_by             UUID        REFERENCES users (id) ON DELETE SET NULL,
    from_configuration BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (connection_id, section)
);

COMMENT ON TABLE support_sharing_default IS
    'The installation''s default per support connection and shared service, for tenants without a choice of their own. No row: not shared.';
