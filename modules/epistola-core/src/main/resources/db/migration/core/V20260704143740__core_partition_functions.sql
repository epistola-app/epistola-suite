-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Runtime partition management as SECURITY DEFINER functions.
--
-- The application maintains monthly RANGE partitions at runtime
-- (PartitionMaintenanceScheduler). Doing that as raw CREATE/DROP DDL forces the
-- runtime DB role to hold table-DDL privileges. Routing it through these
-- SECURITY DEFINER functions lets the runtime role run with NO DDL: in a
-- two-role deployment the migration role owns these functions (and the tables)
-- and the runtime role is granted only EXECUTE, so the app never needs
-- CREATE/DROP. In a single-role deployment the one role owns and executes them,
-- so this is fully transparent. See docs/deployment.md and issue #438.
--
-- Both functions are bounded to partition management and cannot be turned into an
-- arbitrary CREATE/DROP primitive: they require `parent` to be a RANGE-partitioned
-- table, derive the child name/bounds themselves (create) or operate only on
-- actual child partitions of `parent` from pg_inherits (drop). search_path is
-- pinned — mandatory hardening for SECURITY DEFINER functions.

CREATE FUNCTION epistola_create_partition(parent regclass, month date)
    RETURNS text
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    parent_schema text;
    parent_name   text;
    child_name    text;
    start_date    date := date_trunc('month', month)::date;
    end_date      date := (date_trunc('month', month) + interval '1 month')::date;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table WHERE partrelid = parent AND partstrat = 'r'
    ) THEN
        RAISE EXCEPTION 'epistola_create_partition: % is not a RANGE-partitioned table', parent;
    END IF;

    SELECT n.nspname, c.relname INTO parent_schema, parent_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = parent;

    child_name := parent_name || '_' || to_char(month, 'YYYY_MM');

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
        parent_schema, child_name, parent::text, start_date, end_date
    );

    RETURN child_name;
END;
$$;

COMMENT ON FUNCTION epistola_create_partition(regclass, date) IS
    'SECURITY DEFINER: idempotently creates the monthly RANGE partition of `parent` covering `month`. '
    'Lets a DDL-less runtime role manage partitions (issue #438). Rejects non-RANGE-partitioned parents.';

CREATE FUNCTION epistola_drop_partitions_before(parent regclass, cutoff date)
    RETURNS SETOF text
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    parent_name   text;
    cutoff_suffix text := to_char(date_trunc('month', cutoff), 'YYYY_MM');
    child         record;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table WHERE partrelid = parent AND partstrat = 'r'
    ) THEN
        RAISE EXCEPTION 'epistola_drop_partitions_before: % is not a RANGE-partitioned table', parent;
    END IF;

    SELECT c.relname INTO parent_name FROM pg_class c WHERE c.oid = parent;

    -- Drop only actual child partitions of `parent` (pg_inherits) whose monthly
    -- name sorts before the cutoff month — the same lexical rule the scheduler
    -- used, but the targets come from the catalog, never a caller-supplied name.
    FOR child IN
        SELECT n.nspname AS schema_name, c.relname AS table_name
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE i.inhparent = parent
          AND c.relname < parent_name || '_' || cutoff_suffix
        ORDER BY c.relname
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I.%I CASCADE', child.schema_name, child.table_name);
        RETURN NEXT child.table_name;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION epistola_drop_partitions_before(regclass, date) IS
    'SECURITY DEFINER: drops monthly partitions of `parent` older than `cutoff`, returning the dropped names. '
    'Lets a DDL-less runtime role prune partitions (issue #438). Operates only on real child partitions of `parent`.';
