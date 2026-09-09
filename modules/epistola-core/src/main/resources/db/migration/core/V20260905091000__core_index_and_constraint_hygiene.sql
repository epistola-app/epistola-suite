-- backup-restore-compatibility: backward=true forward=true
-- reason: Drops one redundant index and renames two constraints. No column changes, so a backup
-- from either side restores into the other.

-- Two long-standing blemishes, found by SchemaHygieneAppTest rather than by reading.
--
-- Neither is a correctness problem, and neither is worth rewriting a released migration for -- that
-- is forbidden anyway. They are fixed forward, which is the only way they can be fixed.

-- 1. api_keys.key_hash is declared UNIQUE, which already creates an index on exactly that column.
--    The explicit index alongside it (V20260515091200) is a duplicate: every key insert, revoke and
--    usage stamp maintains both, and lookups can only ever use one. The UNIQUE index serves every
--    query the dropped one did, so this changes no plan.
DROP INDEX IF EXISTS idx_api_keys_key_hash;

-- 2. Two constraints on catalog_release_publications were declared inline and unnamed
--    (V20260824160000:86-87), so PostgreSQL generated names and truncated them at 63 characters.
--    A truncated name is unstable -- it changes if the key gains a column -- which makes it unsafe
--    for a later migration to reference. Renaming is metadata-only.
--
--    IF EXISTS is not available for RENAME CONSTRAINT, and the generated names are deterministic
--    for a given column list, so they are matched from the catalogue rather than written out.
DO $$
DECLARE
    generated TEXT;
BEGIN
    SELECT conname INTO generated
    FROM pg_constraint
    WHERE conrelid = 'catalog_release_publications'::regclass
      AND contype = 'u'
      AND conname <> 'uq_catalog_release_publications_release';
    IF FOUND THEN
        EXECUTE format(
            'ALTER TABLE catalog_release_publications RENAME CONSTRAINT %I TO %I',
            generated, 'uq_catalog_release_publications_release'
        );
    END IF;

    SELECT conname INTO generated
    FROM pg_constraint
    WHERE conrelid = 'catalog_release_publications'::regclass
      AND contype = 'f'
      AND confrelid = 'catalog_releases'::regclass
      AND conname <> 'fk_catalog_release_publications_release';
    IF FOUND THEN
        EXECUTE format(
            'ALTER TABLE catalog_release_publications RENAME CONSTRAINT %I TO %I',
            generated, 'fk_catalog_release_publications_release'
        );
    END IF;
END $$;
