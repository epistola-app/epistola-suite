-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Catalog v5 replaces the ambiguous boolean stencil `isDraft` marker with
-- exact `draftVersion` provenance. This helper migrates one stored template
-- document and is dropped at the end of this forward-only migration.
CREATE FUNCTION migrate_catalog_v5_stencil_provenance(
    document JSONB,
    owning_tenant TEXT,
    owning_catalog TEXT,
    document_status TEXT,
    row_identity TEXT
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    node_entry RECORD;
    node_value JSONB;
    props JSONB;
    marker JSONB;
    stencil_key_value TEXT;
    effective_catalog TEXT;
    old_version INTEGER;
    draft_version INTEGER;
    draft_count INTEGER;
    published_count INTEGER;
BEGIN
    IF jsonb_typeof(document -> 'nodes') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'catalog v5 migration: %.nodes must be an object', row_identity;
    END IF;

    FOR node_entry IN SELECT key, value FROM jsonb_each(document -> 'nodes')
    LOOP
        node_value := node_entry.value;
        IF node_value ->> 'type' IS DISTINCT FROM 'stencil' THEN
            CONTINUE;
        END IF;

        props := COALESCE(node_value -> 'props', '{}'::jsonb);
        IF jsonb_typeof(props) IS DISTINCT FROM 'object' THEN
            RAISE EXCEPTION 'catalog v5 migration: %.nodes.%.props must be an object', row_identity, node_entry.key;
        END IF;
        IF NOT props ? 'isDraft' THEN
            CONTINUE;
        END IF;

        marker := props -> 'isDraft';
        IF jsonb_typeof(marker) IS DISTINCT FROM 'boolean' THEN
            RAISE EXCEPTION 'catalog v5 migration: %.nodes.%.props.isDraft must be boolean', row_identity, node_entry.key;
        END IF;

        -- Published and archived documents can only contain the stale RC3
        -- authoring marker. Remove it without resolving or rewriting content.
        IF marker = 'false'::jsonb OR document_status <> 'draft' THEN
            node_value := jsonb_set(node_value, '{props}', props - 'isDraft');
            document := jsonb_set(document, ARRAY['nodes', node_entry.key], node_value);
            CONTINUE;
        END IF;

        stencil_key_value := props ->> 'stencilId';
        IF stencil_key_value IS NULL OR stencil_key_value = '' THEN
            RAISE EXCEPTION 'catalog v5 migration: %.nodes.%.props.stencilId is required for a draft reference',
                row_identity, node_entry.key;
        END IF;
        effective_catalog := COALESCE(NULLIF(props ->> 'catalogKey', ''), owning_catalog);

        SELECT count(*), min(id)
        INTO draft_count, draft_version
        FROM stencil_versions
        WHERE tenant_key::text = owning_tenant
          AND catalog_key::text = effective_catalog
          AND stencil_key::text = stencil_key_value
          AND status = 'draft';

        IF draft_count <> 1 THEN
            RAISE EXCEPTION 'catalog v5 migration: %.nodes.% resolves % drafts for stencil %/% (expected exactly one)',
                row_identity, node_entry.key, draft_count, effective_catalog, stencil_key_value;
        END IF;

        old_version := NULL;
        IF props ? 'version' THEN
            IF jsonb_typeof(props -> 'version') IS DISTINCT FROM 'number'
                OR (props ->> 'version') !~ '^[1-9][0-9]*$' THEN
                RAISE EXCEPTION 'catalog v5 migration: %.nodes.%.props.version must be a positive integer',
                    row_identity, node_entry.key;
            END IF;
            old_version := (props ->> 'version')::integer;
        END IF;

        SELECT count(*)
        INTO published_count
        FROM stencil_versions
        WHERE tenant_key::text = owning_tenant
          AND catalog_key::text = effective_catalog
          AND stencil_key::text = stencil_key_value
          AND status IN ('published', 'archived')
          AND id = old_version;

        IF old_version IS NULL THEN
            SELECT count(*)
            INTO published_count
            FROM stencil_versions
            WHERE tenant_key::text = owning_tenant
              AND catalog_key::text = effective_catalog
              AND stencil_key::text = stencil_key_value
              AND status IN ('published', 'archived');
            IF published_count <> 0 THEN
                RAISE EXCEPTION 'catalog v5 migration: %.nodes.% omits its published base version for stencil %/%',
                    row_identity, node_entry.key, effective_catalog, stencil_key_value;
            END IF;
        ELSIF published_count = 0 THEN
            IF old_version = draft_version THEN
                SELECT count(*)
                INTO published_count
                FROM stencil_versions
                WHERE tenant_key::text = owning_tenant
                  AND catalog_key::text = effective_catalog
                  AND stencil_key::text = stencil_key_value
                  AND status IN ('published', 'archived');
                IF published_count <> 0 THEN
                    RAISE EXCEPTION 'catalog v5 migration: %.nodes.% uses draft version % as a base although published history exists',
                        row_identity, node_entry.key, old_version;
                END IF;
                props := props - 'version';
            ELSE
                RAISE EXCEPTION 'catalog v5 migration: %.nodes.% references inconsistent version % for stencil %/%',
                    row_identity, node_entry.key, old_version, effective_catalog, stencil_key_value;
            END IF;
        END IF;

        props := jsonb_set(props - 'isDraft', '{draftVersion}', to_jsonb(draft_version));
        node_value := jsonb_set(node_value, '{props}', props);
        document := jsonb_set(document, ARRAY['nodes', node_entry.key], node_value);
    END LOOP;

    RETURN document;
END;
$$;

UPDATE template_versions
SET template_model = migrate_catalog_v5_stencil_provenance(
    template_model,
    tenant_key::text,
    catalog_key::text,
    status,
    format(
        'template_versions[%s/%s/%s/%s/%s]',
        tenant_key,
        catalog_key,
        template_key,
        variant_key,
        id
    )
)
WHERE template_model @? '$.nodes.*.props.isDraft';

UPDATE stencil_versions
SET content = migrate_catalog_v5_stencil_provenance(
    content,
    tenant_key::text,
    catalog_key::text,
    status,
    format('stencil_versions[%s/%s/%s/%s]', tenant_key, catalog_key, stencil_key, id)
)
WHERE content @? '$.nodes.*.props.isDraft';

DROP FUNCTION migrate_catalog_v5_stencil_provenance(JSONB, TEXT, TEXT, TEXT, TEXT);
