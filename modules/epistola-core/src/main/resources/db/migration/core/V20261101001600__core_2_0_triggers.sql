-- The 2.0 upgrade, step 16 of 16 (docs/migrations.md, "The 2.0 chain"): the rules that keep 2.0's
-- tables consistent from here on, now that everything of 1.x is converted. Their functions are in
-- step 15. The quality and load-test modules add theirs in their own 2.0 migrations.

-- 1. Revisions: optimistic locking for what people edit (ETag / If-Match) ---------------------------
CREATE TRIGGER trg_tenants_revision BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION bump_content_revision();
CREATE TRIGGER trg_environments_revision BEFORE UPDATE ON environments FOR EACH ROW EXECUTE FUNCTION bump_content_revision();
CREATE TRIGGER trg_catalog_versions_revision BEFORE UPDATE ON catalog_versions FOR EACH ROW EXECUTE FUNCTION bump_catalog_version_revision();
CREATE TRIGGER trg_catalog_version_dependencies_revision
    AFTER INSERT OR UPDATE OR DELETE ON catalog_version_dependencies
    FOR EACH ROW EXECUTE FUNCTION bump_catalog_version_revision_for_dependency();

-- 2. A catalog's next draft ------------------------------------------------------------------------
-- Opened when the catalog is inserted, and checked at commit: at most one, a draft, of its catalog.
CREATE TRIGGER trg_catalogs_open_next_version AFTER INSERT ON catalogs FOR EACH ROW EXECUTE FUNCTION catalog_open_next_version();
CREATE CONSTRAINT TRIGGER trg_catalogs_next_check AFTER INSERT OR UPDATE OF next_version_id ON catalogs
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_next_version_check();
CREATE CONSTRAINT TRIGGER trg_catalog_versions_next_check AFTER INSERT OR UPDATE OF state, name ON catalog_versions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_next_version_check();

-- 3. An environment serves at most one draft of a catalog ------------------------------------------
CREATE TRIGGER trg_environment_catalog_deployments_one_draft
    BEFORE INSERT OR UPDATE OF target_version_id ON environment_catalog_deployments
    FOR EACH ROW EXECUTE FUNCTION epistola_one_deployed_draft();

-- 4. What a version's rows keep ---------------------------------------------------------------------
-- A wire type added later is a storage type too.
CREATE TRIGGER trg_catalog_resource_types_storage AFTER INSERT ON catalog_resource_types
    FOR EACH ROW EXECUTE FUNCTION add_release_resource_type();

-- A draft row's identity, as catalog_resources registers it: an insert adopts the identity registered
-- at its address (or registers one), a move or rename carries it along, and the last row holding one
-- takes it along when it goes. A released row is frozen.
CREATE TRIGGER trg_release_resources_draft_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_id, resource_key ON release_resources
    FOR EACH ROW WHEN (NEW.parent_id IS NULL AND release_resource_is_draft(NEW.tenant_key, NEW.version_id))
    EXECUTE FUNCTION sync_draft_resource_identity();
CREATE TRIGGER trg_release_resources_draft_delete
    AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.parent_id IS NULL AND NOT release_resource_is_released(OLD.tenant_key, OLD.version_id))
    EXECUTE FUNCTION sync_draft_resource_identity();
-- A draft losing a resource changes its catalog's working copy.
CREATE TRIGGER trg_release_resources_draft_touched
    AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (NOT release_resource_is_released(OLD.tenant_key, OLD.version_id))
    EXECUTE FUNCTION touch_catalog_content_on_delete();
-- A draft row's revision and updated_at.
CREATE TRIGGER trg_release_resources_draft_updated_at
    BEFORE UPDATE ON release_resources
    FOR EACH ROW WHEN (release_resource_is_draft(OLD.tenant_key, OLD.version_id)) EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_release_resources_draft_revision
    BEFORE UPDATE ON release_resources
    FOR EACH ROW WHEN (release_resource_is_draft(OLD.tenant_key, OLD.version_id)) EXECUTE FUNCTION bump_content_revision();

-- A template's variants belong to its row in the same version, checked at commit (a template and its
-- variants are written in one transaction); deleting the template's row deletes them, and moving it
-- without them is refused.
CREATE CONSTRAINT TRIGGER trg_release_resources_variant_parent AFTER INSERT OR UPDATE ON release_resources
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.parent_id IS NOT NULL) EXECUTE FUNCTION release_resource_variant_parent();
CREATE TRIGGER trg_release_resources_template_deleted AFTER DELETE ON release_resources
    FOR EACH ROW WHEN (OLD.resource_type = 'template') EXECUTE FUNCTION release_resource_variants_deleted();
CREATE CONSTRAINT TRIGGER trg_release_resources_template_moved AFTER UPDATE OF catalog_id, version_id ON release_resources
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.resource_type = 'template') EXECUTE FUNCTION release_resource_variants_moved();

-- 5. The conversion filled the storage tables at once: the planner must see them as they are before
--    the first node serves (a catalog's reads are planned on their size, ThemePlanStabilityTest).
ANALYZE catalog_versions;
ANALYZE release_resources;
ANALYZE resource_contents;
ANALYZE resource_references;
ANALYZE resource_binaries;
ANALYZE resource_ready_marks;
