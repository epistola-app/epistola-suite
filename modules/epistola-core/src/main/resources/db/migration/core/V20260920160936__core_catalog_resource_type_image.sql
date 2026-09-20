-- backup-restore-compatibility: backward=false forward=false
-- reason: Renames a seeded resource-type token that identity registry rows reference. A backup
-- taken either side names the same resources differently.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- An image is a catalog resource; a binary is not.
--
-- `catalog_resource_types` holds the catalog wire's own resource-type tokens, so that a registry
-- address is the same triple an export uses. Wire v7 stops naming binaries: an image is a resource
-- addressed by a slug, and the bytes behind it -- an image's own, or a font face's -- are
-- identified by their content hash instead. The token follows the wire.
--
-- The `assets` table is untouched and keeps its name. It is storage, not a catalog concept: one
-- content-addressed store that images and font faces both draw on, which is exactly why a binary
-- stopped being a resource. Font faces keep their rows there; they simply are not addressable.

INSERT INTO catalog_resource_types (resource_type) VALUES ('image');

UPDATE catalog_resources SET resource_type = 'image' WHERE resource_type = 'asset';
UPDATE catalog_resource_aliases SET resource_type = 'image' WHERE resource_type = 'asset';

DELETE FROM catalog_resource_types WHERE resource_type = 'asset';

-- The identity trigger names the type as a literal argument, so the assets trigger is recreated
-- with the new token. Its delete counterpart carries the same argument and is recreated too.
DROP TRIGGER trg_assets_resource_identity ON assets;
DROP TRIGGER trg_assets_delete_resource_identity ON assets;

CREATE TRIGGER trg_assets_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON assets
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('image', 'id');
CREATE TRIGGER trg_assets_delete_resource_identity
    AFTER DELETE ON assets
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('image', 'id');
