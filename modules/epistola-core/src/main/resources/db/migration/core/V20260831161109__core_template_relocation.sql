-- Makes templates relocatable.
--
-- A template's address is embedded in eight tables across three modules, and in most of them it is
-- part of the primary key. Two groups need opposite treatment.
--
-- 1. The hierarchy and everything that describes a template's CURRENT state -- variants, versions,
--    contract versions, environment activations, quality findings, load-test runs -- follows the
--    template. Their foreign keys gain ON UPDATE CASCADE, so updating the template's catalog_key
--    propagates in one statement.
--
-- 2. Generation history -- documents and document_generation_requests -- does NOT follow. A
--    generation record states what happened: this document was produced from this template, in this
--    catalog, at this time. Cascading would rewrite that into something that never occurred, and
--    would rewrite an unbounded number of partitioned rows inside what should be a single-row
--    update. Their composite foreign keys into the hierarchy are dropped instead, so the address
--    columns remain as historical facts.
--
--    Consequence, accepted deliberately: deleting a template no longer purges its generation
--    history. History outliving its template is the better answer for an audit record, and
--    partition retention still ages it out.
--
--    To keep the link back to the template durable rather than relying on resolving a stale address
--    through an alias, both tables gain template_resource_id. It is deliberately NOT backfilled:
--    the column is nullable, a trigger fills it on insert, and because these tables are partitioned
--    by created_at with retention, every surviving row carries it within one retention window. The
--    backfill happens by itself. Rows predating this keep resolving through their address.
--
-- Constraint names are resolved from the catalog rather than written literally: these foreign keys
-- were declared inline, so their generated names are truncated at 63 characters and are not safe
-- to hard-code.

-- 1. Generation history stops tracking the template's address.
DO $$
DECLARE
    constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT con.conname, con.conrelid::regclass AS owning_table
        FROM pg_constraint con
        JOIN pg_class referenced ON referenced.oid = con.confrelid
        WHERE con.contype = 'f'
          AND con.conrelid::regclass::text IN ('documents', 'document_generation_requests')
          AND referenced.relname IN ('document_templates', 'template_variants', 'template_versions')
    LOOP
        EXECUTE format(
            'ALTER TABLE %s DROP CONSTRAINT %I',
            constraint_row.owning_table,
            constraint_row.conname
        );
    END LOOP;
END $$;

-- 2. Everything that describes the template's current state follows it.
--    Scoped to foreign keys that actually carry catalog_key: those are the ones a move would break.
DO $$
DECLARE
    constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT con.conname,
               con.conrelid::regclass AS owning_table,
               pg_get_constraintdef(con.oid) AS definition
        FROM pg_constraint con
        JOIN pg_class referenced ON referenced.oid = con.confrelid
        WHERE con.contype = 'f'
          AND referenced.relname IN (
              'document_templates', 'template_variants', 'template_versions', 'contract_versions'
          )
          AND pg_get_constraintdef(con.oid) LIKE '%catalog_key%'
          AND pg_get_constraintdef(con.oid) NOT LIKE '%ON UPDATE CASCADE%'
    LOOP
        EXECUTE format(
            'ALTER TABLE %s DROP CONSTRAINT %I',
            constraint_row.owning_table,
            constraint_row.conname
        );
        EXECUTE format(
            'ALTER TABLE %s ADD CONSTRAINT %I %s ON UPDATE CASCADE',
            constraint_row.owning_table,
            constraint_row.conname,
            constraint_row.definition
        );
    END LOOP;
END $$;

COMMENT ON COLUMN documents.catalog_key IS
    'Catalog the template lived in when this document was generated. A historical fact: it does not follow a later relocation.';
COMMENT ON COLUMN document_generation_requests.catalog_key IS
    'Catalog the template lived in when generation was requested. A historical fact: it does not follow a later relocation.';

-- 3. Durable linkage from generation history to the template, filled forward only.
ALTER TABLE documents ADD COLUMN template_resource_id UUID;
ALTER TABLE document_generation_requests ADD COLUMN template_resource_id UUID;

COMMENT ON COLUMN documents.template_resource_id IS
    'Stable identity of the generating template. Null for rows written before this column existed; those resolve through their recorded address instead. No foreign key: adding one would scan every partition at upgrade time, and a dangling id simply fails to join.';
COMMENT ON COLUMN document_generation_requests.template_resource_id IS
    'Stable identity of the generating template. Null for rows written before this column existed.';

-- Filled by trigger rather than at the three production insert sites, so a future writer -- or a
-- test fake -- cannot silently omit it.
CREATE FUNCTION fill_generation_template_identity() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.template_resource_id IS NULL THEN
        SELECT resource_id INTO NEW.template_resource_id
        FROM document_templates
        WHERE tenant_key = NEW.tenant_key
          AND catalog_key = NEW.catalog_key
          AND id = NEW.template_key;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_documents_template_identity
    BEFORE INSERT ON documents
    FOR EACH ROW EXECUTE FUNCTION fill_generation_template_identity();

CREATE TRIGGER trg_generation_requests_template_identity
    BEFORE INSERT ON document_generation_requests
    FOR EACH ROW EXECUTE FUNCTION fill_generation_template_identity();
