-- Migrate legacy CSS-string `fontFamily` values to the structured catalog ref.
--
-- Before the font feature, `fontFamily` was a free-form CSS font stack string
-- (e.g. "Helvetica, Arial, sans-serif"). It is now a structured reference into
-- a catalog-scoped font family: { "slug": "...", "catalogKey": "..." }.
--
-- The project is pre-production (CLAUDE.md): this is a destructive rewrite with
-- no per-value fidelity. Every string `fontFamily` — wherever it appears in a
-- styles JSONB document — is rewritten to the bundled system Inter family.
-- Values that are already structured (JSON objects) are left untouched.
--
-- Affected JSONB columns:
--   * themes.document_styles                  — top-level fontFamily
--   * template_versions.resolved_theme        — frozen snapshot: documentStyles
--                                               + blockStylePresets.*.fontFamily
--   * template_versions.template_model        — node-level documentStylesOverride
--                                               and inline style fontFamily
--
-- A recursive helper rewrites *every* string-valued "fontFamily" key at any
-- depth, so we don't have to enumerate every nesting path. The helper is
-- dropped at the end of the migration so it doesn't linger in the schema.

CREATE OR REPLACE FUNCTION pg_temp.migrate_font_family(input jsonb)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    result jsonb;
    item   jsonb;
    k      text;
    v      jsonb;
BEGIN
    IF input IS NULL THEN
        RETURN NULL;
    END IF;

    CASE jsonb_typeof(input)
        WHEN 'object' THEN
            result := '{}'::jsonb;
            FOR k, v IN SELECT * FROM jsonb_each(input) LOOP
                IF k = 'fontFamily' AND jsonb_typeof(v) = 'string' THEN
                    result := result || jsonb_build_object(
                        'fontFamily',
                        '{"slug":"inter","catalogKey":"system"}'::jsonb
                    );
                ELSE
                    result := result || jsonb_build_object(
                        k, pg_temp.migrate_font_family(v)
                    );
                END IF;
            END LOOP;
            RETURN result;
        WHEN 'array' THEN
            result := '[]'::jsonb;
            FOR item IN SELECT * FROM jsonb_array_elements(input) LOOP
                result := result || jsonb_build_array(
                    pg_temp.migrate_font_family(item)
                );
            END LOOP;
            RETURN result;
        ELSE
            RETURN input;
    END CASE;
END;
$$;

UPDATE themes
SET document_styles = pg_temp.migrate_font_family(document_styles)
WHERE document_styles::text LIKE '%"fontFamily"%';

UPDATE template_versions
SET resolved_theme = pg_temp.migrate_font_family(resolved_theme)
WHERE resolved_theme IS NOT NULL
  AND resolved_theme::text LIKE '%"fontFamily"%';

UPDATE template_versions
SET template_model = pg_temp.migrate_font_family(template_model)
WHERE template_model::text LIKE '%"fontFamily"%';

DROP FUNCTION pg_temp.migrate_font_family(jsonb);
