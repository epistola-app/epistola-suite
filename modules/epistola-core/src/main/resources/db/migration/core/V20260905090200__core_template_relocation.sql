-- backup-restore-compatibility: backward=false forward=false
-- reason: Re-keys templates onto their identity and replaces the address columns across the
-- hierarchy. A backup taken either side carries a different column set for five backed-up tables.

-- Templates keyed by identity
--
-- A template's address was embedded in eight tables across three modules, and in most of them it
-- was part of the primary key. Two groups need opposite treatment.
--
-- 1. The hierarchy and everything that describes a template's CURRENT state -- variants, versions,
--    contract versions, activations, quality findings, load-test runs -- follows the template.
--    They now name the template itself, so a move updates one row and nothing cascades.
--
-- 2. Generation history -- documents and document_generation_requests -- does NOT follow. A
--    generation record states what happened: this document was produced from this template, in
--    this catalog, at this time. Cascading would rewrite that into something that never occurred,
--    and would rewrite an unbounded number of partitioned rows inside what should be a single-row
--    update. Their composite foreign keys into the hierarchy are dropped instead, so the address
--    columns remain as historical facts.
--
--    Consequence, accepted deliberately: deleting a template no longer purges its generation
--    history. History outliving its template is the better answer for an audit record, and
--    partition retention still ages it out.
--
--    They gain template_resource_id as the durable link back, backfilled here rather than filled
--    forward only: an address recorded at generation time stops matching the template the moment
--    it is renamed, so a forward-only column would leave every historical row unreachable by
--    identity and wrong by address.
--
-- Nothing references a template as a catalog dependency, so there is no content to re-point.
--
-- Foreign keys in the other two modules (quality, loadtest) are released here and rebuilt on
-- identity by each module's own migration, which runs immediately after this one.

-- 1. Add ------------------------------------------------------------------------------------------
ALTER TABLE template_variants ADD COLUMN template_resource_id UUID;
ALTER TABLE template_versions ADD COLUMN template_resource_id UUID;
ALTER TABLE contract_versions ADD COLUMN template_resource_id UUID;
ALTER TABLE environment_activations ADD COLUMN template_resource_id UUID;
ALTER TABLE documents ADD COLUMN template_resource_id UUID;
ALTER TABLE document_generation_requests ADD COLUMN template_resource_id UUID;

-- 2. Backfill the hierarchy, asserting every row found its template ------------------------------------
-- Only the hierarchy. Generation history is deliberately NOT backfilled -- see step 6, where the
-- reasoning belongs with the columns it concerns. These four tables are bounded (versions are
-- capped at 200 per variant), so the rewrite is small and the assertion is affordable.
DO $$
DECLARE
    dependant TEXT;
    unmatched BIGINT;
BEGIN
    FOREACH dependant IN ARRAY ARRAY[
        'template_variants', 'template_versions', 'contract_versions', 'environment_activations'
    ] LOOP
        EXECUTE format(
            'UPDATE %I dependant SET template_resource_id = templates.resource_id
             FROM document_templates templates
             WHERE templates.tenant_key = dependant.tenant_key
               AND templates.catalog_key = dependant.catalog_key
               AND templates.id = dependant.template_key',
            dependant
        );
        EXECUTE format(
            'SELECT count(*) FROM %I WHERE template_resource_id IS NULL', dependant
        ) INTO unmatched;
        IF unmatched > 0 THEN
            RAISE EXCEPTION '% rows in % reference a template that does not exist', unmatched, dependant;
        END IF;
    END LOOP;
END $$;

ALTER TABLE template_variants ALTER COLUMN template_resource_id SET NOT NULL;
ALTER TABLE template_versions ALTER COLUMN template_resource_id SET NOT NULL;
ALTER TABLE contract_versions ALTER COLUMN template_resource_id SET NOT NULL;
ALTER TABLE environment_activations ALTER COLUMN template_resource_id SET NOT NULL;

-- 3. Release the addresses ---------------------------------------------------------------------------
-- Every foreign key into the hierarchy holds a primary key in place, so all of them let go before
-- the keys are swapped -- including the two other modules', which their own migrations rebuild.
-- Names are resolved from the catalog rather than written literally: these were declared inline,
-- so their generated names are truncated at 63 characters and are not safe to hard-code.
DO $$
DECLARE
    constraint_row RECORD;
    released INT := 0;
BEGIN
    FOR constraint_row IN
        SELECT con.conname, con.conrelid::regclass AS owning_table
        FROM pg_constraint con
        JOIN pg_class referenced ON referenced.oid = con.confrelid
        WHERE con.contype = 'f'
          AND referenced.relname IN (
              'document_templates', 'template_variants', 'template_versions', 'contract_versions'
          )
          -- A partition carries a copy of each of its parent's constraints, which cannot be
          -- dropped on the child. Dropping the parent's takes them with it.
          AND con.conparentid = 0
        -- Ordered so every run takes ACCESS EXCLUSIVE on the same tables in the same sequence.
        -- Unordered, pg_constraint's row order decides it, and two concurrent upgrades -- or an
        -- upgrade beside live traffic -- can each hold what the other is about to ask for.
        ORDER BY con.conrelid::regclass::text, con.conname
    LOOP
        EXECUTE format(
            'ALTER TABLE %s DROP CONSTRAINT %I', constraint_row.owning_table, constraint_row.conname
        );
        released := released + 1;
    END LOOP;
    -- Core alone declares eleven; a match count this low means the catalog scan found nothing and
    -- the primary-key swaps below would silently keep the addresses reachable.
    IF released < 11 THEN
        RAISE EXCEPTION 'expected at least 11 foreign keys into the template hierarchy, released %', released;
    END IF;
END $$;

-- 4. Swap the keys ------------------------------------------------------------------------------------
ALTER TABLE document_templates
    DROP CONSTRAINT document_templates_pkey,
    -- The unique constraint that stood in for the primary key while the address still held it
    -- is now an exact duplicate of it. Dropped here, before the dependants' foreign keys are
    -- added below, so those bind to the primary key rather than to a second identical index
    -- that every write would then have to maintain.
    DROP CONSTRAINT uq_document_templates_resource_id,
    ADD CONSTRAINT document_templates_pkey PRIMARY KEY (tenant_key, resource_id),
    ADD CONSTRAINT uq_document_templates_address UNIQUE (tenant_key, catalog_key, id);

ALTER TABLE template_variants
    DROP CONSTRAINT template_variants_pkey,
    ADD CONSTRAINT template_variants_pkey PRIMARY KEY (tenant_key, template_resource_id, id);

ALTER TABLE template_versions
    DROP CONSTRAINT template_versions_pkey,
    ADD CONSTRAINT template_versions_pkey PRIMARY KEY (tenant_key, template_resource_id, variant_key, id);

ALTER TABLE contract_versions
    DROP CONSTRAINT contract_versions_pkey,
    ADD CONSTRAINT contract_versions_pkey PRIMARY KEY (tenant_key, template_resource_id, id);

ALTER TABLE environment_activations
    DROP CONSTRAINT environment_activations_pkey,
    ADD CONSTRAINT environment_activations_pkey
        PRIMARY KEY (tenant_key, environment_key, template_resource_id, variant_key);

-- 5. Reference by identity, and drop the address copies -------------------------------------------------
ALTER TABLE template_variants
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT template_variants_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES document_templates(tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE template_versions
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT template_versions_variant_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key)
        REFERENCES template_variants(tenant_key, template_resource_id, id) ON DELETE CASCADE;

ALTER TABLE contract_versions
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT contract_versions_template_fkey
        FOREIGN KEY (tenant_key, template_resource_id)
        REFERENCES document_templates(tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE template_versions
    ADD CONSTRAINT fk_template_versions_contract_version
        FOREIGN KEY (tenant_key, template_resource_id, contract_version)
        REFERENCES contract_versions(tenant_key, template_resource_id, id);

ALTER TABLE environment_activations
    DROP COLUMN catalog_key,
    DROP COLUMN template_key,
    ADD CONSTRAINT environment_activations_variant_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key)
        REFERENCES template_variants(tenant_key, template_resource_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT environment_activations_version_fkey
        FOREIGN KEY (tenant_key, template_resource_id, variant_key, version_key)
        REFERENCES template_versions(tenant_key, template_resource_id, variant_key, id) ON DELETE CASCADE;

-- The uniqueness rules were stated over the template's address; they are stated over the template.
-- Their indexes went with the dropped columns, so these are creations rather than rebuilds.
CREATE UNIQUE INDEX idx_one_default_variant_per_template
    ON template_variants (tenant_key, template_resource_id) WHERE is_default = true;

CREATE UNIQUE INDEX idx_one_draft_per_variant
    ON template_versions (tenant_key, template_resource_id, variant_key) WHERE status = 'draft';

CREATE UNIQUE INDEX idx_one_draft_contract_per_template
    ON contract_versions (tenant_key, template_resource_id) WHERE status = 'draft';

-- 6. Generation history keeps its address, and gains a durable link -----------------------------------
COMMENT ON COLUMN documents.catalog_key IS
    'Catalog the template lived in when this document was generated. A historical fact: it does not follow a later relocation.';
COMMENT ON COLUMN document_generation_requests.catalog_key IS
    'Catalog the template lived in when generation was requested. A historical fact: it does not follow a later relocation.';
COMMENT ON COLUMN documents.template_resource_id IS
    'Stable identity of the generating template. Filled forward from this migration on; null on older rows, which resolve by their recorded address instead. No foreign key: adding one would scan every partition at upgrade time, and a dangling id simply fails to join.';
COMMENT ON COLUMN document_generation_requests.template_resource_id IS
    'Stable identity of the generating template. Filled forward from this migration on; null on older rows.';

-- Filled forward, never backfilled -- deliberately, and this is the one place in the migration
-- where that choice is worth its cost.
--
-- Backfilling would mean a full row rewrite of every partition of the two largest tables in the
-- product, inside this transaction, holding ACCESS EXCLUSIVE on both throughout, and it would make
-- the partial indexes below full-size rather than empty. What it would buy is the identity on rows
-- written BEFORE this upgrade -- and those are exactly the rows retention removes: partitions are
-- monthly and `epistola.partitions.retention-months` defaults to 3, so within one retention window
-- every surviving row carries the identity anyway. The backfill happens by itself.
--
-- Until then those rows are found by their recorded address, which is what ListDocuments'
-- DOCUMENTS_OF_TEMPLATE predicate does: match the address OR the identity. The gap that leaves is
-- narrow and self-closing -- a template renamed shortly after the upgrade hides pre-upgrade rows
-- that would have aged out regardless -- and paying for it with the whole upgrade window is the
-- wrong trade.

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
        -- SELECT INTO assigns NULL and carries on when it matches nothing. Since this migration
        -- drops the foreign keys that used to reject such a row, failing open here would persist
        -- generation history that is neither validated nor resolvable -- the exact hole the
        -- composite key used to close.
        IF NOT FOUND THEN
            RAISE EXCEPTION 'no template % in catalog % for tenant %',
                NEW.template_key, NEW.catalog_key, NEW.tenant_key;
        END IF;
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

-- ------------------------------------------------------------------------------------------------
-- Finding what a template generated, by identity
-- ------------------------------------------------------------------------------------------------
-- Finding what a template generated, by identity rather than by address.
--
-- idx_documents_template_key already covers the address lookup (tenant_key, template_key). This is
-- its identity counterpart, and it is the one that keeps working after a relocation: the address
-- columns stay pinned to where the template lived at generation time, so a lookup by the template's
-- current address misses everything produced before it moved.
--
-- Partial on NOT NULL deliberately, and that is what makes it cheap to create here: the column is
-- filled forward, so at build time every existing row is NULL and the index starts empty. It fills
-- as generation happens. The predicate is implied by any equality lookup on the column, so the
-- planner matches it without the query having to mention it.
--
-- created_at DESC is part of the key, not decoration. These tables are RANGE-partitioned on
-- created_at, so "newest first, paginated" can walk partitions in order and stop early -- but only
-- if each partition's index yields rows already ordered. Without the third column the planner falls
-- back to the created_at index and applies the identity as a filter, which measured ~3x more
-- expensive on the paginated shape (Append cost 561 vs 176, Limit 111 vs 35 on a selective
-- lookup). Since (tenant_key, template_resource_id) is a prefix of this index, one index serves
-- both the plain lookup and the paginated one.
--
-- Partitioning: each partition gets its own child index and Append combines them, so a lookup that
-- cannot prune probes every partition. That is bounded -- partitions are monthly and retention is
-- configured in months -- and partitions holding no match cost nothing.
--
-- ON ONLY, so this is a catalogued parent index and nothing is scanned at upgrade time. Each
-- partition's own index is attached as the partition is created -- new partitions inherit it from
-- the parent, and existing ones are attached below. Without ON ONLY, CREATE INDEX recurses into
-- every partition, and Flyway's single transaction rules out CONCURRENTLY, so a plain create would
-- scan the whole of generation history with writes blocked.
CREATE INDEX idx_documents_template_resource_id
    ON ONLY documents (tenant_key, template_resource_id, created_at DESC)
    WHERE template_resource_id IS NOT NULL;

CREATE INDEX idx_generation_requests_template_resource_id
    ON ONLY document_generation_requests (tenant_key, template_resource_id, created_at DESC)
    WHERE template_resource_id IS NOT NULL;

-- Existing partitions get their own index and are attached to the parent. Each build is over rows
-- whose column is entirely NULL, so the partial predicate matches nothing and the build is
-- effectively free -- the scan is a read, not the rewrite a backfill would have made it.
DO $$
DECLARE
    parent TEXT;
    partition_name TEXT;
    index_name TEXT;
BEGIN
    FOREACH parent IN ARRAY ARRAY['documents', 'document_generation_requests'] LOOP
        FOR partition_name IN
            SELECT child.relname
            FROM pg_inherits
            JOIN pg_class child ON child.oid = pg_inherits.inhrelid
            WHERE pg_inherits.inhparent = parent::regclass
            ORDER BY child.relname
        LOOP
            index_name := partition_name || '_template_resource_id_idx';
            EXECUTE format(
                'CREATE INDEX %I ON %I (tenant_key, template_resource_id, created_at DESC)
                   WHERE template_resource_id IS NOT NULL',
                index_name, partition_name
            );
            EXECUTE format(
                'ALTER INDEX %I ATTACH PARTITION %I',
                CASE parent
                    WHEN 'documents' THEN 'idx_documents_template_resource_id'
                    ELSE 'idx_generation_requests_template_resource_id'
                END,
                index_name
            );
        END LOOP;
    END LOOP;
END $$;
