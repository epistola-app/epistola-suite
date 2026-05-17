-- Per-face content hash for deterministic published renders.
--
-- A published template version pins a per-family fingerprint derived from the
-- SHA-256 of each face's bytes. `content_hash` is the lowercase hex SHA-256 of
-- the face binary, recomputed by `ImportFont` on every import (system fonts via
-- `EnsureSystemFonts` on boot, catalog-authored fonts on (re-)import).
--
-- Nullable + no backfill: pre-production (CLAUDE.md). Existing rows are
-- repopulated the next time `ImportFont` runs for the family; a null hash is
-- treated as a mismatch ("MISSING") by the family fingerprint, so an
-- unpopulated face fails a published render loudly rather than silently.

ALTER TABLE font_variants ADD COLUMN content_hash TEXT;

COMMENT ON COLUMN font_variants.content_hash IS
    'Lowercase hex SHA-256 of the face binary, recomputed by ImportFont. Pinned (per family) in the published theme snapshot for deterministic renders. NULL = not yet hashed (treated as a mismatch).';
