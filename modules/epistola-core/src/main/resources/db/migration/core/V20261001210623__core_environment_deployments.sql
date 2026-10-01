-- backup-restore-compatibility: backward=true forward=false
-- reason: Adds a table. A backup taken before this migration restores with no deployments, which
-- is the state every environment starts 2.0 in (activations are not converted). Forward is not
-- safe: a newer backup carries a table an older schema cannot classify.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- An environment deploys one release per catalog.
--
-- This replaces environment_activations, which pinned a template version per template variant per
-- environment. An environment now points at a catalog release, and every template of that catalog
-- renders from it there: promoting is deploying the release another environment runs, rolling back
-- is deploying an earlier one. Activations are deliberately not converted (docs/catalog-release-
-- model-v2.md, D1): every environment starts with nothing deployed, and environment_activations is
-- left unread until it is dropped with the version tables.
CREATE TABLE environment_catalog_deployments (
    tenant_key      TENANT_KEY      NOT NULL,
    environment_key ENVIRONMENT_KEY NOT NULL,
    catalog_key     CATALOG_KEY     NOT NULL,
    version         VARCHAR(50)     NOT NULL,
    deployed_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deployed_by     UUID            REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, environment_key, catalog_key),
    CONSTRAINT fk_environment_catalog_deployments_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    -- A deployed release cannot be deleted. Deferred, like the release keys before it, so deleting a
    -- tenant -- which removes both sides -- is not refused by the order Postgres cascades in. The
    -- commands that delete or forget a release refuse first, with a message naming the environment.
    CONSTRAINT fk_environment_catalog_deployments_release FOREIGN KEY (tenant_key, catalog_key, version)
        REFERENCES catalog_releases (tenant_key, catalog_key, version) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE environment_catalog_deployments IS
    'The catalog release each environment serves: one per (environment, catalog). Generation by environment resolves through it when a request is accepted.';

-- "Which environments serve this release" -- the guard on deleting or forgetting it.
CREATE INDEX idx_environment_catalog_deployments_release
    ON environment_catalog_deployments (tenant_key, catalog_key, version);
