-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Issue #631: a template variant's title is now required.
--
-- Backfill NULL/blank titles (default variants, title-less imports) with the variant's
-- own slug, then enforce NOT NULL. Non-destructive: display already fell back to the
-- slug, so this only stores what was shown; no existing non-blank title is touched.

UPDATE template_variants
SET title = id
WHERE title IS NULL OR btrim(title) = '';

ALTER TABLE template_variants ALTER COLUMN title SET NOT NULL;

COMMENT ON COLUMN template_variants.title IS 'Human-readable title (required, for display)';
