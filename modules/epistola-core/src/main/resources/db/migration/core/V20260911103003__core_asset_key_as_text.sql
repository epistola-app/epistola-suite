-- backup-restore-compatibility: backward=true forward=false
-- reason: Widens a key column's type. A backup taken before this migration restores onto the wider
-- column unchanged (every value was already a UUID string); a backup taken after it may contain
-- human-named asset keys that an older schema's UUID column cannot hold.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- An asset's public key becomes text, like every other catalog resource's.
--
-- Assets were the one resource type whose *address* was a machine identifier: `ASSET_KEY` was a
-- UUID, so an asset's key on the wire and in template content was a UUID too. Every other resource
-- is addressed by a human key and carries a UUID only as its internal identity.
--
-- That was not merely inelegant. It made a catalog unreadable and, worse, uninstallable: importing
-- parsed an asset's slug straight back into a UUID, so a catalog naming its assets the way a person
-- would -- `municipality-mark` -- was refused. Nothing upstream prevented publishing one, because
-- the catalog wire schema declares an asset slug as a plain string. Installing from Epistola
-- Exchange therefore worked only between two Epistola Suites, each generating UUIDs the other
-- happened to parse.
--
-- Relocation (V20260905090000/090100) had already separated identity from address for every
-- resource, assets included: `assets.resource_id` is the stable internal identity, and it stays a
-- UUID. It also re-keyed the only other user of this domain -- `font_variants` now references
-- `assets(tenant_key, resource_id)` by identity rather than by key -- which leaves `assets.id` the
-- domain's single remaining column and this migration a narrow one.
--
-- Existing data needs no rewriting. An asset's current key is already a UUID *string*, which is a
-- valid text key, so every stored reference in template content keeps resolving to the same asset
-- and content-storage paths (`assets/{tenant}/{key}`) are byte-identical. Only newly created assets
-- can carry a readable key, and the Suite still generates one for them until authoring a name is
-- built.

-- The identity trigger names `id` in its `UPDATE OF` clause, which pins the column's type, so it is
-- dropped and recreated verbatim around the change (see V20260905090000). The delete trigger names
-- no column and is left alone.
DROP TRIGGER trg_assets_resource_identity ON assets;

-- The pattern admits both, which is what makes the change data-preserving: a UUID string begins
-- with a hex digit, so the stricter slug rule used by the other key domains (`^[a-z]...`) would
-- reject every asset that exists today.
ALTER TABLE assets ALTER COLUMN id TYPE VARCHAR(50) USING id::text;

DROP DOMAIN ASSET_KEY;

CREATE DOMAIN ASSET_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

COMMENT ON DOMAIN ASSET_KEY IS
    'Public key of an asset: a human slug, or the UUID string generated for assets created before '
    'keys could be named. Deliberately looser than the other key domains, which require a leading '
    'letter. The stable internal identity is assets.resource_id, which is a UUID.';

ALTER TABLE assets ALTER COLUMN id TYPE ASSET_KEY;

CREATE TRIGGER trg_assets_resource_identity
    BEFORE INSERT OR UPDATE OF resource_id, catalog_key, id ON assets
    FOR EACH ROW EXECUTE FUNCTION sync_catalog_resource_identity('asset', 'id');

COMMENT ON COLUMN assets.id IS
    'Public key, unique per (tenant, catalog). Travels on the wire as the asset''s slug and appears '
    'in template content as props.assetId. Not the internal identity — that is resource_id.';
