-- ADR 0036, step (d): an environment's deployment names one version row.
--
-- A deployment was a release by its number (`version`) or a draft by its row (`draft_id`), exactly
-- one of the two. Every version is a row of catalog_versions now (step a), so a deployment names
-- that row: `target_version_id`. A deployed draft is a deployment of its row, so when the row is
-- released the environment serves the release with nothing to update (contract ADR 0003, "the
-- deployment stays, an explicit deployment of the released release"); the command logs it as
-- released. Serving the next draft is an explicit new deployment. There is no "follows next".

ALTER TABLE environment_catalog_deployments ADD COLUMN target_version_id UUID;

UPDATE environment_catalog_deployments SET target_version_id = draft_id WHERE draft_id IS NOT NULL;

UPDATE environment_catalog_deployments d
SET target_version_id = v.version_id
FROM catalog_versions v
WHERE d.version IS NOT NULL
  AND v.tenant_key = d.tenant_key AND v.catalog_id = d.catalog_id
  AND v.state = 'released' AND v.version = d.version;

-- Every deployed release has its row (step a made one for each release, and the old foreign key to
-- catalog_releases kept deployments to existing releases), so nothing is left without one.
ALTER TABLE environment_catalog_deployments ALTER COLUMN target_version_id SET NOT NULL;

ALTER TABLE environment_catalog_deployments
    DROP CONSTRAINT chk_environment_catalog_deployments_one,
    DROP CONSTRAINT fk_environment_catalog_deployments_release,
    DROP CONSTRAINT fk_environment_catalog_deployments_draft;
DROP INDEX uq_environment_catalog_deployments_release;
DROP INDEX uq_environment_catalog_deployments_draft;
DROP INDEX idx_environment_catalog_deployments_release;
ALTER TABLE environment_catalog_deployments DROP COLUMN version, DROP COLUMN draft_id;

-- The deployed version is of the deployment's catalog, and cannot be deleted while it is deployed.
-- Deferred, as before, so deleting a tenant (which removes both sides) is not refused by the order
-- Postgres cascades in. The commands refuse first, naming the environment (ReleaseInUse), and end a
-- discarded or deleted draft's deployments first (DraftDeploymentStore), each logged.
ALTER TABLE environment_catalog_deployments
    ADD CONSTRAINT fk_environment_catalog_deployments_version FOREIGN KEY (tenant_key, target_version_id, catalog_id)
        REFERENCES catalog_versions (tenant_key, version_id, catalog_id) DEFERRABLE INITIALLY DEFERRED;

-- A version is served once per environment.
CREATE UNIQUE INDEX uq_environment_catalog_deployments_version
    ON environment_catalog_deployments (tenant_key, environment_key, target_version_id);
-- "Which environments serve this version": the guard on deleting it, and a release taking a draft's place.
CREATE INDEX idx_environment_catalog_deployments_version
    ON environment_catalog_deployments (tenant_key, target_version_id);

-- At most one draft of a catalog per environment. A partial index cannot see the row's state, so a
-- trigger checks it when a deployment is written. Releasing a deployed draft only removes a draft.
CREATE FUNCTION epistola_one_deployed_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_versions v
               WHERE v.tenant_key = NEW.tenant_key AND v.version_id = NEW.target_version_id AND v.state = 'draft')
       AND EXISTS (SELECT 1 FROM environment_catalog_deployments d
                   JOIN catalog_versions o ON o.tenant_key = d.tenant_key AND o.version_id = d.target_version_id
                   WHERE d.tenant_key = NEW.tenant_key AND d.environment_key = NEW.environment_key
                     AND d.catalog_id = NEW.catalog_id AND d.id <> NEW.id AND o.state = 'draft') THEN
        RAISE EXCEPTION 'environment % already serves a draft of this catalog', NEW.environment_key
            USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_environment_catalog_deployments_one_draft
    BEFORE INSERT OR UPDATE OF target_version_id ON environment_catalog_deployments
    FOR EACH ROW EXECUTE FUNCTION epistola_one_deployed_draft();

COMMENT ON COLUMN environment_catalog_deployments.target_version_id IS
    'The version deployed: a release, or a draft rendered live (never on a production environment). A deployed draft stays deployed as the release it becomes.';
