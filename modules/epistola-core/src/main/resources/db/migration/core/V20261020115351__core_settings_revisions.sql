-- Optimistic locking for settings people change through the API (contract: ETag / If-Match on
-- updateTenant, updateEnvironment, updateCatalogDraft and updateCatalogDependencies), as content
-- already has (core_content_revisions): each row carries a revision that every update changing it
-- raises by one, and a write naming the revision it read is refused when the row has moved on.

-- A tenant (its name, default locale, house theme, publishing default) and an environment (its name
-- and production mark): any change counts, as for content. Bookkeeping does not (updated_at, updated_by).
ALTER TABLE tenants ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN tenants.revision IS
    'Raised by one on every update that changes the row (bump_content_revision); a write naming the revision it read is refused when it differs.';
CREATE TRIGGER trg_tenants_revision BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION bump_content_revision();

ALTER TABLE environments ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN environments.revision IS
    'Raised by one on every update that changes the row (bump_content_revision); a write naming the revision it read is refused when it differs.';
CREATE TRIGGER trg_environments_revision BEFORE UPDATE ON environments FOR EACH ROW EXECUTE FUNCTION bump_content_revision();

-- A catalog version: what a person sets on it, its name, default theme and presets, and its
-- dependency declarations, which live in catalog_dependencies. The rest of the row is the version's
-- lifecycle (its key, number, base, whether it is next), which publishing moves; it does not count.
ALTER TABLE catalog_versions ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN catalog_versions.revision IS
    'Raised by one when the version''s name, default theme, presets or dependency declarations change (bump_catalog_version_revision, catalog_dependencies triggers); a write naming the revision it read is refused when it differs.';

CREATE FUNCTION bump_catalog_version_revision() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.revision = OLD.revision + 1 THEN
        RETURN NEW;
    END IF;
    IF (NEW.name, NEW.default_theme_resource_id, NEW.presets) IS DISTINCT FROM (OLD.name, OLD.default_theme_resource_id, OLD.presets) THEN
        NEW.revision := OLD.revision + 1;
    ELSE
        NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bump_catalog_version_revision() IS
    'BEFORE UPDATE trigger function for catalog_versions: raises revision by one when the name, default theme or presets changed; a raise by one the update made itself (a dependency change) is kept.';

CREATE TRIGGER trg_catalog_versions_revision BEFORE UPDATE ON catalog_versions FOR EACH ROW EXECUTE FUNCTION bump_catalog_version_revision();

CREATE FUNCTION bump_catalog_version_revision_for_dependency() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    changed RECORD;
BEGIN
    changed := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    UPDATE catalog_versions SET revision = revision + 1
    WHERE tenant_key = changed.tenant_key AND version_id = changed.version_id;
    IF TG_OP = 'UPDATE' AND OLD.version_id IS DISTINCT FROM NEW.version_id THEN
        UPDATE catalog_versions SET revision = revision + 1
        WHERE tenant_key = OLD.tenant_key AND version_id = OLD.version_id;
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION bump_catalog_version_revision_for_dependency() IS
    'AFTER trigger function for catalog_dependencies: a declaration added, changed or removed raises the revision of the catalog version that declares it.';

CREATE TRIGGER trg_catalog_dependencies_version_revision
    AFTER INSERT OR UPDATE OR DELETE ON catalog_dependencies
    FOR EACH ROW EXECUTE FUNCTION bump_catalog_version_revision_for_dependency();
