-- ADR 0036: a release's settings are frozen on its row. A release cut here keeps the settings of the
-- draft it froze; a release recorded otherwise (migrated in V20261026162943, installed, restored)
-- takes them from its manifest, as the release states them: its default theme (contract ADR 0007,
-- by alias and key, resolved through the release's own declarations like every reference into
-- another catalog) and its declared presets.

-- The theme a release's manifest names as its default, by identity; NULL when it names none or no
-- theme of that catalog is registered here.
CREATE FUNCTION catalog_release_default_theme(p_tenant TEXT, p_catalog UUID, p_version TEXT, p_manifest JSONB)
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
SELECT r.resource_id FROM catalog_resources r
WHERE p_manifest #>> '{catalog,defaultTheme,key}' IS NOT NULL
  AND r.tenant_key = p_tenant AND r.resource_type = 'theme'
  AND r.catalog_id = resolve_catalog_alias(p_tenant, p_catalog, NULL, p_version, p_manifest #>> '{catalog,defaultTheme,catalogKey}')
  AND r.resource_key = p_manifest #>> '{catalog,defaultTheme,key}'
$$;

-- The presets a release's manifest declares, in the form a draft holds them (by key).
CREATE FUNCTION catalog_release_presets(p_manifest JSONB)
    RETURNS JSONB
    LANGUAGE sql
    IMMUTABLE
AS $$
SELECT COALESCE(
    (SELECT jsonb_agg(jsonb_build_object('key', p ->> 'key', 'name', p ->> 'name', 'description', p -> 'description') ORDER BY p ->> 'key')
     FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p_manifest #> '{catalog,presets}') = 'array' THEN p_manifest #> '{catalog,presets}' ELSE '[]'::jsonb END) p),
    '[]'::jsonb)
$$;

COMMENT ON FUNCTION catalog_release_default_theme(TEXT, UUID, TEXT, JSONB) IS
    'A release''s default theme by identity, from its manifest (ADR 0036): its alias resolved through the release''s declarations; NULL when none is named or none is registered.';
COMMENT ON FUNCTION catalog_release_presets(JSONB) IS
    'A release''s declared presets, from its manifest, as a draft holds them (ADR 0036).';

UPDATE catalog_versions v
SET default_theme_resource_id = catalog_release_default_theme(v.tenant_key, v.catalog_id, v.version, v.manifest_snapshot),
    presets = catalog_release_presets(v.manifest_snapshot)
WHERE v.state = 'released' AND v.default_theme_resource_id IS NULL AND v.presets = '[]'::jsonb;

DO $$
DECLARE
    unresolved INTEGER;
BEGIN
    SELECT count(*) INTO unresolved FROM catalog_versions
    WHERE state = 'released' AND default_theme_resource_id IS NULL AND manifest_snapshot #>> '{catalog,defaultTheme,key}' IS NOT NULL;
    IF unresolved > 0 THEN
        RAISE WARNING '% release(s) name a default theme that is not registered here; their row keeps none', unresolved;
    END IF;
END;
$$;
