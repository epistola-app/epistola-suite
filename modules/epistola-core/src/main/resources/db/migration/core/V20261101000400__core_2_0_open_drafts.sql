-- The 2.0 upgrade, step 4 of 16 (docs/migrations.md, "The 2.0 chain"): every catalog's next draft.
--
-- Every catalog has a next version (ADR 0032): the draft its 1.x content becomes, the working copy
-- with any unpublished 1.x work (steps 6 to 8). A catalog its tenant authored names as its base its
-- highest 1.x release by number, which is not here: a release of the draft is numbered above it
-- (ADR 0035). A catalog received from a source names the release it had installed; it receives that
-- release at boot (ReceiveOneXSubscribedReleases).

INSERT INTO catalog_versions (tenant_key, catalog_id, state, base_version)
SELECT c.tenant_key, c.catalog_id, 'draft',
       CASE
           WHEN c.type = 'AUTHORED' THEN (
               SELECT r.version
               FROM catalog_releases r
               WHERE r.tenant_key = c.tenant_key AND r.catalog_key = c.catalog_key AND r.version_major IS NOT NULL
               ORDER BY r.version_major DESC, r.version_minor DESC, r.version_patch DESC
               LIMIT 1)
           ELSE NULLIF(c.installed_release_version, '0.0.0')
       END
FROM catalogs c;

UPDATE catalogs c
SET next_version_id = v.version_id
FROM catalog_versions v
WHERE v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id;

-- Themes per catalog version (contract ADR 0007): a version carries its own default theme and the
-- block style presets its content may use. Nothing renders differently: an authored catalog whose
-- templates inherit their theme takes the tenant's default theme, which they rendered with in 1.x.
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
      WHERE dt.tenant_key = c.tenant_key AND dt.catalog_key = c.catalog_key AND dt.theme_resource_id IS NULL
  );

-- Each version declares the presets its themes style, named by their labels, so the blocks that use
-- them stay valid: one per preset key, named by the first theme (by key) that styles it.
UPDATE catalog_versions v
SET presets = declared.presets
FROM (
    SELECT p.tenant_key, p.catalog_key,
           jsonb_agg(jsonb_build_object('key', p.key, 'name', COALESCE(p.value ->> 'label', p.key), 'description', NULL)
                     ORDER BY p.key) AS presets
    FROM (
        SELECT DISTINCT ON (th.tenant_key, th.catalog_key, preset.key) th.tenant_key, th.catalog_key, preset.key, preset.value
        FROM themes th
        -- Presets that are no object are none (Envelopes.theme stores them as null).
        CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(th.block_style_presets) = 'object' THEN th.block_style_presets ELSE '{}'::jsonb END) AS preset(key, value)
        ORDER BY th.tenant_key, th.catalog_key, preset.key, th.id
    ) p
    GROUP BY p.tenant_key, p.catalog_key
) declared
JOIN catalogs c ON c.tenant_key = declared.tenant_key AND c.catalog_key = declared.catalog_key
WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id;
