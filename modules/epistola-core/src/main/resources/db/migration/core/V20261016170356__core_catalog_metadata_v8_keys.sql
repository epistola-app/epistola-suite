-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Catalog metadata is stored in the catalog wire shape, and wire v8 renamed three of its members
-- (contract CatalogKeyRenames): the presentation's iconAssetSlug and imageAssetSlugs became
-- iconImageKey and imageKeys, and an attribute assignment's catalog became catalogKey. Binding reads
-- either name, but SQL reads members by name (an image is in use when a catalog presents it), so
-- rename them in place, keeping each value: every stored row then has the one shape v8 writes.

CREATE FUNCTION pg_temp.renamed_member(member_owner JSONB, old_name TEXT, new_name TEXT) RETURNS JSONB
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE
           WHEN jsonb_typeof(member_owner) <> 'object' OR NOT member_owner ? old_name THEN member_owner
           -- A member already under its new name wins; the old one is dropped either way.
           ELSE (member_owner - old_name) || jsonb_build_object(new_name, COALESCE(member_owner -> new_name, member_owner -> old_name))
       END
$$;

UPDATE catalogs
SET catalog_metadata = jsonb_set(
        catalog_metadata, '{presentation}',
        pg_temp.renamed_member(
            pg_temp.renamed_member(catalog_metadata -> 'presentation', 'iconAssetSlug', 'iconImageKey'),
            'imageAssetSlugs', 'imageKeys'))
WHERE jsonb_typeof(catalog_metadata -> 'presentation') = 'object';

UPDATE catalogs
SET catalog_metadata = jsonb_set(
        catalog_metadata, '{attributes}',
        (SELECT COALESCE(jsonb_agg(pg_temp.renamed_member(assignment, 'catalog', 'catalogKey') ORDER BY position), '[]'::jsonb)
         FROM jsonb_array_elements(catalog_metadata -> 'attributes') WITH ORDINALITY AS a(assignment, position)))
WHERE jsonb_typeof(catalog_metadata -> 'attributes') = 'array';
