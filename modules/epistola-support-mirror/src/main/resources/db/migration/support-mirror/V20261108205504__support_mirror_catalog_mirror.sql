-- rollback-compatible: true
-- reason: Adds two tables that code predating them never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The catalog mirror (docs/catalog-mirror.md, ADR 0051): which catalogs a tenant shares with the
-- Epistola support service, and how far the reconcile that keeps the service's custody copy in step
-- has got. Commercial-tier state, so it lives in tables of its own rather than in core's catalogs.

-- A tenant's consent and the installation default are in support_sharing_choice and
-- support_sharing_default under the section 'mirror' (V20261107074805): options.shareByDefault there
-- is the person's choice between "every catalog, including new ones" and "only the catalogs I mark".

-- A catalog's own policy. No row is INHERIT: the tenant's share_by_default decides. The CHECK lists
-- a closed set rather than a lookup table: INHERIT is the absence of a row, and a third explicit value
-- would change how sharing resolves, which is a code change anyway.
CREATE TABLE support_mirror_catalog_policies (
    connection_id UUID     NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    tenant_key TENANT_KEY  NOT NULL,
    catalog_id UUID        NOT NULL,
    policy     VARCHAR(16) NOT NULL,
    set_at     TIMESTAMPTZ NOT NULL,
    set_by     UUID,
    PRIMARY KEY (connection_id, tenant_key, catalog_id),
    CONSTRAINT fk_support_mirror_catalog_policies_catalog FOREIGN KEY (tenant_key, catalog_id)
        REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE,
    CONSTRAINT chk_support_mirror_catalog_policies_policy CHECK (policy IN ('SHARE', 'NEVER'))
);

COMMENT ON TABLE support_mirror_catalog_policies IS
    'A catalog''s own catalog-mirror policy: SHARE or NEVER. No row means INHERIT the tenant''s share_by_default.';
COMMENT ON COLUMN support_mirror_catalog_policies.set_by IS 'users.id of who set it. No FK, as for the other audit columns.';

-- How far the reconcile got for a tenant. No outbox of archives: a release never changes and is
-- rebuilt from what it retained whenever the support service lacks it.
CREATE TABLE support_mirror_state (
    connection_id        UUID        NOT NULL REFERENCES support_connections (id) ON DELETE CASCADE,
    tenant_key           TENANT_KEY  NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    last_attempt_at      TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    last_digest          VARCHAR(80),
    bindings_sent        INTEGER     NOT NULL DEFAULT 0,
    releases_uploaded    INTEGER     NOT NULL DEFAULT 0,
    releases_missing     INTEGER     NOT NULL DEFAULT 0,
    failed_releases      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    synced_catalogs      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ,
    last_error           TEXT,
    sync_requested_at    TIMESTAMPTZ,
    claimed_at           TIMESTAMPTZ,
    PRIMARY KEY (connection_id, tenant_key)
);

COMMENT ON TABLE support_mirror_state IS 'Where the catalog-mirror reconcile stands for a tenant (ADR 0051).';
COMMENT ON COLUMN support_mirror_state.last_success_at IS
    'When the support service last accepted the tenant''s complete set of bindings and every release it lacked was uploaded.';
COMMENT ON COLUMN support_mirror_state.last_digest IS
    'SHA-256 of the binding set last sent: an unchanged set is not sent again until a day has passed.';
COMMENT ON COLUMN support_mirror_state.releases_missing IS 'Releases the support service still lacked after the last round.';
COMMENT ON COLUMN support_mirror_state.failed_releases IS
    '[{contentHash, error}] for releases the last round could not upload; at most 50, retried next round.';
COMMENT ON COLUMN support_mirror_state.synced_catalogs IS
    'The catalog_ids whose releases were in the set the support service last accepted: what the page calls synced.';
COMMENT ON COLUMN support_mirror_state.next_attempt_at IS 'Back-off after the support service was unavailable: not tried before this.';
COMMENT ON COLUMN support_mirror_state.sync_requested_at IS 'Someone asked to sync now; cleared when the round for the tenant ends.';
COMMENT ON COLUMN support_mirror_state.claimed_at IS
    'Set while a node reconciles the tenant, so a scheduled round and a "Sync now" never run it at once; a stale claim expires.';
