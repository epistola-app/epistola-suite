-- Optimistic locking for content people edit. Each editable content row carries a revision that
-- every update that changes it raises by one. A reader gets it with the content (the editor, a form, REST as an
-- ETag, MCP), and a write that names the revision it started from is refused when the row has
-- moved on since: two people editing the same template no longer silently overwrite each other.
--
-- Per row: a template (its name, theme and data contract), a variant (its title, attributes and
-- model), a theme, a stencil, a code list (with its entries, which are replaced through it) and an
-- attribute definition. Two people working on different variants of one template do not block each
-- other. A copy into another draft keeps the revision; each draft's row counts on from there.

-- Only a change counts. Bookkeeping does not: when it was saved and by whom, and the columns a
-- trigger passes as arguments (a variant's is_default, which making another variant the default
-- clears without touching this one's content). An update that changes nothing else keeps the
-- revision, so saving unchanged content never makes someone else's open copy stale. A write that
-- changed rows hanging off this one (a code list's entries) raises it itself, by setting
-- revision = revision + 1, which is kept.
CREATE FUNCTION bump_content_revision() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    ignored TEXT[] := ARRAY['revision', 'updated_at', 'updated_by'] || TG_ARGV;
BEGIN
    IF NEW.revision = OLD.revision + 1 THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ignored) IS DISTINCT FROM (to_jsonb(OLD) - ignored) THEN
        NEW.revision := OLD.revision + 1;
    ELSE
        NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bump_content_revision() IS
    'BEFORE UPDATE trigger function for editable content tables: raises revision by one when anything but bookkeeping (revision, updated_at, updated_by, and the columns given as trigger arguments) changed, so a write that names the revision it read can tell whether the row changed since.';

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['document_templates', 'template_variants', 'themes', 'stencils', 'code_lists', 'variant_attribute_definitions']
    LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN revision BIGINT NOT NULL DEFAULT 1', t);
        EXECUTE format(
            'COMMENT ON COLUMN %I.revision IS %L', t,
            'Raised by one on every update that changes the row (bump_content_revision); a write naming the revision it read is refused when it differs.');
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION bump_content_revision(%s)',
            'trg_' || t || '_revision', t, CASE WHEN t = 'template_variants' THEN quote_literal('is_default') ELSE '' END);
    END LOOP;
END $$;
