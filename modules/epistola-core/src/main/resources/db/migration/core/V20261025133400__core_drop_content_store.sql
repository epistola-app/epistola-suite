-- #742: the 1.x shared content store is gone. V20261025105700 moved every document and asset blob
-- whose owner exists into document_content and asset_content; what is left names nothing that
-- exists (a document deleted before 1.x moved its blob, an asset removed since) and goes with it.
DO $$
DECLARE
    total INTEGER;
    orphaned INTEGER;
BEGIN
    SELECT count(*) INTO total FROM content_store;
    SELECT count(*) INTO orphaned FROM content_store cs
    WHERE NOT EXISTS (SELECT 1 FROM document_content dc WHERE dc.key = cs.key)
      AND NOT EXISTS (
          SELECT 1 FROM assets a
          WHERE cs.key = 'assets/' || a.tenant_key || '/' || a.id
            AND EXISTS (SELECT 1 FROM asset_content ac WHERE ac.content_hash = a.content_hash)
      );
    IF total > 0 THEN
        RAISE NOTICE 'Dropping content_store: % row(s), of which % named nothing that exists any more', total, orphaned;
    END IF;
END $$;

DROP TABLE content_store;
