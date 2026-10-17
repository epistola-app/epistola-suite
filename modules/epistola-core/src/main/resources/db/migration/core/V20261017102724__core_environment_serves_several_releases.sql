-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- An environment serves several releases of one catalog (contract 2.0, `deployRelease`).
--
-- Until now an environment served one release per catalog, and deploying replaced it. From 2.0 a
-- deploy adds the release beside the others the environment serves, and a generation request picks
-- among them: the highest, or the highest that satisfies the version selector it sends. Undeploying
-- names the release it stops serving.
--
-- Every row stays: the release each environment served is still served, now as one of possibly
-- several. The foreign key to catalog_releases is untouched; only the primary key gains `version`.
ALTER TABLE environment_catalog_deployments DROP CONSTRAINT pk_environment_catalog_deployments;
ALTER TABLE environment_catalog_deployments
    ADD CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, environment_key, catalog_id, version);

COMMENT ON TABLE environment_catalog_deployments IS
    'The catalog releases each environment serves: any number per (environment, catalog). Generation by environment picks the highest it serves, or the highest that satisfies the request''s version selector, when a request is accepted.';

-- The log names the release each change was about. A deploy no longer replaces anything, and an
-- undeploy stops serving one release, so `version` is that release for both actions. An undeploy
-- logged before recorded it in `previous_version`, with `version` null; it moves to `version`, so
-- every entry reads the same way. `previous_version` keeps what a deploy logged before this
-- migration replaced, and is not written any more.
ALTER TABLE environment_deployment_log DROP CONSTRAINT chk_environment_deployment_log_action;
UPDATE environment_deployment_log
SET version = previous_version, previous_version = NULL
WHERE action = 'UNDEPLOYED';
ALTER TABLE environment_deployment_log ADD CONSTRAINT chk_environment_deployment_log_action CHECK (
    action IN ('DEPLOYED', 'UNDEPLOYED') AND version IS NOT NULL
);

COMMENT ON COLUMN environment_deployment_log.version IS
    'The release deployed or undeployed.';
COMMENT ON COLUMN environment_deployment_log.previous_version IS
    'For a deploy logged while an environment served one release per catalog: the release it replaced. Null otherwise; no longer written.';
