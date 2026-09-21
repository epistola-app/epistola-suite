-- backup-restore-compatibility: backward=true forward=false
-- reason: Widens a key column's type. A backup taken before this migration restores onto the wider
-- column unchanged; a backup taken after it may contain theme keys longer than an older schema's
-- 20-character column can hold.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A theme key is as long as every other resource key.
--
-- `THEME_KEY` has been VARCHAR(20) since the theme feature shipped, and nothing else in the schema
-- is 20: templates, stencils, attributes and variants are 50, fonts and code lists 64. The bound
-- was never a property of themes -- `corporate-identity-2026` is 23 characters and an ordinary
-- theme name -- so it is widened to match the majority rather than kept and explained.
--
-- Catalog wire v7 publishes a per-type slug bound taken from these columns, and its own rule is
-- that a maximum may be relaxed later but never tightened once catalogs use the extra room. Doing
-- this before v7 releases is what keeps the published schema from having to move under consumers
-- that already validate against it.
--
-- Data-preserving by construction: widening never truncates, and every key that exists fits.
--
-- Relocation (V20260905090100) dropped `document_templates.theme_key` and
-- `tenants.default_theme_key`, so `themes.id` is the domain's only remaining column and this is a
-- narrow change -- the same shape as V20260911103003, which widened ASSET_KEY.

-- The identity trigger names `id` in its `UPDATE OF` clause, which pins the column's type, so it is
-- dropped and recreated verbatim around the change (see V20260905090000). The delete trigger names
-- no column and is left alone.
DROP TRIGGER trg_themes_resource_identity ON themes;

ALTER TABLE themes ALTER COLUMN id TYPE VARCHAR(50);

DROP DOMAIN THEME_KEY;

CREATE DOMAIN THEME_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

COMMENT ON DOMAIN THEME_KEY IS
    'Public key of a theme: a human slug, 3 to 50 characters. The stable internal identity is '
    'themes.resource_id, which is a UUID.';

ALTER TABLE themes ALTER COLUMN id TYPE THEME_KEY;

CREATE TRIGGER trg_themes_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON themes
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('theme', 'id');
