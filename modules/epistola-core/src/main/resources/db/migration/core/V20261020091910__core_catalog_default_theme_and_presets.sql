-- Themes per catalog (contract ADR 0007). A catalog version carries its own default theme and the
-- block style presets its content may use; both go out with the version. The tenant's default theme
-- stays as a house theme: a starting value for catalogs, no longer read while rendering.
ALTER TABLE catalog_versions
    ADD COLUMN default_theme_resource_id UUID,
    ADD COLUMN presets JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT fk_catalog_versions_default_theme
        FOREIGN KEY (tenant_key, default_theme_resource_id) REFERENCES catalog_resources (tenant_key, resource_id);

COMMENT ON COLUMN catalog_versions.default_theme_resource_id IS
    'The theme a template that names none renders with (ADR 0007); NULL renders with the rendering defaults alone.';
COMMENT ON COLUMN catalog_versions.presets IS
    'The block style presets this version declares: [{key, name, description}] (ADR 0007). Its themes style them.';

-- Nothing renders differently: an authored catalog version whose templates inherit their theme takes
-- the tenant's default theme, which they rendered with until now.
UPDATE catalog_versions v
SET default_theme_resource_id = t.default_theme_resource_id
FROM catalogs c
JOIN tenants t ON t.id = c.tenant_key
WHERE c.tenant_key = v.tenant_key
  AND c.catalog_id = v.catalog_id
  AND c.type = 'AUTHORED'
  AND t.default_theme_resource_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM document_templates dt
      WHERE dt.tenant_key = v.tenant_key AND dt.version_id = v.version_id AND dt.theme_resource_id IS NULL
  );

-- Each version declares the presets its own themes style, named by their labels, so the blocks that
-- use them stay valid.
UPDATE catalog_versions v
SET presets = declared.presets
FROM (
    SELECT p.tenant_key, p.version_id,
           jsonb_agg(jsonb_build_object('key', p.key, 'name', COALESCE(p.value ->> 'label', p.key), 'description', NULL)
                     ORDER BY p.key) AS presets
    FROM (
        -- One row per preset key in a version: the first theme (by key) that styles it names it.
        SELECT DISTINCT ON (th.tenant_key, th.version_id, preset.key) th.tenant_key, th.version_id, preset.key, preset.value
        FROM themes th
        CROSS JOIN LATERAL jsonb_each(COALESCE(th.block_style_presets, '{}'::jsonb)) AS preset(key, value)
        ORDER BY th.tenant_key, th.version_id, preset.key, th.id
    ) p
    GROUP BY p.tenant_key, p.version_id
) declared
WHERE declared.tenant_key = v.tenant_key AND declared.version_id = v.version_id;
