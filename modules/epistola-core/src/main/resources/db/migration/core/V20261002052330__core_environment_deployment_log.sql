-- backup-restore-compatibility: backward=true forward=false
-- reason: Adds a table. A backup taken before this migration restores with no deployment history,
-- which is the history every environment has before deployments were logged. Forward is not safe:
-- a newer backup carries a table an older schema cannot classify.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- What each environment served over time.
--
-- environment_catalog_deployments holds only what an environment serves now; a deploy overwrites
-- it. This log keeps every change, so an environment can show what changed, when and by whom, and
-- offer to deploy an earlier release again. It is written in the same transaction as the current
-- state, so the two cannot disagree. The event log is not a substitute: it is partitioned with a
-- retention period, and reading history from it would mean parsing command payloads.
--
-- No foreign key to the release: the history outlives a release that is deleted later.
CREATE TABLE environment_deployment_log (
    tenant_key       TENANT_KEY      NOT NULL,
    id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    environment_key  ENVIRONMENT_KEY NOT NULL,
    catalog_key      CATALOG_KEY     NOT NULL,
    action           VARCHAR(20)     NOT NULL,
    -- The release served after the change; null when the catalog was undeployed.
    version          VARCHAR(50),
    -- The release served before the change; null when nothing was.
    previous_version VARCHAR(50),
    changed_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    changed_by       UUID            REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT pk_environment_deployment_log PRIMARY KEY (tenant_key, id),
    CONSTRAINT fk_environment_deployment_log_environment FOREIGN KEY (tenant_key, environment_key)
        REFERENCES environments (tenant_key, id) ON DELETE CASCADE,
    CONSTRAINT chk_environment_deployment_log_action CHECK (
        (action = 'DEPLOYED' AND version IS NOT NULL)
        OR (action = 'UNDEPLOYED' AND version IS NULL AND previous_version IS NOT NULL)
    )
);

COMMENT ON TABLE environment_deployment_log IS
    'Every deploy and undeploy of a catalog release to an environment, newest last. Written with environment_catalog_deployments in one transaction.';

-- An environment's history, newest first.
CREATE INDEX idx_environment_deployment_log_environment
    ON environment_deployment_log (tenant_key, environment_key, changed_at DESC);
