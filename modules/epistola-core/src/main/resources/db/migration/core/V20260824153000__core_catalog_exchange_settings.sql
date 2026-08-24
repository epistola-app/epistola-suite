-- backup-restore-compatibility: backward=true forward=true
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

ALTER TABLE tenants
    ADD COLUMN publish_catalogs_by_default BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE catalogs
    ADD COLUMN exchange_publication_policy VARCHAR(20) NOT NULL DEFAULT 'INHERIT',
    ADD COLUMN exchange_namespace_preference VARCHAR(63),
    ADD CONSTRAINT catalogs_exchange_publication_policy
        CHECK (exchange_publication_policy IN ('INHERIT', 'ALWAYS', 'DEFAULT_YES', 'DEFAULT_NO', 'NEVER')),
    ADD CONSTRAINT catalogs_exchange_namespace_preference
        CHECK (exchange_namespace_preference IS NULL OR exchange_namespace_preference ~ '^[a-z][a-z0-9-]{0,62}$');

COMMENT ON COLUMN tenants.publish_catalogs_by_default IS
    'Default publication choice for authored catalog releases; catalog policy and release override may supersede it.';
COMMENT ON COLUMN catalogs.exchange_publication_policy IS
    'INHERIT, ALWAYS, DEFAULT_YES, DEFAULT_NO, or NEVER publication policy for Exchange releases.';
COMMENT ON COLUMN catalogs.exchange_namespace_preference IS
    'Preferred Exchange namespace before immutable first-publication binding; NULL uses the tenant default.';
